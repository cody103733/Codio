// Codio Search: the query and on-screen keys on the left, results on the right (as on Android TV).
// On Windows the physical keyboard types straight into the query too.

import React, { useEffect, useMemo, useRef, useState } from 'react';
import classnames from 'classnames';
import { useSearchParams } from 'react-router-dom';
import { withCoreSuspender } from 'stremio/common';
import { searchCatalogs, loadCatalog, type Meta } from './api';
import { NavBar, PosterGrid, Spinner, styles } from './components';
import { useAddons, useOpen } from './useCodio';

const CHARS = 'abcdefghijklmnopqrstuvwxyz1234567890'.split('');

// Movies / Series / Both filter. Remembered for this run of the app; starts on Both.
type Kind = 'both' | 'movie' | 'series';
const KINDS: { kind: Kind, label: string }[] = [
    { kind: 'both', label: 'Both' },
    { kind: 'movie', label: 'Movies' },
    { kind: 'series', label: 'Series' },
];
let lastKind: Kind = 'both';

const Search = () => {
    const addons = useAddons();
    const [kind, setKindState] = useState<Kind>(lastKind);
    const setKind = (k: Kind) => {
        lastKind = k;
        setKindState(k);
    };
    const catalogs = useMemo(() => searchCatalogs(addons).filter((c) => kind === 'both' || c.type === kind), [addons, kind]);
    const { open, play } = useOpen();
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

    const press = (key: string) => {
        setQuery((q) => {
            if (key === 'Space') return q.length > 0 && !q.endsWith(' ') ? q + ' ' : q;
            if (key === 'Delete') return q.slice(0, -1);
            if (key === 'Clear') return '';
            return (q + key).slice(0, 60);
        });
    };

    return (
        <div className={classnames(styles['codio'], styles['flat'], styles['search'])}>
            <div className={styles['search-left']}>
                <div className={styles['search-query']}>
                    <input
                        ref={input}
                        className={styles['search-input']}
                        value={query}
                        placeholder={'Search movies & series'}
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
                <div className={styles['keys']}>
                    {CHARS.map((c) => <button key={c} className={styles['key']} onClick={() => press(c)} onMouseDown={(e) => e.preventDefault()}>{c.toUpperCase()}</button>)}
                    {['Space', 'Delete', 'Clear'].map((k) => <button key={k} className={classnames(styles['key'], styles['wide'])} onClick={() => press(k)} onMouseDown={(e) => e.preventDefault()}>{k}</button>)}
                </div>
            </div>
            <div className={styles['search-results']}>
                <PosterGrid items={results} cols={7} onOpen={open} onPlay={play} />
                {
                    results.length === 0 ?
                        <div className={styles['search-empty']}>
                            {pending > 0 ? <Spinner /> : searched.length > 0 ? `No results for “${searched}”` : null}
                        </div>
                        : null
                }
            </div>
            <NavBar current={3} />
        </div>
    );
};

export default withCoreSuspender(Search);
