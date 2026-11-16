// Codio Search: the query box and Movies / Shows chips at the top, results (or recent searches) below.

import React, { useEffect, useMemo, useRef, useState } from 'react';
import classnames from 'classnames';
import { useSearchParams } from 'react-router-dom';
import { withCoreSuspender } from 'stremio/common';
import { searchCatalogs, loadCatalog, type Meta } from './api';
import { NavBar, PosterGrid, Spinner, styles } from './components';
import { useAddons, useOpen } from './useCodio';

// Movies / Shows / Both filter. Remembered for this run of the app; starts on Both.
type Kind = 'both' | 'movie' | 'series';
const KINDS: { kind: Kind, label: string }[] = [
    { kind: 'both', label: 'Both' },
    { kind: 'movie', label: 'Movies' },
    { kind: 'series', label: 'Shows' },
];
let lastKind: Kind = 'both';

// Recent searches (newest first), listed while nothing is typed. A search is kept once you open one of its results.
const RECENT_KEY = 'codioRecentSearches';
const loadRecent = (): string[] => {
    try {
        const list = JSON.parse(window.localStorage.getItem(RECENT_KEY) ?? '[]');
        return Array.isArray(list) ? list.filter((q) => typeof q === 'string').slice(0, 8) : [];
    } catch (_e) {
        return [];
    }
};
const saveRecent = (list: string[]) => {
    try {
        window.localStorage.setItem(RECENT_KEY, JSON.stringify(list));
    } catch (_e) { /* storage full or off */ }
    return list;
};

const Search = () => {
    const addons = useAddons();
    const [kind, setKindState] = useState<Kind>(lastKind);
    const setKind = (k: Kind) => {
        lastKind = k;
        setKindState(k);
    };
    const catalogs = useMemo(() => searchCatalogs(addons).filter((c) => kind === 'both' || c.type === kind), [addons, kind]);
    const opener = useOpen();
    const [recent, setRecent] = useState(loadRecent);
    const [searchParams, setSearchParams] = useSearchParams();
    const [query, setQuery] = useState(() => searchParams.get('search') ?? '');
    const [results, setResults] = useState<Meta[]>([]);
    const [pending, setPending] = useState(0);
    const [searched, setSearched] = useState('');
    const input = useRef<HTMLInputElement>(null);
    const request = useRef(0);

    useEffect(() => {
        input.current?.focus();
    }, []);

    useEffect(() => {
        const q = query.trim();
        const timer = setTimeout(() => {
            const id = ++request.current;
            setResults([]);
            setSearched(q);
            setSearchParams(q.length > 0 ? { search: q } : {}, { replace: true });
            if (q.length === 0) {
                setPending(0);
                return;
            }
            const lists: (Meta[] | null)[] = catalogs.map(() => null);
            setPending(catalogs.length);
            catalogs.forEach((c, index) => {
                loadCatalog(c, null, 0, q).catch(() => [] as Meta[]).then((list) => {
                    if (id !== request.current) return;
                    lists[index] = list;
                    const seen = new Set<string>();
                    const merged: Meta[] = [];
                    lists.forEach((l) => (l ?? []).forEach((m) => {
                        if (kind !== 'both' && m.type !== kind) return;
                        if (seen.has(m.type + m.id)) return;
                        seen.add(m.type + m.id);
                        merged.push(m);
                    }));
                    setResults(merged);
                    setPending((p) => Math.max(0, p - 1));
                });
            });
        }, 450);
        return () => clearTimeout(timer);
    }, [query, catalogs, kind]);

    const remember = () => {
        const q = query.trim();
        if (q.length > 0) saveRecent([q, ...loadRecent().filter((r) => r.toLowerCase() !== q.toLowerCase())].slice(0, 8));
    };
    const open = (meta: Meta) => { remember(); opener.open(meta); };
    const play = (meta: Meta) => { remember(); opener.play(meta); };

    return (
        <div className={classnames(styles['codio'], styles['flat'], styles['search'])}>
            <div className={styles['search-left']}>
                <div className={styles['search-query']}>
                    <input
                        ref={input}
                        className={styles['search-input']}
                        value={query}
                        placeholder={'Search movies and shows'}
                        spellCheck={false}
                        maxLength={60}
                        onChange={(e) => setQuery(e.target.value)}
                    />
                </div>
                <div className={styles['search-types']}>
                    {KINDS.map(({ kind: k, label }) => (
                        <button
                            key={k}
                            className={classnames(styles['chip'], { [styles['selected']]: kind === k })}
                            onClick={() => setKind(k)}
                            onMouseDown={(e) => e.preventDefault()}>
                            {label}
                        </button>
                    ))}
                </div>
            </div>
            <div className={styles['search-results']}>
                {query.trim().length > 0 ? <PosterGrid items={results} cols={7} onOpen={open} onPlay={play} /> : null}
                {
                    results.length === 0 && query.trim().length > 0 ?
                        <div className={styles['search-empty']}>
                            {pending > 0 ? <Spinner /> : searched.length > 0 ? `No results for “${searched}”` : null}
                        </div>
                        : null
                }
                {
                    query.trim().length === 0 && recent.length > 0 ?
                        <div className={styles['recent']}>
                            <div className={styles['recent-title']}>Recent searches</div>
                            {recent.map((q) => <button key={q} className={styles['recent-row']} onClick={() => setQuery(q)}>{q}</button>)}
                            <button className={classnames(styles['recent-row'], styles['clear'])} onClick={() => setRecent(saveRecent([]))}>Clear recent searches</button>
                        </div>
                        : null
                }
            </div>
            <NavBar current={4} />
        </div>
    );
};

export default withCoreSuspender(Search);
