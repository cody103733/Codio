// Your stats: watching from the profile's library, listening from a play log on this PC. Same rules as
// android/.../ui/Stats.java and ios/Codio/Store/Stats.swift (DESIGN.md "Your stats"). Nothing is sent anywhere.

import type { MusicTrack } from './music/types';

const WATCH = 'codioStatsWatch';
const LISTEN = 'codioStatsListen';
export const MAX_SONGS = 2000;

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

// Who is signed in (same id the core keeps), '' before sign-in.
export const profileId = (): string => {
    try {
        const p = JSON.parse(window.localStorage.getItem('profile') ?? 'null');
        const user = p?.auth?.user;
        return String(user?._id ?? user?.email ?? '');
    } catch (_e) {
        return '';
    }
};

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

// Called whenever the stats or Settings are shown: the first time in a new year, every title's time is saved as that
// year's start (only when this PC has already seen an earlier year, so the year stats came counts in full).
export const observeLibrary = (lib: LibraryEntry[], profile: string) => {
    if (lib.length === 0) return;
    const year = thisYear();
    const all = readJson(WATCH);
    const mine = all[profile] ?? {};
    const seen = typeof mine.seen === 'number' ? mine.seen : 0;
    if (seen >= year) return;
    if (seen > 0) {
        const start: Record<string, number> = {};
        lib.forEach((e) => { if (e.overall > 0) start[e.id] = e.overall; });
        const base = { ...(mine.base ?? {}), [String(year)]: start };
        Object.keys(base).forEach((k) => { if (!(Number(k) >= year - 4)) delete base[k]; });
        mine.base = base;
    }
    mine.seen = year;
    all[profile] = mine;
    writeJson(WATCH, all);
};

let checked = '';
// observeLibrary for the current profile, at most once per run and year (cheap to call from Home).
export const observeNow = () => {
    const key = `${profileId()}|${thisYear()}`;
    if (checked === key) return;
    const lib = libraryEntries();
    if (lib.length === 0) return;
    checked = key;
    observeLibrary(lib, profileId());
};

export type Title = { entry: LibraryEntry, ms: number };
export type Watch = { ms: number, shows: Title[], movies: Title[] };

// year 0 = all time.
export const watchStats = (lib: LibraryEntry[], profile: string, year: number): Watch => {
    const start: Record<string, number> | null = year > 0 ? readJson(WATCH)[profile]?.base?.[String(year)] ?? null : null;
    const out: Watch = { ms: 0, shows: [], movies: [] };
    lib.forEach((e) => {
        if ((e.type !== 'movie' && e.type !== 'series') || e.overall <= 0) return;
        let ms = e.overall;
        if (year > 0) {
            if (!e.lastWatched || !e.lastWatched.startsWith(String(year))) return;
            if (start) ms -= Number(start[e.id]) || 0;
        }
        if (ms < 60000) return;
        out.ms += ms;
        (e.type === 'series' ? out.shows : out.movies).push({ entry: e, ms });
    });
    out.shows.sort((a, b) => b.ms - a.ms);
    out.movies.sort((a, b) => b.ms - a.ms);
    return out;
};

// Hours with one decimal under 10 ("2.5", "14").
export const hours = (ms: number) => {
    const h = ms / 3600000;
    return h < 10 ? h.toFixed(1) : String(Math.round(h));
};

export const count = (n: number, word: string) => n === 1 ? `1 ${word}` : `${n} ${word}s`;

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
};

// year 0 = all years added together.
export const listenStats = (year: number): Listen => {
    const mine = readJson(LISTEN)[profileId()] ?? {};
    const songs = new Map<string, { track: MusicTrack, count: number }>();
    const artists = new Map<string, { name: string, count: number }>();
    const out: Listen = { seconds: 0, plays: 0, songs: [], artists: [] };
    Object.keys(mine).forEach((k) => {
        if (year > 0 && k !== String(year)) return;
        const y = mine[k] as ListenYear;
        if (!y || typeof y !== 'object') return;
        out.plays += Number(y.plays) || 0;
        out.seconds += Number(y.seconds) || 0;
        Object.keys(y.songs ?? {}).forEach((sk) => {
            const s = y.songs[sk];
            if (!s?.track) return;
            const e = songs.get(sk) ?? { track: s.track, count: 0 };
            e.count += Number(s.count) || 0;
            songs.set(sk, e);
        });
        Object.keys(y.artists ?? {}).forEach((ak) => {
            const a = y.artists[ak];
            const e = artists.get(ak) ?? { name: a?.name ?? ak, count: 0 };
            e.count += Number(a?.count) || 0;
            artists.set(ak, e);
        });
    });
    out.songs = Array.from(songs.values()).sort((a, b) => b.count - a.count);
    out.artists = Array.from(artists.values()).sort((a, b) => b.count - a.count);
    return out;
};
