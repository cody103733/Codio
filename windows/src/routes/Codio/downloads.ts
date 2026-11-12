// Codio downloads (Windows): talks to the download manager in codio-web.js on this PC.
// Movies and episodes are saved from debrid / direct links (never torrents), show in Library,
// and play from the file, without internet, picking up where you stopped.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { useCore } from 'stremio/core';
import { clearQueue } from 'stremio/common/AutoPlay';
import { parseQuality, parseSizeGB, streamText } from 'stremio/common/AutoPlay/quality';
import { CINEMETA, qualityLabel, type Addon } from './api';
import { useAddons } from './useCodio';

export type DlState = 'waiting' | 'queued' | 'downloading' | 'done' | 'failed';

export type Download = {
    key: string,
    type: string,
    metaId: string,
    videoId: string,
    name: string,
    poster?: string | null,
    season?: number,
    episode?: number,
    episodeTitle?: string | null,
    thumb?: string | null,
    quality?: string | null,
    wantQuality?: string | null,
    bytes?: number,
    done?: number,
    state: DlState,
    error?: string | null,
    file?: string | null,
    metaBase?: string | null,
    streamBase?: string | null,
    addedAt?: number,
};

export type DlOption = { quality: string | null, url: string, bytes: number, cached: boolean, description: string, streamBase: string | null };

export const QUALITIES = ['2160p', '1440p', '1080p', '720p', '480p'];

export const dlKey = (type: string, id: string, videoId: string) => `${type}/${id}/${videoId}`;

export const localUrl = (d: Download) => `${window.location.origin}/dl/${encodeURIComponent(d.key)}`;

export const formatBytes = (n: number | null | undefined) => {
    if (typeof n !== 'number' || !(n > 0)) return '';
    if (n >= 1e12) return `${(n / 1e12).toFixed(1)} TB`;
    if (n >= 1e9) return `${(n / 1e9).toFixed(1)} GB`;
    return `${Math.max(1, Math.round(n / 1e6))} MB`;
};

export const progressOf = (d: Download) => d.state === 'done' ? 1 : d.bytes && d.bytes > 0 ? Math.min(1, (d.done ?? 0) / d.bytes) : 0;

// ---- One shared poller for every screen
type Snapshot = { available: boolean, dir: string | null, free: number | null, entries: Download[] };
let snapshot: Snapshot = { available: false, dir: null, free: null, entries: [] };
const listeners = new Set<(s: Snapshot) => void>();
let timer: ReturnType<typeof setTimeout> | null = null;

const api = (path: string, body?: unknown) => fetch(path, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'X-Codio': '1', ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: 'no-store',
});

const refresh = async () => {
    try {
        const response = await api('/api/dl');
        if (!response.ok) throw new Error(String(response.status));
        const data = await response.json();
        snapshot = { available: true, dir: data.dir ?? null, free: typeof data.free === 'number' ? data.free : null, entries: Array.isArray(data.entries) ? data.entries : [] };
    } catch (_e) {
        snapshot = { ...snapshot, available: false };
    }
    listeners.forEach((l) => l(snapshot));
    schedule();
};

const schedule = () => {
    if (timer) clearTimeout(timer);
    if (listeners.size === 0) { timer = null; return; }
    const busy = snapshot.entries.some((e) => e.state !== 'done' && e.state !== 'failed');
    timer = setTimeout(refresh, busy ? 1000 : 5000);
};

export const useDownloads = (): Snapshot => {
    const [state, setState] = useState(snapshot);
    useEffect(() => {
        listeners.add(setState);
        if (listeners.size === 1) refresh(); else setState(snapshot);
        return () => {
            listeners.delete(setState);
            if (listeners.size === 0 && timer) { clearTimeout(timer); timer = null; }
        };
    }, []);
    return state;
};

export const startDownload = async (entry: Omit<Download, 'state'> & { url: string }) => {
    await api('/api/dl/start', entry);
    refresh();
};

export const queueDownloads = async (list: Omit<Download, 'state'>[]) => {
    const response = await api('/api/dl/queue', { entries: list });
    refresh();
    try { return (await response.json()).added ?? 0; } catch (_e) { return 0; }
};

export const retryDownload = async (key: string) => {
    await api('/api/dl/retry', { key });
    refresh();
};

export const deleteDownload = async (key: string) => {
    await api('/api/dl/delete', { key });
    refresh();
};

// ---- Picking what to download from a list of streams
const isHttp = (s: any) => typeof s?.url === 'string' && /^https?:\/\//i.test(s.url) && !s.infoHash;
const CACHED = /\[(RD|AD|PM|DL|TB|OC|ED|EN|PKP|DLS|ST|TRB)\+\]|⚡|(^|[^a-z])(instant|cached)([^a-z]|$)/i;

// Best source per quality (instant ones first, then the addon's own order), best quality first.
export const optionsFrom = (streams: any[]): DlOption[] => {
    const byQuality = new Map<string, DlOption>();
    streams.filter(isHttp).forEach((s) => {
        const text = streamText(s);
        const quality = parseQuality(text);
        const gb = parseSizeGB(text);
        const option: DlOption = {
            quality,
            url: s.url,
            bytes: gb ? Math.round(gb * 1e9) : 0,
            cached: CACHED.test(text),
            description: String(s.description ?? s.title ?? s.name ?? '').split('\n')[0],
            streamBase: typeof s.addonUrl === 'string' ? s.addonUrl : null,
        };
        const k = quality ?? 'other';
        const old = byQuality.get(k);
        if (!old || (option.cached && !old.cached)) byQuality.set(k, option);
    });
    return Array.from(byQuality.values()).sort((a, b) => {
        const ia = a.quality ? QUALITIES.indexOf(a.quality) : 99;
        const ib = b.quality ? QUALITIES.indexOf(b.quality) : 99;
        return ia - ib;
    });
};

// For a season: the wanted quality, else the closest lower one, else the closest higher one.
export const pickFor = (options: DlOption[], want: string | null | undefined): DlOption | null => {
    if (options.length === 0) return null;
    const w = want ? QUALITIES.indexOf(want) : -1;
    if (w < 0) return options[0];
    for (let i = w; i < QUALITIES.length; i++) { const o = options.find((x) => x.quality === QUALITIES[i]); if (o) return o; }
    for (let i = w - 1; i >= 0; i--) { const o = options.find((x) => x.quality === QUALITIES[i]); if (o) return o; }
    return options[0];
};

// ---- Season downloads: waiting episodes get their source found here, one at a time.
const streamAddons = (addons: Addon[], type: string, id: string) => addons.filter((a: any) => {
    const m = a.manifest ?? a.raw?.manifest ?? null;
    const resources: any[] = Array.isArray(m?.resources) ? m.resources : [];
    const has = resources.some((r) => r === 'stream' || r?.name === 'stream');
    if (!has) return false;
    const types: any[] = Array.isArray(m?.types) ? m.types : [];
    const prefixes: any[] | null = Array.isArray(m?.idPrefixes) ? m.idPrefixes : null;
    return (types.length === 0 || types.includes(type)) && (!prefixes || prefixes.some((p) => id.startsWith(p)));
});

const findStreams = async (addons: Addon[], type: string, videoId: string): Promise<any[]> => {
    const list = streamAddons(addons, type, videoId);
    const results = await Promise.all(list.map(async (a: any) => {
        const base = String(a.transportUrl).replace(/\/manifest\.json$/, '');
        try {
            const controller = new AbortController();
            const t = setTimeout(() => controller.abort(), 25000);
            const response = await fetch(`${base}/stream/${encodeURIComponent(type)}/${encodeURIComponent(videoId)}.json`, { signal: controller.signal });
            clearTimeout(t);
            const data = await response.json();
            return (Array.isArray(data?.streams) ? data.streams : []).map((s: any) => ({ ...s, addonUrl: a.transportUrl }));
        } catch (_e) {
            return [];
        }
    }));
    return results.flat();
};

let working = false;
export const useDownloadWorker = (addons: Addon[]) => {
    const { entries } = useDownloads();
    const waiting = entries.find((e) => e.state === 'waiting');
    useEffect(() => {
        if (!waiting || working || addons.length === 0) return;
        working = true;
        (async () => {
            const streams = await findStreams(addons, waiting.type, waiting.videoId);
            const option = pickFor(optionsFrom(streams), waiting.wantQuality);
            if (option) {
                await startDownload({ ...waiting, url: option.url, quality: option.quality, bytes: option.bytes, streamBase: option.streamBase ?? waiting.streamBase ?? null });
            } else {
                await api('/api/dl/fail', { key: waiting.key, error: 'No downloadable source (only debrid / direct links can be downloaded)' });
                refresh();
            }
        })().finally(() => { working = false; refresh(); });
    }, [waiting?.key, addons.length]);
};

// The download worker lives for as long as the app, so season downloads keep going on any screen.
export const DownloadWorker = () => {
    useDownloadWorker(useAddons());
    return null;
};

// Downloads by key, for badges and buttons.
export const useDownloadMap = () => {
    const snapshot = useDownloads();
    const map = useMemo(() => new Map(snapshot.entries.map((e) => [e.key, e])), [snapshot.entries]);
    return { ...snapshot, map };
};

// What a title as a whole looks like in Library: ✓ when all its downloads are done, else overall progress.
export const titleStatus = (entries: Download[], type: string, metaId: string) => {
    const list = entries.filter((e) => e.type === type && e.metaId === metaId && e.state !== 'failed');
    if (list.length === 0) return null;
    const done = list.every((e) => e.state === 'done');
    const progress = list.reduce((sum, e) => sum + progressOf(e), 0) / list.length;
    return { done, progress, count: list.length };
};

// Play a finished download from the file, resuming where you stopped (the player reads that from your library).
export const usePlayDownload = () => {
    const core = useCore() as any;
    const navigate = useNavigate();
    return useCallback(async (d: Download) => {
        clearQueue();
        const q = qualityLabel(d.quality ?? null);
        const encoded: string = await core.transport.encodeStream({
            url: localUrl(d),
            name: 'Codio',
            description: `Downloaded${q ? ` · ${q}` : ''}`,
            behaviorHints: { filename: d.file ?? undefined },
        });
        const metaBase = d.metaBase ?? CINEMETA;
        const streamBase = d.streamBase ?? metaBase;
        const parts = [encoded, streamBase, metaBase, d.type, d.metaId, d.videoId].map((p) => encodeURIComponent(p));
        navigate(`/player/${parts.join('/')}`);
    }, [core, navigate]);
};
