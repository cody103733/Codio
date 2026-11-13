// Codio for Windows: updates.
// The update list is updates.json in the Codio repository (the TV and phone apps read the same file).
// Its "windows" entry names the newest CodioSetup installer, which the release workflow publishes.
//
//   GET  /api/update           -> { latest: { version, notes, size } | null, state }
//   POST /api/update/download  -> starts downloading the installer, answers the state
//   GET  /api/update/status    -> { phase: idle|downloading|ready|error, version, received, total, error }
//
// When the state is "ready", the interface asks Codio.exe to run the installer ("install-update"):
// Codio closes, the installer replaces it quietly and opens the new version.
// Every request needs the header X-Codio: 1, so other web pages can't use these.

'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

const LIST_URL = 'https://raw.githubusercontent.com/cody103733/Codio/main/updates.json';
const URL_PREFIX = 'https://github.com/cody103733/Codio/releases/download/';
const DIR = path.join(process.env.LOCALAPPDATA || os.tmpdir(), 'Codio', 'update');
const VERSION_RE = /^\d+(\.\d+){1,3}$/;

let state = { phase: 'idle', version: null, received: 0, total: 0, error: null };
let latest = null;

const fetchList = async () => {
    const response = await fetch(`${LIST_URL}?t=${Date.now()}`, {
        headers: { 'Cache-Control': 'no-cache', 'User-Agent': 'Codio' },
        signal: AbortSignal.timeout(15000),
    });
    if (!response.ok) throw new Error(`update list answered ${response.status}`);
    const list = await response.json();
    const entry = list && list.windows;
    if (!entry || !VERSION_RE.test(String(entry.version)) || !String(entry.url || '').startsWith(URL_PREFIX)) return null;
    latest = {
        version: String(entry.version),
        notes: String(entry.notes || ''),
        size: Number(entry.size) || 0,
        sha256: /^[0-9a-f]{64}$/i.test(String(entry.sha256 || '')) ? String(entry.sha256).toLowerCase() : null,
        url: String(entry.url),
    };
    return latest;
};

const installerPath = (version) => path.join(DIR, `CodioSetup-${version}.exe`);

const download = async (target) => {
    state = { phase: 'downloading', version: target.version, received: 0, total: target.size, error: null };
    try {
        fs.mkdirSync(DIR, { recursive: true });
        // Old installers aren't needed any more.
        for (const name of fs.readdirSync(DIR)) {
            try { fs.unlinkSync(path.join(DIR, name)); } catch (_e) { /* in use: leave it */ }
        }
        const response = await fetch(target.url, { headers: { 'User-Agent': 'Codio' }, redirect: 'follow' });
        if (!response.ok || !response.body) throw new Error(`download answered ${response.status}`);
        state.total = Number(response.headers.get('content-length')) || target.size;
        const part = installerPath(target.version) + '.part';
        const out = fs.createWriteStream(part);
        const hash = crypto.createHash('sha256');
        const reader = response.body.getReader();
        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            hash.update(value);
            state.received += value.length;
            if (!out.write(value)) await new Promise((resolve) => out.once('drain', resolve));
        }
        await new Promise((resolve, reject) => out.end((error) => error ? reject(error) : resolve()));
        if (state.total && state.received !== state.total) throw new Error('the download was cut off');
        if (target.sha256 && hash.digest('hex') !== target.sha256) throw new Error('the download is damaged');
        fs.renameSync(part, installerPath(target.version));
        state.phase = 'ready';
    } catch (error) {
        state = { ...state, phase: 'error', error: String(error && error.message || error) };
    }
};

const send = (response, status, body) => {
    if (response.headersSent) return;
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(body));
};

// Answers the request and returns true if it is one of the update paths.
const handle = (request, response, pathname) => {
    if (pathname !== '/api/update' && !pathname.startsWith('/api/update/')) return false;
    if (request.headers['x-codio'] !== '1') {
        response.writeHead(403).end();
        return true;
    }
    if (pathname === '/api/update' && request.method === 'GET') {
        fetchList()
            .then((entry) => send(response, 200, { latest: entry && { version: entry.version, notes: entry.notes, size: entry.size }, state }))
            .catch((error) => send(response, 502, { error: String(error && error.message || error), state }));
        return true;
    }
    if (pathname === '/api/update/status') {
        send(response, 200, state);
        return true;
    }
    if (pathname === '/api/update/download' && request.method === 'POST') {
        const ready = state.phase === 'ready' && latest && state.version === latest.version && fs.existsSync(installerPath(latest.version));
        if (state.phase !== 'downloading' && !ready) {
            (latest ? Promise.resolve(latest) : fetchList())
                .then((entry) => entry ? download(entry) : (state = { ...state, phase: 'error', error: 'no update found' }))
                .catch((error) => { state = { ...state, phase: 'error', error: String(error && error.message || error) }; });
            state = { phase: 'downloading', version: latest ? latest.version : null, received: 0, total: latest ? latest.size : 0, error: null };
        }
        send(response, 200, state);
        return true;
    }
    response.writeHead(404).end();
    return true;
};

module.exports = { handle };
