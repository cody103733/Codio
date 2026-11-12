// Codio downloads: saves movies and episodes from debrid / direct (http) links to
// Videos\Codio, keeps going across restarts (resumes with Range requests), and
// serves the finished files back to the player with seeking.
//
// API (only for the Codio interface on this PC: every call needs the X-Codio
// header, which other web pages can't send without a CORS preflight that this
// server never approves):
//   GET  /api/dl            -> { dir, free, entries }
//   POST /api/dl/start      { entry fields + url, quality, bytes }  start one now
//   POST /api/dl/queue      { entries: [...] }  waiting entries (a season); the interface finds their sources
//   POST /api/dl/fail       { key, error }  no source found for a waiting entry
//   POST /api/dl/retry      { key }  a failed entry looks for a fresh source
//   POST /api/dl/delete     { key }
//   GET  /dl-img/<hash>     a saved poster / episode picture
//   GET  /dl/<key>          the downloaded file (Range supported)

'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const https = require('https');
const crypto = require('crypto');
const { execFile } = require('child_process');

const HOME = process.env.USERPROFILE || process.env.HOME || __dirname;
const DIR = process.env.CODIO_DL_DIR || path.join(HOME, 'Videos', 'Codio');
const STATE_DIR = path.join(process.env.LOCALAPPDATA || path.join(HOME, '.codio'), 'Codio');
const STATE = path.join(STATE_DIR, 'downloads.json');
const IMAGES = path.join(STATE_DIR, 'images');
const MAX_ACTIVE = 2;
const MAX_RETRIES = 6;

let entries = {};
const active = new Map(); // key -> { request, file }
let saveTimer = null;

const load = () => {
    try {
        const list = JSON.parse(fs.readFileSync(STATE, 'utf8'));
        if (Array.isArray(list)) list.forEach((e) => { if (e && typeof e.key === 'string') entries[e.key] = e; });
    } catch (_e) { /* first run */ }
    Object.values(entries).forEach((e) => {
        if (e.state === 'downloading') e.state = 'queued';
        if (e.state === 'done' && e.file && !fs.existsSync(e.file)) delete entries[e.key];
    });
};

const saveNow = () => {
    try {
        fs.mkdirSync(STATE_DIR, { recursive: true });
        fs.writeFileSync(STATE + '.tmp', JSON.stringify(Object.values(entries), null, 1));
        fs.renameSync(STATE + '.tmp', STATE);
    } catch (_e) { /* try again next time */ }
};

const save = () => {
    if (saveTimer) return;
    saveTimer = setTimeout(() => { saveTimer = null; saveNow(); }, 1500);
};

const safe = (s) => {
    const t = String(s || '').replace(/[\\/:*?"<>|\x00-\x1f]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80).trim();
    return t.length > 0 ? t : 'Video';
};

const pad = (n) => String(n).padStart(2, '0');

const extensionOf = (url) => {
    try {
        const ext = path.extname(new URL(url).pathname).toLowerCase();
        if (/^\.(mkv|mp4|avi|mov|webm|m4v|ts|wmv)$/.test(ext)) return ext;
    } catch (_e) { /* fall through */ }
    return '.mkv';
};

const fileFor = (e) => {
    const episode = e.season || e.episode ? ` S${pad(e.season || 0)}E${pad(e.episode || 0)}` : '';
    const quality = e.quality ? ` ${String(e.quality).replace('2160p', '4K')}` : '';
    return path.join(DIR, `${safe(e.name)}${episode}${quality}${extensionOf(e.url)}`);
};

// ---- Free space (Node 18.12 has no fs.statfs): ask Windows every 30 seconds.
let free = null;
let freeAt = 0;
const refreshFree = () => {
    if (Date.now() - freeAt < 30000) return;
    freeAt = Date.now();
    if (process.platform !== 'win32') { if (process.env.CODIO_TEST_FREE) free = Number(process.env.CODIO_TEST_FREE); return; }
    const drive = path.parse(DIR).root.replace(/\\$/, '');
    execFile('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', `(Get-CimInstance Win32_LogicalDisk -Filter "DeviceID='${drive}'").FreeSpace`],
        { windowsHide: true, timeout: 15000 }, (error, stdout) => {
            const n = parseInt(String(stdout).trim(), 10);
            if (!error && n > 0) free = n;
        });
};

// ---- Posters and episode pictures, kept on disk so Library looks right without internet.
const hashOf = (url) => crypto.createHash('sha1').update(String(url)).digest('hex');
const imageFile = (hash) => path.join(IMAGES, `${hash}.img`);
const savingImages = new Set();
const saveImage = (url) => {
    if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return;
    const hash = hashOf(url);
    const file = imageFile(hash);
    if (savingImages.has(hash) || fs.existsSync(file)) return;
    savingImages.add(hash);
    fs.mkdirSync(IMAGES, { recursive: true });
    get(url, 0, 0, (error, response) => {
        if (error || response.statusCode !== 200) { if (response) response.resume(); savingImages.delete(hash); return; }
        const out = fs.createWriteStream(file + '.part');
        response.pipe(out);
        out.on('finish', () => { fs.rename(file + '.part', file, () => savingImages.delete(hash)); });
        out.on('error', () => savingImages.delete(hash));
    });
};
const localImage = (url) => typeof url === 'string' && /^https?:\/\//i.test(url) && fs.existsSync(imageFile(hashOf(url))) ? `/dl-img/${hashOf(url)}` : url;
const saveImagesOf = (e) => { saveImage(e.poster); saveImage(e.thumb); };

// ---- Transfers
const get = (url, from, redirects, done) => {
    let mod;
    try { mod = new URL(url).protocol === 'https:' ? https : http; } catch (e) { done(e); return null; }
    const headers = { 'User-Agent': 'Codio' };
    if (from > 0) headers.Range = `bytes=${from}-`;
    const request = mod.get(url, { headers, timeout: 60000 }, (response) => {
        if ([301, 302, 303, 307, 308].includes(response.statusCode) && response.headers.location && redirects < 8) {
            response.resume();
            const next = new URL(response.headers.location, url).toString();
            const r = get(next, from, redirects + 1, done);
            if (r) request.emit('codio-redirect', r);
            return;
        }
        done(null, response);
    });
    request.on('timeout', () => request.destroy(new Error('No answer from the source')));
    request.on('error', (e) => done(e));
    return request;
};

const transfer = (e) => {
    fs.mkdirSync(DIR, { recursive: true });
    if (!e.file) e.file = fileFor(e);
    const part = e.file + '.part';
    let have = 0;
    try { have = fs.statSync(part).size; } catch (_e) { have = 0; }
    e.state = 'downloading';
    e.error = null;
    save();
    const job = { request: null, out: null, stopped: false };
    active.set(e.key, job);
    const finish = (error) => {
        if (job.out) job.out.end();
        active.delete(e.key);
        if (job.stopped) { pump(); return; }
        if (error && error.expired) {
            // Debrid links expire: look for a fresh one once (the interface finds it), then give up.
            if (!e.refreshed) {
                try { fs.unlinkSync(part); } catch (_e) { /* none */ }
                Object.assign(e, { state: 'waiting', url: undefined, file: null, done: 0, retries: 0, error: null, refreshed: true, wantQuality: e.wantQuality || e.quality || null });
            } else {
                e.state = 'failed';
                e.error = error.message;
            }
            save();
            pump();
            return;
        }
        if (error) {
            e.retries = (e.retries || 0) + 1;
            if (e.retries <= MAX_RETRIES) {
                e.state = 'queued';
                e.error = `Retrying (${error.message || error})`;
                setTimeout(pump, Math.min(60000, 2000 * 2 ** e.retries));
            } else {
                e.state = 'failed';
                e.error = error.message || String(error);
            }
            save();
            pump();
            return;
        }
        try {
            fs.renameSync(part, e.file);
            e.state = 'done';
            e.done = e.bytes = fs.statSync(e.file).size;
            e.finishedAt = Date.now();
            e.error = null;
        } catch (err) {
            e.state = 'failed';
            e.error = `Couldn't save the file (${err.message})`;
        }
        save();
        pump();
    };
    job.request = get(e.url, have, 0, (error, response) => {
        if (job.stopped) { if (response) response.resume(); return; }
        if (error) { finish(error); return; }
        if (response.statusCode === 416 && have > 0) { response.resume(); finish(null); return; }
        if (response.statusCode !== 200 && response.statusCode !== 206) {
            response.resume();
            const expired = response.statusCode === 403 || response.statusCode === 404 || response.statusCode === 410;
            const err = new Error(expired ? 'The link has expired' : `The source answered ${response.statusCode}`);
            err.expired = expired;
            finish(err);
            return;
        }
        let start = have;
        if (response.statusCode === 200) start = 0; // the source can't resume: start over
        const range = /\/(\d+)\s*$/.exec(response.headers['content-range'] || '');
        const length = parseInt(response.headers['content-length'], 10);
        const total = range ? parseInt(range[1], 10) : (length > 0 ? start + length : 0);
        if (total > 0) e.bytes = total;
        e.done = start;
        job.out = fs.createWriteStream(part, { flags: start > 0 ? 'a' : 'w' });
        job.out.on('error', (err) => { response.destroy(); finish(new Error(`Couldn't write the file (${err.message})`)); });
        response.on('data', (chunk) => {
            e.done += chunk.length;
            e.retries = 0;
            save();
        });
        response.on('error', (err) => finish(err));
        response.on('end', () => {
            if (job.stopped) return;
            if (total > 0 && e.done < total) finish(new Error('The download was cut off'));
            else job.out.end(() => finish(null));
        });
        response.pipe(job.out, { end: false });
    });
    if (job.request) job.request.on('codio-redirect', (r) => { job.request = r; });
};

const pump = () => {
    if (active.size >= MAX_ACTIVE) return;
    const next = Object.values(entries).filter((e) => e.state === 'queued' && e.url)
        .sort((a, b) => (a.addedAt || 0) - (b.addedAt || 0))[0];
    if (!next) return;
    transfer(next);
    pump();
};

const stop = (key) => {
    const job = active.get(key);
    if (!job) return;
    job.stopped = true;
    try { if (job.request) job.request.destroy(); } catch (_e) { /* gone */ }
    try { if (job.out) job.out.end(); } catch (_e) { /* gone */ }
    active.delete(key);
};

const remove = (key) => {
    const e = entries[key];
    if (!e) return;
    stop(key);
    delete entries[key];
    const files = e.file ? [e.file, e.file + '.part'] : [];
    // Windows may hold the file a moment after the transfer stops.
    const unlink = (attempt) => files.forEach((f) => fs.unlink(f, (err) => {
        if (err && err.code !== 'ENOENT' && attempt < 5) setTimeout(() => unlink(attempt + 1), 1000);
    }));
    setTimeout(() => unlink(0), 200);
    saveNow();
    pump();
};

const FIELDS = ['key', 'type', 'metaId', 'videoId', 'name', 'poster', 'season', 'episode', 'episodeTitle', 'thumb', 'quality', 'url', 'bytes', 'wantQuality', 'metaBase', 'streamBase', 'description'];

const clean = (input) => {
    const e = {};
    FIELDS.forEach((f) => { if (input[f] !== undefined && input[f] !== null) e[f] = input[f]; });
    if (typeof e.key !== 'string' || e.key.length === 0) return null;
    ['poster', 'thumb'].forEach((f) => { if (typeof e[f] === 'string' && !/^https?:\/\//i.test(e[f])) delete e[f]; });
    if (e.url !== undefined && !/^https?:\/\//i.test(String(e.url))) return null;
    return e;
};

const publicEntry = (e) => ({ ...e, url: undefined, file: e.file ? path.basename(e.file) : null, poster: localImage(e.poster), thumb: localImage(e.thumb) });

const readBody = (request) => new Promise((resolve, reject) => {
    let body = '';
    request.on('data', (c) => { body += c; if (body.length > 1e6) request.destroy(); });
    request.on('end', () => { try { resolve(body ? JSON.parse(body) : {}); } catch (e) { reject(e); } });
    request.on('error', reject);
});

const sendJson = (response, code, value) => {
    response.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(value));
};

const serveFile = (request, response, e) => {
    fs.stat(e.file, (error, stat) => {
        if (error) { response.writeHead(404).end(); return; }
        const size = stat.size;
        const ext = path.extname(e.file).toLowerCase();
        const type = ext === '.mp4' || ext === '.m4v' ? 'video/mp4' : ext === '.webm' ? 'video/webm' : 'video/x-matroska';
        const m = /^bytes=(\d*)-(\d*)$/.exec(request.headers.range || '');
        let start = 0;
        let end = size - 1;
        if (m) {
            if (m[1] === '' && m[2] !== '') { start = Math.max(0, size - parseInt(m[2], 10)); } else {
                start = parseInt(m[1] || '0', 10);
                if (m[2] !== '') end = Math.min(end, parseInt(m[2], 10));
            }
            if (start > end || start >= size) { response.writeHead(416, { 'Content-Range': `bytes */${size}` }).end(); return; }
        }
        response.writeHead(m ? 206 : 200, {
            'Content-Type': type,
            'Content-Length': end - start + 1,
            'Accept-Ranges': 'bytes',
            'Access-Control-Allow-Origin': '*',
            ...(m ? { 'Content-Range': `bytes ${start}-${end}/${size}` } : {}),
        });
        if (request.method === 'HEAD') { response.end(); return; }
        fs.createReadStream(e.file, { start, end }).on('error', () => response.destroy()).pipe(response);
    });
};

// Returns true when it handled the request.
const handle = (request, response, pathname) => {
    if (pathname.startsWith('/dl-img/')) {
        const hash = pathname.slice(8);
        if (!/^[0-9a-f]{40}$/.test(hash)) { response.writeHead(404).end(); return true; }
        fs.readFile(imageFile(hash), (error, data) => {
            if (error) { response.writeHead(404).end(); return; }
            const type = data[0] === 0x89 ? 'image/png' : data[0] === 0x52 ? 'image/webp' : 'image/jpeg';
            response.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'max-age=31536000' });
            response.end(data);
        });
        return true;
    }
    if (pathname.startsWith('/dl/')) {
        const key = decodeURIComponent(pathname.slice(4));
        const e = entries[key];
        if (!e || e.state !== 'done' || !e.file) { response.writeHead(404).end(); return true; }
        serveFile(request, response, e);
        return true;
    }
    if (!pathname.startsWith('/api/dl')) return false;
    if (request.headers['x-codio'] !== '1') { response.writeHead(403).end(); return true; }
    if (request.method === 'GET' && pathname === '/api/dl') {
        refreshFree();
        sendJson(response, 200, { dir: DIR, free, entries: Object.values(entries).map(publicEntry) });
        return true;
    }
    if (request.method !== 'POST') { response.writeHead(405).end(); return true; }
    readBody(request).then((body) => {
        if (pathname === '/api/dl/start') {
            const e = clean(body);
            if (!e || !e.url) { sendJson(response, 400, { error: 'bad entry' }); return; }
            const old = entries[e.key];
            if (old) { stop(old.key); if (old.file && old.state !== 'done') fs.unlink(old.file + '.part', () => {}); }
            entries[e.key] = { ...(old || {}), ...e, file: null, state: 'queued', done: 0, retries: 0, error: null, addedAt: old?.addedAt || Date.now(), refreshed: body.refresh === true ? old?.refreshed : false };
            saveImagesOf(entries[e.key]);
            saveNow();
            pump();
            sendJson(response, 200, { ok: true });
        } else if (pathname === '/api/dl/queue') {
            const list = Array.isArray(body.entries) ? body.entries.map(clean).filter(Boolean) : [];
            let added = 0;
            list.forEach((e, i) => {
                const old = entries[e.key];
                if (old && old.state !== 'failed') return;
                entries[e.key] = { ...(old || {}), ...e, url: undefined, file: null, state: 'waiting', done: 0, retries: 0, error: null, addedAt: Date.now() + i };
                saveImagesOf(entries[e.key]);
                added++;
            });
            saveNow();
            sendJson(response, 200, { ok: true, added });
        } else if (pathname === '/api/dl/fail') {
            const e = entries[body.key];
            if (e && e.state === 'waiting') { e.state = 'failed'; e.error = String(body.error || 'No source'); saveNow(); }
            sendJson(response, 200, { ok: true });
        } else if (pathname === '/api/dl/retry') {
            // Look for a fresh source (debrid links expire), keeping the wanted quality.
            const e = entries[body.key];
            if (e && e.state === 'failed') {
                if (e.file) fs.unlink(e.file + '.part', () => {});
                Object.assign(e, { state: 'waiting', url: undefined, file: null, done: 0, retries: 0, error: null, refreshed: false, wantQuality: e.wantQuality || e.quality || null });
                saveNow();
            }
            sendJson(response, 200, { ok: true });
        } else if (pathname === '/api/dl/delete') {
            remove(String(body.key));
            sendJson(response, 200, { ok: true });
        } else {
            response.writeHead(404).end();
        }
    }, () => sendJson(response, 400, { error: 'bad request' }));
    return true;
};

const init = () => {
    load();
    refreshFree();
    pump();
};

module.exports = { init, handle, DIR };
