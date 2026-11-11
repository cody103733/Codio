// Auto-play settings for the Stremio AutoPlay fork.
// Stored in localStorage (the core's settings struct is fixed, so we keep these client-side).

import { useCallback, useEffect, useState } from 'react';

export type Quality = '2160p' | '1440p' | '1080p' | '720p' | '480p';

// How to treat sources that are not exactly the preferred quality.
//  - 'closest': preferred first, then the next lower qualities, then higher ones
//  - 'lower':   preferred first, then only lower qualities (never bigger files)
//  - 'exact':   only the preferred quality
export type FallbackMode = 'closest' | 'lower' | 'exact';

export type AutoPlaySettings = {
    enabled: boolean,
    // Hide the source list: movies get a Play button, episodes start right away.
    hideSources: boolean,
    preferredQuality: Quality,
    fallbackMode: FallbackMode,
    // Seconds to wait before starting so the user can cancel.
    countdownSeconds: number,
    // Max seconds to wait for slow addons before picking from what has loaded.
    addonWaitSeconds: number,
    // Skip to the next source when the player reports an error.
    skipOnError: boolean,
    // Skip if the source hasn't started playing within this many seconds (0 = off).
    startupTimeoutSeconds: number,
    // Skip if the player reports a resolution clearly below what was promised.
    verifyResolution: boolean,
    // Prefer debrid-cached / instant sources inside the same quality.
    preferCached: boolean,
    // Comma separated words; sources containing any of them are never auto-picked.
    excludeKeywords: string,
    // Max number of sources to try before giving up.
    maxAttempts: number,
    // Sources whose estimated bitrate (size / runtime) is above this many Mbit/s are tried last (0 = no limit).
    // Huge 4K remuxes can need more than the internet connection delivers, which shows up as stalls.
    maxBitrateMbps: number,
};

export const QUALITIES: Quality[] = ['2160p', '1440p', '1080p', '720p', '480p'];

export const QUALITY_HEIGHT: Record<Quality, number> = {
    '2160p': 2160,
    '1440p': 1440,
    '1080p': 1080,
    '720p': 720,
    '480p': 480,
};

export const DEFAULT_SETTINGS: AutoPlaySettings = {
    enabled: false,
    hideSources: true,
    preferredQuality: '1080p',
    fallbackMode: 'closest',
    countdownSeconds: 3,
    addonWaitSeconds: 8,
    skipOnError: true,
    startupTimeoutSeconds: 20,
    verifyResolution: true,
    preferCached: true,
    excludeKeywords: 'CAM, HDCAM, TS, TELESYNC, HDTS, TC, SCR',
    maxAttempts: 8,
    maxBitrateMbps: 0,
};

const STORAGE_KEY = 'autoPlaySettings';
const CHANGE_EVENT = 'autoplay-settings-changed';

export const loadAutoPlaySettings = (): AutoPlaySettings => {
    try {
        const raw = window.localStorage.getItem(STORAGE_KEY);
        if (!raw) return { ...DEFAULT_SETTINGS };
        const parsed = JSON.parse(raw);
        return { ...DEFAULT_SETTINGS, ...(parsed && typeof parsed === 'object' ? parsed : {}) };
    } catch (_e) {
        return { ...DEFAULT_SETTINGS };
    }
};

export const saveAutoPlaySettings = (settings: AutoPlaySettings) => {
    try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(settings));
    } catch (_e) {
        // storage unavailable, keep in-memory only
    }
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT));
};

const useAutoPlaySettings = (): [AutoPlaySettings, (update: Partial<AutoPlaySettings>) => void] => {
    const [settings, setSettings] = useState<AutoPlaySettings>(loadAutoPlaySettings);

    useEffect(() => {
        const onChange = () => setSettings(loadAutoPlaySettings());
        const onStorage = (event: StorageEvent) => {
            if (event.key === STORAGE_KEY) onChange();
        };
        window.addEventListener(CHANGE_EVENT, onChange);
        window.addEventListener('storage', onStorage);
        return () => {
            window.removeEventListener(CHANGE_EVENT, onChange);
            window.removeEventListener('storage', onStorage);
        };
    }, []);

    const update = useCallback((partial: Partial<AutoPlaySettings>) => {
        saveAutoPlaySettings({ ...loadAutoPlaySettings(), ...partial });
    }, []);

    return [settings, update];
};

export default useAutoPlaySettings;
