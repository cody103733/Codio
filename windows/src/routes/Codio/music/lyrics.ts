// Song lyrics from LRCLIB (lrclib.net, free, no key), timed line by line when LRCLIB has them. The same rules as
// android/.../music/Lyrics.java and ios/Codio/Music/Lyrics.swift (DESIGN.md "Lyrics"): an exact lookup by artist,
// title, album and length first, then a search whose answer must be the same recording. The desktop part caches answers.

import { useEffect, useState } from 'react';
import type { MusicTrack } from './types';
import { isSameRecording } from './match';

export const LYRICS_CREDIT = 'Lyrics from LRCLIB';

export type LyricLine = { ms: number, text: string };
export type Lyrics = { lines: LyricLine[], plain: string | null, instrumental: boolean };

const TAG = /^\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)\]/;
const OFFSET = /^\[offset:\s*([+-]?\d+)\]/i;

// Turns LRC text ("[01:02.50]words") into timed lines. A line with several times appears once per time.
export const parseLrc = (lrc: string | null | undefined): LyricLine[] => {
    const out: LyricLine[] = [];
    if (!lrc) return out;
    let offset = 0;
    lrc.split(/\r?\n/).forEach((raw) => {
        let line = raw.trim();
        const off = OFFSET.exec(line);
        if (off) {
            offset = parseInt(off[1], 10) || 0;
            return;
        }
        const times: number[] = [];
        let m = TAG.exec(line);
        while (m) {
            times.push(Math.round((parseInt(m[1], 10) * 60 + parseFloat(m[2].replace(':', '.'))) * 1000));
            line = line.slice(m[0].length);
            m = TAG.exec(line);
        }
        const text = line.trim();
        times.forEach((t) => out.push({ ms: Math.max(0, t - offset), text }));
    });
    return out.sort((a, b) => a.ms - b.ms);
};

// The line playing at this point of the song, or -1 before the first one.
export const lineAt = (lyrics: Lyrics | null, seconds: number) => {
    if (!lyrics) return -1;
    const ms = seconds * 1000 + 250;
    let found = -1;
    for (let i = 0; i < lyrics.lines.length; i++) {
        if (lyrics.lines[i].ms <= ms) found = i;
        else break;
    }
    return found;
};

const fromAnswer = (o: any): Lyrics | null => {
    if (!o || typeof o !== 'object') return null;
    const l: Lyrics = {
        lines: parseLrc(typeof o.syncedLyrics === 'string' ? o.syncedLyrics : null),
        plain: typeof o.plainLyrics === 'string' && o.plainLyrics.trim() ? o.plainLyrics : null,
        instrumental: o.instrumental === true,
    };
    return l.lines.length > 0 || l.plain || l.instrumental ? l : null;
};

const ask = async (p: string): Promise<any> => {
    const response = await fetch(`/api/music/lrclib?p=${encodeURIComponent(p)}`, { headers: { 'X-Codio': '1' } });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`Lyrics answered ${response.status}`);
    return response.json();
};

const lookup = async (t: MusicTrack, durationMs: number): Promise<Lyrics | null> => {
    const q = (k: string, v: string) => `${k}=${encodeURIComponent(v)}`;
    if (durationMs > 0) {
        const parts = [q('artist_name', t.artist), q('track_name', t.title)];
        if (t.album) parts.push(q('album_name', t.album));
        parts.push(`duration=${Math.round(durationMs / 1000)}`);
        const exact = fromAnswer(await ask(`/api/get?${parts.join('&')}`));
        if (exact) return exact;
    }
    const list = await ask(`/api/search?${q('artist_name', t.artist)}&${q('track_name', t.title)}`);
    if (!Array.isArray(list)) return null;
    const wanted: MusicTrack = { id: '', title: t.title, artist: t.artist, durationMs };
    let plainOnly: Lyrics | null = null;
    for (const o of list) {
        const candidate: MusicTrack = { id: '', title: String(o?.trackName ?? ''), artist: String(o?.artistName ?? ''), durationMs: Math.round((Number(o?.duration) || 0) * 1000) };
        if (!isSameRecording(wanted, candidate)) continue;
        const l = fromAnswer(o);
        if (!l) continue;
        if (l.lines.length > 0) return l;
        if (!plainOnly) plainOnly = l;
    }
    return plainOnly;
};

// Answers for this run, by track: a song is looked up once (a failed lookup is tried again next time).
const memo = new Map<string, Lyrics | null>();
const pending = new Map<string, Promise<Lyrics | null>>();
const keyOf = (t: MusicTrack) => `${t.addon ?? ''}|${t.id}`;

export const getLyrics = (t: MusicTrack, durationMs: number): Promise<Lyrics | null> => {
    const k = keyOf(t);
    if (memo.has(k)) return Promise.resolve(memo.get(k) ?? null);
    const going = pending.get(k);
    if (going) return going;
    const job = lookup(t, durationMs > 0 ? durationMs : (t.durationMs ?? 0)).then((l) => {
        memo.set(k, l);
        if (memo.size > 40) memo.delete(memo.keys().next().value as string);
        return l;
    }).then((l) => { pending.delete(k); return l; }, (e) => { pending.delete(k); throw e; });
    pending.set(k, job);
    return job;
};

export type LyricsState = { lyrics: Lyrics | null, loading: boolean, failed: boolean };

// The lyrics of a track, for a screen. Waits while the song is still being found (its length isn't known yet).
export const useLyrics = (track: MusicTrack | null, durationSeconds: number, ready: boolean): LyricsState => {
    const [state, setState] = useState<LyricsState>({ lyrics: null, loading: false, failed: false });
    const k = track ? keyOf(track) : null;
    useEffect(() => {
        if (!track || !ready) {
            setState({ lyrics: null, loading: !!track, failed: false });
            return;
        }
        let live = true;
        setState({ lyrics: null, loading: true, failed: false });
        getLyrics(track, durationSeconds * 1000).then(
            (lyrics) => { if (live) setState({ lyrics, loading: false, failed: false }); },
            () => { if (live) setState({ lyrics: null, loading: false, failed: true }); },
        );
        return () => { live = false; };
    }, [k, ready]);
    return state;
};
