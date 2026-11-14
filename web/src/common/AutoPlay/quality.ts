// Quality detection and source ranking for auto-play.
// Pure functions, no React, so they can be unit tested.

import { AutoPlaySettings, QUALITIES, QUALITY_HEIGHT, Quality } from './settings';
import { languageRank } from './language';

export type StreamLike = {
    name?: string | null,
    description?: string | null,
    title?: string | null,
    behaviorHints?: { filename?: string | null, bingeGroup?: string | null } | null,
    deepLinks?: { player?: string | null } | null,
    addonName?: string,
};

export type Candidate = {
    link: string,
    label: string,
    quality: Quality | null,
    cached: boolean,
    // Estimated average bitrate in Mbit/s (from the size in the source's text and the runtime), if known.
    mbps?: number | null,
};

export type RankOptions = {
    runtime?: string | null,
    // The viewer's audio language (profile setting, e.g. "eng"): sources only in another language go last.
    audioLanguage?: string | null,
};

// "178 min", "2h 58min", "2 h 58 m", "1:58:00" -> minutes
export const parseRuntimeMinutes = (runtime: string | null | undefined): number | null => {
    if (typeof runtime !== 'string') return null;
    const clock = runtime.match(/(\d+):(\d{2})(?::(\d{2}))?/);
    if (clock) return clock[3] !== undefined ? parseInt(clock[1], 10) * 60 + parseInt(clock[2], 10) : parseInt(clock[1], 10);
    const h = runtime.match(/(\d+)\s*h/i);
    const m = runtime.match(/(\d+)\s*m/i);
    const total = (h ? parseInt(h[1], 10) * 60 : 0) + (m ? parseInt(m[1], 10) : 0);
    if (total > 0) return total;
    const bare = runtime.match(/^\s*(\d+)\s*$/);
    return bare ? parseInt(bare[1], 10) : null;
};

// "💾 58.3 GB", "Size: 4.2GB", "1.1 TB" -> GB
export const parseSizeGB = (text: string): number | null => {
    const match = text.match(/(\d+(?:[.,]\d+)?)\s*(TB|GB|MB|TiB|GiB|MiB)(?![a-z])/i);
    if (!match) return null;
    const value = parseFloat(match[1].replace(',', '.'));
    const unit = match[2].toUpperCase();
    if (!isFinite(value) || value <= 0) return null;
    return unit.startsWith('T') ? value * 1000 : unit.startsWith('M') ? value / 1000 : value;
};

const QUALITY_PATTERNS: [Quality, RegExp][] = [
    ['2160p', /(^|[^a-z0-9])(2160p|4k|uhd)([^a-z0-9]|$)/i],
    ['1440p', /(^|[^a-z0-9])(1440p|2k|qhd)([^a-z0-9]|$)/i],
    ['1080p', /(^|[^a-z0-9])(1080p|1080i|fhd)([^a-z0-9]|$)/i],
    ['720p', /(^|[^a-z0-9])(720p)([^a-z0-9]|$)/i],
    ['480p', /(^|[^a-z0-9])(576p|480p|360p|240p|sd|dvdrip)([^a-z0-9]|$)/i],
];

const CACHED_PATTERN = /\[(RD|AD|PM|DL|TB|OC|ED|EN|PKP|DLS|ST|TRB)\+\]|⚡|(^|[^a-z])(instant|cached)([^a-z]|$)/i;
const UNCACHED_PATTERN = /\[(RD|AD|PM|DL|TB|OC|ED|EN|PKP|DLS|ST|TRB) ?download\]|⏳|(^|[^a-z])uncached([^a-z]|$)/i;

export const streamText = (stream: StreamLike): string => {
    return [
        stream.name,
        stream.title,
        stream.description,
        stream.behaviorHints?.filename,
        stream.behaviorHints?.bingeGroup,
    ].filter((part) => typeof part === 'string' && part.length > 0).join(' \n ');
};

export const parseQuality = (text: string): Quality | null => {
    for (const [quality, pattern] of QUALITY_PATTERNS) {
        if (pattern.test(text)) return quality;
    }
    return null;
};

const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export const parseKeywords = (value: string): string[] => {
    return value
        .split(',')
        .map((word) => word.trim())
        .filter((word) => word.length > 0);
};

export const isExcluded = (text: string, keywords: string[]): boolean => {
    return keywords.some((keyword) => {
        const pattern = new RegExp(`(^|[^a-z0-9])${escapeRegExp(keyword)}([^a-z0-9]|$)`, 'i');
        return pattern.test(text);
    });
};

// Order in which quality tiers are tried for a given preference and fallback mode.
// `null` means "quality unknown".
export const qualityOrder = (preferred: Quality, mode: AutoPlaySettings['fallbackMode']): (Quality | null)[] => {
    const preferredHeight = QUALITY_HEIGHT[preferred];
    const lower = QUALITIES.filter((q) => QUALITY_HEIGHT[q] < preferredHeight); // already descending
    const higher = QUALITIES.filter((q) => QUALITY_HEIGHT[q] > preferredHeight).reverse(); // ascending
    switch (mode) {
        case 'exact':
            return [preferred];
        case 'lower':
            return [preferred, ...lower];
        case 'closest':
        default:
            return [preferred, ...lower, ...higher, null];
    }
};

const streamLabel = (stream: StreamLike): string => {
    const name = (stream.name ?? '').replace(/\s+/g, ' ').trim();
    const addon = stream.addonName ?? '';
    return [addon, name].filter(Boolean).join(' · ') || 'Source';
};

// Returns the ordered list of sources auto-play should try.
export const rankStreams = (streams: StreamLike[], settings: AutoPlaySettings, options: RankOptions = {}): Candidate[] => {
    const keywords = parseKeywords(settings.excludeKeywords);
    const minutes = parseRuntimeMinutes(options.runtime);
    const maxMbps = typeof settings.maxBitrateMbps === 'number' && settings.maxBitrateMbps > 0 ? settings.maxBitrateMbps : 0;
    const order = qualityOrder(settings.preferredQuality, settings.fallbackMode);

    const parsed = streams
        .filter((stream) => typeof stream.deepLinks?.player === 'string')
        .map((stream, index) => {
            const text = streamText(stream);
            const size = parseSizeGB(text);
            const mbps = size !== null && minutes !== null && minutes >= 20 ? size * 8000 / (minutes * 60) : null;
            return {
                index,
                text,
                candidate: {
                    link: stream.deepLinks!.player as string,
                    label: streamLabel(stream),
                    quality: parseQuality(text),
                    cached: CACHED_PATTERN.test(text),
                    mbps,
                } as Candidate,
                uncached: UNCACHED_PATTERN.test(text),
            };
        })
        .filter(({ text }) => !isExcluded(text, keywords));

    const cacheScore = (item: typeof parsed[number]) => {
        if (!settings.preferCached) return 0;
        if (item.candidate.cached) return 0;
        if (item.uncached) return 2;
        return 1;
    };

    // Over the bitrate limit: still a candidate, but only after every source that fits.
    const tooFast = (item: typeof parsed[number]) => maxMbps > 0 && typeof item.candidate.mbps === 'number' && item.candidate.mbps > maxMbps ? 1 : 0;

    const seen = new Set<string>();
    const result: Candidate[] = [];
    const overLimit: Candidate[] = [];
    for (const tier of order) {
        const inTier = parsed
            .filter((item) => item.candidate.quality === tier)
            .sort((a, b) => tooFast(a) - tooFast(b) || cacheScore(a) - cacheScore(b) || a.index - b.index);
        for (const item of inTier) {
            if (seen.has(item.candidate.link)) continue;
            seen.add(item.candidate.link);
            (tooFast(item) ? overLimit : result).push(item.candidate);
        }
    }
    // Sources over the limit come only after everything that fits (a smooth 1080p beats a stalling 4K remux).
    result.push(...overLimit);

    // Sources whose audio is only in another language come after all the others, whatever their quality.
    const lang = new Map(parsed.map((item) => [item.candidate.link, languageRank(item.text, options.audioLanguage)]));
    const ordered = result
        .map((candidate, position) => ({ candidate, position }))
        .sort((a, b) => (lang.get(a.candidate.link) ?? 0) - (lang.get(b.candidate.link) ?? 0) || a.position - b.position)
        .map(({ candidate }) => candidate);

    return ordered.slice(0, Math.max(1, settings.maxAttempts));
};
