// Codio Home: full-screen artwork with the featured title, rows of catalogs below.
// Made for the mouse: the page scrolls freely, rows scroll sideways with their arrows (or Shift + wheel).

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import classnames from 'classnames';
import { withCoreSuspender } from 'stremio/common';
import { homeCatalogs, loadCatalog, rowTitle, dedupe, backgroundOf, logoOf, infoLine, type Catalog, type Meta } from './api';
import { NavBar, Backdrop, TitleArt, Poster, Strip, Spinner, CodioButton, styles } from './components';
import { useAddons, useContinueWatching, useOpen } from './useCodio';
import BrandLogo from 'stremio/components/BrandLogo';

type RowState = { items: Meta[], loading: boolean, done: boolean, failed: boolean, requested: boolean };

const EMPTY: RowState = { items: [], loading: false, done: false, failed: false, requested: false };

type RowProps = {
    title: string,
    catalog: Catalog | null,
    items?: Meta[],
    eager: boolean,
    onFocusItem: (meta: Meta) => void,
    onOpen: (meta: Meta) => void,
    onPlay: (meta: Meta) => void,
    onFirstItems?: (items: Meta[]) => void,
    onVisible?: (visible: boolean) => void,
};

const Row = ({ title, catalog, items: fixedItems, eager, onFocusItem, onOpen, onPlay, onFirstItems, onVisible }: RowProps) => {
    const [state, setState] = useState<RowState>(EMPTY);
    // Load a row once it gets near the screen.
    const box = useRef<HTMLDivElement>(null);
    // The first rows load right away (the featured title comes from them).
    const [shouldLoad, setShouldLoad] = useState(eager);
    useEffect(() => {
        if (eager) setShouldLoad(true);
    }, [eager]);
    useEffect(() => {
        const el = box.current;
        if (!el || shouldLoad) return;
        if (typeof IntersectionObserver === 'undefined') { setShouldLoad(true); return; }
        const observer = new IntersectionObserver((entries) => {
            if (entries.some((e) => e.isIntersecting)) setShouldLoad(true);
        }, { root: el.closest('[data-scroller]'), rootMargin: '900px 0px 900px 0px' });
        observer.observe(el);
        return () => observer.disconnect();
    }, [shouldLoad]);
    const stateRef = useRef(state);
    stateRef.current = state;
    const load = useCallback(() => {
        const current = stateRef.current;
        if (!catalog || current.loading || current.done) return;
        setState({ ...current, loading: true, requested: true });
        loadCatalog(catalog, null, current.items.length, null).then((list) => {
            setState((s) => ({ ...s, loading: false, done: list.length === 0 || !catalog.skip, items: dedupe(s.items, list) }));
        }, () => {
            setState((s) => ({ ...s, loading: false, done: true, failed: s.items.length === 0 }));
        });
    }, [catalog]);
    useEffect(() => {
        if (shouldLoad && !stateRef.current.requested) load();
    }, [shouldLoad, load]);
    const items = fixedItems ?? state.items;
    useEffect(() => {
        if (onFirstItems && items.length > 0) onFirstItems(items);
    }, [items.length > 0]);
    const visible = fixedItems ? fixedItems.length > 0 : (items.length > 0 || (state.loading && !state.failed) || !state.requested);
    useEffect(() => {
        if (onVisible) onVisible(visible);
    }, [visible]);
    if (!visible) return null;
    return (
        <div ref={box} className={styles['row']}>
            <div className={styles['row-title']}>{title}</div>
            {
                items.length === 0 ?
                    <div className={styles['row-skeleton']}>{Array.from({ length: 8 }, (_, i) => <div key={i} />)}</div>
                    :
                    <Strip>
                        <div className={styles['row-items']}>
                            {items.map((meta) => <Poster key={meta.id} meta={meta} onOpen={onOpen} onPlay={onPlay} onFocusItem={onFocusItem} />)}
                            {!fixedItems && !state.done ? <RowMore onVisible={load} /> : null}
                        </div>
                    </Strip>
            }
        </div>
    );
};

// Loads the next page when the end of a row scrolls into view.
const RowMore = ({ onVisible }: { onVisible: () => void }) => {
    const ref = useRef<HTMLDivElement>(null);
    const cb = useRef(onVisible);
    cb.current = onVisible;
    useEffect(() => {
        const el = ref.current;
        if (!el || typeof IntersectionObserver === 'undefined') return;
        const observer = new IntersectionObserver((entries) => {
            if (entries.some((e) => e.isIntersecting)) cb.current();
        }, { rootMargin: '0px 1600px 0px 0px' });
        observer.observe(el);
        return () => observer.disconnect();
    }, []);
    return <div ref={ref} className={styles['row-more']} />;
};

const Home = () => {
    const addons = useAddons();
    const catalogs = useMemo(() => homeCatalogs(addons), [addons]);
    const continueWatching = useContinueWatching();
    const { open, play } = useOpen();
    const [hero, setHero] = useState<Meta | null>(null);
    const [active, setActive] = useState<Meta | null>(null);
    const [hidden, setHidden] = useState<Record<string, boolean>>({});
    const [scrollY, setScrollY] = useState(0);

    const rows = useMemo(() => {
        const list: { key: string, title: string, catalog: Catalog | null, items?: Meta[] }[] = [];
        if (continueWatching.length > 0) list.push({ key: 'cw', title: 'Continue Watching', catalog: null, items: continueWatching });
        catalogs.forEach((c) => list.push({ key: `${c.addon.transportUrl}|${c.type}|${c.id}`, title: rowTitle(c), catalog: c }));
        return list;
    }, [catalogs, continueWatching]);
    const shownRows = rows.filter((r) => !hidden[r.key]);

    const takeHero = useCallback((items: Meta[]) => {
        setHero((current) => current ?? items.find((m) => m.background) ?? null);
    }, []);

    // Scrolled down into the rows, the artwork follows the poster under the pointer.
    const viewport = typeof window !== 'undefined' ? window.innerHeight : 1080;
    const inRows = scrollY > viewport * 0.35;
    const shown = inRows && active ? active : hero;

    return (
        <div className={classnames(styles['codio'], styles['home'])}>
            <Backdrop src={backgroundOf(shown)} delay={inRows ? 300 : 0} />
            <div className={styles['home-dim']} style={{ opacity: Math.min(0.55, scrollY / (viewport * 1.2)) }} />
            <div className={styles['page-scroll']} data-scroller={''} onScroll={(e) => setScrollY((e.target as HTMLDivElement).scrollTop)}>
                <div className={styles['hero']}>
                    {
                        hero ?
                            <div className={classnames(styles['info'], styles['hero-info'])}>
                                <button className={styles['hero-art']} onClick={() => open(hero)} title={hero.name}>
                                    <TitleArt meta={hero} logo={logoOf(hero)} width={680} height={220} textSize={76} className={styles['info-art']} />
                                </button>
                                <div className={styles['meta-line']}>{infoLine(hero)}</div>
                                {hero.description ? <div className={classnames(styles['body'], styles['lines-3'])} style={{ width: 'calc(var(--u) * 860)' }}>{hero.description}</div> : null}
                                <div className={styles['buttons']}>
                                    <CodioButton label={'Play'} play primary autoFocus onClick={() => (hero.type === 'movie' ? play(hero) : open(hero))} />
                                    <CodioButton label={'More info'} onClick={() => open(hero)} />
                                </div>
                            </div>
                            :
                            shownRows.length === 0 || addons.length === 0 ?
                                <div className={styles['home-loading']}>
                                    <BrandLogo className={styles['logo-big']} />
                                    <Spinner />
                                </div>
                                : null
                    }
                </div>
                <div className={styles['rows']}>
                    {
                        rows.map((row, index) => (
                            <Row
                                key={row.key}
                                eager={index < 3}
                                title={row.title}
                                catalog={row.catalog}
                                items={row.items}
                                onFocusItem={setActive}
                                onOpen={open}
                                onPlay={play}
                                onFirstItems={row.catalog ? takeHero : undefined}
                                onVisible={(visible) => setHidden((h) => (h[row.key] === !visible ? h : { ...h, [row.key]: !visible }))}
                            />
                        ))
                    }
                </div>
            </div>
            <NavBar current={0} solid={scrollY > 40} />
        </div>
    );
};

export default withCoreSuspender(Home);
