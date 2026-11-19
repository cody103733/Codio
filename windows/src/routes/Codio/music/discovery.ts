// What to listen to, without any account: what is popular right now, new releases, and artists like the ones you played.
// Charts and new releases come from ListenBrainz (open listening data), similar artists from ListenBrainz Labs. Each call
// is made once and kept by Codio's server for hours or days, so the Music page opens fast after the first time.

import type { MusicAlbum, MusicArtist, MusicTrack } from './types';
import { activeLastfmKey, artUrl, coverUrl, releaseCoverUrl } from './musicbrainz';

const enc = encodeURIComponent;

const lb = async (path: string, host: 'lb' | 'labs' = 'lb'): Promise<any> => {
    const response = await fetch(`/api/music/${host}?p=${enc(path)}`, { headers: { 'X-Codio': '1' } });
    if (response.status === 204 || !response.ok) return null;
    try {
        return await response.json();
    } catch (_e) {
        return null;
    }
};

const list = (x: unknown): any[] => Array.isArray(x) ? x : [];
const first = (x: unknown): string | null => Array.isArray(x) && typeof x[0] === 'string' ? x[0] : null;

// The country's most played albums (Apple's chart), matched to MusicBrainz by Codio's server.
let albumJob: Promise<any> | null = null;
const albumChart = (): Promise<any> => {
    if (!albumJob) {
        albumJob = fetch(`/api/music/chart-albums?cc=${country()}`, { headers: { 'X-Codio': '1' } })
            .then((r) => r.ok ? r.json() : null)
            .catch(() => null)
            .then((j) => {
                if (!j) albumJob = null;
                return j;
            });
    }
    return albumJob;
};
const chartAlbum = (a: any): MusicAlbum => ({
    id: String(a.id), title: String(a.title ?? ''), artist: String(a.artist ?? ''), year: a.year ?? null, kind: a.kind ?? null,
    artworkURL: typeof a.art === 'string' ? artUrl(a.art) : coverUrl(String(a.id)), artistMbid: a.artistMbid ?? null,
});

// Albums people are playing now in your country.
export const popularAlbums = async (): Promise<MusicAlbum[]> => list((await albumChart())?.popular).filter((a) => a.id).map(chartAlbum);

// Popular albums out in the last 8 weeks, newest first.
export const chartNewReleases = async (): Promise<MusicAlbum[]> => list((await albumChart())?.recent).filter((a) => a.id).map(chartAlbum);

// Every album out in the last two weeks (ListenBrainz, worldwide): only used to find new music from artists you like.
export const newReleases = async (): Promise<MusicAlbum[]> => {
    const j = await lb('/1/explore/fresh-releases/?days=14&past=true&future=false&sort=release_date');
    const items = Array.isArray(j) ? j : list(j?.payload?.releases ?? j?.releases);
    const seen = new Set<string>();
    return items
        .filter((r) => r && r.release_group_mbid && (r.release_group_primary_type === 'Album' || r.release_group_primary_type === 'EP' || !r.release_group_primary_type))
        .sort((a, b) => String(b.release_date ?? '').localeCompare(String(a.release_date ?? '')))
        .filter((r) => !seen.has(r.release_group_mbid) && seen.add(r.release_group_mbid))
        .slice(0, 300)
        .map((r): MusicAlbum => ({
            artistMbid: first(r.artist_mbids),
            id: String(r.release_group_mbid), title: String(r.release_name ?? ''), artist: String(r.artist_credit_name ?? ''), year: typeof r.release_date === 'string' ? r.release_date.slice(0, 4) : null,
            kind: r.release_group_primary_type ?? null, artworkURL: coverUrl(String(r.release_group_mbid)),
        }));
};

// The country's chart (Apple Music's most played, matched to MusicBrainz by Codio's server). The country comes from
// Windows' region setting, US if it has none.
const country = (): string => {
    try {
        const tag = Intl.DateTimeFormat().resolvedOptions().locale || navigator.language || '';
        const m = /[-_]([A-Za-z]{2})(?:[-_]|$)/.exec(tag);
        return m ? m[1].toLowerCase() : 'us';
    } catch (_e) {
        return 'us';
    }
};
// Last.fm wants the country's English name ("United States").
const countryName = (cc: string): string => {
    try {
        return new (Intl as any).DisplayNames(['en'], { type: 'region' }).of(cc.toUpperCase()) || 'United States';
    } catch (_e) {
        return 'United States';
    }
};
// 'lastfm' when your Last.fm key is set (its chart replaces Apple's), else 'apple'.
export const chartSource = (): 'lastfm' | 'apple' => activeLastfmKey() ? 'lastfm' : 'apple';
let chartJob: Promise<any> | null = null;
let chartFor = '';
const chart = (): Promise<any> => {
    const key = activeLastfmKey();
    if (chartFor !== key) {
        chartFor = key;
        chartJob = null;
        appleLinks.clear();
    }
    if (!chartJob) {
        const cc = country();
        const headers: Record<string, string> = { 'X-Codio': '1' };
        if (key) headers['X-Lastfm-Key'] = key;
        chartJob = fetch(`/api/music/chart?cc=${cc}&country=${enc(countryName(cc))}`, { headers })
            .then((r) => r.ok ? r.json() : null)
            .catch(() => null)
            .then((j) => {
                if (!j) chartJob = null;
                return j;
            });
    }
    return chartJob;
};

// The song's page on Apple Music, for songs that came from Apple's chart (the chart is meant to send people there).
const appleLinks = new Map<string, string>();
export const appleMusicUrl = (t: MusicTrack): string | null => appleLinks.get(t.id) ?? null;

// Songs people are playing now: the country's chart (Apple's, or Last.fm's with your key). A song the music database
// didn't match still plays: the addons find it by title and artist.
export const popularSongs = async (): Promise<MusicTrack[]> => {
    const c = await chart();
    return list(c?.songs).filter((r) => r.title && r.artist).slice(0, 100).map((r): MusicTrack => {
        const id = r.mbid ? `mbid:${r.mbid}` : `chart:${String(r.artist).toLowerCase()}|${String(r.title).toLowerCase()}`;
        if (typeof r.appleUrl === 'string') appleLinks.set(id, r.appleUrl);
        return {
            id, mbid: r.mbid ? String(r.mbid) : null, title: String(r.title), artist: String(r.artist), album: r.album ?? null,
            artworkURL: r.releaseMbid ? releaseCoverUrl(String(r.releaseMbid)) : r.art ? artUrl(String(r.art)) : null, artistMbid: r.artistMbid ?? null,
        };
    });
};

// Genre charts (the iTunes Store's top 100 per genre; Last.fm has no current genre charts, so these are Apple's whichever
// chart you picked). The ids are Apple's genre ids. You pick which ones show in Music settings > Charts.
export const GENRES: { id: string, name: string }[] = [
    { id: '14', name: 'Pop' }, { id: '18', name: 'Hip-Hop/Rap' }, { id: '6', name: 'Country' }, { id: '21', name: 'Rock' },
    { id: '15', name: 'R&B/Soul' }, { id: '20', name: 'Alternative' }, { id: '17', name: 'Dance' }, { id: '7', name: 'Electronic' },
    { id: '12', name: 'Latin' }, { id: '1153', name: 'Metal' }, { id: '22', name: 'Christian' }, { id: '10', name: 'Singer/Songwriter' },
    { id: '11', name: 'Jazz' }, { id: '2', name: 'Blues' }, { id: '5', name: 'Classical' }, { id: '24', name: 'Reggae' },
    { id: '16', name: 'Soundtrack' }, { id: '51', name: 'K-Pop' }, { id: '4', name: "Children's Music" },
];
export const genreName = (id: string) => GENRES.find((g) => g.id === id)?.name ?? 'Genre';

const genreJobs = new Map<string, Promise<any>>();
export const genreSongs = async (genre: string): Promise<MusicTrack[]> => {
    let job = genreJobs.get(genre);
    if (!job) {
        job = fetch(`/api/music/genre-chart?cc=${country()}&g=${enc(genre)}`, { headers: { 'X-Codio': '1' } })
            .then((r) => r.ok ? r.json() : null).catch(() => null)
            .then((j) => { if (!j) genreJobs.delete(genre); return j; });
        genreJobs.set(genre, job);
    }
    const c = await job;
    return list(c?.songs).filter((r) => r.title && r.artist).slice(0, 100).map((r): MusicTrack => {
        const id = `chart:${String(r.artist).toLowerCase()}|${String(r.title).toLowerCase()}`;
        if (typeof r.appleUrl === 'string') appleLinks.set(id, r.appleUrl);
        return { id, mbid: null, title: String(r.title), artist: String(r.artist), album: r.album ?? null, artworkURL: r.art ? artUrl(String(r.art)) : null, artistMbid: null };
    });
};

// A chart by its page id: 'top' (the Top 100) or a genre id.
export const chartSongs = (id: string): Promise<MusicTrack[]> => id === 'top' ? popularSongs() : genreSongs(id);
export const chartTitle = (id: string) => id === 'top' ? 'Top 100' : `Top ${genreName(id)}`;

// Artists in the country's chart (a separate, slower call: their ids are looked up).
export const popularArtists = async (): Promise<MusicArtist[]> => {
    const key = activeLastfmKey();
    const cc = country();
    const headers: Record<string, string> = { 'X-Codio': '1' };
    if (key) headers['X-Lastfm-Key'] = key;
    const c = await fetch(`/api/music/chart-artists?cc=${cc}&country=${enc(countryName(cc))}`, { headers }).then((r) => r.ok ? r.json() : null).catch(() => null);
    return list(c?.artists).filter((a) => a.id && a.name).slice(0, 20).map((a): MusicArtist => ({ id: String(a.id), name: String(a.name), detail: null }));
};

const SIMILAR = 'session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30';

// Artists like this one (ListenBrainz Labs). Empty if the service doesn't answer.
export const similarArtists = async (artistMbid: string): Promise<MusicArtist[]> => {
    const j = await lb(`/similar-artists/json?artist_mbids=${artistMbid}&algorithm=${SIMILAR}`, 'labs');
    const flat: any[] = [];
    const walk = (x: any) => Array.isArray(x) ? x.forEach(walk) : x && typeof x === 'object' ? (x.artist_mbid && x.name ? flat.push(x) : Object.values(x).forEach((v) => { if (Array.isArray(v)) walk(v); })) : null;
    walk(j);
    return flat.filter((a) => a.artist_mbid !== artistMbid).slice(0, 20).map((a): MusicArtist => ({
        id: String(a.artist_mbid), name: String(a.name), detail: [a.type, a.comment].filter(Boolean).join(' · ') || null,
    }));
};

// More albums by an artist, most played first.
export const moreFrom = async (artistMbid: string): Promise<MusicAlbum[]> => {
    const j = await lb(`/1/popularity/top-release-groups-for-artist/${artistMbid}`);
    return list(j).filter((g) => g.release_group_mbid).slice(0, 20).map((g): MusicAlbum => ({
        id: String(g.release_group_mbid), title: String(g.release_group?.name ?? ''), artist: String(g.artist?.name ?? ''), year: typeof g.release_group?.date === 'string' ? g.release_group.date.slice(0, 4) : null,
        artworkURL: g.release_group?.caa_id && g.release_group?.caa_release_mbid ? artUrl(`https://coverartarchive.org/release/${g.release_group.caa_release_mbid}/front-250`) : coverUrl(String(g.release_group_mbid)),
    }));
};
