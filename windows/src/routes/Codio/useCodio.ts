// Hooks shared by the Codio screens.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { useProfile, useModelState, useNotifications } from 'stremio/common';
import { useCore } from 'stremio/core';
import { useAutoPlaySettings } from 'stremio/common/AutoPlay';
import { observeNow } from './stats';
import { fallbackAddons, toAddon, detailHref, libraryMeta, type Addon, type Meta } from './api';

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

// Continue Watching. The engine also lists shows with a new episode out (it asks the catalog addons a few times a
// day) and puts them first: those get a "New episode" mark, or are left out when new episode alerts are off.
export const useContinueWatching = (): Meta[] => {
    const preview = (useModelState as any)({ model: 'continue_watching_preview' }) as any;
    const notifications = (useNotifications as any)() as any;
    const [settings] = useAutoPlaySettings();
    const alerts = settings.newEpisodeAlerts !== false;
    // Your stats: a new year's starting point is saved the first time the library is seen that year.
    useEffect(() => { observeNow(); }, [preview?.items]);
    return useMemo(() => (Array.isArray(preview?.items) ? preview.items : [])
        .map((item: any): Meta => {
            const meta = libraryMeta(item);
            const fresh = notifications?.items?.[meta.id];
            const count = Array.isArray(fresh) ? fresh.length : fresh && typeof fresh === 'object' ? Object.keys(fresh).length : 0;
            return count > 0 ? { ...meta, newEpisodes: count } : meta;
        })
        .filter((m: Meta) => m.id.length > 0 && (alerts || !m.newEpisodes || (m.progress ?? 0) > 0))
        .map((m: Meta) => (alerts ? m : { ...m, newEpisodes: 0 })), [preview?.items, notifications?.items, alerts]);
};

// Opening and playing titles.
export const useOpen = () => {
    const navigate = useNavigate();
    const core = useCore() as any;
    const open = useCallback((meta: Meta) => {
        // Its "New episode" mark has done its job.
        if (meta.newEpisodes) core.transport.dispatch({ action: 'Ctx', args: { action: 'DismissNotificationItem', args: meta.id } });
        navigate(detailHref(meta));
    }, [navigate, core]);
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
