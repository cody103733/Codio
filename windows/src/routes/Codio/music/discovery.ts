// What to listen to, without any account: what is popular right now, new releases, and artists like the ones you played.
// Charts and new releases come from ListenBrainz (open listening data), similar artists from ListenBrainz Labs. Each call
// is made once and kept by Codio's server for hours or days, so the Music page opens fast after the first time.

import type { MusicAlbum, MusicArtist, MusicTrack } from './types';
import { artUrl, coverUrl, releaseCoverUrl } from './musicbrainz';

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

// Albums people are playing this week.
export const popularAlbums = async (): Promise<MusicAlbum[]> => {
    const j = await lb('/1/stats/sitewide/release-groups?range=week&count=25');
    return list(j?.payload?.release_groups).filter((g) => g.release_group_mbid).map((g): MusicAlbum => ({
        id: String(g.release_group_mbid), title: String(g.release_group_name ?? ''), artist: String(g.artist_name ?? ''), artworkURL: coverUrl(String(g.release_group_mbid)), artistMbid: first(g.artist_mbids),
    }));
};

// Albums out in the last two weeks.
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
let chartJob: Promise<any> | null = null;
const chart = (): Promise<any> => {
    if (!chartJob) {
        chartJob = fetch(`/api/music/chart?cc=${country()}`, { headers: { 'X-Codio': '1' } })
            .then((r) => r.ok ? r.json() : null)
            .catch(() => null)
            .then((j) => {
                if (!j) chartJob = null;
                return j;
            });
    }
    return chartJob;
};

// Songs people are playing this week: the country's chart, else ListenBrainz's.
export const popularSongs = async (): Promise<MusicTrack[]> => {
    const c = await chart();
    const fromChart = list(c?.songs).filter((r) => r.mbid && r.title).slice(0, 25).map((r): MusicTrack => ({
        id: `mbid:${r.mbid}`, mbid: String(r.mbid), title: String(r.title), artist: String(r.artist ?? ''), album: r.album ?? null,
        artworkURL: r.releaseMbid ? releaseCoverUrl(String(r.releaseMbid)) : null, artistMbid: r.artistMbid ?? null,
    }));
    if (fromChart.length >= 5) return fromChart;
    const j = await lb('/1/stats/sitewide/recordings?range=week&count=25');
    return list(j?.payload?.recordings).filter((r) => r.recording_mbid && r.track_name).map((r): MusicTrack => ({
        id: `mbid:${r.recording_mbid}`, mbid: String(r.recording_mbid), title: String(r.track_name), artist: String(r.artist_name ?? ''), album: r.release_name ?? null,
        artworkURL: r.release_mbid ? releaseCoverUrl(String(r.release_mbid)) : null, artistMbid: first(r.artist_mbids),
    }));
};

// Artists people are playing now: the ones in the country's chart, else ListenBrainz's.
export const popularArtists = async (): Promise<MusicArtist[]> => {
    const c = await chart();
    const fromChart = list(c?.artists).filter((a) => a.id && a.name).slice(0, 20).map((a): MusicArtist => ({ id: String(a.id), name: String(a.name), detail: null }));
    if (fromChart.length >= 5) return fromChart;
    const j = await lb('/1/stats/sitewide/artists?range=month&count=30');
    return list(j?.payload?.artists).filter((a) => a.artist_mbid && a.artist_name).slice(0, 20).map((a): MusicArtist => ({
        id: String(a.artist_mbid), name: String(a.artist_name), detail: null,
    }));
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
