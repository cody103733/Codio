// Your stats. Codio counts it itself: the seconds a video really played (from the player's clock, so pauses and jumps
// don't count) and the songs that started, on this PC, for each profile. Every device keeps its own counts in the
// account (hidden library entry codio:stats, one slot per device), so all of them show the same totals. Same rules and
// format as android/.../ui/Stats.java and ios/Codio/Store/Stats.swift (DESIGN.md "Your stats").

import type { MusicTrack } from './music/types';

const WATCH = 'codioStatsWatch2';
const LISTEN = 'codioStatsListen';
const REMOTE = 'codioStatsRemote';
const DEVICE = 'codioStatsDevice';
const ITEM_ID = 'codio:stats';
const API = 'https://api.strem.io/api/';
export const MAX_SONGS = 2000;
const SYNC_TITLES = 400;
const SYNC_SONGS = 150;
const SYNC_ARTISTS = 100;

export const thisYear = () => new Date().getFullYear();

const readJson = (key: string): any => {
    try {
        const v = JSON.parse(window.localStorage.getItem(key) ?? '{}');
        return v && typeof v === 'object' ? v : {};
    } catch (_e) {
        return {};
    }
};

const writeJson = (key: string, value: any) => {
    try {
        window.localStorage.setItem(key, JSON.stringify(value));
    } catch (_e) { /* storage full or off */ }
};

const savedProfile = (): any => {
    try {
        return JSON.parse(window.localStorage.getItem('profile') ?? 'null');
    } catch (_e) {
        return null;
    }
};

// Who is signed in (same id the core keeps), '' before sign-in.
export const profileId = (): string => {
    const user = savedProfile()?.auth?.user;
    return String(user?._id ?? user?.email ?? '');
};

const deviceId = (): string => {
    let id = window.localStorage.getItem(DEVICE);
    if (!id) {
        id = `windows-${Math.random().toString(16).slice(2, 10)}`;
        try { window.localStorage.setItem(DEVICE, id); } catch (_e) { /* storage off */ }
    }
    return id;
};

let dirty = true;

export type LibraryEntry = { id: string, type: string, name: string, poster: string | null, overall: number, lastWatched: string | null };

// The whole library as the engine saved it ('library', then 'library_recent', which is newer), removed and temp titles too.
export const libraryEntries = (): LibraryEntry[] => {
    const out = new Map<string, LibraryEntry>();
    ['library', 'library_recent'].forEach((key) => {
        const items = readJson(key)?.items;
        if (!items || typeof items !== 'object') return;
        Object.keys(items).forEach((k) => {
            const it = items[k];
            if (!it || typeof it !== 'object') return;
            const id = String(it._id ?? k);
            out.set(id, {
                id,
                type: String(it.type ?? ''),
                name: String(it.name ?? ''),
                poster: typeof it.poster === 'string' ? it.poster : null,
                overall: Number(it.state?.overallTimeWatched) || 0,
                lastWatched: typeof it.state?.lastWatched === 'string' ? it.state.lastWatched : null,
            });
        });
    });
    return Array.from(out.values());
};

// ---- Counting what plays

type PlayingMeta = { id: string, type: string, name: string, poster: string | null };
let playingId: string | null = null;
let lastPos = -1;
let pendingMs = 0;
let pendingMeta: PlayingMeta | null = null;
let lastFlush = Date.now();

const flush = () => {
    lastFlush = Date.now();
    const m = pendingMeta;
    if (pendingMs <= 0 || !m) {
        pendingMs = 0;
        return;
    }
    const add = pendingMs;
    pendingMs = 0;
    const all = readJson(WATCH);
    const profile = profileId();
    const mine = all[profile] ?? {};
    if (!mine.since) mine.since = Date.now();
    const years = mine.years ?? {};
    const year = years[String(thisYear())] ?? {};
    const prev = Array.isArray(year[m.id]) ? Number(year[m.id][0]) || 0 : 0;
    year[m.id] = [Math.round(prev + add), m.type, m.name, m.poster];
    years[String(thisYear())] = year;
    mine.years = years;
    all[profile] = mine;
    writeJson(WATCH, all);
    dirty = true;
};

// The player's clock moved to pos (seconds) while m plays. Only forward steps of up to 3 s count, so a pause, a seek or a
// source switch adds nothing. Saved every 30 s of watching and when the video stops.
export const watchTick = (m: PlayingMeta, pos: number) => {
    if (!m.id) return;
    if (m.id !== playingId) {
        flush();
        playingId = m.id;
        lastPos = pos;
        return;
    }
    const d = pos - lastPos;
    lastPos = pos;
    if (d > 0 && d <= 3) {
        pendingMs += d * 1000;
        pendingMeta = m;
    }
    if (pendingMs >= 30000 || (pendingMs > 0 && Date.now() - lastFlush > 60000)) flush();
};

export const watchStopped = () => {
    flush();
    playingId = null;
    lastPos = -1;
};

// ---- Listening

type ListenYear = { seconds: number, plays: number, songs: Record<string, { track: MusicTrack, count: number }>, artists: Record<string, { name: string, count: number }> };
export type Listen = { seconds: number, plays: number, songs: { track: MusicTrack, count: number }[], artists: { name: string, count: number }[] };

const norm = (s: string | null | undefined) => (s ?? '').trim().toLowerCase();

// A song started playing.
export const recordListen = (t: MusicTrack) => {
    const all = readJson(LISTEN);
    const profile = profileId();
    const mine = all[profile] ?? {};
    const year = String(thisYear());
    const y: ListenYear = { seconds: 0, plays: 0, songs: {}, artists: {}, ...(mine[year] ?? {}) };
    y.plays += 1;
    y.seconds += Math.max(0, (t.durationMs ?? 0) / 1000);
    const sk = `${norm(t.artist)}|${norm(t.title)}`;
    const prev = y.songs[sk];
    y.songs[sk] = { track: prev && !t.artworkURL ? prev.track : t, count: (prev?.count ?? 0) + 1 };
    const keys = Object.keys(y.songs);
    if (keys.length > MAX_SONGS) {
        keys.sort((a, b) => y.songs[b].count - y.songs[a].count).slice(MAX_SONGS).forEach((k) => { delete y.songs[k]; });
    }
    const ak = norm(t.artist);
    if (ak) y.artists[ak] = { name: t.artist, count: (y.artists[ak]?.count ?? 0) + 1 };
    mine[year] = y;
    all[profile] = mine;
    writeJson(LISTEN, all);
    dirty = true;
};

// ---- This PC's slot in the account, and everyone's together

const mySlot = (profile: string): any => {
    const w = readJson(WATCH)[profile] ?? {};
    const watch: Record<string, any> = {};
    Object.keys(w.years ?? {}).forEach((y) => {
        const titles = w.years[y] ?? {};
        const keep: Record<string, any> = {};
        Object.keys(titles).sort((a, b) => (Number(titles[b]?.[0]) || 0) - (Number(titles[a]?.[0]) || 0)).slice(0, SYNC_TITLES)
            .forEach((id) => { keep[id] = titles[id]; });
        watch[y] = keep;
    });
    const l = readJson(LISTEN)[profile] ?? {};
    const listen: Record<string, any> = {};
    Object.keys(l).forEach((y) => {
        const v = l[y] as ListenYear;
        if (!v || typeof v !== 'object') return;
        const songs: Record<string, any> = {};
        Object.keys(v.songs ?? {}).sort((a, b) => v.songs[b].count - v.songs[a].count).slice(0, SYNC_SONGS)
            .forEach((k) => { songs[k] = [v.songs[k].count, v.songs[k].track]; });
        const artists: Record<string, any> = {};
        Object.keys(v.artists ?? {}).sort((a, b) => v.artists[b].count - v.artists[a].count).slice(0, SYNC_ARTISTS)
            .forEach((k) => { artists[k] = [v.artists[k].count, v.artists[k].name]; });
        listen[y] = { s: Math.round(Number(v.seconds) || 0), p: Number(v.plays) || 0, songs, artists };
    });
    return { kind: 'windows', at: Date.now(), since: w.since ?? undefined, watch, listen };
};

// Every device's slot for the profile: the others as last read from the account, this PC's as it is now.
const slots = (): any[] => {
    const profile = profileId();
    const others = readJson(REMOTE)[profile] ?? {};
    const me = deviceId();
    const out = Object.keys(others).filter((id) => id !== me && others[id] && typeof others[id] === 'object').map((id) => others[id]);
    out.push(mySlot(profile));
    return out;
};

// ---- Account sync

const call = async (method: string, body: unknown): Promise<any> => {
    const response = await fetch(`${API}${method}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!response.ok) throw new Error(`Stremio answered ${response.status}`);
    const json = await response.json();
    if (json?.error) throw new Error(String(json.error?.message ?? json.error));
    return json;
};

const hiddenItem = (name: string) => {
    const now = new Date().toISOString();
    return {
        _id: ITEM_ID, name, type: 'other', poster: null, posterShape: 'poster', removed: true, temp: true, _ctime: now, _mtime: now,
        state: { lastWatched: null, timeWatched: 0, timeOffset: 0, overallTimeWatched: 0, timesWatched: 0, flaggedWatched: 0, duration: 0, video_id: null, watched: null, noNotif: true },
        behaviorHints: { defaultVideoId: null, featuredVideoId: null, hasScheduledVideos: false },
    };
};

let busy = false;
let lastSync = 0;

// Puts this PC's counts in the account and reads the other devices'. At most every 10 minutes unless forced.
export const syncStats = async (force = false) => {
    const authKey = savedProfile()?.auth?.key;
    if (busy || typeof authKey !== 'string' || !authKey) return;
    if (!force && Date.now() - lastSync < 10 * 60000) return;
    busy = true;
    lastSync = Date.now();
    const profile = profileId();
    try {
        flush();
        const r = await call('datastoreGet', { authKey, collection: 'libraryItem', ids: [ITEM_ID], all: false });
        const item = Array.isArray(r?.result) ? r.result.find((i: any) => i && i._id === ITEM_ID) : null;
        let devices: Record<string, any> = {};
        try {
            const box = item && typeof item.name === 'string' && item.name ? JSON.parse(item.name) : null;
            if (box && box.devices && typeof box.devices === 'object') devices = box.devices;
        } catch (_e) { /* unreadable: start again */ }
        const remote = readJson(REMOTE);
        remote[profile] = devices;
        writeJson(REMOTE, remote);
        const me = deviceId();
        if (dirty || !devices[me]) {
            devices[me] = mySlot(profile);
            await call('datastorePut', { authKey, collection: 'libraryItem', changes: [hiddenItem(JSON.stringify({ v: 1, devices }))] });
            dirty = false;
        }
    } catch (_e) {
        lastSync = 0;
    } finally {
        busy = false;
    }
};

// Kept for Home: a cheap call that syncs now and then.
export const observeNow = () => { void syncStats(false); };

// ---- What the stats page shows

export type Title = { entry: LibraryEntry, ms: number };
export type Watch = { ms: number, before: number, since: number, shows: Title[], movies: Title[] };

// year 0 = all time. lib gives names, posters and the older totals for "before Codio".
export const watchStats = (lib: LibraryEntry[], _profile: string, year: number): Watch => {
    const out: Watch = { ms: 0, before: 0, since: 0, shows: [], movies: [] };
    const ms = new Map<string, number>();
    const info = new Map<string, any[]>();
    slots().forEach((slot) => {
        const since = Number(slot?.since) || 0;
        if (since > 0 && (out.since === 0 || since < out.since)) out.since = since;
        const watch = slot?.watch ?? {};
        Object.keys(watch).forEach((y) => {
            if (year > 0 && y !== String(year)) return;
            const titles = watch[y] ?? {};
            Object.keys(titles).forEach((id) => {
                const e = titles[id];
                if (!Array.isArray(e)) return;
                ms.set(id, (ms.get(id) ?? 0) + (Number(e[0]) || 0));
                info.set(id, e);
            });
        });
    });
    const byId = new Map(lib.map((e) => [e.id, e]));
    ms.forEach((t, id) => {
        if (t < 60000) return;
        const e = info.get(id) ?? [];
        const entry: LibraryEntry = byId.get(id) ?? { id, type: String(e[1] ?? 'movie'), name: String(e[2] ?? ''), poster: typeof e[3] === 'string' ? e[3] : null, overall: 0, lastWatched: null };
        const type = entry.type || String(e[1] ?? 'movie');
        if (type !== 'movie' && type !== 'series') return;
        out.ms += t;
        (type === 'series' ? out.shows : out.movies).push({ entry, ms: t });
    });
    if (year === 0) {
        lib.forEach((e) => {
            if ((e.type === 'movie' || e.type === 'series') && e.overall > 0) out.before += Math.max(0, e.overall - (ms.get(e.id) ?? 0));
        });
    }
    out.shows.sort((a, b) => b.ms - a.ms);
    out.movies.sort((a, b) => b.ms - a.ms);
    return out;
};

export const sinceLine = (w: Watch) => w.since > 0
    ? `Counted by Codio on all your devices since ${new Date(w.since).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })}.`
    : 'Codio counts what plays on all your devices, from now on.';

// Hours with one decimal under 10 ("2.5", "14").
export const hours = (ms: number) => {
    const h = ms / 3600000;
    return h < 10 ? h.toFixed(1) : String(Math.round(h));
};

export const count = (n: number, word: string) => n === 1 ? `1 ${word}` : `${n} ${word}s`;

// year 0 = all years added together; every device of the profile.
export const listenStats = (year: number): Listen => {
    const songs = new Map<string, { track: MusicTrack, count: number }>();
    const artists = new Map<string, { name: string, count: number }>();
    const out: Listen = { seconds: 0, plays: 0, songs: [], artists: [] };
    slots().forEach((slot) => {
        const listen = slot?.listen ?? {};
        Object.keys(listen).forEach((k) => {
            if (year > 0 && k !== String(year)) return;
            const y = listen[k];
            if (!y || typeof y !== 'object') return;
            out.plays += Number(y.p) || 0;
            out.seconds += Number(y.s) || 0;
            Object.keys(y.songs ?? {}).forEach((sk) => {
                const v = y.songs[sk];
                if (!Array.isArray(v) || !v[1]) return;
                const e = songs.get(sk) ?? { track: v[1] as MusicTrack, count: 0 };
                e.count += Number(v[0]) || 0;
                songs.set(sk, e);
            });
            Object.keys(y.artists ?? {}).forEach((ak) => {
                const v = y.artists[ak];
                if (!Array.isArray(v)) return;
                const e = artists.get(ak) ?? { name: String(v[1] ?? ak), count: 0 };
                e.count += Number(v[0]) || 0;
                artists.set(ak, e);
            });
        });
    });
    out.songs = Array.from(songs.values()).sort((a, b) => b.count - a.count);
    out.artists = Array.from(artists.values()).sort((a, b) => b.count - a.count);
    return out;
};
