// Your ListenBrainz account (listenbrainz.org, free and open): sign in once with your user token on each device, and
// what you play and the songs you heart follow you. Codio sends each song you listen to (a "listen") and each heart (a
// "love"), and reads back your loved songs and the listens from your other devices, so Music's rows fit you everywhere.
// Calls go through Codio's server (/api/music/me), which adds the token. Nothing is sent while signed out.

import { useSyncExternalStore } from 'react';
import type { MusicTrack } from './types';
import { releaseCoverUrl } from './musicbrainz';
import { addPlaysFromAccount, applyLovedFromAccount, getTaste, likeHooks } from './taste';

const KEY = 'codioListenBrainz';
const QUEUE_KEY = 'codioListenBrainzQueue';
const enc = encodeURIComponent;

export type Account = {
    token: string,
    user: string,
    // This computer, so its own listens aren't counted twice when they come back.
    device: string,
    // Newest listen already read back (seconds).
    listensUntil: number,
    lastSync: number | null,
    syncError: string | null,
};

type Queue = {
    listens: { listened_at: number, track_metadata: any }[],
    // recording mbid -> 1 (love) or 0 (no longer loved), with when it was changed here.
    feedback: Record<string, { score: number, at: number }>,
};

const readJson = <T,>(key: string, fallback: T): T => {
    try {
        const v = JSON.parse(window.localStorage.getItem(key) ?? 'null');
        return v && typeof v === 'object' ? v : fallback;
    } catch (_e) {
        return fallback;
    }
};
const writeJson = (key: string, value: unknown) => {
    try {
        if (value === null) window.localStorage.removeItem(key);
        else window.localStorage.setItem(key, JSON.stringify(value));
    } catch (_e) { /* storage full or off */ }
};

let account: Account | null = (() => {
    const a = readJson<Account | null>(KEY, null);
    return a && typeof a.token === 'string' && typeof a.user === 'string' ? a : null;
})();
let queue: Queue = (() => {
    const q = readJson<Queue>(QUEUE_KEY, { listens: [], feedback: {} });
    return { listens: Array.isArray(q.listens) ? q.listens : [], feedback: q.feedback && typeof q.feedback === 'object' ? q.feedback : {} };
})();
const listeners = new Set<() => void>();
const setAccount = (next: Account | null) => {
    account = next;
    writeJson(KEY, next);
    listeners.forEach((l) => l());
};
const saveQueue = () => writeJson(QUEUE_KEY, queue);

export const useListenBrainz = (): Account | null => useSyncExternalStore((l) => { listeners.add(l); return () => { listeners.delete(l); }; }, () => account);

const call = async (token: string, path: string, body?: unknown): Promise<any> => {
    const response = await fetch(`/api/music/me?p=${enc(path)}`, {
        method: body === undefined ? 'GET' : 'POST',
        headers: { 'X-Codio': '1', 'X-LB-Token': token, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    let json: any = null;
    try { json = text ? JSON.parse(text) : null; } catch (_e) { /* not json */ }
    if (!response.ok) {
        const error = new Error(json?.error || `ListenBrainz answered ${response.status}`) as Error & { status?: number };
        error.status = response.status;
        throw error;
    }
    return json;
};

const MBID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const metadata = (t: MusicTrack, device: string) => ({
    artist_name: t.artist || 'Unknown artist',
    track_name: t.title || 'Unknown song',
    ...(t.album ? { release_name: t.album } : {}),
    additional_info: {
        ...(t.mbid && MBID.test(t.mbid) ? { recording_mbid: t.mbid } : {}),
        ...(t.artistMbid && MBID.test(t.artistMbid) ? { artist_mbids: [t.artistMbid] } : {}),
        ...(t.durationMs ? { duration_ms: Math.round(t.durationMs) } : {}),
        ...(t.isrc ? { isrc: t.isrc } : {}),
        media_player: 'Codio',
        submission_client: 'Codio for Windows',
        codio_device: device,
    },
});

// ---- Sign in and out

export const signIn = async (tokenText: string): Promise<string> => {
    const token = tokenText.trim();
    if (!/^[\w-]{8,100}$/.test(token)) throw new Error('That doesn\'t look like a ListenBrainz user token');
    const answer = await call(token, '/1/validate-token');
    if (!answer?.valid || !answer.user_name) throw new Error('ListenBrainz didn\'t accept that token');
    const device = account?.device ?? (window.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`);
    // Songs hearted here before signing in go to the account first, so the first sync keeps them.
    getTaste().likedTracks.forEach((t) => {
        if (t.mbid && MBID.test(t.mbid) && !queue.feedback[t.mbid]) queue.feedback[t.mbid] = { score: 1, at: Date.now() };
    });
    saveQueue();
    setAccount({ token, user: String(answer.user_name), device, listensUntil: Math.floor(Date.now() / 1000) - 30 * 24 * 3600, lastSync: null, syncError: null });
    void syncNow();
    return String(answer.user_name);
};

export const signOut = () => {
    queue = { listens: [], feedback: {} };
    saveQueue();
    setAccount(null);
};

// ---- What you play and heart, sent as it happens (kept and sent later when offline)

const sendQueued = async (a: Account) => {
    if (queue.listens.length > 0) {
        const batch = queue.listens.slice(0, 100);
        await call(a.token, '/1/submit-listens', { listen_type: batch.length === 1 ? 'single' : 'import', payload: batch });
        queue.listens = queue.listens.slice(batch.length);
        saveQueue();
    }
    for (const [mbid, f] of Object.entries(queue.feedback)) {
        await call(a.token, '/1/feedback/recording-feedback', { recording_mbid: mbid, score: f.score });
        if (queue.feedback[mbid]?.at === f.at) delete queue.feedback[mbid];
        saveQueue();
    }
};

export const playingNow = (t: MusicTrack) => {
    const a = account;
    if (!a) return;
    call(a.token, '/1/submit-listens', { listen_type: 'playing_now', payload: [{ track_metadata: metadata(t, a.device) }] }).catch(() => { /* only a hint */ });
};

// A song counts as listened to after half of it, or four minutes, whichever comes first (ListenBrainz's rule).
export const listened = (t: MusicTrack, startedAt: number) => {
    const a = account;
    if (!a) return;
    queue.listens = [...queue.listens, { listened_at: Math.floor(startedAt / 1000), track_metadata: metadata(t, a.device) }].slice(-500);
    saveQueue();
    sendQueued(a).catch(() => { /* sent at the next sync */ });
};

likeHooks.add((t, liked) => {
    const a = account;
    if (!a || !t.mbid || !MBID.test(t.mbid)) return;
    queue.feedback[t.mbid] = { score: liked ? 1 : 0, at: Date.now() };
    saveQueue();
    sendQueued(a).catch(() => { /* sent at the next sync */ });
});

// ---- Reading back: loved songs and listens from your other devices

const lovedTrack = (f: any): MusicTrack | null => {
    const meta = f?.track_metadata;
    const mbid = String(f?.recording_mbid ?? '');
    if (!MBID.test(mbid) || !meta?.track_name) return null;
    const map = meta.mbid_mapping ?? {};
    const release = map.caa_release_mbid ?? map.release_mbid ?? null;
    return {
        id: `mbid:${mbid}`,
        mbid,
        title: String(meta.track_name),
        artist: String(meta.artist_name ?? ''),
        album: meta.release_name ?? null,
        artworkURL: release ? releaseCoverUrl(String(release)) : null,
        artistMbid: Array.isArray(map.artist_mbids) && map.artist_mbids[0] ? String(map.artist_mbids[0]) : null,
    };
};

const pullLoved = async (a: Account) => {
    const ids = new Set<string>();
    const tracks: MusicTrack[] = [];
    for (let offset = 0; offset < 2000; offset += 100) {
        const page = await call(a.token, `/1/feedback/user/${enc(a.user)}/get-feedback?score=1&count=100&offset=${offset}&metadata=true`);
        const list: any[] = Array.isArray(page?.feedback) ? page.feedback : [];
        list.forEach((f) => {
            if (f?.recording_mbid) ids.add(String(f.recording_mbid));
            const t = lovedTrack(f);
            if (t) tracks.push(t);
        });
        if (list.length < 100 || offset + 100 >= (page?.total_count ?? 0)) break;
    }
    // Hearts changed here that haven't reached the account yet win over what it says.
    applyLovedFromAccount(ids, tracks, (t) => !!t.mbid && !!queue.feedback[t.mbid]);
};

const pullListens = async (a: Account) => {
    const answer = await call(a.token, `/1/user/${enc(a.user)}/listens?count=100&min_ts=${a.listensUntil}`);
    const listens: any[] = Array.isArray(answer?.payload?.listens) ? answer.payload.listens : [];
    let newest = a.listensUntil;
    const plays: { artistMbid: string, name: string, at: number }[] = [];
    listens.forEach((l) => {
        const at = Number(l?.listened_at) || 0;
        newest = Math.max(newest, at);
        const meta = l?.track_metadata ?? {};
        if (meta.additional_info?.codio_device === a.device) return;
        const artists = meta.mbid_mapping?.artist_mbids ?? meta.additional_info?.artist_mbids;
        const artistMbid = Array.isArray(artists) && typeof artists[0] === 'string' ? artists[0] : null;
        if (artistMbid && MBID.test(artistMbid)) plays.push({ artistMbid, name: String(meta.artist_name ?? ''), at: at * 1000 });
    });
    addPlaysFromAccount(plays);
    return newest;
};

let syncing: Promise<void> | null = null;
export const syncNow = (): Promise<void> => {
    if (syncing) return syncing;
    const a = account;
    if (!a) return Promise.resolve();
    syncing = (async () => {
        try {
            await sendQueued(a);
            await pullLoved(a);
            const newest = await pullListens(a);
            if (account && account.token === a.token) setAccount({ ...account, listensUntil: newest, lastSync: Date.now(), syncError: null });
        } catch (e) {
            const status = (e as { status?: number }).status;
            const message = status === 401 ? 'ListenBrainz no longer accepts your token. Sign in again.' : 'Couldn\'t reach ListenBrainz. Trying again later.';
            if (account && account.token === a.token) setAccount({ ...account, syncError: message });
        } finally {
            syncing = null;
        }
    })();
    return syncing;
};

// Started once by the app: syncs now, every ten minutes, and when the window comes back to the front.
let started = false;
export const startListenBrainz = () => {
    if (started) return;
    started = true;
    let lastFocus = 0;
    void syncNow();
    window.setInterval(() => { void syncNow(); }, 10 * 60 * 1000);
    window.addEventListener('focus', () => {
        if (Date.now() - lastFocus < 60 * 1000) return;
        lastFocus = Date.now();
        void syncNow();
    });
};
