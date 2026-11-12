// Intro / recap / credits times for the episode that is playing, from the
// community database IntroDB (https://introdb.app, no account needed).
// Codio for Windows asks through its own local server (/api/skip), which has
// no browser CORS limits; anywhere else the API is asked directly.

import { useEffect, useState } from 'react';

export type Segment = { start: number, end: number }; // seconds
export type SkipTimes = { intro: Segment | null, recap: Segment | null, outro: Segment | null };

const cache = new Map<string, SkipTimes | null>();

const segment = (o: any): Segment | null => {
    if (!o || typeof o !== 'object') return null;
    const start = typeof o.start_sec === 'number' ? o.start_sec : typeof o.start_ms === 'number' ? o.start_ms / 1000 : NaN;
    const end = typeof o.end_sec === 'number' ? o.end_sec : typeof o.end_ms === 'number' ? o.end_ms / 1000 : NaN;
    if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end <= start + 2) return null;
    return { start, end };
};

const parse = (json: any): SkipTimes | null => {
    if (!json || typeof json !== 'object') return null;
    const times = { intro: segment(json.intro), recap: segment(json.recap), outro: segment(json.outro) };
    return times.intro || times.recap || times.outro ? times : null;
};

const ask = async (url: string, headers: Record<string, string> = {}): Promise<any> => {
    const response = await fetch(url, { headers });
    if (response.status === 404) return null; // nothing timed for this episode
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
};

const load = async (imdbId: string, season: number, episode: number): Promise<SkipTimes | null> => {
    const query = `imdb_id=${encodeURIComponent(imdbId)}&season=${season}&episode=${episode}`;
    try {
        return parse(await ask(`/api/skip?${query}`, { 'X-Codio': '1' }));
    } catch (_e) {
        return parse(await ask(`https://api.introdb.app/segments?${query}`));
    }
};

export const imdbIdOf = (id: string | undefined, videoId: string | undefined, meta: any): string | null => {
    const candidates = [id, typeof videoId === 'string' ? videoId.split(':')[0] : undefined, meta?.imdb_id, meta?.imdbId, meta?.behaviorHints?.imdbId];
    for (const c of candidates) {
        if (typeof c === 'string' && /^tt\d+$/.test(c)) return c;
    }
    return null;
};

export const episodeOf = (videoId: string | undefined, meta: any): { season: number, episode: number } | null => {
    if (typeof videoId !== 'string') return null;
    const video = Array.isArray(meta?.videos) ? meta.videos.find((v: any) => v && v.id === videoId) : null;
    const season = Number(video?.season ?? video?.seriesInfo?.season);
    const episode = Number(video?.episode ?? video?.seriesInfo?.episode);
    if (season > 0 && episode > 0) return { season, episode };
    const parts = videoId.split(':');
    if (parts.length >= 3) {
        const s = parseInt(parts[parts.length - 2], 10);
        const e = parseInt(parts[parts.length - 1], 10);
        if (s > 0 && e > 0) return { season: s, episode: e };
    }
    return null;
};

const useSkipSegments = (enabled: boolean, imdbId: string | null, season: number | null, episode: number | null): SkipTimes | null => {
    const key = enabled && imdbId && season && episode ? `${imdbId}:${season}:${episode}` : null;
    const [times, setTimes] = useState<{ key: string, times: SkipTimes | null } | null>(null);
    useEffect(() => {
        if (key === null || imdbId === null || season === null || episode === null) return;
        if (cache.has(key)) {
            setTimes({ key, times: cache.get(key) ?? null });
            return;
        }
        let cancelled = false;
        let attempt = 0;
        let timer: ReturnType<typeof setTimeout> | null = null;
        const run = () => {
            load(imdbId, season, episode).then((result) => {
                // Only answers are remembered; a failed request is tried again next time.
                cache.set(key, result);
                if (!cancelled) setTimes({ key, times: result });
                // eslint-disable-next-line no-console
                console.log('[Codio] skip times', key, result ?? 'none');
            }).catch((error) => {
                // eslint-disable-next-line no-console
                console.warn('[Codio] skip times', key, 'failed:', error?.message ?? error);
                if (!cancelled && ++attempt < 3) timer = setTimeout(run, 4000 * attempt);
            });
        };
        run();
        return () => {
            cancelled = true;
            if (timer) clearTimeout(timer);
        };
    }, [key]);
    return times !== null && times.key === key ? times.times : null;
};

export default useSkipSegments;
