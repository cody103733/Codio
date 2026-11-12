// Codio Movies / Series: catalog chips, genre chips and a poster grid (as on Android TV).

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router';
import { withCoreSuspender } from 'stremio/common';
import { discoverCatalogs, loadCatalog, dedupe, typeLabel, type Catalog, type Meta } from './api';
import { NavBar, Chip, PosterGrid, Spinner, Strip, styles } from './components';
import { useAddons, useOpen } from './useCodio';
import classnames from 'classnames';

const catLabel = (catalogs: Catalog[], c: Catalog) =>
    catalogs.some((other) => other !== c && other.name === c.name) ? `${c.name} · ${c.addon.name}` : c.name;

const Discover = ({ type: fixedType }: { type?: string }) => {
    const params = useParams();
    const type = fixedType ?? (typeof params.type === 'string' && params.type.length > 0 ? params.type : 'movie');
    const addons = useAddons();
    const catalogs = useMemo(() => discoverCatalogs(addons, type), [addons, type]);
    const { open, play } = useOpen();
    const [cat, setCat] = useState(0);
    const [genre, setGenre] = useState<string | null>(null);
    const [items, setItems] = useState<Meta[]>([]);
    const [status, setStatus] = useState({ loading: false, done: false, failed: false });
    const request = useRef(0);
    const scroller = useRef<HTMLDivElement>(null);
    const [scrolled, setScrolled] = useState(false);

    const catalog = catalogs[Math.min(cat, Math.max(0, catalogs.length - 1))] ?? null;
    const genres = useMemo(() => catalog ? [...(catalog.genreRequired ? [] : [null]), ...catalog.genres] : [], [catalog]);

    // Opening the page from a stremio link with a catalog id selects it.
    useEffect(() => {
        if (typeof params.catalogId === 'string') {
            const index = catalogs.findIndex((c) => c.id === params.catalogId);
            if (index >= 0) setCat(index);
        }
    }, [catalogs, params.catalogId]);

    useEffect(() => {
        setGenre(catalog && catalog.genreRequired ? catalog.genres[0] ?? null : null);
    }, [catalog]);

    const loadMore = useCallback((reset: boolean) => {
        if (!catalog) return;
        const id = ++request.current;
        setStatus({ loading: true, done: false, failed: false });
        loadCatalog(catalog, genre, reset ? 0 : items.length, null).then((list) => {
            if (id !== request.current) return;
            setItems((current) => reset ? dedupe([], list) : dedupe(current, list));
            setStatus({ loading: false, done: list.length === 0 || !catalog.skip, failed: false });
        }, () => {
            if (id !== request.current) return;
            setStatus((s) => ({ loading: false, done: true, failed: reset || s.failed }));
        });
    }, [catalog, genre, items.length]);

    useEffect(() => {
        setItems([]);
        if (scroller.current) scroller.current.scrollTop = 0;
        loadMore(true);
    }, [catalog, genre]);

    const nearEnd = useCallback(() => {
        if (!status.loading && !status.done && items.length > 0) loadMore(false);
    }, [status, items.length, loadMore]);

    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div ref={scroller} className={styles['page-scroll']} onScroll={(e) => setScrolled((e.target as HTMLDivElement).scrollTop > 0)}>
                <div className={styles['discover-chips']}>
                    {
                        catalogs.length > 1 ?
                            <Strip className={styles['chip-strip']}>
                                {catalogs.map((c, index) => <Chip key={`${c.addon.transportUrl}|${c.id}`} label={catLabel(catalogs, c)} selected={index === cat} onClick={() => setCat(index)} />)}
                            </Strip>
                            : <div className={styles['chip-strip-empty']} />
                    }
                    {
                        genres.length > 1 ?
                            <Strip className={styles['chip-strip']} resetKey={catalog ? catalog.id : ''}>
                                {genres.map((g) => <Chip key={g ?? '*'} label={g ?? 'All genres'} selected={g === genre} onClick={() => setGenre(g)} />)}
                            </Strip>
                            : <div className={styles['chip-strip-empty']} />
                    }
                </div>
                <PosterGrid items={items} cols={7} onOpen={open} onPlay={play} onNearEnd={nearEnd} />
                {
                    items.length === 0 ?
                        <div className={styles['empty']}>
                            {
                                status.loading ? <Spinner /> :
                                    status.failed ? 'Couldn\'t load this catalog. Check the connection and try again.' :
                                        catalogs.length === 0 && addons.length > 0 ? `None of your addons has a ${typeLabel(type).toLowerCase()} catalog.` : null
                            }
                        </div>
                        : status.loading ? <div className={styles['more-spinner']}><Spinner size={22} /></div> : null
                }
            </div>
            <NavBar current={type === 'series' ? 2 : 1} solid={scrolled} />
        </div>
    );
};

export const Movies = withCoreSuspender(() => <Discover type={'movie'} />);
export const Series = withCoreSuspender(() => <Discover type={'series'} />);
export default withCoreSuspender(Discover);
