// A whole chart as a list: the Top 100 or a genre's top 100, opened from its row on the Music page.

import React, { useEffect, useState } from 'react';
import classnames from 'classnames';
import { useNavigate, useParams } from 'react-router';
import { NavBar, CodioButton, Spinner, styles } from '../components';
import { musicStyles as m, TrackRow } from './parts';
import { appleMusicUrl, chartSongs, chartSource, chartTitle } from './discovery';
import { playQueue, usePlayer } from './player';
import { useTaste, visibleTracks } from './taste';
import type { MusicTrack } from './types';
import { usePlatform } from 'stremio/common';

const Chart = () => {
    const { id = 'top' } = useParams();
    const navigate = useNavigate();
    const player = usePlayer();
    const taste = useTaste();
    const platform = usePlatform() as any;
    const [songs, setSongs] = useState<MusicTrack[] | null>(null);
    useEffect(() => {
        let cancelled = false;
        setSongs(null);
        chartSongs(id).then((list) => { if (!cancelled) setSongs(list); }, () => { if (!cancelled) setSongs([]); });
        return () => { cancelled = true; };
    }, [id]);
    const shown = visibleTracks(taste, songs ?? []);
    const current = player.queue[player.index]?.id ?? null;
    const credit = shown.some((t) => appleMusicUrl(t)) ? 'Chart from Apple Music' : id === 'top' && chartSource() === 'lastfm' ? 'Chart data from Last.fm' : null;
    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div className={styles['page-scroll']}>
                <div className={m['music']}>
                    <div className={m['section-head']}>
                        <button className={m['mini-button']} onClick={() => navigate(-1)}>Back</button>
                        <div className={m['title']}>{chartTitle(id)}</div>
                    </div>
                    {credit ? <div className={m['chart-credit']}>{credit}</div> : null}
                    {songs === null ? <div className={m['center']}><Spinner /></div> : null}
                    {songs !== null && shown.length === 0 ? <div className={m['note']}>Couldn't load this chart right now (no connection?).</div> : null}
                    {shown.length > 0 ? (
                        <>
                            <div className={m['addon-box']}>
                                <CodioButton label={'Play'} play primary onClick={() => playQueue(shown, 0, false)} />
                                <CodioButton label={'Shuffle'} onClick={() => playQueue(shown, Math.floor(Math.random() * shown.length), true)} />
                            </div>
                            <div className={m['tracks']}>
                                {shown.map((t, i) => {
                                    const apple = appleMusicUrl(t);
                                    return <TrackRow key={t.id} track={t} number={i + 1} canHide current={current === t.id} onPlay={() => playQueue(shown, i)}
                                        link={apple ? { label: 'Listen on Apple Music', onOpen: () => platform.openExternal(apple) } : null} />;
                                })}
                            </div>
                        </>
                    ) : null}
                </div>
            </div>
            <NavBar current={3} solid />
        </div>
    );
};

export default Chart;
