// Codio Library: what you saved or watched, as a poster grid with type and sort chips,
// plus Downloads: what's saved on this PC, playable without internet.

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation } from 'react-router';
import classnames from 'classnames';
import { withCoreSuspender, useModelState } from 'stremio/common';
import { useCore } from 'stremio/core';
import { qualityLabel, typeLabel, type Meta } from './api';
import { NavBar, Chip, CodioButton, PosterGrid, Spinner, Strip, useEscape, styles } from './components';
import { deleteDownload, formatBytes, progressOf, retryDownload, titleStatus, useDownloadMap, usePlayDownload, type Download } from './downloads';
import { useOpen } from './useCodio';

const SORT_LABELS: Record<string, string> = {
    lastwatched: 'Recently watched',
    name: 'A to Z',
    namereverse: 'Z to A',
    timeswatched: 'Most watched',
    watched: 'Watched',
    notwatched: 'Not watched',
};

const sortLabel = (sort: string) => SORT_LABELS[sort] ?? (sort.charAt(0).toUpperCase() + sort.slice(1));

const toMeta = (item: any): Meta => {
    const raw = typeof item.progress === 'number' ? item.progress : 0;
    return {
        id: String(item._id ?? item.id ?? ''),
        type: String(item.type ?? 'movie'),
        name: String(item.name ?? ''),
        poster: typeof item.poster === 'string' ? item.poster : null,
        background: null,
        logo: null,
        description: null,
        releaseInfo: null,
        runtime: null,
        imdbRating: null,
        genres: [],
        cast: [],
        videos: [],
        progress: Math.min(1, raw > 1 ? raw / 100 : raw),
    };
};

const downloadMeta = (d: Download): Meta => ({
    id: d.metaId, type: d.type, name: d.name, poster: d.poster ?? null, background: null, logo: null, description: null,
    releaseInfo: null, runtime: null, imdbRating: null, genres: [], cast: [], videos: [],
});

const statusLine = (d: Download) => {
    const q = qualityLabel(d.quality ?? null);
    if (d.state === 'done') return [q, formatBytes(d.bytes)].filter(Boolean).join('  ·  ');
    if (d.state === 'failed') return `Failed: ${d.error ?? 'unknown error'}`;
    if (d.state === 'waiting') return 'Waiting for a source…';
    const size = d.bytes ? `${formatBytes(d.done)} of ${formatBytes(d.bytes)}` : formatBytes(d.done);
    if (d.state === 'queued') return d.error ? d.error : `Queued${q ? `  ·  ${q}` : ''}`;
    return [`${Math.round(progressOf(d) * 100)}%`, q, size].filter(Boolean).join('  ·  ');
};

const DownloadRow = ({ d, onOpen, onPlay }: { d: Download, onOpen: () => void, onPlay: () => void }) => {
    const [confirm, setConfirm] = useState(false);
    const [failed, setFailed] = useState(false);
    useEffect(() => {
        if (!confirm) return;
        const timer = setTimeout(() => setConfirm(false), 4000);
        return () => clearTimeout(timer);
    }, [confirm]);
    const picture = d.thumb ?? d.poster ?? null;
    const episode = d.season || d.episode ? `S${d.season ?? 0} E${d.episode ?? 0}${d.episodeTitle ? `  ·  ${d.episodeTitle}` : ''}` : null;
    return (
        <div className={classnames(styles['dl-row'], styles[d.state])}>
            <button className={styles['dl-thumb']} onClick={d.state === 'done' ? onPlay : onOpen} title={d.state === 'done' ? 'Play' : d.name}>
                {picture && !failed ? <img src={picture} alt={''} loading={'lazy'} onError={() => setFailed(true)} /> : <span>{d.name}</span>}
                {d.state === 'done' ? <span className={styles['dl-thumb-play']} /> : null}
                {d.state !== 'done' && d.state !== 'failed' ? <div className={styles['progress']}><div style={{ width: `${Math.max(2, progressOf(d) * 100)}%` }} /></div> : null}
            </button>
            <button className={styles['dl-text']} onClick={onOpen}>
                <div className={styles['dl-name']}>{d.name}</div>
                {episode ? <div className={styles['dl-episode']}>{episode}</div> : null}
                <div className={styles['dl-status']}>{statusLine(d)}</div>
            </button>
            <div className={styles['dl-actions']}>
                {d.state === 'done' ? <CodioButton label={'Play'} play primary onClick={onPlay} /> : null}
                {d.state === 'failed' ? <CodioButton label={'Try again'} onClick={() => retryDownload(d.key)} /> : null}
                <CodioButton
                    label={confirm ? (d.state === 'done' ? 'Delete the file?' : 'Cancel download?') : (d.state === 'done' ? '✕ Delete' : '✕ Cancel')}
                    className={confirm ? styles['danger'] : undefined}
                    onClick={() => { if (confirm) deleteDownload(d.key); else setConfirm(true); }}
                />
            </div>
        </div>
    );
};

type Group = { key: string, first: Download, list: Download[] };

const stateOrder = (d: Download) => d.state === 'downloading' ? 0 : d.state === 'queued' || d.state === 'waiting' ? 1 : d.state === 'failed' ? 2 : 3;

// A show in Downloads: one entry; click it to see only its downloaded episodes.
const ShowRow = ({ group, onEnter }: { group: Group, onEnter: () => void }) => {
    const [confirm, setConfirm] = useState(false);
    const [failed, setFailed] = useState(false);
    useEffect(() => {
        if (!confirm) return;
        const timer = setTimeout(() => setConfirm(false), 4000);
        return () => clearTimeout(timer);
    }, [confirm]);
    const { first, list } = group;
    const done = list.filter((d) => d.state === 'done');
    const busy = list.filter((d) => d.state !== 'done' && d.state !== 'failed');
    const bytes = done.reduce((sum, d) => sum + (d.bytes ?? 0), 0);
    const progress = busy.length > 0 ? list.filter((d) => d.state !== 'failed').reduce((sum, d) => sum + progressOf(d), 0) / list.filter((d) => d.state !== 'failed').length : 0;
    const status = [
        `${done.length} episode${done.length === 1 ? '' : 's'} downloaded`,
        busy.length > 0 ? `${busy.length} downloading  ·  ${Math.round(progress * 100)}%` : null,
        list.some((d) => d.state === 'failed') ? `${list.filter((d) => d.state === 'failed').length} failed` : null,
        bytes > 0 ? formatBytes(bytes) : null,
    ].filter(Boolean).join('  ·  ');
    const picture = first.poster ?? first.thumb ?? null;
    return (
        <div className={styles['dl-row']}>
            <button className={classnames(styles['dl-thumb'], styles['dl-poster'])} onClick={onEnter} title={first.name}>
                {picture && !failed ? <img src={picture} alt={''} loading={'lazy'} onError={() => setFailed(true)} /> : <span>{first.name}</span>}
                {busy.length > 0 ? <div className={styles['progress']}><div style={{ width: `${Math.max(2, progress * 100)}%` }} /></div> : null}
            </button>
            <button className={styles['dl-text']} onClick={onEnter}>
                <div className={styles['dl-name']}>{first.name}</div>
                <div className={styles['dl-status']}>{status}</div>
            </button>
            <div className={styles['dl-actions']}>
                <CodioButton label={'Episodes  ›'} primary onClick={onEnter} />
                <CodioButton
                    label={confirm ? 'Delete all episodes?' : '✕ Delete all'}
                    className={confirm ? styles['danger'] : undefined}
                    onClick={() => { if (confirm) list.forEach((d) => deleteDownload(d.key)); else setConfirm(true); }}
                />
            </div>
        </div>
    );
};

const Downloads = ({ onOpen, onScrollTop }: { onOpen: (d: Download) => void, onScrollTop: () => void }) => {
    const { entries, free, dir, available } = useDownloadMap();
    const playDownload = usePlayDownload();
    const [show, setShow] = useState<string | null>(null);
    const groups = useMemo(() => {
        const map = new Map<string, Group>();
        entries.forEach((d) => {
            const key = d.type === 'movie' ? d.key : `${d.type}/${d.metaId}`;
            const g = map.get(key);
            if (g) g.list.push(d); else map.set(key, { key, first: d, list: [d] });
        });
        const list = Array.from(map.values());
        list.forEach((g) => g.list.sort((a, b) => (a.season ?? 0) - (b.season ?? 0) || (a.episode ?? 0) - (b.episode ?? 0)));
        const order = (g: Group) => Math.min(...g.list.map(stateOrder));
        const newest = (g: Group) => Math.max(...g.list.map((d) => d.addedAt ?? 0));
        return list.sort((a, b) => order(a) - order(b) || newest(b) - newest(a));
    }, [entries]);
    const current = show ? groups.find((g) => g.key === show) ?? null : null;
    useEffect(() => {
        if (show && !current) setShow(null);
    }, [show, current]);
    useEscape(() => {
        if (!show) return false;
        setShow(null);
        return true;
    }, !!show);
    const enter = (key: string | null) => { setShow(key); onScrollTop(); };
    const used = entries.reduce((sum, d) => sum + (d.state === 'done' ? d.bytes ?? 0 : d.done ?? 0), 0);
    if (!available) return <div className={styles['empty']}>Downloads aren't available right now. Restart Codio and try again.</div>;
    if (current) {
        return (
            <div className={styles['downloads']}>
                <div className={styles['dl-show-head']}>
                    <button className={styles['back']} onClick={() => enter(null)} aria-label={'All downloads'} title={'All downloads'} />
                    <div className={styles['dl-show-title']}>{current.first.name}</div>
                    <CodioButton label={'Show page'} onClick={() => onOpen(current.first)} />
                </div>
                <div className={styles['dl-summary']}>{`${current.list.length} downloaded episode${current.list.length === 1 ? '' : 's'}`}</div>
                {current.list.map((d) => <DownloadRow key={d.key} d={d} onOpen={() => onOpen(d)} onPlay={() => playDownload(d)} />)}
            </div>
        );
    }
    return (
        <div className={styles['downloads']}>
            <div className={styles['dl-summary']}>
                {[`${entries.length} download${entries.length === 1 ? '' : 's'}`, used > 0 ? `${formatBytes(used)} used` : null, free !== null ? `${formatBytes(free)} free` : null, dir ? `Saved in ${dir}` : null].filter(Boolean).join('  ·  ')}
            </div>
            {
                groups.map((g) => g.first.type === 'movie' ?
                    <DownloadRow key={g.key} d={g.first} onOpen={() => onOpen(g.first)} onPlay={() => playDownload(g.first)} />
                    : <ShowRow key={g.key} group={g} onEnter={() => enter(g.key)} />)
            }
            {
                entries.length === 0 ?
                    <div className={styles['empty']}>Nothing downloaded yet. Open a movie or a show and press Download to watch it without internet.</div>
                    : null
            }
        </div>
    );
};

const Library = () => {
    const core = useCore() as any;
    const { open, play } = useOpen();
    const location = useLocation();
    const [showDownloads, setShowDownloads] = useState(() => new URLSearchParams(location.search).get('downloads') === '1');
    useEffect(() => {
        if (new URLSearchParams(location.search).get('downloads') === '1') setShowDownloads(true);
    }, [location.search, location.key]);
    const downloads = useDownloadMap();
    const [type, setType] = useState<string | null>(null);
    const [sort, setSort] = useState<string | undefined>(undefined);
    const [scrolled, setScrolled] = useState(false);
    const scroller = useRef<HTMLDivElement>(null);
    const action = useMemo(() => ({
        action: 'Load',
        args: { model: 'LibraryWithFilters', args: { request: { type, sort } } },
    }), [type, sort]);
    const library = (useModelState as any)({ model: 'library', action }) as any;
    const libraryItems = useMemo(() => (Array.isArray(library?.catalog) ? library.catalog : []).map(toMeta).filter((m: Meta) => m.id.length > 0), [library?.catalog]);
    // Downloaded titles always show, even if they were taken off the library.
    const items = useMemo(() => {
        const have = new Set(libraryItems.map((m: Meta) => `${m.type}/${m.id}`));
        const extra: Meta[] = [];
        downloads.entries.forEach((d) => {
            const k = `${d.type}/${d.metaId}`;
            if (have.has(k) || d.state === 'failed' || (type !== null && d.type !== type)) return;
            have.add(k);
            extra.push(downloadMeta(d));
        });
        return [...extra, ...libraryItems];
    }, [libraryItems, downloads.entries, type]);
    const badgeOf = useCallback((m: Meta) => titleStatus(downloads.entries, m.type, m.id), [downloads.entries]);
    const types: { type: string | null, selected: boolean }[] = library?.selectable?.types ?? [];
    const sorts: { sort: string, selected: boolean }[] = library?.selectable?.sorts ?? [];
    const hasNext = !!library?.selectable?.nextPage;
    const pending = useRef(false);
    if (items.length > 0) pending.current = false;
    const nearEnd = useCallback(() => {
        if (!hasNext || pending.current) return;
        pending.current = true;
        core.transport.dispatch({ action: 'LibraryWithFilters', args: { action: 'LoadNextPage' } }, 'library');
        setTimeout(() => { pending.current = false; }, 800);
    }, [hasNext]);
    const pick = (fn: () => void) => {
        setShowDownloads(false);
        fn();
        if (scroller.current) scroller.current.scrollTop = 0;
    };

    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div ref={scroller} className={styles['page-scroll']} onScroll={(e) => setScrolled((e.target as HTMLDivElement).scrollTop > 0)}>
                <div className={styles['discover-chips']}>
                    <Strip className={styles['chip-strip']}>
                        {types.map((t) => <Chip key={t.type ?? '*'} label={t.type === null ? 'Everything' : typeLabel(t.type)} selected={!showDownloads && (t.selected || (t.type === null && !types.some((o) => o.selected)))} onClick={() => pick(() => setType(t.type))} />)}
                        {downloads.available ? <Chip label={`Downloads${downloads.entries.length > 0 ? `  ${downloads.entries.length}` : ''}`} selected={showDownloads} onClick={() => { setShowDownloads(true); if (scroller.current) scroller.current.scrollTop = 0; }} /> : null}
                    </Strip>
                    {
                        sorts.length > 1 && !showDownloads ?
                            <Strip className={styles['chip-strip']}>
                                {sorts.map((s) => <Chip key={s.sort} label={sortLabel(s.sort)} selected={s.selected} onClick={() => pick(() => setSort(s.sort))} />)}
                            </Strip>
                            : <div className={styles['chip-strip-empty']} />
                    }
                </div>
                {showDownloads ? <Downloads onOpen={(d) => open(downloadMeta(d))} onScrollTop={() => { if (scroller.current) scroller.current.scrollTop = 0; }} /> : <PosterGrid items={items} cols={7} onOpen={open} onPlay={play} onNearEnd={nearEnd} badgeOf={badgeOf} />}
                {
                    items.length === 0 && !showDownloads ?
                        <div className={styles['empty']}>
                            {
                                library?.selected === null || library === null ? <Spinner /> :
                                    'Nothing here yet. Titles you watch, or add with "+ My List", show up here.'
                            }
                        </div>
                        : null
                }
            </div>
            <NavBar current={4} solid={scrolled} />
        </div>
    );
};

export default withCoreSuspender(Library);
