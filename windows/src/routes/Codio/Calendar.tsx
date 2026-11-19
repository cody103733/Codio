// Library > Calendar: when the next episodes and movies of what you follow come out.
//
// Follows the movies and shows in the Library plus the shows in Continue Watching (at most 60, the most recent
// first). Dates come from each title's page at the catalog addon (Cinemeta lists every episode, also the ones still
// to come). Same rules as android/src/com/codio/tv/ui/ReleaseCalendar.java; see DESIGN.md "Calendar".

import React, { useEffect, useMemo, useState } from 'react';
import { type Addon, type Meta } from './api';
import { Spinner, styles } from './components';

export type CalendarItem = {
    showId: string,
    type: string,
    name: string,
    poster: string | null,
    videoId: string,
    title: string,
    season: number,
    episode: number,
    lastEpisode: number,
    // Episodes of the show out that day (1 for a movie).
    count: number,
    // The local day it comes out, "yyyy-MM-dd".
    day: string,
    released: number,
};

type Section = { day: string, heading: string, items: CalendarItem[] };

const DAY = 86400000;
// How far back ("Out this past week") and ahead the page looks.
const BACK = 7 * DAY, AHEAD = 365 * DAY;
const MAX_TITLES = 60;
const REFRESH_EVERY = 6 * 3600000;

const pad = (n: number) => (n < 10 ? `0${n}` : String(n));
export const dayKey = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

const parseIso = (s: unknown): number => {
    if (typeof s !== 'string' || s.length < 10) return 0;
    const time = s.length >= 19 && s.charAt(10) === 'T';
    const t = Date.parse(time ? `${s.slice(0, 19)}Z` : `${s.slice(0, 10)}T00:00:00Z`);
    return Number.isFinite(t) ? t : 0;
};

// A date with a time is moved into this PC's time zone; a bare date stays.
const localDay = (s: string) => (s.length >= 19 && s.charAt(10) === 'T' ? dayKey(new Date(parseIso(s))) : s.slice(0, 10));

const DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

// "Today", "Tomorrow", "Friday" (this week), else "Sat 13 Dec" ("Sat 13 Dec 2027" in another year).
export const heading = (day: string, today: string) => {
    const d = Date.parse(`${day}T00:00:00Z`), t = Date.parse(`${today}T00:00:00Z`);
    if (!Number.isFinite(d) || !Number.isFinite(t)) return day;
    const days = Math.round((d - t) / DAY);
    if (days === 0) return 'Today';
    if (days === 1) return 'Tomorrow';
    const date = new Date(d);
    if (days > 1 && days < 7) return DAYS[date.getUTCDay()];
    const out = `${DAYS[date.getUTCDay()].slice(0, 3)} ${date.getUTCDate()} ${MONTHS[date.getUTCMonth()]}`;
    return day.slice(0, 4) === today.slice(0, 4) ? out : `${out} ${date.getUTCFullYear()}`;
};

export const shortDay = (day: string) => {
    const h = heading(day, dayKey(new Date()));
    const parts = h.split(' ');
    return parts.length >= 3 ? `${parts[1]} ${parts[2]}` : h;
};

export const itemLine = (i: CalendarItem) => {
    if (i.type === 'movie') return 'Movie';
    if (i.count > 1) return `S${i.season} E${i.episode}–E${i.lastEpisode} · ${i.count} episodes`;
    return `S${i.season} E${i.episode}${i.title ? ` · ${i.title}` : ''}`;
};

const supportsMeta = (a: Addon, type: string, id: string) => {
    const resources = Array.isArray(a.manifest?.resources) ? a.manifest.resources : [];
    return resources.some((r: any) => {
        let types = a.manifest?.types, prefixes = a.manifest?.idPrefixes;
        if (typeof r === 'string') {
            if (r !== 'meta') return false;
        } else if (r && typeof r === 'object') {
            if (r.name !== 'meta') return false;
            if (r.types) types = r.types;
            if (r.idPrefixes) prefixes = r.idPrefixes;
        } else return false;
        if (Array.isArray(types) && !types.includes(type)) return false;
        if (Array.isArray(prefixes) && prefixes.length > 0 && !prefixes.some((p: string) => id.startsWith(p))) return false;
        return true;
    });
};

const parse = (m: any, t: Meta): CalendarItem[] => {
    const now = Date.now();
    const name = typeof m.name === 'string' && m.name ? m.name : t.name;
    const poster = typeof m.poster === 'string' && m.poster ? m.poster : t.poster;
    const inWindow = (r: number) => r > 0 && r >= now - BACK && r <= now + AHEAD;
    if (t.type === 'movie') {
        const r = parseIso(m.released);
        if (!inWindow(r)) return [];
        return [{ showId: t.id, type: 'movie', name, poster, videoId: t.id, title: '', season: 0, episode: 0, lastEpisode: 0, count: 1, day: localDay(m.released), released: r }];
    }
    const byDay = new Map<string, CalendarItem>();
    (Array.isArray(m.videos) ? m.videos : []).forEach((v: any) => {
        if (!v || typeof v !== 'object') return;
        const season = typeof v.season === 'number' ? v.season : 0;
        const r = parseIso(v.released);
        if (season <= 0 || !inWindow(r)) return;
        const ep = typeof v.episode === 'number' ? v.episode : typeof v.number === 'number' ? v.number : 0;
        const title = String(v.title ?? v.name ?? '');
        const day = localDay(v.released);
        const have = byDay.get(day);
        if (have) {
            have.count += 1;
            if (season * 10000 + ep < have.season * 10000 + have.episode) {
                have.season = season;
                have.episode = ep;
                have.videoId = String(v.id ?? have.videoId);
                have.title = title;
            }
            if (season === have.season) have.lastEpisode = Math.max(have.lastEpisode, ep);
            return;
        }
        byDay.set(day, { showId: t.id, type: 'series', name, poster, videoId: String(v.id ?? ''), title, season, episode: ep, lastEpisode: ep, count: 1, day, released: r });
    });
    return Array.from(byDay.values());
};

const itemsFor = async (t: Meta, addons: Addon[]): Promise<CalendarItem[]> => {
    for (const a of addons) {
        if (!supportsMeta(a, t.type, t.id)) continue;
        try {
            const response = await fetch(`${a.base}/meta/${encodeURIComponent(t.type)}/${encodeURIComponent(t.id)}.json`);
            if (!response.ok) continue;
            const json = await response.json();
            if (json?.meta) return parse(json.meta, t);
        } catch (e) {
            // Try the next addon.
        }
    }
    return [];
};

// The last answer, kept while Codio runs so that the Library opens without waiting.
let last: { sig: string, at: number, items: CalendarItem[] } | null = null;

export const useReleaseCalendar = (titles: Meta[], addons: Addon[], ready: boolean) => {
    const follow = useMemo(() => {
        const seen = new Set<string>();
        return titles.filter((m) => (m.type === 'movie' || m.type === 'series') && !seen.has(m.id) && seen.add(m.id)).slice(0, MAX_TITLES);
    }, [titles]);
    const sig = `${follow.map((m) => `${m.type}/${m.id}`).join(',')}|${addons.map((a) => a.base).join(',')}`;
    const [state, setState] = useState<{ items: CalendarItem[], loaded: boolean }>(() => ({ items: last?.items ?? [], loaded: last !== null }));
    useEffect(() => {
        if (!ready || addons.length === 0) return;
        if (last && last.sig === sig && Date.now() - last.at < REFRESH_EVERY) {
            setState({ items: last.items, loaded: true });
            return;
        }
        let cancelled = false;
        (async () => {
            const found: CalendarItem[] = [];
            let next = 0;
            const worker = async () => {
                while (next < follow.length && !cancelled) {
                    const t = follow[next++];
                    found.push(...await itemsFor(t, addons));
                }
            };
            await Promise.all([worker(), worker(), worker(), worker(), worker()]);
            if (cancelled) return;
            found.sort((a, b) => a.released - b.released || a.name.localeCompare(b.name));
            last = { sig, at: Date.now(), items: found };
            setState({ items: found, loaded: true });
        })();
        return () => { cancelled = true; };
    }, [sig, ready]);
    const today = dayKey(new Date());
    const sections = useMemo((): Section[] => {
        const ahead = new Map<string, Section>();
        const past: CalendarItem[] = [];
        state.items.forEach((i) => {
            if (i.day < today) { past.unshift(i); return; }
            const s = ahead.get(i.day);
            if (s) s.items.push(i); else ahead.set(i.day, { day: i.day, heading: heading(i.day, today), items: [i] });
        });
        const out = Array.from(ahead.values());
        if (past.length > 0) out.push({ day: 'past', heading: 'Out this past week', items: past });
        return out;
    }, [state.items, today]);
    const next = state.items.find((i) => i.day >= today) ?? null;
    return { sections, next, loaded: state.loaded };
};

export const nextLine = (next: CalendarItem | null, loaded: boolean) => {
    if (next) return `Next: ${next.name}${next.type === 'movie' ? '' : ` S${next.season} E${next.episode}`} · ${shortDay(next.day)}`;
    return loaded ? 'Upcoming episodes and movies' : 'Looking for release dates…';
};

const Poster = ({ url, name }: { url: string | null, name: string }) => {
    const [failed, setFailed] = useState(false);
    return (
        <span className={styles['cal-poster']}>
            {url && !failed ? <img src={url} alt={''} loading={'lazy'} onError={() => setFailed(true)} /> : <span>{name}</span>}
        </span>
    );
};

const Calendar = ({ sections, loaded, onOpen }: { sections: Section[], loaded: boolean, onOpen: (i: CalendarItem) => void }) => {
    if (sections.length === 0) {
        return loaded ?
            <div className={styles['empty']}>Nothing coming up. New episodes and movies for what's in your Library show up here.</div>
            : <div className={styles['empty']}><Spinner /></div>;
    }
    return (
        <div className={styles['calendar']}>
            {
                sections.map((s) => (
                    <React.Fragment key={s.day}>
                        <div className={styles['cal-day']}>{s.heading}</div>
                        {
                            s.items.map((i) => (
                                <button key={`${i.showId}|${i.videoId}`} className={styles['cal-row']} onClick={() => onOpen(i)}>
                                    <Poster url={i.poster} name={i.name} />
                                    <span className={styles['cal-text']}>
                                        <span className={styles['cal-name']}>{i.name}</span>
                                        <span className={styles['cal-line']}>{itemLine(i)}</span>
                                    </span>
                                    {s.day === 'past' ? <span className={styles['cal-when']}>{shortDay(i.day)}</span> : null}
                                </button>
                            ))
                        }
                    </React.Fragment>
                ))
            }
        </div>
    );
};

export default Calendar;
