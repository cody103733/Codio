// Jumps to the next queued source when the current one fails, never starts,
// or turns out to be a lower resolution than its label promised.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { usePlatform, useToast } from 'stremio/common';
import {
    useAutoPlaySettings,
    getQueue,
    setQueueIndex,
    findCandidateIndex,
    QUALITY_HEIGHT,
    type AutoPlayQueue,
    toPath,
} from 'stremio/common/AutoPlay';

type VideoState = {
    loaded: boolean | null,
    buffering: boolean | null,
    paused: boolean | null,
    time: number | null,
};

type Options = {
    streamParam: string | undefined,
    videoState: VideoState,
    containerRef: React.RefObject<HTMLElement>,
};

export type AutoPlayStatus = {
    position: number,
    total: number,
    label: string,
    quality: string | null,
} | null;

// A 1920x800 "scope" encode is still 1080p, so judge by width as well as height.
const effectiveHeight = (width: number, height: number) => Math.max(height, Math.round(width * 9 / 16));

const useAutoPlayFallback = ({ streamParam, videoState, containerRef }: Options) => {
    const navigate = useNavigate();
    const toast = useToast();
    const platform = usePlatform();
    const [settings] = useAutoPlaySettings();

    const [queue, setQueue] = useState<AutoPlayQueue | null>(() => getQueue());
    useEffect(() => {
        setQueue(getQueue());
    }, [streamParam]);

    const index = useMemo(() => {
        return queue !== null ? findCandidateIndex(queue, streamParam) : -1;
    }, [queue, streamParam]);

    const active = settings.enabled && queue !== null && index !== -1;
    const candidate = active ? queue!.candidates[index] : null;

    useEffect(() => {
        if (active) setQueueIndex(index);
    }, [active, index]);

    // Only skip once per source, even if several triggers fire together.
    const skippedRef = useRef<string | null>(null);

    // `source` is the stream the trigger belongs to; late events from a previous
    // source are ignored so they can't skip the one that is playing now.
    const skip = useCallback((source: string | undefined, reason: string): boolean => {
        if (!active || queue === null || typeof streamParam !== 'string') return false;
        if (source !== streamParam) return false;
        if (skippedRef.current === streamParam) return true;
        skippedRef.current = streamParam;
        const next = queue.candidates[index + 1];
        if (!next) {
            toast.show({
                type: 'error',
                title: 'Auto-play',
                message: `${reason} No more sources to try.`,
                timeout: 6000,
            });
            return false;
        }
        toast.show({
            type: 'info',
            title: 'Auto-play',
            message: `${reason} Trying source ${index + 2} of ${queue.candidates.length}${next.quality ? ` (${next.quality})` : ''}.`,
            timeout: 4000,
        });
        setQueueIndex(index + 1);
        navigate(toPath(next.link), { replace: true });
        return true;
    }, [active, queue, index, streamParam, navigate, toast]);

    const skipRef = useRef(skip);
    skipRef.current = skip;
    const streamRef = useRef(streamParam);
    streamRef.current = streamParam;
    const settingsRef = useRef(settings);
    settingsRef.current = settings;

    // Returns true if the error was handled by jumping to another source.
    const handleError = useCallback((error: { critical?: boolean }) => {
        if (!error || !error.critical || !settingsRef.current.skipOnError) return false;
        return skipRef.current(streamRef.current, 'This source failed to play.');
    }, []);

    const next = useCallback(() => {
        skipRef.current(streamRef.current, 'Skipped.');
    }, []);

    // Startup timeout: the source must get past initial loading/buffering in time.
    const startedRef = useRef(false);
    useEffect(() => {
        startedRef.current = false;
    }, [streamParam]);
    useEffect(() => {
        if (videoState.loaded && videoState.buffering === false) {
            startedRef.current = true;
        }
    }, [videoState.loaded, videoState.buffering]);
    useEffect(() => {
        if (!active || settings.startupTimeoutSeconds <= 0) return;
        const source = streamParam;
        const timer = setTimeout(() => {
            if (!startedRef.current) {
                skipRef.current(source, `This source did not start within ${settings.startupTimeoutSeconds}s.`);
            }
        }, settings.startupTimeoutSeconds * 1000);
        return () => clearTimeout(timer);
    }, [active, streamParam, settings.startupTimeoutSeconds]);

    // Resolution check. Only trust reports after the new source has loaded
    // (the player keeps the previous source's size until then).
    // Tagged with the stream it belongs to, so a stale `true` from the previous
    // source is never read during the render where the route changes.
    const [loadedFor, setLoadedFor] = useState<string | null>(null);
    const unloadedSinceChangeRef = useRef(false);
    useEffect(() => {
        unloadedSinceChangeRef.current = false;
    }, [streamParam]);
    useEffect(() => {
        if (!videoState.loaded) {
            unloadedSinceChangeRef.current = true;
        } else if (unloadedSinceChangeRef.current && typeof streamParam === 'string') {
            setLoadedFor(streamParam);
        }
    }, [videoState.loaded, streamParam]);
    const freshlyLoaded = loadedFor !== null && loadedFor === streamParam;

    const expectedHeight = candidate?.quality ? QUALITY_HEIGHT[candidate.quality] : null;
    const checkResolution = useCallback((source: string | undefined, width: number, height: number) => {
        if (!settingsRef.current.verifyResolution || expectedHeight === null) return;
        if (!(width > 0 && height > 0)) return;
        const actual = effectiveHeight(width, height);
        if (actual < expectedHeight * 0.75) {
            skipRef.current(source, `This source is really ${actual}p, not ${expectedHeight}p.`);
        }
    }, [expectedHeight]);

    // Built-in HTML5 player: read the <video> element.
    useEffect(() => {
        if (!active || !freshlyLoaded) return;
        const element = containerRef.current?.querySelector('video');
        if (!element) return;
        const source = streamParam;
        const onMeta = () => checkResolution(source, element.videoWidth, element.videoHeight);
        if (element.videoWidth > 0) onMeta();
        element.addEventListener('loadedmetadata', onMeta);
        element.addEventListener('resize', onMeta);
        return () => {
            element.removeEventListener('loadedmetadata', onMeta);
            element.removeEventListener('resize', onMeta);
        };
    }, [active, freshlyLoaded, checkResolution, streamParam]);

    // Windows desktop app (mpv): listen to mpv's video-params. mpv may report
    // them before the player flags the source as loaded, so remember the last one.
    const mpvParamsRef = useRef<{ width: number, height: number } | null>(null);
    useEffect(() => {
        mpvParamsRef.current = null;
    }, [streamParam]);
    useEffect(() => {
        if (!active || !platform.shell.active) return;
        const onPropChange = (args: { name?: string, data?: any }) => {
            if (args?.name === 'video-params' && args.data) {
                const width = args.data.dw ?? args.data.w;
                const height = args.data.dh ?? args.data.h;
                if (typeof width === 'number' && typeof height === 'number') {
                    mpvParamsRef.current = { width, height };
                    if (freshlyLoaded) checkResolution(streamParam, width, height);
                }
            }
        };
        platform.shell.on('mpv-prop-change', onPropChange);
        if (freshlyLoaded && mpvParamsRef.current) {
            checkResolution(streamParam, mpvParamsRef.current.width, mpvParamsRef.current.height);
        }
        return () => {
            (platform.shell as any).off?.('mpv-prop-change', onPropChange);
        };
    }, [active, freshlyLoaded, platform.shell.active, checkResolution, streamParam]);

    const status: AutoPlayStatus = useMemo(() => {
        if (!active || candidate === null || queue === null) return null;
        return {
            position: index + 1,
            total: queue.candidates.length,
            label: candidate.label,
            quality: candidate.quality,
        };
    }, [active, candidate, queue, index]);

    return { status, handleError, next, compact: settings.hideSources, hasNext: active && queue !== null && index + 1 < queue.candidates.length };
};

export default useAutoPlayFallback;
