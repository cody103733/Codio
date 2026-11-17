// What you like, kept on this computer only: the artists you play, the songs, albums and artists you heart, and the ones
// you said "not interested" to. The Music home builds its rows from this (and nothing leaves the computer: only artist
// ids go to ListenBrainz when asking for similar artists, the same as a search would).

import { useSyncExternalStore } from 'react';
import type { MusicAlbum, MusicArtist, MusicTrack } from './types';

const KEY = 'codioMusicTaste';

export type Taste = {
    // Artist (MusicBrainz id) -> how often you played them.
    plays: Record<string, { name: string, count: number, last: number }>,
    likedTracks: MusicTrack[],
    likedAlbums: MusicAlbum[],
    likedArtists: MusicArtist[],
    // id -> name, for the lists in Music > Addons where you can bring them back.
    hiddenArtists: Record<string, string>,
    hiddenAlbums: Record<string, string>,
    hiddenTracks: Record<string, string>,
};

const empty = (): Taste => ({ plays: {}, likedTracks: [], likedAlbums: [], likedArtists: [], hiddenArtists: {}, hiddenAlbums: {}, hiddenTracks: {} });

const read = (): Taste => {
    try {
        const t = JSON.parse(window.localStorage.getItem(KEY) ?? '{}');
        return { ...empty(), ...t };
    } catch (_e) {
        return empty();
    }
};

let taste: Taste = read();
const listeners = new Set<() => void>();

const commit = (next: Taste) => {
    taste = next;
    try {
        window.localStorage.setItem(KEY, JSON.stringify(taste));
    } catch (_e) { /* storage full or off */ }
    listeners.forEach((l) => l());
};

export const useTaste = (): Taste => useSyncExternalStore((l) => { listeners.add(l); return () => { listeners.delete(l); }; }, () => taste);

export const getTaste = () => taste;

const trackKey = (t: { id: string, addon?: string | null }) => `${t.addon ?? ''}|${t.id}`;
const sameTrack = (a: MusicTrack, b: MusicTrack) => trackKey(a) === trackKey(b);

// A track started playing.
export const recordPlay = (t: MusicTrack) => {
    if (!t.artistMbid) return;
    const prev = taste.plays[t.artistMbid];
    commit({ ...taste, plays: { ...taste.plays, [t.artistMbid]: { name: t.artist, count: (prev?.count ?? 0) + 1, last: Date.now() } } });
};

export const isLikedTrack = (t: Taste, track: MusicTrack) => t.likedTracks.some((x) => sameTrack(x, track));
export const isLikedAlbum = (t: Taste, a: { id: string }) => t.likedAlbums.some((x) => x.id === a.id);
export const isLikedArtist = (t: Taste, a: { id: string }) => t.likedArtists.some((x) => x.id === a.id);

// Told when you heart or un-heart a song (the ListenBrainz account, when signed in, keeps them in step).
export const likeHooks = new Set<(track: MusicTrack, liked: boolean) => void>();

export const toggleLikeTrack = (track: MusicTrack) => {
    const liked = isLikedTrack(taste, track);
    const hidden = { ...taste.hiddenTracks };
    delete hidden[trackKey(track)];
    commit({ ...taste, hiddenTracks: hidden, likedTracks: liked ? taste.likedTracks.filter((x) => !sameTrack(x, track)) : [track, ...taste.likedTracks].slice(0, 500) });
    likeHooks.forEach((h) => h(track, !liked));
};

// The songs loved on your ListenBrainz account (from any device). For songs with a MusicBrainz id the account is the
// truth: loved ones are added here, ones no longer loved are dropped, except those keep() says changed here just now.
export const applyLovedFromAccount = (lovedIds: Set<string>, loved: MusicTrack[], keep: (t: MusicTrack) => boolean) => {
    const kept = taste.likedTracks.filter((t) => !t.mbid || lovedIds.has(t.mbid) || keep(t));
    const have = new Set(kept.map((t) => t.mbid).filter(Boolean));
    const added = loved.filter((t) => t.mbid && !have.has(t.mbid) && !taste.hiddenTracks[trackKey(t)] && !keep(t));
    if (added.length === 0 && kept.length === taste.likedTracks.length) return;
    commit({ ...taste, likedTracks: [...kept, ...added].slice(0, 500) });
};

// Plays from your other devices (ListenBrainz listens): they count towards your taste like the ones here.
export const addPlaysFromAccount = (plays: { artistMbid: string, name: string, at: number }[]) => {
    if (plays.length === 0) return;
    const next = { ...taste.plays };
    plays.forEach((p) => {
        if (taste.hiddenArtists[p.artistMbid]) return;
        const prev = next[p.artistMbid];
        next[p.artistMbid] = { name: prev?.name ?? p.name, count: (prev?.count ?? 0) + 1, last: Math.max(prev?.last ?? 0, p.at) };
    });
    commit({ ...taste, plays: next });
};

export const toggleLikeAlbum = (album: MusicAlbum) => {
    const liked = isLikedAlbum(taste, album);
    const hidden = { ...taste.hiddenAlbums };
    delete hidden[album.id];
    commit({ ...taste, hiddenAlbums: hidden, likedAlbums: liked ? taste.likedAlbums.filter((x) => x.id !== album.id) : [album, ...taste.likedAlbums].slice(0, 300) });
};

export const toggleLikeArtist = (artist: MusicArtist) => {
    const liked = isLikedArtist(taste, artist);
    const hidden = { ...taste.hiddenArtists };
    delete hidden[artist.id];
    commit({ ...taste, hiddenArtists: hidden, likedArtists: liked ? taste.likedArtists.filter((x) => x.id !== artist.id) : [artist, ...taste.likedArtists].slice(0, 300) });
};

// "Not interested": gone from the rows, and no longer counts towards your taste.
export const hideTrack = (track: MusicTrack) => {
    const wasLiked = isLikedTrack(taste, track);
    commit({
        ...taste, likedTracks: taste.likedTracks.filter((x) => !sameTrack(x, track)), hiddenTracks: { ...taste.hiddenTracks, [trackKey(track)]: `${track.title} · ${track.artist}` },
    });
    if (wasLiked) likeHooks.forEach((h) => h(track, false));
};
export const hideAlbum = (album: MusicAlbum) => commit({
    ...taste, likedAlbums: taste.likedAlbums.filter((x) => x.id !== album.id), hiddenAlbums: { ...taste.hiddenAlbums, [album.id]: `${album.title} · ${album.artist}` },
});
export const hideArtist = (artist: { id: string, name: string }) => {
    const plays = { ...taste.plays };
    delete plays[artist.id];
    commit({
        ...taste, plays, likedArtists: taste.likedArtists.filter((x) => x.id !== artist.id), hiddenArtists: { ...taste.hiddenArtists, [artist.id]: artist.name },
    });
};
export const unhide = (kind: 'artist' | 'album' | 'track', key: string) => {
    const field = kind === 'artist' ? 'hiddenArtists' : kind === 'album' ? 'hiddenAlbums' : 'hiddenTracks';
    const next = { ...taste[field] };
    delete next[key];
    commit({ ...taste, [field]: next });
};
export const unhideAll = () => commit({ ...taste, hiddenArtists: {}, hiddenAlbums: {}, hiddenTracks: {} });
export const clearHistory = () => commit({ ...taste, plays: {} });
export const clearAll = () => commit(empty());

export const isHiddenTrack = (t: Taste, track: MusicTrack) => !!t.hiddenTracks[trackKey(track)] || (!!track.artistMbid && !!t.hiddenArtists[track.artistMbid]);
export const isHiddenAlbum = (t: Taste, a: MusicAlbum) => !!t.hiddenAlbums[a.id] || (!!a.artistMbid && !!t.hiddenArtists[a.artistMbid]);
export const isHiddenArtist = (t: Taste, a: { id: string }) => !!t.hiddenArtists[a.id];

export const visibleTracks = (t: Taste, list: MusicTrack[]) => list.filter((x) => !isHiddenTrack(t, x));
export const visibleAlbums = (t: Taste, list: MusicAlbum[]) => list.filter((x) => !isHiddenAlbum(t, x));
export const visibleArtists = (t: Taste, list: MusicArtist[]) => list.filter((x) => !isHiddenArtist(t, x));

export type Seed = { id: string, name: string, score: number };

// The artists you like most, best first. A play counts 1 (less as it gets old), a heart on the artist 6, a hearted song or
// album by them 3.
export const topSeeds = (t: Taste, limit = 8): Seed[] => {
    const scores = new Map<string, Seed>();
    const add = (id: string | null | undefined, name: string, points: number) => {
        if (!id || t.hiddenArtists[id]) return;
        const s = scores.get(id) ?? { id, name, score: 0 };
        s.score += points;
        scores.set(id, s);
    };
    const now = Date.now();
    Object.entries(t.plays).forEach(([id, p]) => add(id, p.name, p.count / (1 + (now - p.last) / (30 * 24 * 3600 * 1000))));
    t.likedArtists.forEach((a) => add(a.id, a.name, 6));
    t.likedTracks.forEach((x) => add(x.artistMbid, x.artist, 3));
    t.likedAlbums.forEach((x) => add(x.artistMbid, x.artist, 3));
    return Array.from(scores.values()).sort((a, b) => b.score - a.score).slice(0, limit);
};

// Artists similar to your favorites, put together: one that shows up for several favorites ranks higher.
export const mergeSimilar = (seeds: Seed[], lists: MusicArtist[][], t: Taste): MusicArtist[] => {
    const known = new Set(seeds.map((s) => s.id));
    const scored = new Map<string, { artist: MusicArtist, score: number }>();
    seeds.forEach((seed, i) => (lists[i] ?? []).forEach((a, rank) => {
        if (known.has(a.id) || t.hiddenArtists[a.id]) return;
        const entry = scored.get(a.id) ?? { artist: a, score: 0 };
        entry.score += seed.score / (1 + rank * 0.2);
        scored.set(a.id, entry);
    }));
    return Array.from(scored.values()).sort((a, b) => b.score - a.score).slice(0, 24).map((e) => e.artist);
};

// The same shuffle all day, a different one tomorrow.
export const dayShuffle = <T,>(list: T[], salt = ''): T[] => {
    const day = new Date();
    let seed = 0;
    const text = `${day.getFullYear()}-${day.getMonth()}-${day.getDate()}${salt}`;
    for (let i = 0; i < text.length; i++) seed = (seed * 31 + text.charCodeAt(i)) >>> 0;
    const rand = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
    const copy = list.slice();
    for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(rand() * (i + 1));
        [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
};
