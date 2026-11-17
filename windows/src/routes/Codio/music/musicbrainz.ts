// MusicBrainz and the Cover Art Archive: artists, albums, tracklists with ISRC codes, artwork.
// Calls go through Codio's own server (desktop/codio-music.js), which sends Codio's User-Agent, keeps to one request a
// second and caches what it gets. The server address is a setting, so a self-hosted mirror works without code changes.

import type { MusicAlbum, MusicArtist, MusicTrack, SearchResults } from './types';

const SETTINGS_KEY = 'codioMusicSettings';

export type MusicSettings = { mbBase: string };

export const loadMusicSettings = (): MusicSettings => {
    try {
        const saved = JSON.parse(window.localStorage.getItem(SETTINGS_KEY) ?? '{}');
        return { mbBase: typeof saved.mbBase === 'string' && saved.mbBase.length > 0 ? saved.mbBase : '' };
    } catch (_e) {
        return { mbBase: '' };
    }
};

export const saveMusicSettings = (settings: MusicSettings) => {
    try {
        window.localStorage.setItem(SETTINGS_KEY, JSON.stringify(settings));
    } catch (_e) { /* storage full or off */ }
};

const enc = encodeURIComponent;

// What was asked this run is kept, so going back to a page costs nothing (Codio's server keeps it for days besides).
const asked = new Map<string, Promise<any>>();

const mb = (path: string): Promise<any> => {
    const base = loadMusicSettings().mbBase;
    const url = `/api/music/mb?p=${enc(path)}${base ? `&base=${enc(base)}` : ''}`;
    const known = asked.get(url);
    if (known) return known;
    const job = (async () => {
        for (let attempt = 0; attempt < 3; attempt++) {
            const response = await fetch(url, { headers: { 'X-Codio': '1' } });
            if (response.status === 503 && attempt < 2) {
                await new Promise((r) => setTimeout(r, 3000));
                continue;
            }
            if (!response.ok) throw new Error(`MusicBrainz ${response.status}`);
            return response.json();
        }
        throw new Error('MusicBrainz is busy');
    })();
    asked.set(url, job);
    job.catch(() => asked.delete(url));
    return job;
};

// Lucene special characters in a search must be escaped.
const lucene = (text: string) => text.replace(/([+\-&|!(){}[\]^"~*?:\\/])/g, '\\$1').trim();

export const credit = (list: any): { name: string, id: string | null } => {
    if (!Array.isArray(list) || list.length === 0) return { name: '', id: null };
    const name = list.map((c: any) => `${c?.name ?? c?.artist?.name ?? ''}${c?.joinphrase ?? ''}`).join('').trim();
    return { name, id: list[0]?.artist?.id ?? null };
};

const year = (date: unknown) => typeof date === 'string' && /^\d{4}/.test(date) ? date.slice(0, 4) : null;

// Cover art goes through Codio's server, which keeps each picture on disk after the first time.
export const artUrl = (url: string) => `/api/music/img?u=${enc(url)}`;
export const coverUrl = (releaseGroupId: string, size: 250 | 500 | 1200 = 250) => artUrl(`https://coverartarchive.org/release-group/${releaseGroupId}/front-${size}`);
// An artist's photo, found by Codio's server (Deezer, by exact name; else the cover of their most played album).
const MBID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export const artistPhotoUrl = (artistId: string, name?: string | null): string | null =>
    MBID.test(artistId) ? `/api/music/artist-img?id=${enc(artistId)}${name ? `&name=${enc(name)}` : ''}` : null;
export const releaseCoverUrl = (releaseId: string, size: 250 | 500 = 250) => artUrl(`https://coverartarchive.org/release/${releaseId}/front-${size}`);

const toAlbum = (g: any): MusicAlbum => {
    const secondary: string[] = Array.isArray(g['secondary-types']) ? g['secondary-types'] : [];
    return {
        id: String(g.id),
        title: String(g.title ?? ''),
        artist: credit(g['artist-credit']).name,
        year: year(g['first-release-date']),
        kind: [g['primary-type'], ...secondary].filter(Boolean).join(' · ') || null,
        artworkURL: coverUrl(String(g.id)),
        artistMbid: credit(g['artist-credit']).id,
    };
};

const toTrack = (recording: any, album?: { title: string, artworkURL: string | null }, artistFallback = ''): MusicTrack | null => {
    if (!recording || !recording.id) return null;
    const isrcs: string[] = Array.isArray(recording.isrcs) ? recording.isrcs : [];
    return {
        id: `mbid:${recording.id}`,
        mbid: String(recording.id),
        title: String(recording.title ?? ''),
        artist: credit(recording['artist-credit']).name || artistFallback,
        artistMbid: credit(recording['artist-credit']).id,
        album: album?.title ?? null,
        artworkURL: album?.artworkURL ?? null,
        durationMs: typeof recording.length === 'number' ? recording.length : null,
        isrc: isrcs.length > 0 ? isrcs[0] : null,
    };
};

// Artists, then albums, then songs: each is handed over as soon as it arrives (the server spaces its calls one second
// apart), so the first results show after a second instead of after all three.
export const searchMusicBrainz = async (query: string, onPart?: (partial: SearchResults) => void): Promise<SearchResults> => {
    const q = lucene(query);
    const out: SearchResults = { artists: [], albums: [], tracks: [] };
    const part = () => onPart && onPart({ artists: out.artists, albums: out.albums, tracks: out.tracks });
    await Promise.all([
        mb(`/ws/2/artist?query=${enc(q)}&limit=8`).catch(() => ({})).then((artists) => {
            out.artists = (Array.isArray(artists.artists) ? artists.artists : []).map((a: any): MusicArtist => ({
                id: String(a.id), name: String(a.name ?? ''), detail: [a.disambiguation, a.type].filter(Boolean).join(' · ') || null,
            }));
            part();
        }),
        mb(`/ws/2/release-group?query=${enc(q)}&limit=12`).catch(() => ({})).then((groups) => {
            out.albums = (Array.isArray(groups['release-groups']) ? groups['release-groups'] : []).map(toAlbum);
            part();
        }),
        mb(`/ws/2/recording?query=${enc(q)}&limit=20`).catch(() => ({})).then((recordings) => {
            out.tracks = (Array.isArray(recordings.recordings) ? recordings.recordings : []).map((r: any) => {
                const release = Array.isArray(r.releases) && r.releases.length > 0 ? r.releases[0] : null;
                const groupId = release?.['release-group']?.id;
                return toTrack(r, release ? { title: String(release.title ?? ''), artworkURL: groupId ? coverUrl(String(groupId)) : null } : undefined);
            }).filter(Boolean) as MusicTrack[];
            part();
        }),
    ]);
    return out;
};

export type ArtistPage = {
    id: string,
    name: string,
    detail: string | null,
    albums: MusicAlbum[],
    singles: MusicAlbum[],
    other: MusicAlbum[],
};

const newestFirst = (a: MusicAlbum, b: MusicAlbum) => (b.year ?? '0').localeCompare(a.year ?? '0');

export const getArtist = async (id: string, knownName?: string | null): Promise<ArtistPage> => {
    const [artist, groups] = await Promise.all([
        knownName ? Promise.resolve({ name: knownName }) : mb(`/ws/2/artist/${id}`),
        mb(`/ws/2/release-group?artist=${id}&type=album|ep|single&limit=100`).catch(() => ({})),
    ]);
    const albums: MusicAlbum[] = [], singles: MusicAlbum[] = [], other: MusicAlbum[] = [];
    (Array.isArray(groups['release-groups']) ? groups['release-groups'] : []).forEach((g: any) => {
        const album = toAlbum(g);
        const secondary: string[] = Array.isArray(g['secondary-types']) ? g['secondary-types'] : [];
        if (secondary.length > 0) other.push(album);
        else if (g['primary-type'] === 'Album') albums.push(album);
        else singles.push(album);
    });
    return {
        id,
        name: String(artist.name ?? ''),
        detail: [artist.disambiguation, artist.type, artist.country].filter(Boolean).join(' · ') || null,
        albums: albums.sort(newestFirst),
        singles: singles.sort(newestFirst),
        other: other.sort(newestFirst),
    };
};

export type AlbumPage = {
    id: string,
    title: string,
    artist: string,
    artistId: string | null,
    year: string | null,
    artworkURL: string,
    tracks: MusicTrack[],
};

// An album page: the earliest official release of the release group, with its tracks and their ISRC codes.
// Two requests: which releases there are, then the earliest one in full (it carries the album's own details too).
export const getAlbum = async (groupId: string): Promise<AlbumPage> => {
    const list = await mb(`/ws/2/release?release-group=${groupId}&status=official&limit=100`);
    const releases: any[] = Array.isArray(list.releases) ? list.releases : [];
    releases.sort((a, b) => String(a.date || '9999').localeCompare(String(b.date || '9999')));
    if (releases.length === 0) throw new Error('No release found for this album');
    const release = await mb(`/ws/2/release/${releases[0].id}?inc=recordings+isrcs+artist-credits+release-groups`);
    const group = release['release-group'] ?? {};
    const art = coverUrl(groupId);
    const artist = credit(release['artist-credit']);
    const title = String(group.title ?? release.title ?? '');
    const tracks: MusicTrack[] = [];
    (Array.isArray(release.media) ? release.media : []).forEach((medium: any) => {
        (Array.isArray(medium.tracks) ? medium.tracks : []).forEach((t: any) => {
            const recording = t.recording ?? {};
            const track = toTrack({ ...recording, title: recording.title ?? t.title, length: recording.length ?? t.length }, { title, artworkURL: art }, artist.name);
            if (track) tracks.push(track);
        });
    });
    return {
        id: groupId,
        title,
        artist: artist.name,
        artistId: artist.id,
        year: year(group['first-release-date'] ?? release.date),
        artworkURL: art,
        tracks,
    };
};

// A recording's ISRC codes, asked when a track from a list without them is played.
export const getRecordingIsrc = async (mbid: string): Promise<string | null> => {
    const recording = await mb(`/ws/2/recording/${mbid}?inc=isrcs`);
    return Array.isArray(recording.isrcs) && recording.isrcs.length > 0 ? String(recording.isrcs[0]) : null;
};

const first0 = (x: unknown): string | null => Array.isArray(x) && typeof x[0] === 'string' ? x[0] : null;

// The artist's most listened-to tracks (ListenBrainz; MusicBrainz itself has no popularity). Empty if unavailable.
export const getTopTracks = async (artistId: string, artistName: string): Promise<MusicTrack[]> => {
    try {
        const response = await fetch(`/api/music/lb?p=${enc(`/1/popularity/top-recordings-for-artist/${artistId}`)}`, { headers: { 'X-Codio': '1' } });
        if (!response.ok) return [];
        const list = await response.json();
        return (Array.isArray(list) ? list : []).slice(0, 10).map((r: any): MusicTrack | null => r?.recording_mbid ? {
            id: `mbid:${r.recording_mbid}`,
            mbid: String(r.recording_mbid),
            title: String(r.recording_name ?? ''),
            artist: String(r.artist_name ?? artistName),
            album: r.release_name ?? null,
            artworkURL: r.release_mbid ? releaseCoverUrl(String(r.release_mbid)) : null,
            artistMbid: first0(r.artist_mbids) ?? artistId,
            durationMs: typeof r.length === 'number' ? r.length : null,
            isrc: null,
        } : null).filter(Boolean) as MusicTrack[];
    } catch (_e) {
        return [];
    }
};
