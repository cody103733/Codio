// Decides when the source list should auto-play, and which source.

import { useCallback, useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import {
    useAutoPlaySettings,
    rankStreams,
    startQueue,
    markHandled,
    wasHandled,
    type Candidate,
    type StreamLike,
    toPath,
} from 'stremio/common/AutoPlay';

export type AutoPlayPhase =
    | { type: 'disabled' }
    | { type: 'waiting' } // addons still loading
    | { type: 'countdown', candidates: Candidate[], remaining: number }
    | { type: 'starting', candidates: Candidate[] }
    | { type: 'cancelled', candidates: Candidate[] }
    | { type: 'handled', candidates: Candidate[] } // already auto-played this video recently
    | { type: 'no-match' };

type Options = {
    key: string | null,
    streams: StreamLike[],
    loading: boolean,
    // Auto-play only drives the built-in player.
    externalPlayer: boolean,
    // The title's runtime as the addon gives it ("178 min", "2h 58min"), to estimate each source's bitrate.
    runtime?: string | null,
    // 'countdown': start after the configured countdown (source list visible)
    // 'immediate': start as soon as a source is picked (hidden sources, episode clicked)
    // 'manual':    wait for the Play button (hidden sources, movie page)
    mode?: 'countdown' | 'immediate' | 'manual',
};

const useAutoPlay = ({ key, streams, loading, externalPlayer, runtime = null, mode = 'countdown' }: Options) => {
    const navigate = useNavigate();
    const [settings] = useAutoPlaySettings();
    const active = settings.enabled && !externalPlayer && key !== null;

    const candidates = useMemo(() => {
        return active ? rankStreams(streams, settings, { runtime }) : [];
    }, [active, streams, settings, runtime]);

    // Wait for slow addons, but not forever.
    const [waitedEnough, setWaitedEnough] = useState(false);
    const hasAnyStreams = streams.length > 0;
    useEffect(() => {
        setWaitedEnough(false);
    }, [key]);
    useEffect(() => {
        if (!active || !hasAnyStreams || waitedEnough) return;
        const timer = setTimeout(() => setWaitedEnough(true), Math.max(0, settings.addonWaitSeconds) * 1000);
        return () => clearTimeout(timer);
    }, [active, hasAnyStreams, waitedEnough, settings.addonWaitSeconds, key]);

    // When starting right away, a source that already matches the preferred
    // quality is good enough; don't wait for slower addons.
    const ready = !loading || waitedEnough ||
        (mode === 'immediate' && candidates.length > 0 && candidates[0].quality === settings.preferredQuality);

    // 'decision' freezes the candidate list once we commit, so late addons don't reshuffle it.
    const [decision, setDecision] = useState<{ key: string, state: 'countdown' | 'cancelled' | 'starting' | 'handled', candidates: Candidate[], startedAt: number } | null>(null);
    const [now, setNow] = useState(Date.now());

    useEffect(() => {
        if (!active || key === null) return;
        if (decision !== null && decision.key === key) return;
        if (wasHandled(key) || mode === 'manual') {
            setDecision({ key, state: 'handled', candidates, startedAt: Date.now() });
            return;
        }
        if (!ready || candidates.length === 0) return;
        setDecision({ key, state: 'countdown', candidates, startedAt: Date.now() });
    }, [active, key, ready, candidates, decision, mode]);

    const countdownSeconds = mode === 'immediate' ? 0 : settings.countdownSeconds;

    // Keep the "handled" entry's list fresh so "Auto-play now" uses all loaded sources.
    useEffect(() => {
        if (decision?.state === 'handled' && decision.candidates !== candidates) {
            setDecision({ ...decision, candidates });
        }
    }, [decision, candidates]);

    const play = useCallback((list: Candidate[]) => {
        if (key === null || list.length === 0) return;
        startQueue(key, list);
        setDecision((current) => current ? { ...current, state: 'starting' } : current);
        navigate(toPath(list[0].link));
    }, [key, navigate]);

    // Coming back from the player to this page (Back), a "starting" state left over from the last start would
    // show its spinner forever; once the start is long done, offer Play again.
    useEffect(() => {
        if (decision?.state !== 'starting') return;
        const timer = setTimeout(() => {
            setDecision((current) => current && current.state === 'starting' ? { ...current, state: 'handled' } : current);
        }, 6000);
        return () => clearTimeout(timer);
    }, [decision]);

    // Countdown ticker.
    useEffect(() => {
        if (decision?.state !== 'countdown') return;
        const total = Math.max(0, countdownSeconds) * 1000;
        let fired = false;
        const tick = () => {
            if (fired) return;
            const current = Date.now();
            setNow(current);
            if (current - decision.startedAt >= total) {
                fired = true;
                play(decision.candidates);
            }
        };
        tick();
        const interval = setInterval(tick, 250);
        return () => clearInterval(interval);
    }, [decision, countdownSeconds, play]);

    const cancel = useCallback(() => {
        if (key !== null) markHandled(key);
        setDecision((current) => current ? { ...current, state: 'cancelled' } : current);
    }, [key]);

    const playNow = useCallback(() => {
        play(decision && decision.state === 'countdown' ? decision.candidates : candidates);
    }, [decision, candidates, play]);

    const phase: AutoPlayPhase = useMemo(() => {
        if (!active) return { type: 'disabled' };
        if (decision !== null && decision.key === key) {
            switch (decision.state) {
                case 'countdown': {
                    const remaining = Math.max(0, Math.ceil((decision.startedAt + countdownSeconds * 1000 - now) / 1000));
                    return { type: 'countdown', candidates: decision.candidates, remaining };
                }
                case 'starting':
                    return { type: 'starting', candidates: decision.candidates };
                case 'cancelled':
                    return { type: 'cancelled', candidates: candidates.length > 0 ? candidates : decision.candidates };
                case 'handled':
                    return { type: 'handled', candidates: decision.candidates };
            }
        }
        if (!ready) return { type: 'waiting' };
        if (hasAnyStreams && candidates.length === 0) return { type: 'no-match' };
        return { type: 'waiting' };
    }, [active, decision, key, ready, hasAnyStreams, candidates, now, countdownSeconds]);

    return { phase, settings, cancel, playNow, ready, candidates };
};

export default useAutoPlay;
