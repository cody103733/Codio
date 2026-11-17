// Music for Codio: the part that needs a real network client.
//
//   GET /api/music/mb?p=/ws/2/...[&base=https://mirror]   MusicBrainz (or a mirror), one request a second, cached on disk
//   GET /api/music/lb?p=/1/{popularity,stats/sitewide,explore/fresh-releases}/...   ListenBrainz (charts, new releases, an artist's top tracks), cached
//   GET /api/music/labs?p=/similar-artists/json?...        ListenBrainz Labs (artists like an artist), cached
//   GET /api/music/artistpic?name=Radiohead               an artist's picture (Deezer, matched by exact name), cached a month
//   GET /api/music/img?u=https://coverartarchive.org/...   cover art, fetched once and kept on disk
//   GET /api/music/addon?url=https://addon/...             any music addon call, passed through as it is
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
// (CODIO_TEST_DEEZER does the same for the artist picture lookup.)
const DEEZER = process.env.CODIO_TEST_DEEZER || 'https://api.deezer.com';
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
const spaced = (origin, job) => new Promise((resolve, reject) => {
    const at = Math.max(Date.now(), nextStart.get(origin) || 0);
    nextStart.set(origin, at + GAP_MS);
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
const IMAGE_HOSTS = /^(coverartarchive\.org|([a-z0-9-]+\.)*(archive\.org|dzcdn\.net|e-cdns-images\.com))$/;
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

// An artist's picture: Deezer's artist whose name is exactly the same, or null. Only the artist's name is sent.
const artistPicture = async (response, name) => {
    if (!name || name.length > 120) {
        response.writeHead(400).end();
        return;
    }
    const url = `${DEEZER}/search/artist?limit=8&q=${encodeURIComponent(name)}`;
    const key = url + '#pic';
    const cached = readCache(key, 30 * DAY);
    if (cached) {
        send(response, 200, cached);
        return;
    }
    try {
        const res = await spaced(DEEZER, () => fetchUrl(url));
        if (res.status !== 200) {
            fail(response, 502, 'No answer');
            return;
        }
        const wanted = name.trim().toLowerCase();
        const hit = (JSON.parse(res.body.toString('utf8')).data || []).find((a) => String(a.name || '').trim().toLowerCase() === wanted && a.picture_big && !/\/images\/artist\/\/|artist\/\//.test(a.picture_big));
        const body = JSON.stringify({ url: hit ? hit.picture_big : null });
        writeCache(key, body);
        send(response, 200, body);
    } catch (_e) {
        fail(response, 502, 'No answer');
    }
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
    // Pictures are loaded by <img>, which can't send the header; they only come from the cover art hosts.
    if (request.method !== 'GET' || (request.headers['x-codio'] !== '1' && pathname !== '/api/music/img')) {
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
        if (!/^\/1\/(popularity|stats\/sitewide|explore\/fresh-releases)\//.test(p)) {
            response.writeHead(400).end();
            return true;
        }
        // Charts and new releases change by the day; an artist's top tracks hardly at all.
        metadata(response, LB, p, p.startsWith('/1/popularity/') ? DAY : 6 * 3600 * 1000);
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
    if (pathname === '/api/music/artistpic') {
        artistPicture(response, url.searchParams.get('name') || '');
        return true;
    }
    if (pathname === '/api/music/img') {
        image(response, url.searchParams.get('u') || '');
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
