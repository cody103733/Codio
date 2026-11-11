// Keeps the list of sources auto-play is working through, so the player can
// jump to the next one. Stored in sessionStorage so it survives route changes
// (and a reload) but not an app restart.

import { Candidate } from './quality';

export type AutoPlayQueue = {
    key: string,
    candidates: Candidate[],
    index: number,
    createdAt: number,
};

const QUEUE_KEY = 'autoPlayQueue';
const HANDLED_KEY = 'autoPlayHandled';
// After auto-play ran for a video, coming back to its source list will not
// trigger it again for this long (you probably came back on purpose).
const HANDLED_TTL_MS = 30 * 60 * 1000;

const read = <T>(key: string): T | null => {
    try {
        const raw = window.sessionStorage.getItem(key);
        return raw ? JSON.parse(raw) as T : null;
    } catch (_e) {
        return null;
    }
};

const write = (key: string, value: unknown) => {
    try {
        window.sessionStorage.setItem(key, JSON.stringify(value));
    } catch (_e) {
        // ignore
    }
};

export const getQueue = (): AutoPlayQueue | null => read<AutoPlayQueue>(QUEUE_KEY);

export const startQueue = (key: string, candidates: Candidate[]): AutoPlayQueue => {
    const queue: AutoPlayQueue = { key, candidates, index: 0, createdAt: Date.now() };
    write(QUEUE_KEY, queue);
    markHandled(key);
    return queue;
};

export const setQueueIndex = (index: number) => {
    const queue = getQueue();
    if (queue) write(QUEUE_KEY, { ...queue, index });
};

export const clearQueue = () => {
    try {
        window.sessionStorage.removeItem(QUEUE_KEY);
    } catch (_e) {
        // ignore
    }
};

export const markHandled = (key: string) => {
    const handled = read<Record<string, number>>(HANDLED_KEY) ?? {};
    const now = Date.now();
    for (const k of Object.keys(handled)) {
        if (now - handled[k] > HANDLED_TTL_MS) delete handled[k];
    }
    handled[key] = now;
    write(HANDLED_KEY, handled);
};

export const wasHandled = (key: string): boolean => {
    const handled = read<Record<string, number>>(HANDLED_KEY) ?? {};
    return typeof handled[key] === 'number' && Date.now() - handled[key] <= HANDLED_TTL_MS;
};

const safeDecode = (value: string) => {
    try {
        return decodeURIComponent(value);
    } catch (_e) {
        return value;
    }
};

// The `stream` route segment of a "#/player/<stream>/..." deep link.
export const streamSegmentOf = (link: string): string | null => {
    const match = /^#?\/player\/([^/?]+)/.exec(link);
    return match ? safeDecode(match[1]) : null;
};

// Finds which queued source the player is currently showing.
export const findCandidateIndex = (queue: AutoPlayQueue, streamParam: string | undefined): number => {
    if (typeof streamParam !== 'string') return -1;
    const current = safeDecode(streamParam);
    return queue.candidates.findIndex((candidate) => {
        const segment = streamSegmentOf(candidate.link);
        return segment === streamParam || segment === current;
    });
};

// "#/player/..." deep link -> router path.
export const toPath = (link: string): string => link.startsWith('#') ? link.slice(1) : link;
