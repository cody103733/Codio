// The ambient screen (DESIGN.md "Ambient screen"): when Codio sits untouched, a slow slideshow of big pictures from
// Continue Watching (else the first Home row) with the time, instead of a still page. Any key, click or mouse move
// brings the page back, and that first key or click does nothing else. After an hour it turns black.
// Never over a video, a trailer, or while Codio is minimized.

import React, { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router';
import { useAutoPlaySettings } from 'stremio/common/AutoPlay';
import { styles } from './components';
import { useAddons, useContinueWatching } from './useCodio';
import { backgroundOf, homeCatalogs, loadCatalog, logoOf, type Meta } from './api';

const SLIDE_MS = 12000;
const RUN_MS = 60 * 60 * 1000;

const pictureOf = (m: Meta): string | null => {
    if (m.background) return m.background;
    return m.id.startsWith('tt') ? backgroundOf(m) : null;
};

const shuffled = <T,>(list: T[]): T[] => {
    const out = list.slice();
    for (let i = out.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [out[i], out[j]] = [out[j], out[i]];
    }
    return out;
};

const Slides = ({ items }: { items: Meta[] }) => {
    const [index, setIndex] = useState(0);
    const [ready, setReady] = useState<Record<number, boolean>>({});
    const [now, setNow] = useState(() => new Date());
    const [asleep, setAsleep] = useState(false);
    const [logoFailed, setLogoFailed] = useState<Record<string, boolean>>({});

    useEffect(() => {
        const started = Date.now();
        const tick = window.setInterval(() => {
            setNow(new Date());
            if (Date.now() - started > RUN_MS) setAsleep(true);
        }, 15000);
        return () => window.clearInterval(tick);
    }, []);

    // Next picture once it has loaded (preloaded during the current one).
    useEffect(() => {
        if (items.length < 2 || asleep) return;
        const next = (index + 1) % items.length;
        const img = new Image();
        let loaded = false, due = false;
        const advance = () => {
            if (!loaded || !due) return;
            setReady((r) => ({ ...r, [next]: false }));
            setIndex(next);
        };
        img.onload = () => { loaded = true; advance(); };
        img.onerror = () => { loaded = true; advance(); };
        img.src = pictureOf(items[next]) ?? '';
        const t = window.setTimeout(() => { due = true; advance(); }, SLIDE_MS);
        return () => { window.clearTimeout(t); img.onload = null; img.onerror = null; };
    }, [index, items, asleep]);

    if (asleep) return null;
    // Only this picture and the one before it are in the page, so memory stays flat.
    const shown = [index > 0 || items.length > 1 ? (index - 1 + items.length) % items.length : -1, index].filter((i, k, a) => i >= 0 && a.indexOf(i) === k);
    const meta = items[index];
    const logo = logoOf(meta);
    const nudge = (index % 3) * 18;
    const time = now.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
    return (
        <>
            {shown.map((i) => (
                <img
                    key={items[i].id}
                    className={styles['ambient-picture']}
                    style={{ opacity: i === index && ready[i] ? 1 : i === index ? 0 : 1, zIndex: i === index ? 1 : 0 }}
                    src={pictureOf(items[i]) ?? ''}
                    onLoad={() => setReady((r) => ({ ...r, [i]: true }))}
                    alt=""
                />
            ))}
            <div className={styles['ambient-shade']} />
            <div className={styles['ambient-clock']} style={{ transform: `translate(calc(var(--u) * ${-nudge}), calc(var(--u) * ${nudge}))` }}>{time}</div>
            <div key={meta.id} className={styles['ambient-title']} style={{ transform: `translate(calc(var(--u) * ${nudge}), calc(var(--u) * ${-nudge}))` }}>
                {logo && !logoFailed[logo]
                    ? <img className={styles['ambient-logo']} src={logo} alt={meta.name} onError={() => setLogoFailed((f) => ({ ...f, [logo]: true }))} />
                    : <div className={styles['ambient-name']}>{meta.name}</div>}
                {meta.releaseInfo ? <div className={styles['ambient-meta']}>{meta.releaseInfo}</div> : null}
            </div>
        </>
    );
};

const Ambient = () => {
    const [settings] = useAutoPlaySettings();
    const location = useLocation();
    const continueWatching = useContinueWatching();
    const addons = useAddons();
    const [items, setItems] = useState<Meta[] | null>(null);
    const fallback = useRef<Meta[]>([]);
    const on = (settings as any).ambient !== false;
    const delay = Math.max(1, Number((settings as any).ambientDelayMinutes) || 5) * 60000;
    const inVideo = location.pathname.startsWith('/player');
    const latest = useRef({ continueWatching, inVideo });
    latest.current = { continueWatching, inVideo };
    const shownRef = useRef(false);
    shownRef.current = items !== null && items.length > 0;

    // The first Home row, for when Continue Watching has too few pictures.
    useEffect(() => {
        const first = homeCatalogs(addons)[0];
        if (!first) return;
        let cancelled = false;
        loadCatalog(first, null, 0, null).then((list) => { if (!cancelled) fallback.current = list.filter((m) => m.background).slice(0, 30); }, () => { /* offline */ });
        return () => { cancelled = true; };
    }, [addons]);

    useEffect(() => {
        if (!on) {
            setItems(null);
            return;
        }
        let timer = 0;
        const start = () => {
            const busy = latest.current.inVideo || document.hidden || document.querySelector('iframe') !== null;
            const seen = new Set<string>();
            let list = latest.current.continueWatching.filter((m) => pictureOf(m) && !seen.has(m.id) && seen.add(m.id));
            if (list.length < 6) list = list.concat(fallback.current.filter((m) => !seen.has(m.id) && seen.add(m.id)));
            if (busy || list.length === 0) {
                arm();
                return;
            }
            shownRef.current = true;
            setItems(shuffled(list).slice(0, 40));
        };
        const arm = () => {
            window.clearTimeout(timer);
            timer = window.setTimeout(start, delay);
        };
        const wake = (e: Event) => {
            if (shownRef.current) {
                // The press that wakes it does nothing else.
                if (e.type === 'keydown' || e.type === 'mousedown' || e.type === 'click' || e.type === 'wheel') {
                    e.preventDefault();
                    e.stopPropagation();
                }
                if (e.type === 'click' || e.type === 'keyup' || e.type === 'mouseup') return;
                shownRef.current = false;
                setItems(null);
                swallowUntil = Date.now() + 400;
            } else if (Date.now() < swallowUntil && (e.type === 'click' || e.type === 'mouseup' || e.type === 'keyup')) {
                e.preventDefault();
                e.stopPropagation();
                return;
            }
            arm();
        };
        let swallowUntil = 0;
        const events = ['keydown', 'keyup', 'mousedown', 'mouseup', 'click', 'mousemove', 'wheel', 'touchstart'];
        events.forEach((name) => window.addEventListener(name, wake, { capture: true, passive: false }));
        arm();
        return () => {
            window.clearTimeout(timer);
            events.forEach((name) => window.removeEventListener(name, wake, { capture: true } as any));
            setItems(null);
        };
    }, [on, delay]);

    // A video starting (e.g. from a phone remote) closes it.
    useEffect(() => {
        if (inVideo) setItems(null);
    }, [inVideo]);

    if (!items || items.length === 0) return null;
    return (
        <div className={styles['ambient']}>
            <Slides items={items} />
        </div>
    );
};

export default Ambient;
