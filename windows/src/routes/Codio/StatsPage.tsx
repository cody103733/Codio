// Your stats: what this profile watched and listened to, this year or all time (DESIGN.md "Your stats").
// Opened from Settings; laid out like the iPhone's StatsView, adapted to the TV-style Windows grid.

import React, { useEffect, useMemo, useState } from 'react';
import classnames from 'classnames';
import { useNavigate } from 'react-router';
import { useProfile } from 'stremio/common';
import { NavBar, Chip, Poster, styles } from './components';
import { libraryMeta, type Meta } from './api';
import { useOpen } from './useCodio';
import { playQueue } from './music/player';
import { count, hours, libraryEntries, listenStats, profileId, sinceLine, syncStats, thisYear, watchStats, type Title } from './stats';

const Tile = ({ value, label, detail }: { value: string, label: string, detail?: string }) => (
    <div className={styles['stats-tile']}>
        <div className={styles['stats-value']}>{value}</div>
        <div className={styles['stats-label']}>{label}</div>
        <div className={styles['stats-detail']}>{detail ?? ' '}</div>
    </div>
);

const StatsPage = () => {
    const navigate = useNavigate();
    const { open } = useOpen();
    const profile = useProfile() as any;
    const [allTime, setAllTime] = useState(false);
    const year = allTime ? 0 : thisYear();
    const libraryKey = profile?.auth?.user?._id ?? '';
    const [synced, setSynced] = useState(0);
    useEffect(() => {
        let gone = false;
        syncStats(true).then(() => { if (!gone) setSynced((n) => n + 1); });
        return () => { gone = true; };
    }, [libraryKey]);
    const { watch, listen } = useMemo(() => {
        return { watch: watchStats(libraryEntries(), profileId(), year), listen: listenStats(year) };
    }, [year, libraryKey, synced]);
    const empty = (what: string) => <div className={styles['stats-empty']}>{what}{allTime ? '.' : ' this year.'}</div>;
    const titles = (head: string, list: Title[]) => (
        <>
            <div className={styles['stats-head']}>{head}</div>
            {
                list.length === 0 ? empty('Nothing watched yet') :
                    <div className={styles['stats-posters']}>
                        {list.slice(0, 5).map((t) => {
                            const meta: Meta = libraryMeta({ _id: t.entry.id, type: t.entry.type, name: t.entry.name, poster: t.entry.poster });
                            return (
                                <div key={t.entry.id} className={styles['stats-poster']}>
                                    <Poster meta={meta} onOpen={open} />
                                    <div className={styles['stats-poster-name']}>{t.entry.name}</div>
                                    <div className={styles['stats-red']}>{hours(t.ms)} h</div>
                                </div>
                            );
                        })}
                    </div>
            }
        </>
    );
    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div className={styles['page-scroll']}>
                <div className={styles['stats']}>
                    <div className={styles['stats-top']}>
                        <button className={styles['stats-back']} onClick={() => navigate(-1)}>‹ Back</button>
                        <div className={styles['stats-title']}>Your stats</div>
                        <Chip label={String(thisYear())} selected={!allTime} onClick={() => setAllTime(false)} />
                        <Chip label={'All time'} selected={allTime} onClick={() => setAllTime(true)} />
                    </div>
                    <div className={styles['stats-tiles']}>
                        <Tile value={hours(watch.ms)} label={'Hours watched'} detail={watch.before >= 60000 ? `+ ${hours(watch.before)} h before Codio` : undefined} />
                        <Tile value={String(watch.shows.length + watch.movies.length)} label={'Titles watched'}
                            detail={`${count(watch.movies.length, 'movie')} · ${count(watch.shows.length, 'show')}`} />
                        <Tile value={String(Math.round(listen.seconds / 60))} label={'Minutes listened'} />
                        <Tile value={String(listen.plays)} label={'Songs played'} />
                    </div>
                    <div className={styles['stats-columns']}>
                        <div className={styles['stats-column']}>
                            {titles('Top shows', watch.shows)}
                            {titles('Top movies', watch.movies)}
                        </div>
                        <div className={styles['stats-column']}>
                            <div className={styles['stats-head']}>Top songs</div>
                            {
                                listen.songs.length === 0 ? empty('Nothing played yet') :
                                    <div className={styles['stats-list']}>
                                        {listen.songs.slice(0, 5).map((s, i) => (
                                            <button key={i} className={styles['stats-song']} onClick={() => playQueue([s.track], 0, false)}>
                                                {s.track.artworkURL ? <img src={s.track.artworkURL} alt={''} /> : <span className={styles['stats-cover']}>♪</span>}
                                                <span className={styles['stats-song-text']}>
                                                    <span className={styles['stats-song-title']}>{s.track.title}</span>
                                                    <span className={styles['stats-song-artist']}>{s.track.artist}</span>
                                                </span>
                                                <span className={styles['stats-red']}>{count(s.count, 'play')}</span>
                                            </button>
                                        ))}
                                    </div>
                            }
                            <div className={styles['stats-head']}>Top artists</div>
                            {
                                listen.artists.length === 0 ? empty('Nothing played yet') :
                                    <div className={styles['stats-group']}>
                                        {listen.artists.slice(0, 5).map((a, i) => (
                                            <div key={i} className={styles['stats-artist']}>
                                                <span className={styles['stats-rank']}>{i + 1}</span>
                                                <span className={styles['stats-artist-name']}>{a.name}</span>
                                                <span className={styles['stats-red']}>{count(a.count, 'play')}</span>
                                            </div>
                                        ))}
                                    </div>
                            }
                        </div>
                    </div>
                    <div className={styles['stats-foot']}>{sinceLine(watch)} Paused time and skipping don't count.{allTime && watch.before >= 60000 ? ' Before Codio is your Stremio library\'s own total, which also counts rewatches.' : ''}</div>
                </div>
            </div>
            <NavBar current={8} solid />
        </div>
    );
};

export default StatsPage;
