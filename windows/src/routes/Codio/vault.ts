// Sync passcode: your private settings (TMDB key, music addon links, ListenBrainz sign-in, MusicBrainz server) follow
// you to every device signed in to the same Stremio account, locked with a passcode only you know.
// They are encrypted here (PBKDF2-SHA256 600,000 rounds -> AES-256-GCM) and kept in a hidden library entry,
// "codio:vault". Stremio stores the locked box but can't open it, and nothing in Codio's public code can either.
// Same format and rules on every Codio app: see SYNC.md at the repo root.

import { useEffect, useSyncExternalStore } from 'react';
import { useProfile } from 'stremio/common';
import { loadAddons, saveAddons } from './music/addons';
import { loadMusicSettings, saveMusicSettings } from './music/musicbrainz';
import { adoptAccount, signOut as lbSignOut, getAccount as lbAccount } from './music/listenbrainz';

const API = 'https://api.strem.io/api/';
const VAULT_ID = 'codio:vault';
const LEGACY_TMDB_ID = 'codio:tmdb';
const STATE_KEY = 'codio.sync';
const TMDB_STORAGE = 'codio.tmdbKey';
const ITERATIONS = 600000;
// Without a local change, the account's copy is read again at most this often.
const PULL_EVERY_MS = 10 * 60 * 1000;

type Item = { value: any, at: number };
type Items = Record<string, Item>;
type Box = { v: number, kdf: string, iter: number, salt: string, iv: string, data: string };

type State = {
    // The passcode's key (never the passcode itself), and the salt it was made with.
    key: string | null,
    salt: string | null,
    iter: number,
    // Per account: what each item looked like the last time this device and the account agreed.
    synced: Record<string, Record<string, string>>,
    lastSync: number | null,
    // 'locked': the account's box was locked with another passcode (changed on another device).
    problem: 'locked' | 'offline' | null,
};

export type SyncStatus = {
    on: boolean,
    busy: boolean,
    lastSync: number | null,
    problem: 'locked' | 'offline' | null,
};

// ---- Bytes and crypto

const b64 = (bytes: Uint8Array) => {
    let s = '';
    bytes.forEach((b) => { s += String.fromCharCode(b); });
    return btoa(s);
};
const unb64 = (text: string) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
const utf8 = (text: string) => new TextEncoder().encode(text);

export const normalisePasscode = (text: string) => text.trim().normalize('NFC');
export const MIN_PASSCODE = 6;

const deriveKey = async (passcode: string, salt: Uint8Array, iter: number): Promise<Uint8Array> => {
    const base = await crypto.subtle.importKey('raw', utf8(normalisePasscode(passcode)), 'PBKDF2', false, ['deriveBits']);
    const bits = await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: iter }, base, 256);
    return new Uint8Array(bits);
};

const aesKey = (raw: Uint8Array) => crypto.subtle.importKey('raw', raw, 'AES-GCM', false, ['encrypt', 'decrypt']);

const seal = async (raw: Uint8Array, salt: string, iter: number, items: Items): Promise<Box> => {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const plain = utf8(JSON.stringify({ v: 1, items }));
    const data = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv }, await aesKey(raw), plain));
    return { v: 1, kdf: 'pbkdf2-sha256', iter, salt, iv: b64(iv), data: b64(data) };
};

// The items in the box, or null when this key doesn't open it.
const open = async (raw: Uint8Array, box: Box): Promise<Items | null> => {
    try {
        const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: unb64(box.iv) }, await aesKey(raw), unb64(box.data));
        const json = JSON.parse(new TextDecoder().decode(plain));
        return json && typeof json.items === 'object' && json.items ? json.items : {};
    } catch (_e) {
        return null;
    }
};

// ---- The hidden entry

const call = async (method: string, body: unknown): Promise<any> => {
    const response = await fetch(`${API}${method}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (!response.ok) throw new Error(`Stremio answered ${response.status}`);
    const json = await response.json();
    if (json?.error) throw new Error(String(json.error?.message ?? json.error));
    return json;
};

// The box saved with the account: a Box, or null when there is none yet.
const readBox = async (authKey: string): Promise<Box | null> => {
    const json = await call('datastoreGet', { authKey, collection: 'libraryItem', ids: [VAULT_ID], all: false });
    const item = Array.isArray(json?.result) ? json.result.find((i: any) => i && i._id === VAULT_ID) : null;
    if (!item || typeof item.name !== 'string' || item.name.length === 0) return null;
    try {
        const box = JSON.parse(item.name);
        return box && typeof box.salt === 'string' && typeof box.iv === 'string' && typeof box.data === 'string' ? box : null;
    } catch (_e) {
        return null;
    }
};

const hiddenItem = (id: string, name: string) => {
    const now = new Date().toISOString();
    return {
        _id: id, name, type: 'other', poster: null, posterShape: 'poster', removed: true, temp: true, _ctime: now, _mtime: now,
        state: { lastWatched: null, timeWatched: 0, timeOffset: 0, overallTimeWatched: 0, timesWatched: 0, flaggedWatched: 0, duration: 0, video_id: null, watched: null, noNotif: true },
        behaviorHints: { defaultVideoId: null, featuredVideoId: null, hasScheduledVideos: false },
    };
};

// Saves the box, and empties the old unlocked TMDB entry so the key isn't readable there any more.
const writeBox = async (authKey: string, box: Box) => {
    await call('datastorePut', { authKey, collection: 'libraryItem', changes: [hiddenItem(VAULT_ID, JSON.stringify(box)), hiddenItem(LEGACY_TMDB_ID, '')] });
};

// ---- What is synced, as it is on this PC

type Kind = { read: () => any, write: (value: any) => void, empty: (value: any) => boolean, print: (value: any) => string, join?: (remote: any, local: any) => any };

const tmdbRead = () => { try { return (localStorage.getItem(TMDB_STORAGE) ?? '').trim(); } catch (_e) { return ''; } };
const addonList = (value: any) => (Array.isArray(value) ? value : []).filter((a: any) => a && typeof a.url === 'string' && a.manifest && typeof a.manifest === 'object');

const KINDS: Record<string, Kind> = {
    tmdbKey: {
        read: tmdbRead,
        write: (v) => { try { if (v) localStorage.setItem(TMDB_STORAGE, v); else localStorage.removeItem(TMDB_STORAGE); } catch (_e) { /* storage off */ } },
        empty: (v) => typeof v !== 'string' || v.trim() === '',
        print: (v) => typeof v === 'string' ? v.trim() : '',
    },
    musicAddons: {
        read: () => loadAddons().map((a) => ({ url: a.url, manifest: a.manifest })),
        write: (v) => { saveAddons(addonList(v)); },
        empty: (v) => addonList(v).length === 0,
        print: (v) => addonList(v).map((a: any) => a.url).join('\n'),
        // Joining: the account's addons, then the ones only this PC had.
        join: (remote, local) => {
            const list = addonList(remote);
            addonList(local).forEach((a: any) => { if (!list.some((r: any) => r.url === a.url)) list.push(a); });
            return list;
        },
    },
    listenBrainz: {
        read: () => { const a = lbAccount(); return a ? { token: a.token, user: a.user } : null; },
        write: (v) => { if (v && typeof v.token === 'string' && typeof v.user === 'string') adoptAccount(v.token, v.user); else lbSignOut(); },
        empty: (v) => !v || typeof v.token !== 'string' || v.token === '',
        print: (v) => (v && typeof v.token === 'string' ? v.token : ''),
    },
    musicBrainzServer: {
        read: () => loadMusicSettings().mbBase.trim(),
        write: (v) => { saveMusicSettings({ ...loadMusicSettings(), mbBase: typeof v === 'string' ? v.trim() : '' }); },
        empty: (v) => typeof v !== 'string' || v.trim() === '',
        print: (v) => typeof v === 'string' ? v.trim() : '',
    },
};

// ---- State

const readState = (): State => {
    try {
        const s = JSON.parse(localStorage.getItem(STATE_KEY) ?? 'null');
        if (s && typeof s === 'object') {
            return { key: s.key ?? null, salt: s.salt ?? null, iter: Number(s.iter) || ITERATIONS, synced: s.synced && typeof s.synced === 'object' ? s.synced : {}, lastSync: s.lastSync ?? null, problem: s.problem ?? null };
        }
    } catch (_e) { /* fresh */ }
    return { key: null, salt: null, iter: ITERATIONS, synced: {}, lastSync: null, problem: null };
};

let state = readState();
let busy = false;
let status: SyncStatus = { on: !!state.key, busy, lastSync: state.lastSync, problem: state.problem };
const listeners = new Set<() => void>();
const setState = (patch: Partial<State>) => {
    state = { ...state, ...patch };
    try { localStorage.setItem(STATE_KEY, JSON.stringify(state)); } catch (_e) { /* storage off */ }
    publish();
};
const publish = () => {
    status = { on: !!state.key, busy, lastSync: state.lastSync, problem: state.problem };
    listeners.forEach((l) => l());
};

export const useSyncStatus = (): SyncStatus => useSyncExternalStore((l) => { listeners.add(l); return () => { listeners.delete(l); }; }, () => status);

// ---- Sync

let account: { authKey: string, id: string } | null = null;
let running: Promise<void> | null = null;

const localPrints = () => Object.fromEntries(Object.entries(KINDS).map(([name, k]) => [name, k.print(k.read())]));

// Merges this PC's values with the account's box and saves both. `fresh` = start the box over from this PC.
const syncWith = async (raw: Uint8Array, salt: string, iter: number, fresh = false) => {
    if (!account) throw new Error('Sign in first');
    const { authKey, id } = account;
    const box = fresh ? null : await readBox(authKey);
    let remote: Items = {};
    if (box) {
        if (box.salt !== salt) {
            setState({ problem: 'locked' });
            return;
        }
        const opened = await open(raw, box);
        if (!opened) {
            setState({ problem: 'locked' });
            return;
        }
        remote = opened;
    }
    const before = fresh ? {} : (state.synced[id] ?? {});
    const now = Date.now();
    const result: Items = { ...remote };
    const prints: Record<string, string> = {};
    let changedRemote = !box;
    Object.entries(KINDS).forEach(([name, kind]) => {
        const local = kind.read();
        const r = remote[name];
        let value: any;
        let at: number;
        if (!r) {
            value = local; at = now;
        } else if (before[name] === undefined) {
            // First time with this account: keep both where we can.
            if (kind.join) value = kind.join(r.value, local);
            else value = kind.empty(r.value) ? local : r.value;
            at = kind.print(value) === kind.print(r.value) ? r.at : now;
        } else if (kind.print(local) !== before[name]) {
            value = local; at = now;
        } else {
            value = r.value; at = r.at;
        }
        if (kind.print(value) !== kind.print(local)) kind.write(value);
        if (!r || kind.print(value) !== kind.print(r.value)) changedRemote = true;
        result[name] = { value, at };
        prints[name] = kind.print(kind.read());
    });
    if (changedRemote) await writeBox(authKey, await seal(raw, salt, iter, result));
    setState({ synced: { ...state.synced, [id]: prints }, lastSync: Date.now(), problem: null });
};

const run = (job: () => Promise<void>): Promise<void> => {
    const next = (running ?? Promise.resolve()).catch(() => undefined).then(async () => {
        busy = true;
        publish();
        try {
            await job();
        } finally {
            busy = false;
            publish();
        }
    });
    running = next;
    return next;
};

const syncNow = (): Promise<void> => run(async () => {
    if (!state.key || !state.salt || !account) return;
    try {
        await syncWith(unb64(state.key), state.salt, state.iter);
    } catch (_e) {
        setState({ problem: state.problem === 'locked' ? 'locked' : 'offline' });
    }
});

// Cheap check, called often: syncs when something changed here, or now and then to pick up other devices' changes.
export const maybeSync = (force = false) => {
    if (!state.key || !account || busy) return;
    const before = state.synced[account.id];
    const changed = !before || Object.entries(localPrints()).some(([name, p]) => before[name] !== p);
    const stale = !state.lastSync || Date.now() - state.lastSync > PULL_EVERY_MS || state.problem === 'offline';
    if (force || changed || stale) void syncNow();
};

/** Turns sync on with this passcode. With a box already saved, the passcode must open it. Throws a message to show. */
export const setPasscode = (passcode: string): Promise<void> => run(async () => {
    const p = normalisePasscode(passcode);
    if (p.length < MIN_PASSCODE) throw new Error(`Use at least ${MIN_PASSCODE} characters`);
    if (!account) throw new Error('Sign in first');
    let box: Box | null;
    try {
        box = await readBox(account.authKey);
    } catch (_e) {
        throw new Error('Couldn\'t reach your Stremio account. Check the connection and try again.');
    }
    const salt = box ? unb64(box.salt) : crypto.getRandomValues(new Uint8Array(16));
    const iter = box ? box.iter || ITERATIONS : ITERATIONS;
    const raw = await deriveKey(p, salt, iter);
    if (box && !(await open(raw, box))) throw new Error('That\'s not the sync passcode. Try again, or start over with a new one.');
    setState({ key: b64(raw), salt: b64(salt), iter, problem: null });
    await syncWith(raw, b64(salt), iter);
});

/** Locks the box with a new passcode (the other devices then ask for it). Throws a message to show. */
export const changePasscode = (passcode: string): Promise<void> => run(async () => {
    const p = normalisePasscode(passcode);
    if (p.length < MIN_PASSCODE) throw new Error(`Use at least ${MIN_PASSCODE} characters`);
    if (!account) throw new Error('Sign in first');
    if (!state.key || !state.salt) throw new Error('Turn sync on first');
    await syncWith(unb64(state.key), state.salt, state.iter);
    if (state.problem) throw new Error('Enter the current sync passcode first');
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const raw = await deriveKey(p, salt, ITERATIONS);
    // Everything is in step now, so the box is simply written again with the new key.
    setState({ key: b64(raw), salt: b64(salt), iter: ITERATIONS });
    await syncWith(raw, b64(salt), ITERATIONS, true);
});

/** Forgot the passcode: a new box with a new passcode, from what this PC has. */
export const startOver = (passcode: string): Promise<void> => run(async () => {
    const p = normalisePasscode(passcode);
    if (p.length < MIN_PASSCODE) throw new Error(`Use at least ${MIN_PASSCODE} characters`);
    if (!account) throw new Error('Sign in first');
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const raw = await deriveKey(p, salt, ITERATIONS);
    setState({ key: b64(raw), salt: b64(salt), iter: ITERATIONS, synced: {}, problem: null });
    await syncWith(raw, b64(salt), ITERATIONS, true);
});

/** Stops syncing on this PC. What's here stays here. */
export const turnOff = () => setState({ key: null, salt: null, synced: {}, lastSync: null, problem: null });

/** Keeps sync running while Codio is open (call once, near the top). */
export const useVaultSync = () => {
    const profile = useProfile() as any;
    const authKey: string | null = profile?.auth?.key ?? null;
    const id: string | null = profile?.auth?.user?._id ?? profile?.auth?.user?.email ?? null;
    useEffect(() => {
        const next = authKey && id ? { authKey, id } : null;
        const changed = (next?.authKey ?? null) !== (account?.authKey ?? null);
        account = next;
        if (account && changed) maybeSync(true);
    }, [authKey, id]);
    useEffect(() => {
        const soon = () => setTimeout(() => maybeSync(), 1500);
        window.addEventListener('codio-music-addons', soon);
        window.addEventListener('codio-sync-check', soon);
        const timer = setInterval(() => maybeSync(), 30000);
        return () => {
            window.removeEventListener('codio-music-addons', soon);
            window.removeEventListener('codio-sync-check', soon);
            clearInterval(timer);
        };
    }, []);
};

export const signedInForSync = () => account !== null;
