// Music for Codio: the part that needs a real network client.
//
//   GET /api/music/mb?p=/ws/2/...[&base=https://mirror]   MusicBrainz (or a mirror), one request a second, cached on disk
//   GET /api/music/lb?p=/1/{popularity,stats/sitewide,explore/fresh-releases}/...   ListenBrainz (charts, new releases, an artist's top tracks), cached
//   GET /api/music/lb?p=/1/user/<name>/playlists/createdfor?... and /1/playlist/<mbid>   playlists made for you (Daily Jams), cached
//   GET /api/music/labs?p=/similar-artists/json?...        ListenBrainz Labs (artists like an artist), cached
//   GET /api/music/chart?cc=us                             the country's most played songs (Apple Music chart) matched to
//                                                           MusicBrainz ids, plus the artists in it: { songs, artists }
//   GET /api/music/img?u=https://coverartarchive.org/...   cover art, fetched once and kept on disk
//   GET /api/music/addon?url=https://addon/...             any music addon call, passed through as it is
//   GET /api/music/artist-img?id=<artist mbid>&name=...    a picture of the artist, kept on disk
//   GET|POST /api/music/me?p=/1/...                         your ListenBrainz account (header X-LB-Token): sign-in check,
//                                                           listens and loved songs. Never cached, the token never logged.
//
// Every call needs the header X-Codio: 1. Addon addresses (they can contain a personal token) are never logged or cached.

'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

const USER_AGENT = 'Codio/1.7 ( https://github.com/cody103733/Codio )';
const DEFAULT_MB = 'https://musicbrainz.org';
// (CODIO_TEST_LB points both ListenBrainz hosts at a stand-in server, for testing without the internet.)
const LB = process.env.CODIO_TEST_LB || 'https://api.listenbrainz.org';
const LABS = process.env.CODIO_TEST_LB || 'https://labs.api.listenbrainz.org';
const CACHE_DIR = path.join(process.env.LOCALAPPDATA || os.tmpdir(), 'Codio', 'music-cache');
const DAY = 24 * 3600 * 1000;
const GAP_MS = 1000;

// Gets url (following up to 3 redirects). Resolves { status, headers, body } and rejects on network errors.
const fetchUrl = (url, { headers = {}, timeout = 15000, redirects = 3 } = {}) => new Promise((resolve, reject) => {
    let target;
    try {
        target = new URL(url);
    } catch (e) {
        reject(e);
        return;
    }
    const lib = target.protocol === 'http:' ? http : target.protocol === 'https:' ? https : null;
    if (!lib) {
        reject(new Error('Only http and https links work'));
        return;
    }
    const req = lib.get(target, { headers: { 'User-Agent': USER_AGENT, 'Accept': 'application/json', ...headers }, timeout }, (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects > 0) {
            res.resume();
            fetchUrl(new URL(res.headers.location, target).toString(), { headers, timeout, redirects: redirects - 1 }).then(resolve, reject);
            return;
        }
        const chunks = [];
        let size = 0;
        res.on('data', (c) => {
            size += c.length;
            if (size > 8 * 1024 * 1024) {
                req.destroy(new Error('Answer too large'));
                return;
            }
            chunks.push(c);
        });
        res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
        res.on('error', reject);
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', reject);
});

// Calls to one server start at least GAP_MS apart (MusicBrainz asks for no more than one a second); they may overlap.
const nextStart = new Map();
const spaced = (origin, job, gap = GAP_MS) => new Promise((resolve, reject) => {
    const at = Math.max(Date.now(), nextStart.get(origin) || 0);
    nextStart.set(origin, at + gap);
    setTimeout(() => job().then(resolve, reject), at - Date.now());
});

const cacheFile = (url) => path.join(CACHE_DIR, crypto.createHash('sha1').update(url).digest('hex') + '.json');
const readCache = (url, ttl) => {
    try {
        const file = cacheFile(url);
        const stat = fs.statSync(file);
        if (Date.now() - stat.mtimeMs > ttl) return null;
        return fs.readFileSync(file);
    } catch (_e) {
        return null;
    }
};
const writeCache = (url, body) => {
    try {
        fs.mkdirSync(CACHE_DIR, { recursive: true });
        fs.writeFileSync(cacheFile(url), body);
    } catch (_e) { /* the cache is a bonus */ }
};

const send = (response, status, body, headers = {}) => {
    if (response.headersSent) return;
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', ...headers });
    response.end(body);
};

const fail = (response, status, message) => send(response, status, JSON.stringify({ error: message }));

// MusicBrainz or ListenBrainz: queued, cached, JSON only.
const metadata = async (response, origin, p, ttl) => {
    const url = origin + p;
    const cached = readCache(url, ttl);
    if (cached) {
        send(response, 200, cached);
        return;
    }
    try {
        const res = await spaced(origin, () => fetchUrl(url));
        if (res.status === 200) {
            writeCache(url, res.body);
            send(response, 200, res.body);
        } else if (res.status === 204) {
            send(response, 204, '');
        } else if (res.status === 503 || res.status === 429) {
            send(response, 503, JSON.stringify({ error: 'The music database is busy, try again in a moment' }), { 'Retry-After': '5' });
        } else {
            fail(response, res.status === 404 ? 404 : 502, `The music database answered ${res.status}`);
        }
    } catch (e) {
        fail(response, 502, 'Couldn\'t reach the music database');
    }
};

// Cover art: fetched once, kept on disk (a miss is remembered for a day), so every later look is instant.
const IMAGE_HOSTS = /^(coverartarchive\.org|([a-z0-9-]+\.)*archive\.org|([a-z0-9-]+\.)*dzcdn\.net)$/;
const image = async (response, target) => {
    let u;
    try {
        u = new URL(target);
    } catch (_e) {
        response.writeHead(400).end();
        return;
    }
    if (u.protocol !== 'https:' || !IMAGE_HOSTS.test(u.hostname)) {
        response.writeHead(400).end();
        return;
    }
    const base = path.join(CACHE_DIR, 'img-' + crypto.createHash('sha1').update(target).digest('hex'));
    try {
        const stat = fs.statSync(base + '.none');
        if (Date.now() - stat.mtimeMs < DAY) {
            response.writeHead(404, { 'Cache-Control': 'max-age=3600' }).end();
            return;
        }
    } catch (_e) { /* no miss on file */ }
    try {
        const body = fs.readFileSync(base + '.img');
        const type = fs.readFileSync(base + '.type', 'utf8');
        response.writeHead(200, { 'Content-Type': type, 'Content-Length': body.length, 'Cache-Control': 'max-age=604800' });
        response.end(body);
        return;
    } catch (_e) { /* not kept yet */ }
    try {
        const res = await fetchUrl(target, { headers: { Accept: 'image/*' }, timeout: 20000 });
        fs.mkdirSync(CACHE_DIR, { recursive: true });
        if (res.status === 200 && String(res.headers['content-type'] || '').startsWith('image/')) {
            fs.writeFileSync(base + '.img', res.body);
            fs.writeFileSync(base + '.type', String(res.headers['content-type']));
            response.writeHead(200, { 'Content-Type': res.headers['content-type'], 'Content-Length': res.body.length, 'Cache-Control': 'max-age=604800' });
            response.end(res.body);
        } else {
            if (res.status === 404) fs.writeFileSync(base + '.none', '');
            response.writeHead(res.status === 404 ? 404 : 502).end();
        }
    } catch (_e) {
        response.writeHead(502).end();
    }
};

// An artist's picture. MusicBrainz has none, so: Deezer's photo of the artist with exactly that name, else the cover of
// their most played album (ListenBrainz). What was found is kept for a month (a miss for a day), so a row of artists
// only costs a lookup the first time.
const DEEZER = process.env.CODIO_TEST_DEEZER || 'https://api.deezer.com';
const MBID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const plain = (s) => String(s || '').normalize('NFKD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/&/g, 'and').replace(/[^a-z0-9]+/g, '');
const findingPicture = new Map();

const deezerPicture = async (name) => {
    const res = await spaced(DEEZER, () => fetchUrl(`${DEEZER}/search/artist?q=${encodeURIComponent(name)}&limit=10`), 120);
    if (res.status !== 200) return null;
    const list = JSON.parse(res.body.toString('utf8')).data;
    if (!Array.isArray(list)) return null;
    const wanted = plain(name);
    const match = list.filter((a) => a && plain(a.name) === wanted).sort((a, b) => (b.nb_fan || 0) - (a.nb_fan || 0))[0];
    const url = match && (match.picture_big || match.picture_medium);
    // Deezer's stand-in for "no photo" has an empty picture id (/images/artist//...).
    return typeof url === 'string' && url.startsWith('https://') && !url.includes('/artist//') ? url : null;
};

// What a country is playing: Apple Music's public "most played" chart for that storefront (no account or key), each song
// matched to MusicBrainz ids with ListenBrainz's name lookup. ListenBrainz's own sitewide charts only reflect its few
// thousand listeners, which leans heavily towards a handful of very active fans (mostly J-pop and K-pop), so the
// Popular rows looked nothing like what people actually play. The chart is kept 6 hours, each lookup a month.
const CHART = process.env.CODIO_TEST_CHART || 'https://rss.applemarketingtools.com';
const charts = new Map();

const lookupSong = async (artist, title) => {
    const url = `${LB}/1/metadata/lookup/?artist_name=${encodeURIComponent(artist)}&recording_name=${encodeURIComponent(title)}`;
    let body = readCache(url, 30 * DAY);
    if (!body) {
        const res = await spaced(LB, () => fetchUrl(url), 150);
        if (res.status !== 200) return null;
        body = res.body;
        writeCache(url, body);
    }
    const j = JSON.parse(body.toString('utf8'));
    return j && j.recording_mbid ? j : null;
};

// "Artist A & Artist B", "A, B & C", "A feat. B": the first name is who the song is by.
const leadArtist = (name) => String(name || '').split(/\s+(?:&|feat\.?|ft\.?|featuring|with|x)\s+|,\s+(?!the\s)/i)[0].trim();

const buildChart = async (cc) => {
    const url = `${CHART}/api/v2/${cc}/music/most-played/50/songs.json`;
    let body = readCache(url, 6 * 3600 * 1000);
    if (!body) {
        const res = await fetchUrl(url);
        if (res.status !== 200) {
            if (cc !== 'us') return buildChart('us');
            throw new Error(`chart ${res.status}`);
        }
        body = res.body;
        writeCache(url, body);
    }
    const feed = JSON.parse(body.toString('utf8'));
    const items = (feed && feed.feed && Array.isArray(feed.feed.results) ? feed.feed.results : []).slice(0, 40);
    const found = await Promise.all(items.map((r) => lookupSong(String(r.artistName || ''), String(r.name || '')).catch(() => null)));
    const songs = [];
    const artists = [];
    const seen = new Set();
    items.forEach((r, i) => {
        const m = found[i];
        if (!m) return;
        const ids = Array.isArray(m.artist_mbids) ? m.artist_mbids.filter((x) => MBID.test(x)) : [];
        const credit = String(m.artist_credit_name || r.artistName || '');
        songs.push({
            mbid: m.recording_mbid, title: String(m.recording_name || r.name || ''), artist: credit, album: m.release_name || null,
            releaseMbid: m.release_mbid || null, artistMbid: ids[0] || null,
        });
        if (ids[0] && !seen.has(ids[0])) {
            seen.add(ids[0]);
            artists.push({ id: ids[0], name: ids.length === 1 ? credit : leadArtist(credit) });
        }
    });
    return JSON.stringify({ country: cc, songs, artists });
};

const chartFor = (cc) => {
    const known = charts.get(cc);
    if (known && Date.now() - known.at < 30 * 60 * 1000) return known.job;
    const job = buildChart(cc);
    charts.set(cc, { at: Date.now(), job });
    job.catch(() => charts.delete(cc));
    return job;
};

const albumPicture = async (id) => {
    const res = await spaced(LB, () => fetchUrl(`${LB}/1/popularity/top-release-groups-for-artist/${id}`));
    if (res.status !== 200) return null;
    const list = JSON.parse(res.body.toString('utf8'));
    const g = Array.isArray(list) ? list.find((x) => x && x.release_group && x.release_group.caa_release_mbid) : null;
    return g ? `https://coverartarchive.org/release/${g.release_group.caa_release_mbid}/front-500` : null;
};

const artistPicture = (id, name) => {
    const key = `artist-picture:${id}`;
    const cached = readCache(key, 30 * DAY);
    if (cached) {
        try {
            const saved = JSON.parse(cached.toString('utf8'));
            if (saved.url || Date.now() - (saved.at || 0) < DAY) return Promise.resolve(saved.url || null);
        } catch (_e) { /* look again */ }
    }
    if (findingPicture.has(id)) return findingPicture.get(id);
    const job = (async () => {
        let url = null;
        try { if (name) url = await deezerPicture(name); } catch (_e) { /* try the album */ }
        try { if (!url) url = await albumPicture(id); } catch (_e) { /* none */ }
        writeCache(key, JSON.stringify({ url, at: Date.now() }));
        return url;
    })();
    findingPicture.set(id, job);
    job.finally(() => findingPicture.delete(id));
    return job;
};

// Your ListenBrainz account. Only these calls are let through, with your token added as ListenBrainz asks.
const ME_PATHS = [
    /^\/1\/validate-token$/,
    /^\/1\/submit-listens$/,
    /^\/1\/feedback\/recording-feedback$/,
    /^\/1\/feedback\/user\/[^/?]+\/get-feedback\?[\w=&-]*$/,
    /^\/1\/user\/[^/?]+\/listens\?[\w=&-]*$/,
];
const account = (request, response, p) => {
    const token = String(request.headers['x-lb-token'] || '');
    if (!ME_PATHS.some((r) => r.test(p)) || !/^[\w-]{8,100}$/.test(token) || (request.method === 'POST') !== (p === '/1/submit-listens' || p === '/1/feedback/recording-feedback')) {
        response.writeHead(400).end();
        return;
    }
    const chunks = [];
    let size = 0;
    request.on('data', (c) => { size += c.length; if (size <= 1024 * 1024) chunks.push(c); });
    request.on('end', () => {
        if (size > 1024 * 1024) { fail(response, 413, 'Too large'); return; }
        const body = Buffer.concat(chunks);
        const target = new URL(LB + p);
        const lib = target.protocol === 'http:' ? http : https;
        const req = lib.request(target, {
            method: request.method,
            timeout: 15000,
            headers: {
                'User-Agent': USER_AGENT, 'Accept': 'application/json', 'Authorization': `Token ${token}`,
                ...(request.method === 'POST' ? { 'Content-Type': 'application/json', 'Content-Length': body.length } : {}),
            },
        }, (res) => {
            const out = [];
            res.on('data', (c) => out.push(c));
            res.on('end', () => send(response, res.statusCode || 502, Buffer.concat(out)));
            res.on('error', () => fail(response, 502, 'ListenBrainz read failed'));
        });
        req.on('timeout', () => req.destroy(new Error('timeout')));
        req.on('error', () => fail(response, 502, 'Couldn\'t reach ListenBrainz'));
        req.end(request.method === 'POST' ? body : undefined);
    });
};

const validBase = (text) => {
    try {
        const u = new URL(text);
        return (u.protocol === 'https:' || u.protocol === 'http:') ? u.origin + u.pathname.replace(/\/+$/, '') : null;
    } catch (_e) {
        return null;
    }
};

const handle = (request, response, pathname) => {
    if (!pathname.startsWith('/api/music/')) return false;
    const url = new URL(request.url, 'http://127.0.0.1');
    if (pathname === '/api/music/me' && request.headers['x-codio'] === '1' && (request.method === 'GET' || request.method === 'POST')) {
        account(request, response, url.searchParams.get('p') || '');
        return true;
    }
    // Pictures are loaded by <img>, which can't send the header; they only come from the cover art hosts.
    if (request.method !== 'GET' ||(request.headers['x-codio'] !== '1' && pathname !== '/api/music/img' && pathname !== '/api/music/artist-img')) {
        response.writeHead(400).end();
        return true;
    }
    if (pathname === '/api/music/mb') {
        const p = url.searchParams.get('p') || '';
        const base = validBase(url.searchParams.get('base') || DEFAULT_MB);
        if (!p.startsWith('/ws/2/') || !base) {
            response.writeHead(400).end();
            return true;
        }
        const withFmt = p.includes('fmt=') ? p : p + (p.includes('?') ? '&' : '?') + 'fmt=json';
        const search = /^\/ws\/2\/[a-z-]+\?(?:.*&)?query=/.test(p);
        metadata(response, base, withFmt, search ? DAY : 7 * DAY);
        return true;
    }
    if (pathname === '/api/music/lb') {
        const p = url.searchParams.get('p') || '';
        const madeFor = /^\/1\/user\/[^/?]+\/playlists\/createdfor\?[\w=&-]*$/.test(p);
        const playlist = /^\/1\/playlist\/[0-9a-f-]{36}$/.test(p);
        if (!madeFor && !playlist && !/^\/1\/(popularity|stats\/sitewide|explore\/fresh-releases)\//.test(p)) {
            response.writeHead(400).end();
            return true;
        }
        // Charts and new releases change by the day; an artist's top tracks hardly at all. The playlists ListenBrainz
        // makes for you (Daily Jams) are looked for every two hours; one playlist never changes once made.
        metadata(response, LB, p, madeFor ? 2 * 3600 * 1000 : playlist ? 7 * DAY : p.startsWith('/1/popularity/') ? DAY : 6 * 3600 * 1000);
        return true;
    }
    if (pathname === '/api/music/labs') {
        const p = url.searchParams.get('p') || '';
        if (!p.startsWith('/similar-artists/')) {
            response.writeHead(400).end();
            return true;
        }
        metadata(response, LABS, p, 7 * DAY);
        return true;
    }
    if (pathname === '/api/music/chart') {
        const cc = String(url.searchParams.get('cc') || 'us').toLowerCase();
        chartFor(/^[a-z]{2}$/.test(cc) ? cc : 'us').then((body) => send(response, 200, body), () => fail(response, 502, 'Couldn\'t reach the charts'));
        return true;
    }
    if (pathname === '/api/music/img') {
        image(response, url.searchParams.get('u') || '');
        return true;
    }
    if (pathname === '/api/music/artist-img') {
        const id = url.searchParams.get('id') || '';
        const name = (url.searchParams.get('name') || '').slice(0, 200);
        if (!MBID.test(id)) {
            response.writeHead(400).end();
            return true;
        }
        artistPicture(id, name).then((found) => {
            if (found) image(response, found);
            else response.writeHead(404, { 'Cache-Control': 'max-age=3600' }).end();
        }, () => response.writeHead(502).end());
        return true;
    }
    if (pathname === '/api/music/addon') {
        const target = url.searchParams.get('url') || '';
        if (!validBase(target)) {
            response.writeHead(400).end();
            return true;
        }
        fetchUrl(target, { timeout: 12000 }).then((res) => {
            const headers = {};
            if (res.headers['retry-after']) headers['Retry-After'] = String(res.headers['retry-after']);
            send(response, res.status, res.body, headers);
        }, () => fail(response, 502, 'Couldn\'t reach the addon'));
        return true;
    }
    response.writeHead(404).end();
    return true;
};

module.exports = { handle, fetchUrl };
