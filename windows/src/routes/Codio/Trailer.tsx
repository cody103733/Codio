// A title's trailer (a YouTube video) for the Trailer button on the title page. Rules in DESIGN.md "Trailers":
// TMDB first (needs the TMDB key, which comes with the sync passcode), else the trailer the addon gave
// (Cinemeta has one for most titles). It plays in YouTube's own player over the page.

import React, { useEffect, useRef, useState } from 'react';
import { styles } from './components';

const API = 'https://api.themoviedb.org/3/';
const cache = new Map<string, string | null>();

const tmdbKey = () => (typeof localStorage !== 'undefined' && localStorage.getItem('codio.tmdbKey')) || '';

const imdbOf = (item: any): string | null => {
    const isImdb = (s: any) => typeof s === 'string' && /^tt\d+$/.test(s);
    if (isImdb(item?.id)) return item.id;
    for (const k of ['imdb_id', 'imdbId']) if (isImdb(item?.[k])) return item[k];
    const link = (Array.isArray(item?.links) ? item.links : []).find((l: any) => l && l.category === 'imdb' && typeof l.url === 'string');
    const m = link ? /tt\d+/.exec(link.url) : null;
    return m ? m[0] : null;
};

const fromAddon = (item: any): string | null => {
    for (const s of Array.isArray(item?.trailerStreams) ? item.trailerStreams : []) {
        if (s && typeof s.ytId === 'string' && s.ytId.length > 0) return s.ytId;
    }
    for (const t of Array.isArray(item?.trailers) ? item.trailers : []) {
        if (t && typeof t.source === 'string' && t.source.length > 0 && (t.type ?? 'Trailer') === 'Trailer') return t.source;
    }
    return null;
};

// YouTube only; a Trailer before a Teaser, official before fan-made, otherwise TMDB's order.
export const pickTrailer = (videos: any[]): string | null => {
    let best: string | null = null;
    let bestScore = -1;
    for (const v of Array.isArray(videos) ? videos : []) {
        if (!v || v.site !== 'YouTube' || typeof v.key !== 'string' || !v.key) continue;
        if (v.type !== 'Trailer' && v.type !== 'Teaser') continue;
        const score = (v.type === 'Trailer' ? 2 : 0) + (v.official ? 1 : 0);
        if (score > bestScore) {
            bestScore = score;
            best = v.key;
        }
    }
    return best;
};

const fromTmdb = async (imdb: string | null, series: boolean): Promise<string | null> => {
    const key = tmdbKey();
    if (!key || !imdb) return null;
    const get = async (path: string) => {
        const r = await fetch(`${API}${path}${path.includes('?') ? '&' : '?'}api_key=${encodeURIComponent(key)}`);
        if (!r.ok) throw new Error(`TMDB answered ${r.status}`);
        return r.json();
    };
    const found = await get(`find/${imdb}?external_source=imdb_id`);
    const show = found?.tv_results?.[0];
    const movie = found?.movie_results?.[0];
    const path = series ? (show ? `tv/${show.id}` : movie ? `movie/${movie.id}` : null) : (movie ? `movie/${movie.id}` : show ? `tv/${show.id}` : null);
    if (!path) return null;
    const videos = await get(`${path}/videos?include_video_language=en,null`);
    return pickTrailer(videos?.results);
};

// The YouTube id of the title's trailer, or null (also while it's being looked up).
export const useTrailer = (item: any): string | null => {
    const imdb = imdbOf(item);
    const cacheKey = imdb ?? (item?.id ? String(item.id) : null);
    const [id, setId] = useState<string | null>(cacheKey ? cache.get(cacheKey) ?? null : null);
    // The core hands over a new object on every update; only a different title starts a new lookup.
    const itemRef = useRef(item);
    itemRef.current = item;
    useEffect(() => {
        const item = itemRef.current;
        if (!item || !cacheKey) {
            setId(null);
            return;
        }
        if (cache.has(cacheKey)) {
            setId(cache.get(cacheKey) ?? null);
            return;
        }
        let live = true;
        fromTmdb(imdbOf(item), item.type !== 'movie')
            .catch(() => null)
            .then((found) => {
                const result = found ?? fromAddon(item);
                cache.set(cacheKey, result);
                if (live) setId(result);
            });
        return () => { live = false; };
    }, [cacheKey]);
    return id;
};

export const TrailerPlayer = ({ id, onClose }: { id: string, onClose: () => void }) => {
    const src = `https://www.youtube-nocookie.com/embed/${encodeURIComponent(id)}?autoplay=1&rel=0&modestbranding=1&origin=${encodeURIComponent(window.location.origin)}`;
    return (
        <div className={styles['overlay']} onClick={onClose}>
            <div className={styles['trailer']}>
                <button className={styles['back']} onClick={onClose} aria-label={'Back'} autoFocus />
                <iframe
                    className={styles['trailer-video']}
                    src={src}
                    title={'Trailer'}
                    allow={'autoplay; encrypted-media; fullscreen; picture-in-picture'}
                    referrerPolicy={'strict-origin-when-cross-origin'}
                    allowFullScreen
                />
            </div>
        </div>
    );
};
