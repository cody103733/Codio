// Profiles, as on the TV and phone: the iPhone app keeps a list of profiles (each its own Stremio
// account) in a hidden library entry, "codio:profiles", on every profile's account. Codio reads it
// after sign-in and switches profiles with the saved sign-in token (never a password).

import { useCallback, useEffect, useState } from 'react';
import { useProfile } from 'stremio/common';
import { useCore } from 'stremio/core';

export type CodioProfile = { email: string, name: string, color: number, token: string };

const ITEM_ID = 'codio:profiles';
const STORAGE_KEY = 'codio.profiles';
const API = 'https://api.strem.io/api/';

// The iPhone's profile colours, in the same order.
export const PROFILE_COLORS = ['#E60A14', '#2178F2', '#FABA1A', '#29AD61', '#8C4DD9', '#F27326'];
export const profileColor = (p: CodioProfile) => PROFILE_COLORS[((p.color % PROFILE_COLORS.length) + PROFILE_COLORS.length) % PROFILE_COLORS.length];

const parse = (text: string | null | undefined): CodioProfile[] => {
    try {
        const json = JSON.parse(text ?? '');
        const list = Array.isArray(json) ? json : Array.isArray(json?.profiles) ? json.profiles : [];
        return list
            .filter((p: any) => p && typeof p.email === 'string' && typeof p.name === 'string')
            .map((p: any) => ({ email: p.email, name: p.name, color: Number(p.color) || 0, token: typeof p.token === 'string' ? p.token : '' }));
    } catch (_e) {
        return [];
    }
};

const saved = (): CodioProfile[] => {
    try {
        return parse(localStorage.getItem(STORAGE_KEY));
    } catch (_e) {
        return [];
    }
};

/** Reads the profile list saved with the signed-in account (null when there is none). */
const fetchProfiles = async (authKey: string): Promise<CodioProfile[] | null> => {
    const response = await fetch(`${API}datastoreGet`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ authKey, collection: 'libraryItem', ids: [ITEM_ID], all: false }),
    });
    if (!response.ok) return null;
    const json = await response.json();
    const item = Array.isArray(json?.result) ? json.result.find((i: any) => i && i._id === ITEM_ID) : null;
    return item ? parse(item.name) : null;
};

const KEY_ITEM_ID = 'codio:tmdb';
const KEY_STORAGE = 'codio.tmdbKey';
const looksLikeKey = (key: unknown): key is string => typeof key === 'string' && /^[0-9a-f]{32}$/i.test(key.trim());
let keyRequest: Promise<void> | null = null;

/**
 * Makes sure the TMDB key for cast photos is here: the TV and iPhone apps save the user's own key
 * with the account (hidden entry "codio:tmdb"), so Codio for Windows takes it from there.
 */
export const syncTmdbKey = (authKey: string | null | undefined): Promise<void> => {
    try {
        if (looksLikeKey(localStorage.getItem(KEY_STORAGE))) return Promise.resolve();
    } catch (_e) {
        return Promise.resolve();
    }
    if (!authKey) return Promise.resolve();
    if (!keyRequest) {
        keyRequest = fetch(`${API}datastoreGet`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ authKey, collection: 'libraryItem', ids: [KEY_ITEM_ID], all: false }),
        })
            .then((response) => (response.ok ? response.json() : null))
            .then((json) => {
                const item = Array.isArray(json?.result) ? json.result.find((i: any) => i && i._id === KEY_ITEM_ID) : null;
                if (looksLikeKey(item?.name)) localStorage.setItem(KEY_STORAGE, item.name.trim());
            })
            .catch(() => { /* offline: cast shows names only */ })
            .finally(() => { keyRequest = null; });
    }
    return keyRequest;
};

/** The profiles, the one being watched, and a way to switch. */
export const useProfiles = () => {
    const profile = useProfile() as any;
    const core = useCore() as any;
    const authKey: string | null = profile?.auth?.key ?? null;
    const email: string | null = profile?.auth?.user?.email ?? null;
    const [list, setList] = useState<CodioProfile[]>(saved);
    const [switching, setSwitching] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        if (!authKey) return;
        void syncTmdbKey(authKey);
        let cancelled = false;
        fetchProfiles(authKey).then((fresh) => {
            if (cancelled || !fresh || fresh.length === 0) return;
            setList(fresh);
            try {
                localStorage.setItem(STORAGE_KEY, JSON.stringify(fresh));
            } catch (_e) { /* storage full or blocked: keep it in memory */ }
        }, () => { /* offline: keep the saved list */ });
        return () => { cancelled = true; };
    }, [authKey]);

    // A switch is done when the account changes to the chosen profile.
    useEffect(() => {
        if (switching && email && email.toLowerCase() === switching.toLowerCase()) setSwitching(null);
    }, [email, switching]);
    useEffect(() => {
        if (!switching) return;
        const timer = setTimeout(() => {
            setSwitching(null);
            setError('Couldn\'t switch profiles. Open that profile once on the iPhone, then try again.');
        }, 15000);
        return () => clearTimeout(timer);
    }, [switching]);

    const current = list.find((p) => email && p.email.toLowerCase() === email.toLowerCase()) ?? null;

    const switchTo = useCallback((p: CodioProfile) => {
        setError(null);
        if (email && p.email.toLowerCase() === email.toLowerCase()) return;
        if (!p.token) {
            setError(`Open ${p.name} once on the iPhone, then try again.`);
            return;
        }
        setSwitching(p.email);
        core.transport.dispatch({ action: 'Ctx', args: { action: 'Authenticate', args: { type: 'LoginWithToken', token: p.token } } });
    }, [core, email]);

    return { list, current, switchTo, switching, error };
};
