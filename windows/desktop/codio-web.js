// Codio interface server.
// Codio.exe starts this on its bundled Node.js runtime (codio-runtime.exe) and it
// serves the "web" folder next to it on http://127.0.0.1:11471/ until Codio closes
// (Codio.exe keeps it in its job, so it stops with the app).

'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const https = require('https');

const PORT = parseInt(process.env.CODIO_WEB_PORT, 10) || 11471;
const BASE_URL = `http://127.0.0.1:${PORT}/`;
const WEB_DIR = path.resolve(__dirname, 'web');
const downloads = require('./codio-downloads.js');
const updates = require('./codio-update.js');

const MIME = {
    '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
    '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
    '.map': 'application/json; charset=utf-8', '.wasm': 'application/wasm',
    '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
    '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.webp': 'image/webp',
    '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
    '.txt': 'text/plain; charset=utf-8', '.webmanifest': 'application/manifest+json',
};

// Skip Intro times (IntroDB) for the player, asked from here so browser CORS rules don't matter.
// GET /api/skip?imdb_id=tt..&season=N&episode=N -> IntroDB's JSON (404 when nothing is timed).
const skipCache = new Map();
const skipTimes = (request, response, url) => {
    const imdb = url.searchParams.get('imdb_id') || '';
    const season = parseInt(url.searchParams.get('season'), 10);
    const episode = parseInt(url.searchParams.get('episode'), 10);
    if (request.headers['x-codio'] !== '1' || !/^tt\d+$/.test(imdb) || !(season > 0) || !(episode > 0)) {
        response.writeHead(400).end();
        return;
    }
    const key = `${imdb}:${season}:${episode}`;
    const send = (status, body) => {
        if (response.headersSent) return;
        response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
        response.end(body);
    };
    const cached = skipCache.get(key);
    if (cached && Date.now() - cached.at < 6 * 3600 * 1000) {
        send(cached.status, cached.body);
        return;
    }
    const target = `https://api.introdb.app/segments?imdb_id=${imdb}&season=${season}&episode=${episode}`;
    const req = https.get(target, { headers: { 'User-Agent': 'Codio/1.5 (+https://introdb.app)', 'Accept': 'application/json' }, timeout: 12000 }, (res) => {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => {
            const body = Buffer.concat(chunks).toString('utf8');
            const status = res.statusCode === 200 ? 200 : res.statusCode === 404 ? 404 : 502;
            if (status !== 502) skipCache.set(key, { at: Date.now(), status, body });
            send(status, status === 502 ? JSON.stringify({ error: `IntroDB answered ${res.statusCode}` }) : body);
        });
        res.on('error', () => send(502, '{"error":"IntroDB read failed"}'));
    });
    req.on('timeout', () => req.destroy(new Error('timeout')));
    req.on('error', (e) => send(502, JSON.stringify({ error: String(e && e.message || e) })));
};

const serve = (request, response) => {
    let pathname;
    try {
        const url = new URL(request.url, BASE_URL);
        if (url.pathname === '/api/skip') {
            skipTimes(request, response, url);
            return;
        }
        if (updates.handle(request, response, url.pathname)) return;
        if (downloads.handle(request, response, url.pathname)) return;
        pathname = decodeURIComponent(new URL(request.url, BASE_URL).pathname);
    } catch (_e) {
        response.writeHead(400).end();
        return;
    }
    if (pathname === '/' || pathname === '') pathname = '/index.html';
    const file = path.resolve(WEB_DIR, '.' + pathname);
    if (!file.toLowerCase().startsWith(WEB_DIR.toLowerCase() + path.sep)) {
        response.writeHead(403).end();
        return;
    }
    fs.stat(file, (error, stat) => {
        if (error || !stat.isFile()) {
            response.writeHead(404).end();
            return;
        }
        const ext = path.extname(file).toLowerCase();
        const name = path.basename(file).toLowerCase();
        const headers = {
            'Content-Type': MIME[ext] || 'application/octet-stream',
            'Content-Length': stat.size,
        };
        if (ext === '.html' || name === 'service-worker.js' || name === 'manifest.json') {
            headers['Cache-Control'] = 'no-cache';
        }
        response.writeHead(200, headers);
        if (request.method === 'HEAD') {
            response.end();
            return;
        }
        fs.createReadStream(file).on('error', () => response.destroy()).pipe(response);
    });
};

downloads.init();
http.createServer(serve).listen(PORT, '127.0.0.1');
