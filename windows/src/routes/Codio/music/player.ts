// The music player: one audio element for the whole app, a queue, shuffle and repeat. It keeps playing while you move
// around Codio (the mini player in App shows it), and pauses by itself when a video starts.

import { useSyncExternalStore } from 'react';
import type { MusicTrack } from './types';
import { loadAddons, onNotice } from './addons';
import { findAudio } from './match';
import { getRecordingIsrc } from './musicbrainz';
import { recordPlay } from './taste';

export type Repeat = 'off' | 'all' | 'one';

export type PlayerState = {
    queue: MusicTrack[],
    index: number,
    playing: boolean,
    loading: boolean,
    position: number,
    duration: number,
    shuffle: boolean,
    repeat: Repeat,
    message: string | null,
    quality: string | null,
    source: string | null,
    volume: number,
};

const RECENT_KEY = 'codioMusicRecent';
const VOLUME_KEY = 'codioMusicVolume';
const savedVolume = (): number => {
    try {
        const v = parseFloat(window.localStorage.getItem(VOLUME_KEY) ?? '');
        return isFinite(v) ? Math.min(1, Math.max(0, v)) : 1;
    } catch (_e) {
        return 1;
    }
};

let state: PlayerState = {
    queue: [], index: -1, playing: false, loading: false, position: 0, duration: 0,
    shuffle: false, repeat: 'off', message: null, quality: null, source: null, volume: savedVolume(),
};
const listeners = new Set<() => void>();
const set = (patch: Partial<PlayerState>) => {
    state = { ...state, ...patch };
    listeners.forEach((l) => l());
};

let audio: HTMLAudioElement | null = null;
let token = 0;
let messageTimer: ReturnType<typeof setTimeout> | null = null;

const say = (text: string | null) => {
    if (messageTimer) clearTimeout(messageTimer);
    set({ message: text });
    if (text) messageTimer = setTimeout(() => set({ message: null }), 6000);
};

const current = () => state.queue[state.index] ?? null;

const ensureAudio = () => {
    if (audio) return audio;
    audio = new Audio();
    audio.preload = 'auto';
    audio.volume = state.volume;
    audio.addEventListener('timeupdate', () => set({ position: audio!.currentTime, duration: isFinite(audio!.duration) ? audio!.duration : state.duration }));
    audio.addEventListener('durationchange', () => set({ duration: isFinite(audio!.duration) ? audio!.duration : 0 }));
    audio.addEventListener('play', () => set({ playing: true }));
    audio.addEventListener('pause', () => set({ playing: false }));
    audio.addEventListener('ended', () => advance(true));
    audio.addEventListener('error', () => {
        if (!audio!.src) return;
        const t = current();
        say(`Couldn't play ${t ? `“${t.title}”` : 'this track'}`);
        advance(true);
    });
    onNotice((text) => say(text));
    setupMediaSession();
    return audio;
};

const setupMediaSession = () => {
    const ms = (navigator as any).mediaSession;
    if (!ms) return;
    const handlers: [string, () => void][] = [
        ['play', () => resume()], ['pause', () => pause()], ['previoustrack', () => previous()], ['nexttrack', () => next()],
    ];
    handlers.forEach(([name, fn]) => { try { ms.setActionHandler(name, fn); } catch (_e) { /* not supported */ } });
};

const publishToSystem = (t: MusicTrack) => {
    const ms = (navigator as any).mediaSession;
    if (!ms || typeof (window as any).MediaMetadata !== 'function') return;
    ms.metadata = new (window as any).MediaMetadata({ title: t.title, artist: t.artist, album: t.album ?? '', artwork: t.artworkURL ? [{ src: t.artworkURL, sizes: '250x250' }] : [] });
};

const remember = (t: MusicTrack) => {
    try {
        const list: MusicTrack[] = JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? '[]');
        const kept = [t, ...(Array.isArray(list) ? list : []).filter((x) => !(x.id === t.id && x.addon === t.addon))].slice(0, 20);
        window.localStorage.setItem(RECENT_KEY, JSON.stringify(kept));
    } catch (_e) { /* storage full or off */ }
};

export const recentTracks = (): MusicTrack[] => {
    try {
        const list = JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? '[]');
        return Array.isArray(list) ? list : [];
    } catch (_e) {
        return [];
    }
};

// Loads and starts the track at index in the queue.
const load = async (index: number) => {
    const t = state.queue[index];
    if (!t) return;
    const a = ensureAudio();
    const mine = ++token;
    a.pause();
    set({ index, loading: true, position: 0, duration: t.durationMs ? t.durationMs / 1000 : 0, quality: null, source: null });
    publishToSystem(t);
    const addons = loadAddons();
    if (addons.length === 0) {
        set({ loading: false, playing: false });
        say('No music addon installed yet. Add one in Music > Addons.');
        return;
    }
    const found = await findAudio(t, addons, t.mbid ? () => getRecordingIsrc(t.mbid as string) : undefined);
    if (mine !== token) return;
    if (!found) {
        set({ loading: false });
        say(`No source available for “${t.title}”`);
        // Move on so a queue doesn't stall on one missing song.
        if (state.index < state.queue.length - 1) setTimeout(() => { if (mine === token) advance(true); }, 1500);
        return;
    }
    a.src = found.url;
    set({ loading: false, quality: found.quality ?? found.format, source: found.addon.manifest?.name ?? null });
    remember(t);
    recordPlay(t);
    try {
        await a.play();
    } catch (_e) {
        if (mine === token) say(`Couldn't play “${t.title}”`);
    }
};

const advance = (auto: boolean) => {
    if (state.queue.length === 0) return;
    if (auto && state.repeat === 'one') {
        if (audio) { audio.currentTime = 0; void audio.play(); }
        return;
    }
    const last = state.index >= state.queue.length - 1;
    if (last) {
        if (state.repeat === 'all') void load(0);
        else set({ playing: false, position: 0 });
        return;
    }
    void load(state.index + 1);
};

const shuffled = <T,>(list: T[]) => {
    const copy = list.slice();
    for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
};

export const playQueue = (tracks: MusicTrack[], start = 0, shuffleOn = state.shuffle) => {
    if (tracks.length === 0) return;
    let queue = tracks.slice();
    let index = Math.max(0, Math.min(start, queue.length - 1));
    if (shuffleOn) {
        const first = queue[index];
        queue = [first, ...shuffled(queue.filter((_, i) => i !== index))];
        index = 0;
    }
    set({ queue, index: -1, shuffle: shuffleOn });
    void load(index);
};

export const playNext = (track: MusicTrack) => {
    if (state.queue.length === 0) { playQueue([track]); return; }
    const queue = state.queue.slice();
    queue.splice(state.index + 1, 0, track);
    set({ queue });
};

export const addToQueue = (track: MusicTrack) => {
    if (state.queue.length === 0) { playQueue([track]); return; }
    set({ queue: [...state.queue, track] });
};

export const playIndex = (index: number) => void load(index);
export const next = () => advance(false);
export const previous = () => {
    if (!audio) return;
    if (audio.currentTime > 4 || state.index <= 0) { audio.currentTime = 0; return; }
    void load(state.index - 1);
};
export const pause = () => { audio?.pause(); };
export const resume = () => { if (audio && audio.src) void audio.play(); };
export const toggle = () => { if (state.playing) pause(); else resume(); };
export const seek = (seconds: number) => { if (audio) audio.currentTime = Math.max(0, seconds); };
export const toggleShuffle = () => {
    if (!state.shuffle && state.queue.length > 2) {
        const rest = shuffled(state.queue.slice(state.index + 1));
        set({ shuffle: true, queue: [...state.queue.slice(0, state.index + 1), ...rest] });
    } else {
        set({ shuffle: !state.shuffle });
    }
};
export const cycleRepeat = () => set({ repeat: state.repeat === 'off' ? 'all' : state.repeat === 'all' ? 'one' : 'off' });
export const removeFromQueue = (index: number) => {
    if (index === state.index) return;
    const queue = state.queue.filter((_, i) => i !== index);
    set({ queue, index: index < state.index ? state.index - 1 : state.index });
};
export const stop = () => {
    token++;
    if (audio) { audio.pause(); audio.removeAttribute('src'); audio.load(); }
    set({ queue: [], index: -1, playing: false, loading: false, position: 0, duration: 0, quality: null, source: null });
};
export const nowPlaying = current;

const subscribe = (l: () => void) => {
    listeners.add(l);
    return () => { listeners.delete(l); };
};

export const usePlayer = (): PlayerState => useSyncExternalStore(subscribe, () => state);

// Volume 0 to 1, kept for next time. This is the music's own volume, on top of the computer's.
export const setVolume = (value: number) => {
    const volume = Math.min(1, Math.max(0, value));
    if (audio) audio.volume = volume;
    try { window.localStorage.setItem(VOLUME_KEY, String(volume)); } catch (_e) { /* ignore */ }
    set({ volume });
};
let volumeBeforeMute = 1;
export const toggleMute = () => {
    if (state.volume > 0) { volumeBeforeMute = state.volume; setVolume(0); } else setVolume(volumeBeforeMute || 1);
};
