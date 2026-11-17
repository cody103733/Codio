// An artist: their most played songs, then albums, singles and the rest. From MusicBrainz, or from the addon that offered them.

import React, { useEffect, useState } from 'react';
import classnames from 'classnames';
import { useLocation, useNavigate, useParams } from 'react-router';
import { useSearchParams } from 'react-router-dom';
import { NavBar, CodioButton, Spinner, Strip, styles } from '../components';
import { musicStyles as m, Cover, Tile, TrackRow } from './parts';
import { artistDetail, loadAddons } from './addons';
import { getArtist, getTopTracks } from './musicbrainz';
import { playQueue, usePlayer } from './player';
import { hideArtist, isHiddenArtist, isLikedArtist, toggleLikeArtist, unhide, useTaste } from './taste';
import { openAlbum } from './Music';
import type { MusicAlbum, MusicTrack } from './types';

type Page = { name: string, detail: string | null, artworkURL: string | null, addon: string | null, top: MusicTrack[], groups: { title: string, albums: MusicAlbum[] }[] };

const Artist = () => {
    const { id = '' } = useParams();
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const player = usePlayer();
    const taste = useTaste();
    const preview = (useLocation().state ?? null) as { name?: string } | null;
    const [page, setPage] = useState<Page | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        setPage(null);
        setError(null);
        const addonId = params.get('addon');
        const addon = addonId ? loadAddons().find((a) => a.manifest.id === addonId) : null;
        if (addon) {
            artistDetail(addon, id).then((d) => {
                if (cancelled) return;
                if (!d) { setError('The addon has no page for this artist'); return; }
                setPage({ name: d.name, detail: d.bio, artworkURL: d.artworkURL, addon: addon.url, top: d.topTracks, groups: [{ title: 'Albums', albums: d.albums }] });
            }, () => { if (!cancelled) setError('Couldn\'t load this artist'); });
        } else {
            getArtist(id, preview?.name).then((a) => {
                if (cancelled) return;
                const cover = a.albums[0]?.artworkURL ?? a.singles[0]?.artworkURL ?? null;
                setPage({
                    name: a.name, detail: a.detail, artworkURL: cover, addon: null, top: [],
                    groups: [{ title: 'Albums', albums: a.albums }, { title: 'Singles and EPs', albums: a.singles }, { title: 'Live, compilations and more', albums: a.other }].filter((g) => g.albums.length > 0),
                });
                getTopTracks(id, a.name).then((top) => { if (!cancelled && top.length > 0) setPage((p) => p ? { ...p, top } : p); });
            }, (e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Couldn\'t load this artist'); });
        }
        return () => { cancelled = true; };
    }, [id, params]);

    const current = player.queue[player.index]?.id ?? null;
    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div className={styles['page-scroll']}>
                <div className={m['music']}>
                    {error ? <div className={m['error']}>{error}</div> : null}
                    {!page && !error && preview?.name ? (
                        <div className={classnames(m['hero'], m['round'])}>
                            <Cover src={null} label={preview.name} />
                            <div>
                                <div className={m['hero-kind']}>Artist</div>
                                <div className={m['hero-title']}>{preview.name}</div>
                            </div>
                        </div>
                    ) : null}
                    {!page && !error ? <div className={m['center']}><Spinner /></div> : null}
                    {page ? (
                        <>
                            <div className={classnames(m['hero'], m['round'])}>
                                <Cover src={page.artworkURL} label={page.name} />
                                <div>
                                    <div className={m['hero-kind']}>Artist</div>
                                    <div className={m['hero-title']}>{page.name}</div>
                                    {page.detail ? <div className={m['hero-sub']}>{page.detail}</div> : null}
                                    <div className={m['hero-actions']}>
                                        {page.top.length > 0 ? <CodioButton label={'Play'} play primary onClick={() => playQueue(page.top, 0, false)} /> : null}
                                        {page.top.length > 0 ? <CodioButton label={'Shuffle'} onClick={() => playQueue(page.top, 0, true)} /> : null}
                                        <button className={classnames(m['toggle'], { [m['on']]: isLikedArtist(taste, { id }) })} onClick={() => toggleLikeArtist({ id, name: page.name, artworkURL: null, addon: page.addon })}>
                                            {isLikedArtist(taste, { id }) ? '♥ Liked' : '♡ Like'}
                                        </button>
                                        {isHiddenArtist(taste, { id }) ?
                                            <button className={m['toggle']} onClick={() => unhide('artist', id)}>Hidden: undo</button>
                                            : <button className={m['toggle']} onClick={() => hideArtist({ id, name: page.name })}>Not interested</button>}
                                    </div>
                                </div>
                            </div>
                            {
                                page.top.length > 0 ?
                                    <>
                                        <div className={m['section-title']}>Popular songs</div>
                                        <div className={m['tracks']}>
                                            {page.top.map((t, i) => <TrackRow key={`${t.id}/${i}`} track={t} number={i + 1} current={current === t.id} onPlay={() => playQueue(page.top, i, false)} />)}
                                        </div>
                                    </>
                                    : null
                            }
                            {page.groups.map((g) => (
                                <div key={g.title}>
                                    <div className={m['section-title']}>{g.title}</div>
                                    <Strip className={m['row-strip']}>
                                        {g.albums.map((a) => <Tile key={a.id} title={a.title} sub={[a.year, a.kind?.replace(/^Album · ?/, '')].filter(Boolean).join(' · ')} art={a.artworkURL} onClick={() => openAlbum(navigate, { ...a, addon: page.addon }, loadAddons())} />)}
                                    </Strip>
                                </div>
                            ))}
                            {page.groups.every((g) => g.albums.length === 0) && page.top.length === 0 ? <div className={m['note']}>No albums found for this artist.</div> : null}
                        </>
                    ) : null}
                </div>
            </div>
            <NavBar current={3} solid />
        </div>
    );
};

export default Artist;
