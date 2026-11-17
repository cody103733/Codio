// The Daily mix: 50 to 100 songs made for you, new every day and steady within the day.
//
// It blends four sources:
//   1. Your ListenBrainz "Daily Jams" (when you're signed in and ListenBrainz has made one for you).
//   2. Songs from the artists you play and heart (a few each, a different few every day), and songs you hearted.
//   3. Songs from artists like those (ListenBrainz similar artists, two or three each).
//   4. A handful from your ListenBrainz "Weekly Exploration".
// No song twice (the same title by the same artist counts as the same song), at most two per artist (three when the mix
// would be short otherwise), nothing you marked Not interested. Songs found later in the day (new listens, a fresh Daily
// Jams) are added at the end, so the mix you're in the middle of doesn't reshuffle.
//
// The same rules are in android/src/com/codio/tv/music/DailyMix.java and ios/Codio/Music/DailyMix.swift.

import type { MusicArtist, MusicTrack } from './types';
import { getTopTracks, releaseCoverUrl } from './musicbrainz';
import { dayShuffle, isHiddenTrack, type Seed, type Taste } from './taste';

const enc = encodeURIComponent;
const MBID = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/;
const MAX = 100;
const ENOUGH = 50;
const STORE = 'codioDailyMix';
// Daily Jams, artists like yours, your artists, Daily Jams, artists like yours, hearted songs, Weekly Exploration.
const ORDER = [0, 3, 1, 0, 3, 2, 4];

export const today = () => {
    const d = new Date();
    return `${d.getFullYear()}-${d.getMonth() + 1}-${d.getDate()}`;
};

const lb = async (path: string): Promise<any> => {
    try {
        const response = await fetch(`/api/music/lb?p=${enc(path)}`, { headers: { 'X-Codio': '1' } });
        return response.ok && response.status !== 204 ? await response.json() : null;
    } catch (_e) {
        return null;
    }
};

const TRACK_EXT = 'https://musicbrainz.org/doc/jspf#track';
const PLAYLIST_EXT = 'https://musicbrainz.org/doc/jspf#playlist';

const playlistTrack = (t: any): MusicTrack | null => {
    const ids: unknown[] = Array.isArray(t?.identifier) ? t.identifier : [t?.identifier];
    const mbid = ids.map((x) => MBID.exec(String(x ?? ''))?.[0]).find(Boolean);
    if (!mbid || !t?.title) return null;
    const meta = t.extension?.[TRACK_EXT]?.additional_metadata ?? {};
    const artist = Array.isArray(meta.artists) ? meta.artists[0] : null;
    return {
        id: `mbid:${mbid}`,
        mbid,
        title: String(t.title),
        artist: String(t.creator ?? artist?.artist_credit_name ?? ''),
        album: t.album ?? null,
        durationMs: typeof t.duration === 'number' ? t.duration : null,
        artworkURL: meta.caa_release_mbid ? releaseCoverUrl(String(meta.caa_release_mbid)) : null,
        artistMbid: artist?.artist_mbid ? String(artist.artist_mbid) : null,
        isrc: null,
    };
};

// The newest Daily Jams and Weekly Exploration ListenBrainz made for this user (they're public). Empty when there are none.
export const madeForYou = async (user: string): Promise<{ jams: MusicTrack[], explore: MusicTrack[] }> => {
    const list = await lb(`/1/user/${enc(user)}/playlists/createdfor?count=25`);
    const playlists: any[] = (Array.isArray(list?.playlists) ? list.playlists : []).map((p: any) => p?.playlist).filter(Boolean);
    const newest = (patch: string) => playlists
        .filter((p) => p.extension?.[PLAYLIST_EXT]?.additional_metadata?.algorithm_metadata?.source_patch === patch)
        .sort((a, b) => String(b.date ?? '').localeCompare(String(a.date ?? '')))[0];
    const tracks = async (p: any): Promise<MusicTrack[]> => {
        const id = p ? MBID.exec(String(p.identifier ?? ''))?.[0] : null;
        if (!id) return [];
        const j = await lb(`/1/playlist/${id}`);
        return (Array.isArray(j?.playlist?.track) ? j.playlist.track : []).map(playlistTrack).filter(Boolean) as MusicTrack[];
    };
    const [jams, explore] = await Promise.all([tracks(newest('daily-jams')), tracks(newest('weekly-exploration'))]);
    return { jams, explore };
};

const norm = (s: string) => s.toLowerCase().replace(/\s*[([].*?[)\]]\s*/g, ' ').replace(/\s+-\s+.*$/, '').replace(/[^a-z0-9\u00c0-\uffff]+/g, ' ').trim();
const songKey = (t: MusicTrack) => `${norm(t.artist)}|${norm(t.title)}`;
const artistKey = (t: MusicTrack) => t.artistMbid || norm(t.artist);

// A different few of an artist's best-known songs each day (each song once, live and remastered versions folded in).
export const pickTop = (top: MusicTrack[], count: number, salt: string): MusicTrack[] => {
    const seen = new Set<string>();
    const unique = top.filter((t) => { const k = norm(t.title); return !seen.has(k) && !!seen.add(k); }).slice(0, 8);
    return dayShuffle(unique, salt).slice(0, count);
};

// Put the sources together: a song from each in turn, no repeats, a cap per artist, nothing hidden, no artist twice in a row.
// order says which group each turn draws from, e.g. [0, 1, 0, 2].
export const blend = (groups: { tracks: MusicTrack[] }[], order: number[], taste: Taste): MusicTrack[] => {
    const out: MusicTrack[] = [];
    const songs = new Set<string>();
    const perArtist = new Map<string, number>();
    const pass = (cap: number) => {
        const at = groups.map(() => 0);
        let left = true;
        while (left && out.length < MAX) {
            left = false;
            for (const i of order) {
                const g = groups[i].tracks;
                while (at[i] < g.length) {
                    const t = g[at[i]++];
                    const a = artistKey(t);
                    if (songs.has(songKey(t)) || (t.mbid && songs.has(t.mbid)) || isHiddenTrack(taste, t) || (perArtist.get(a) ?? 0) >= cap) continue;
                    songs.add(songKey(t));
                    if (t.mbid) songs.add(t.mbid);
                    perArtist.set(a, (perArtist.get(a) ?? 0) + 1);
                    out.push(t);
                    break;
                }
                if (at[i] < g.length) left = true;
                if (out.length >= MAX) break;
            }
        }
    };
    pass(2);
    if (out.length < ENOUGH) pass(3);
    // Spread out the same artist.
    for (let i = 1; i < out.length; i++) {
        if (artistKey(out[i]) !== artistKey(out[i - 1])) continue;
        const j = out.findIndex((t, k) => k > i && artistKey(t) !== artistKey(out[i]));
        if (j > i) [out[i], out[j]] = [out[j], out[i]];
    }
    return out;
};

// Today's mix stays in the order it had; songs that are new since then go at the end.
export const keepToday = (fresh: MusicTrack[], taste: Taste): MusicTrack[] => {
    let saved: { day: string, tracks: MusicTrack[] } | null = null;
    try { saved = JSON.parse(window.localStorage.getItem(STORE) ?? 'null'); } catch (_e) { saved = null; }
    const before = saved && saved.day === today() && Array.isArray(saved.tracks) ? saved.tracks : [];
    const songs = new Set(before.map(songKey));
    const merged = [...before, ...fresh.filter((t) => !songs.has(songKey(t)))].filter((t) => !isHiddenTrack(taste, t)).slice(0, MAX);
    try { window.localStorage.setItem(STORE, JSON.stringify({ day: today(), tracks: merged })); } catch (_e) { /* storage full */ }
    return merged;
};

// Today's mix as it was last built, shown right away while the new one is put together.
export const savedMix = (): MusicTrack[] => {
    try {
        const saved = JSON.parse(window.localStorage.getItem(STORE) ?? 'null');
        return saved && saved.day === today() && Array.isArray(saved.tracks) ? saved.tracks : [];
    } catch (_e) {
        return [];
    }
};

export const buildDailyMix = async (seeds: Seed[], similar: MusicArtist[], taste: Taste, user: string | null): Promise<MusicTrack[]> => {
    const day = today();
    const mine = seeds.slice(0, 8);
    // Artists like yours: the closest ones always, the rest a different set each day.
    const like = [...similar.slice(0, 8), ...dayShuffle(similar.slice(8), `like${day}`)].slice(0, 16);
    const [lists, likeLists, made] = await Promise.all([
        Promise.all(mine.map((s) => getTopTracks(s.id, s.name).catch(() => []))),
        Promise.all(like.map((a) => getTopTracks(a.id, a.name).catch(() => []))),
        user ? madeForYou(user).catch(() => ({ jams: [], explore: [] })) : Promise.resolve({ jams: [], explore: [] }),
    ]);
    const own: MusicTrack[] = [];
    const ownLists = lists.map((l, i) => pickTop(l, 4, `own${mine[i].id}`));
    for (let r = 0; r < 4; r++) ownLists.forEach((l) => { if (l[r]) own.push(l[r]); });
    const hearted = dayShuffle(taste.likedTracks, 'liked').slice(0, 12);
    const likeTracks: MusicTrack[] = [];
    const likePicked = likeLists.map((l, i) => pickTop(l, 3, `like${like[i].id}`));
    for (let r = 0; r < 3; r++) likePicked.forEach((l) => { if (l[r]) likeTracks.push(l[r]); });
    const fresh = blend([
        { tracks: made.jams },
        { tracks: own },
        { tracks: hearted },
        { tracks: likeTracks },
        { tracks: dayShuffle(made.explore, 'explore').slice(0, 15) },
    ], ORDER, taste);
    return keepToday(fresh, taste);
};
