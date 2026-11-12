// Hooks shared by the Codio screens.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { useProfile, useModelState } from 'stremio/common';
import { fallbackAddons, toAddon, detailHref, type Addon, type Meta } from './api';

// The signed-in profile's addons; Cinemeta while there are none (first start).
export const useAddons = (): Addon[] => {
    const profile = useProfile() as any;
    const fromProfile = useMemo(() => (Array.isArray(profile?.addons) ? profile.addons : [])
        .filter((a: any) => a && typeof a.transportUrl === 'string' && a.manifest)
        .map(toAddon), [profile?.addons]);
    const [fallback, setFallback] = useState<Addon[]>([]);
    useEffect(() => {
        if (fromProfile.length > 0) return;
        let cancelled = false;
        fallbackAddons().then((list) => { if (!cancelled) setFallback(list); }, () => { /* offline */ });
        return () => { cancelled = true; };
    }, [fromProfile.length]);
    return fromProfile.length > 0 ? fromProfile : fallback;
};

export const useContinueWatching = (): Meta[] => {
    const preview = (useModelState as any)({ model: 'continue_watching_preview' }) as any;
    return useMemo(() => (Array.isArray(preview?.items) ? preview.items : []).map((item: any): Meta => {
        const raw = typeof item.progress === 'number' ? item.progress : 0;
        return {
            id: String(item._id ?? item.id ?? ''),
            type: String(item.type ?? 'movie'),
            name: String(item.name ?? ''),
            poster: typeof item.poster === 'string' ? item.poster : null,
            background: null,
            logo: null,
            description: null,
            releaseInfo: null,
            runtime: null,
            imdbRating: null,
            genres: [],
            cast: [],
            videos: [],
            progress: Math.min(1, raw > 1 ? raw / 100 : raw),
        };
    }).filter((m: Meta) => m.id.length > 0), [preview?.items]);
};

// Opening and playing titles.
export const useOpen = () => {
    const navigate = useNavigate();
    const open = useCallback((meta: Meta) => navigate(detailHref(meta)), [navigate]);
    // Play: movies start right away (smart auto-play picks the source), series open their page.
    const play = useCallback((meta: Meta, videoId?: string | null) => {
        if (meta.type !== 'movie' && !videoId) {
            navigate(detailHref(meta));
            return;
        }
        navigate(`${detailHref(meta, videoId ?? meta.id)}?autoplay=1`);
    }, [navigate]);
    return { open, play };
};
