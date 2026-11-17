// Music addons: plain HTTP servers that follow Eclipse Music's addon protocol (https://eclipsemusic.app/docs).
// The list lives on this device (locked copies go to your other devices with the sync passcode, vault.ts). Addon links (which can hold a personal token) go to nobody but the addon itself.

import type { MusicAddon, MusicAlbum, MusicArtist, MusicTrack, SearchResults } from './types';

const KEY = 'codioMusicAddons';

export const loadAddons = (): MusicAddon[] => {
    try {
        const list = JSON.parse(window.localStorage.getItem(KEY) ?? '[]');
        return Array.isArray(list) ? list.filter((a) => a && typeof a.url === 'string' && a.manifest) : [];
    } catch (_e) {
        return [];
    }
};

export const saveAddons = (list: MusicAddon[]) => {
    try {
        window.localStorage.setItem(KEY, JSON.stringify(list));
    } catch (_e) { /* storage full or off */ }
    window.dispatchEvent(new Event('codio-music-addons'));
    return list;
};

export const has = (addon: MusicAddon, resource: string) => Array.isArray(addon.manifest?.resources) && addon.manifest.resources.includes(resource);

// What was typed or pasted, as the addon's base link (no manifest.json).
export const baseLink = (text: string) => {
    let u = text.trim();
    if (u.startsWith('stremio://') || u.startsWith('eclipse://')) u = `https://${u.replace(/^[a-z]+:\/\//, '')}`;
    if (!/^https?:\/\//i.test(u)) u = `https://${u}`;
    return u.replace(/\/manifest\.json$/i, '').replace(/\/+$/, '');
};

type Reply = { status: number, retryAfter: number | null, json: any };

// A call to an addon, through Codio's server (so no browser rules stand in the way).
const call = async (addon: { url: string }, path: string, query: Record<string, string | number | null | undefined> = {}): Promise<Reply> => {
    const params = new URLSearchParams();
    Object.entries(query).forEach(([k, v]) => { if (v !== null && v !== undefined && v !== '') params.set(k, String(v)); });
    const target = `${addon.url}${path}${params.toString() ? `${path.includes('?') ? '&' : '?'}${params}` : ''}`;
    const response = await fetch(`/api/music/addon?url=${encodeURIComponent(target)}`, { headers: { 'X-Codio': '1' } });
    const retry = response.headers.get('Retry-After');
    let json: any = null;
    try {
        json = await response.json();
    } catch (_e) { /* no JSON body */ }
    return { status: response.status, retryAfter: retry ? Number(retry) : null, json };
};

// The values the addon's own settings default to; Eclipse sends them with every call.
const settingsOf = (addon: MusicAddon): Record<string, string> => {
    const out: Record<string, string> = {};
    (Array.isArray(addon.manifest?.settings) ? addon.manifest.settings : []).forEach((s: any) => {
        if (s && typeof s.key === 'string' && s.default !== undefined && s.default !== null) out[s.key] = String(s.default);
    });
    return out;
};

export const fetchManifest = async (text: string): Promise<MusicAddon> => {
    const url = baseLink(text);
    let reply: Reply;
    try {
        reply = await call({ url }, '/manifest.json');
    } catch (_e) {
        throw new Error('Couldn\'t reach that link');
    }
    const m = reply.json;
    if (reply.status !== 200 || !m || typeof m.id !== 'string' || typeof m.name !== 'string' || !Array.isArray(m.resources)) {
        throw new Error('That link isn\'t a music addon (no manifest found)');
    }
    if (!has({ url, manifest: m }, 'search') && !has({ url, manifest: m }, 'stream') && !has({ url, manifest: m }, 'catalog')) {
        throw new Error('That addon doesn\'t search, stream or list anything');
    }
    return { url, manifest: m };
};

// Notices an addon sends along (shown as a short message); listeners are the Music screens.
type NoticeListener = (message: string, level: string) => void;
const listeners = new Set<NoticeListener>();
export const onNotice = (l: NoticeListener) => {
    listeners.add(l);
    return () => { listeners.delete(l); };
};
const noticeOf = (addon: MusicAddon, json: any) => {
    const n = json?.notice;
    if (n && typeof n.message === 'string' && n.message.length > 0) {
        const text = `${addon.manifest?.name ?? 'Addon'}: ${n.title ? `${n.title}. ` : ''}${n.message}`.slice(0, 200);
        listeners.forEach((l) => l(text, typeof n.level === 'string' ? n.level : 'info'));
    }
};

const str = (v: unknown) => typeof v === 'string' && v.length > 0 ? v : null;
const seconds = (v: unknown) => typeof v === 'number' && isFinite(v) && v > 0 ? Math.round(v * 1000) : null;

export const toTrack = (addon: MusicAddon, t: any): MusicTrack | null => {
    if (!t || (typeof t.id !== 'string' && typeof t.id !== 'number') || !t.title) return null;
    return {
        id: String(t.id),
        title: String(t.title),
        artist: String(t.artist ?? ''),
        album: str(t.album),
        artworkURL: str(t.artworkURL),
        durationMs: typeof t.durationMs === 'number' ? t.durationMs : seconds(t.duration),
        isrc: str(t.isrc),
        addon: addon.url,
        streamURL: str(t.streamURL),
    };
};

const toAlbum = (addon: MusicAddon, a: any): MusicAlbum | null => a && a.id !== undefined && a.title ? ({
    id: String(a.id), title: String(a.title), artist: String(a.artist ?? ''), year: a.year ? String(a.year) : null, artworkURL: str(a.artworkURL), addon: addon.url,
}) : null;

const toArtist = (addon: MusicAddon, a: any): MusicArtist | null => a && a.id !== undefined && a.name ? ({
    id: String(a.id), name: String(a.name), detail: Array.isArray(a.genres) ? a.genres.slice(0, 3).join(', ') || null : null, artworkURL: str(a.artworkURL), addon: addon.url,
}) : null;

const compact = <T,>(list: (T | null)[]): T[] => list.filter((x): x is T => x !== null);

export const searchAddon = async (addon: MusicAddon, q: string): Promise<SearchResults> => {
    const reply = await call(addon, '/search', { q, ...settingsOf(addon) });
    noticeOf(addon, reply.json);
    const j = reply.json ?? {};
    if (reply.status !== 200) return { artists: [], albums: [], tracks: [] };
    return {
        tracks: compact((Array.isArray(j.tracks) ? j.tracks : []).map((t: any) => toTrack(addon, t))),
        albums: compact((Array.isArray(j.albums) ? j.albums : []).map((a: any) => toAlbum(addon, a))),
        artists: compact((Array.isArray(j.artists) ? j.artists : []).map((a: any) => toArtist(addon, a))),
    };
};

export type Stream = { url: string, quality: string | null, format: string | null };

// The audio link for an addon's own track id. A 503 with Retry-After means "still preparing": wait (up to 30 s a try) and ask again.
export const streamFor = async (addon: MusicAddon, id: string): Promise<Stream | null> => {
    for (let attempt = 0; attempt < 4; attempt++) {
        const reply = await call(addon, `/stream/${encodeURIComponent(id)}`, settingsOf(addon));
        noticeOf(addon, reply.json);
        if (reply.status === 503) {
            const wait = Math.min(30, Math.max(2, reply.retryAfter ?? 5));
            await new Promise((r) => setTimeout(r, wait * 1000));
            continue;
        }
        if (reply.status !== 200 || !reply.json || typeof reply.json.url !== 'string') return null;
        return { url: reply.json.url, quality: str(reply.json.quality), format: str(reply.json.format) };
    }
    return null;
};

export const resolveIsrc = async (addon: MusicAddon, isrc: string): Promise<string | null> => {
    const reply = await call(addon, '/resolve-isrc', { isrc, ...settingsOf(addon) });
    noticeOf(addon, reply.json);
    if (reply.status !== 200) return null;
    const id = reply.json?.trackId ?? reply.json?.id;
    return typeof id === 'string' || typeof id === 'number' ? String(id) : null;
};

export const resolveTrack = async (addon: MusicAddon, track: MusicTrack): Promise<MusicTrack | null> => {
    const reply = await call(addon, '/resolve', {
        isrc: track.isrc, title: track.title, artist: track.artist, durationMs: track.durationMs, ...settingsOf(addon),
    });
    noticeOf(addon, reply.json);
    if (reply.status !== 200 || !reply.json || !reply.json.item) return null;
    return toTrack(addon, reply.json.item);
};

export type CatalogRow = { addon: MusicAddon, id: string, name: string, type: string, items: (MusicTrack | MusicAlbum | MusicArtist)[] };

// The rows an addon offers for Home (its catalogs).
export const loadCatalogs = async (addons: MusicAddon[]): Promise<CatalogRow[]> => {
    const rows: CatalogRow[] = [];
    await Promise.all(addons.filter((a) => has(a, 'catalog')).flatMap((addon) => (Array.isArray(addon.manifest.catalogs) ? addon.manifest.catalogs : []).map(async (c: any) => {
        if (!c || typeof c.id !== 'string') return;
        try {
            const reply = await call(addon, `/catalog/${encodeURIComponent(c.id)}`, { skip: 0, ...settingsOf(addon) });
            noticeOf(addon, reply.json);
            const items: any[] = Array.isArray(reply.json?.items) ? reply.json.items : [];
            const type = String(c.type ?? 'track');
            const mapped = compact(items.map((i: any) => type === 'album' ? toAlbum(addon, i) : type === 'artist' ? toArtist(addon, { ...i, name: i.name ?? i.title }) : toTrack(addon, i)));
            if (mapped.length > 0) rows.push({ addon, id: c.id, name: String(c.name ?? c.id), type, items: mapped });
        } catch (_e) { /* a row that fails is left out */ }
    })));
    return rows;
};

export type AlbumDetail = { title: string, artist: string, artworkURL: string | null, year: string | null, tracks: MusicTrack[] };

export const albumDetail = async (addon: MusicAddon, id: string): Promise<AlbumDetail | null> => {
    const reply = await call(addon, `/album/${encodeURIComponent(id)}`, settingsOf(addon));
    noticeOf(addon, reply.json);
    const j = reply.json;
    if (reply.status !== 200 || !j) return null;
    return { title: String(j.title ?? ''), artist: String(j.artist ?? ''), artworkURL: str(j.artworkURL), year: j.year ? String(j.year) : null, tracks: compact((Array.isArray(j.tracks) ? j.tracks : []).map((t: any) => toTrack(addon, { artist: j.artist, album: j.title, artworkURL: j.artworkURL, ...t }))) };
};

export type ArtistDetail = { name: string, artworkURL: string | null, bio: string | null, topTracks: MusicTrack[], albums: MusicAlbum[] };

export const artistDetail = async (addon: MusicAddon, id: string): Promise<ArtistDetail | null> => {
    const reply = await call(addon, `/artist/${encodeURIComponent(id)}`, settingsOf(addon));
    noticeOf(addon, reply.json);
    const j = reply.json;
    if (reply.status !== 200 || !j) return null;
    return {
        name: String(j.name ?? ''), artworkURL: str(j.artworkURL), bio: str(j.bio),
        topTracks: compact((Array.isArray(j.topTracks) ? j.topTracks : []).map((t: any) => toTrack(addon, { artist: j.name, ...t }))),
        albums: compact((Array.isArray(j.albums) ? j.albums : []).map((a: any) => toAlbum(addon, { artist: j.name, ...a }))),
    };
};
