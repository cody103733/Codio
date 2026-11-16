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

const mb = async (path: string): Promise<any> => {
    const base = loadMusicSettings().mbBase;
    const url = `/api/music/mb?p=${enc(path)}${base ? `&base=${enc(base)}` : ''}`;
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
};

// Lucene special characters in a search must be escaped.
const lucene = (text: string) => text.replace(/([+\-&|!(){}[\]^"~*?:\\/])/g, '\\$1').trim();

const credit = (list: any): { name: string, id: string | null } => {
    if (!Array.isArray(list) || list.length === 0) return { name: '', id: null };
    const name = list.map((c: any) => `${c?.name ?? c?.artist?.name ?? ''}${c?.joinphrase ?? ''}`).join('').trim();
    return { name, id: list[0]?.artist?.id ?? null };
};

const year = (date: unknown) => typeof date === 'string' && /^\d{4}/.test(date) ? date.slice(0, 4) : null;

export const coverUrl = (releaseGroupId: string, size: 250 | 500 | 1200 = 250) => `https://coverartarchive.org/release-group/${releaseGroupId}/front-${size}`;

const toAlbum = (g: any): MusicAlbum => {
    const secondary: string[] = Array.isArray(g['secondary-types']) ? g['secondary-types'] : [];
    return {
        id: String(g.id),
        title: String(g.title ?? ''),
        artist: credit(g['artist-credit']).name,
        year: year(g['first-release-date']),
        kind: [g['primary-type'], ...secondary].filter(Boolean).join(' · ') || null,
        artworkURL: coverUrl(String(g.id)),
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
        album: album?.title ?? null,
        artworkURL: album?.artworkURL ?? null,
        durationMs: typeof recording.length === 'number' ? recording.length : null,
        isrc: isrcs.length > 0 ? isrcs[0] : null,
    };
};

export const searchMusicBrainz = async (query: string): Promise<SearchResults> => {
    const q = lucene(query);
    const [artists, groups, recordings] = await Promise.all([
        mb(`/ws/2/artist?query=${enc(q)}&limit=8`).catch(() => ({})),
        mb(`/ws/2/release-group?query=${enc(q)}&limit=12`).catch(() => ({})),
        mb(`/ws/2/recording?query=${enc(q)}&limit=20`).catch(() => ({})),
    ]);
    return {
        artists: (Array.isArray(artists.artists) ? artists.artists : []).map((a: any): MusicArtist => ({
            id: String(a.id), name: String(a.name ?? ''), detail: [a.disambiguation, a.type].filter(Boolean).join(' · ') || null,
        })),
        albums: (Array.isArray(groups['release-groups']) ? groups['release-groups'] : []).map(toAlbum),
        tracks: (Array.isArray(recordings.recordings) ? recordings.recordings : []).map((r: any) => {
            const release = Array.isArray(r.releases) && r.releases.length > 0 ? r.releases[0] : null;
            const groupId = release?.['release-group']?.id;
            return toTrack(r, release ? { title: String(release.title ?? ''), artworkURL: groupId ? coverUrl(String(groupId)) : null } : undefined);
        }).filter(Boolean) as MusicTrack[],
    };
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

export const getArtist = async (id: string): Promise<ArtistPage> => {
    const [artist, groups] = await Promise.all([
        mb(`/ws/2/artist/${id}`),
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
export const getAlbum = async (groupId: string): Promise<AlbumPage> => {
    const group = await mb(`/ws/2/release-group/${groupId}?inc=artist-credits`);
    const list = await mb(`/ws/2/release?release-group=${groupId}&status=official&limit=100`);
    const releases: any[] = Array.isArray(list.releases) ? list.releases : [];
    releases.sort((a, b) => String(a.date || '9999').localeCompare(String(b.date || '9999')));
    if (releases.length === 0) throw new Error('No release found for this album');
    const release = await mb(`/ws/2/release/${releases[0].id}?inc=recordings+isrcs+artist-credits`);
    const art = coverUrl(groupId);
    const artist = credit(group['artist-credit']);
    const tracks: MusicTrack[] = [];
    (Array.isArray(release.media) ? release.media : []).forEach((medium: any) => {
        (Array.isArray(medium.tracks) ? medium.tracks : []).forEach((t: any) => {
            const recording = t.recording ?? {};
            const track = toTrack({ ...recording, title: recording.title ?? t.title, length: recording.length ?? t.length }, { title: String(group.title ?? ''), artworkURL: art }, artist.name);
            if (track) tracks.push(track);
        });
    });
    return {
        id: groupId,
        title: String(group.title ?? release.title ?? ''),
        artist: artist.name,
        artistId: artist.id,
        year: year(group['first-release-date']),
        artworkURL: art,
        tracks,
    };
};

// A recording's ISRC codes, asked when a track from a list without them is played.
export const getRecordingIsrc = async (mbid: string): Promise<string | null> => {
    const recording = await mb(`/ws/2/recording/${mbid}?inc=isrcs`);
    return Array.isArray(recording.isrcs) && recording.isrcs.length > 0 ? String(recording.isrcs[0]) : null;
};

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
            artworkURL: null,
            durationMs: typeof r.length === 'number' ? r.length : null,
            isrc: null,
        } : null).filter(Boolean) as MusicTrack[];
    } catch (_e) {
        return [];
    }
};
