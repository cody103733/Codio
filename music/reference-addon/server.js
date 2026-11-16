// A tiny music addon for testing Codio's Music tab. It follows Eclipse Music's addon protocol
// (https://eclipsemusic.app/docs) and serves made-up songs: every "recording" is a synthesised tone melody, so there is
// no copyrighted audio anywhere. Run it with `node server.js [port]`, then add http://127.0.0.1:7878 in Music > Addons.
//
// What it shows: manifest with settings and notifications, /search, /stream, /album, /artist, /resolve-isrc, /resolve,
// /catalog, a notice on a reply, and a "still preparing" 503 with Retry-After (the song "Slow Burn").

'use strict';

const http = require('http');

const PORT = parseInt(process.argv[2] || process.env.PORT, 10) || 7878;

// ISRCs here are made up (country code ZZ is not assigned to any real label).
const TRACKS = [
    { id: 't1', title: 'Test Tone One', artist: 'Codio Test Band', album: 'First Light', duration: 12, isrc: 'ZZCOD2600001', notes: [440, 494, 523, 587] },
    { id: 't2', title: 'Test Tone Two', artist: 'Codio Test Band', album: 'First Light', duration: 12, isrc: 'ZZCOD2600002', notes: [523, 587, 659, 698] },
    { id: 't3', title: 'Slow Burn', artist: 'Codio Test Band', album: 'First Light', duration: 10, isrc: 'ZZCOD2600003', notes: [330, 392, 440, 392], slow: true },
    { id: 't4', title: 'Quiet Hours', artist: 'The Sine Waves', album: 'Low Gain', duration: 14, isrc: 'ZZCOD2600004', notes: [262, 330, 392, 330] },
];
const ALBUMS = [
    { id: 'a1', title: 'First Light', artist: 'Codio Test Band', year: 2026, trackIds: ['t1', 't2', 't3'] },
    { id: 'a2', title: 'Low Gain', artist: 'The Sine Waves', year: 2025, trackIds: ['t4'] },
];
const ARTISTS = [
    { id: 'r1', name: 'Codio Test Band', genres: ['Test'], trackIds: ['t1', 't2', 't3'], albumIds: ['a1'] },
    { id: 'r2', name: 'The Sine Waves', genres: ['Ambient'], trackIds: ['t4'], albumIds: ['a2'] },
];

const manifest = (base) => ({
    id: 'app.codio.reference-addon',
    name: 'Codio test addon',
    version: '1.0.0',
    description: 'Made-up songs made of tones, for trying out the Music tab.',
    resources: ['search', 'stream', 'catalog', 'isrc', 'resolve', 'notifications', 'settings'],
    types: ['track', 'album', 'artist'],
    contentType: 'music',
    catalogs: [
        { id: 'songs', type: 'track', name: 'Test songs' },
        { id: 'albums', type: 'album', name: 'Test albums' },
    ],
    settings: [{ key: 'tone', type: 'select', label: 'Tone', default: 'sine', options: [{ value: 'sine', label: 'Sine' }, { value: 'square', label: 'Square' }] }],
    base,
});

const trackJson = (t) => ({ id: t.id, title: t.title, artist: t.artist, album: t.album, duration: t.duration, isrc: t.isrc, format: 'wav' });
const albumJson = (a) => ({ id: a.id, title: a.title, artist: a.artist, year: a.year, trackCount: a.trackIds.length });

// A WAV file of the notes (16-bit mono, 22.05 kHz), generated on request.
const wav = (track, shape) => {
    const rate = 22050;
    const total = rate * track.duration;
    const data = Buffer.alloc(total * 2);
    const per = Math.floor(total / track.notes.length);
    for (let i = 0; i < total; i++) {
        const f = track.notes[Math.min(track.notes.length - 1, Math.floor(i / per))];
        const phase = (i * f) / rate;
        const s = shape === 'square' ? (phase % 1 < 0.5 ? 1 : -1) * 0.25 : Math.sin(2 * Math.PI * phase) * 0.5;
        const fade = Math.min(1, (i % per) / 400, (per - (i % per)) / 400);
        data.writeInt16LE(Math.round(s * fade * 32767 * 0.6), i * 2);
    }
    const head = Buffer.alloc(44);
    head.write('RIFF', 0); head.writeUInt32LE(36 + data.length, 4); head.write('WAVEfmt ', 8);
    head.writeUInt32LE(16, 16); head.writeUInt16LE(1, 20); head.writeUInt16LE(1, 22);
    head.writeUInt32LE(rate, 24); head.writeUInt32LE(rate * 2, 28); head.writeUInt16LE(2, 32); head.writeUInt16LE(16, 34);
    head.write('data', 36); head.writeUInt32LE(data.length, 40);
    return Buffer.concat([head, data]);
};

const preparing = new Map();

const server = http.createServer((request, response) => {
    const url = new URL(request.url, `http://${request.headers.host}`);
    const origin = `http://${request.headers.host}`;
    const json = (status, body, headers = {}) => {
        response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*', 'Access-Control-Expose-Headers': 'Retry-After', ...headers });
        response.end(JSON.stringify(body));
    };
    const parts = url.pathname.split('/').filter(Boolean).map(decodeURIComponent);
    const find = (list, id) => list.find((x) => x.id === id);
    const q = (url.searchParams.get('q') || '').toLowerCase();
    const contains = (text) => text.toLowerCase().includes(q);

    if (request.method === 'OPTIONS') {
        response.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*' }).end();
        return;
    }
    if (parts[0] === 'manifest.json') return json(200, manifest(origin));

    if (parts[0] === 'search') {
        const notice = q.includes('notice') ? { notice: { level: 'info', title: 'Hello', message: 'This is a notice from the test addon.' } } : {};
        return json(200, {
            tracks: TRACKS.filter((t) => contains(t.title) || contains(t.artist) || contains(t.album)).map(trackJson),
            albums: ALBUMS.filter((a) => contains(a.title) || contains(a.artist)).map(albumJson),
            artists: ARTISTS.filter((a) => contains(a.name)).map((a) => ({ id: a.id, name: a.name, genres: a.genres })),
            ...notice,
        });
    }

    if (parts[0] === 'stream' && parts[1]) {
        const t = find(TRACKS, parts[1]);
        if (!t) return json(404, { error: 'No such track', notice: { level: 'error', message: 'The test addon has no such track.' } });
        if (t.slow) {
            const n = (preparing.get(t.id) || 0) + 1;
            preparing.set(t.id, n);
            if (n % 3 !== 0) return json(503, { notice: { id: 'slow', level: 'info', message: 'Preparing this song…' } }, { 'Retry-After': '2' });
        }
        return json(200, { url: `${origin}/audio/${t.id}.wav?tone=${url.searchParams.get('tone') || 'sine'}`, format: 'wav', quality: 'test tone' });
    }

    if (parts[0] === 'audio' && parts[1]) {
        const t = find(TRACKS, parts[1].replace(/\.wav$/, ''));
        if (!t) { response.writeHead(404).end(); return; }
        const body = wav(t, url.searchParams.get('tone'));
        response.writeHead(200, { 'Content-Type': 'audio/wav', 'Content-Length': body.length, 'Access-Control-Allow-Origin': '*', 'Accept-Ranges': 'none' });
        response.end(body);
        return;
    }

    if (parts[0] === 'album' && parts[1]) {
        const a = find(ALBUMS, parts[1]);
        if (!a) return json(404, {});
        return json(200, { ...albumJson(a), tracks: a.trackIds.map((id) => trackJson(find(TRACKS, id))) });
    }

    if (parts[0] === 'artist' && parts[1]) {
        const a = find(ARTISTS, parts[1]);
        if (!a) return json(404, {});
        return json(200, { id: a.id, name: a.name, genres: a.genres, bio: 'Not a real band.', topTracks: a.trackIds.map((id) => trackJson(find(TRACKS, id))), albums: a.albumIds.map((id) => albumJson(find(ALBUMS, id))) });
    }

    if (parts[0] === 'resolve-isrc') {
        const t = TRACKS.find((x) => x.isrc === url.searchParams.get('isrc'));
        return t ? json(200, { trackId: t.id }) : json(404, { trackId: null });
    }

    if (parts[0] === 'resolve') {
        const isrc = url.searchParams.get('isrc');
        const t = isrc ? TRACKS.find((x) => x.isrc === isrc) : TRACKS.find((x) => x.title.toLowerCase() === (url.searchParams.get('title') || '').toLowerCase() && x.artist.toLowerCase() === (url.searchParams.get('artist') || '').toLowerCase());
        return json(200, { item: t ? { ...trackJson(t), type: 'track' } : null });
    }

    if (parts[0] === 'catalog' && parts[1]) {
        const skip = parseInt(url.searchParams.get('skip'), 10) || 0;
        if (skip > 0) return json(200, { items: [] });
        if (parts[1] === 'songs') return json(200, { items: TRACKS.map((t) => ({ ...trackJson(t), type: 'track', durationMs: t.duration * 1000 })) });
        if (parts[1] === 'albums') return json(200, { items: ALBUMS.map((a) => ({ ...albumJson(a), type: 'album' })) });
        return json(404, {});
    }

    json(404, { error: 'Not found' });
});

if (require.main === module) {
    server.listen(PORT, '127.0.0.1', () => console.log(`Codio test addon: http://127.0.0.1:${PORT}/manifest.json`));
}

module.exports = server;
