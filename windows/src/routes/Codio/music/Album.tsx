// An album: cover, tracklist with lengths, Play and Shuffle. From MusicBrainz, or from the addon that offered it.

import React, { useEffect, useState } from 'react';
import classnames from 'classnames';
import { useLocation, useNavigate, useParams } from 'react-router';
import { useSearchParams } from 'react-router-dom';
import { NavBar, CodioButton, Spinner, styles } from '../components';
import { musicStyles as m, Cover, TrackRow } from './parts';
import { albumDetail, loadAddons } from './addons';
import { getAlbum } from './musicbrainz';
import { playQueue, usePlayer } from './player';
import { artistHref } from './Music';
import type { MusicTrack } from './types';

type Page = { title: string, artist: string, artistId: string | null, addon: string | null, year: string | null, artworkURL: string | null, tracks: MusicTrack[] };

const Album = () => {
    const { id = '' } = useParams();
    const [params] = useSearchParams();
    const navigate = useNavigate();
    const player = usePlayer();
    const preview = (useLocation().state ?? null) as { title?: string, artist?: string, art?: string | null, year?: string | null } | null;
    const [page, setPage] = useState<Page | null>(null);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => {
        let cancelled = false;
        setPage(null);
        setError(null);
        const addonId = params.get('addon');
        const addon = addonId ? loadAddons().find((a) => a.manifest.id === addonId) : null;
        const job: Promise<Page> = addon ?
            albumDetail(addon, id).then((d) => {
                if (!d) throw new Error('The addon has no page for this album');
                return { ...d, artistId: null, addon: addon.url };
            })
            : getAlbum(id).then((a) => ({ title: a.title, artist: a.artist, artistId: a.artistId, addon: null, year: a.year, artworkURL: a.artworkURL, tracks: a.tracks }));
        job.then((p) => { if (!cancelled) setPage(p); }, (e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Couldn\'t load this album'); });
        return () => { cancelled = true; };
    }, [id, params]);

    const current = player.queue[player.index]?.id ?? null;
    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div className={styles['page-scroll']}>
                <div className={m['music']}>
                    {error ? <div className={m['error']}>{error}</div> : null}
                    {!page && !error && preview?.title ? (
                        <div className={m['hero']}>
                            <Cover src={preview.art} label={preview.title} />
                            <div>
                                <div className={m['hero-kind']}>Album</div>
                                <div className={m['hero-title']}>{preview.title}</div>
                                <div className={m['hero-sub']}>{[preview.artist, preview.year].filter(Boolean).join(' · ')}</div>
                            </div>
                        </div>
                    ) : null}
                    {!page && !error ? <div className={m['center']}><Spinner /></div> : null}
                    {page ? (
                        <>
                            <div className={m['hero']}>
                                <Cover src={page.artworkURL} label={page.title} />
                                <div>
                                    <div className={m['hero-kind']}>Album</div>
                                    <div className={m['hero-title']}>{page.title}</div>
                                    <div className={m['hero-sub']}>
                                        {page.artistId ?
                                            <span className={m['link']} onClick={() => navigate(artistHref({ id: page.artistId as string }, []), { state: { name: page.artist } })}>{page.artist}</span>
                                            : page.artist}
                                        {page.year ? ` · ${page.year}` : ''}{` · ${page.tracks.length} songs`}
                                    </div>
                                    <div className={m['hero-actions']}>
                                        <CodioButton label={'Play'} play primary onClick={() => playQueue(page.tracks, 0, false)} />
                                        <CodioButton label={'Shuffle'} onClick={() => playQueue(page.tracks, Math.floor(Math.random() * page.tracks.length), true)} />
                                    </div>
                                </div>
                            </div>
                            <div className={m['tracks']}>
                                {page.tracks.map((t, i) => <TrackRow key={`${t.id}/${i}`} track={t} number={i + 1} current={current === t.id} onPlay={() => playQueue(page.tracks, i, false)} />)}
                            </div>
                        </>
                    ) : null}
                </div>
            </div>
            <NavBar current={3} solid />
        </div>
    );
};

export default Album;
