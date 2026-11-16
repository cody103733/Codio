// Small building blocks of the Music screens: artwork, a song row, a tile.

import React, { useState } from 'react';
import classnames from 'classnames';
import styles from './Music.less';
import { formatTime, type MusicTrack } from './types';
import { addToQueue, playNext } from './player';

export { styles as musicStyles };

export const Cover = ({ src, label, className }: { src?: string | null, label: string, className?: string }) => {
    const [failed, setFailed] = useState(false);
    return src && !failed ?
        <img className={classnames(styles['cover'], className)} src={src} alt={''} loading={'lazy'} decoding={'async'} onError={() => setFailed(true)} />
        : <div className={classnames(styles['cover'], styles['cover-empty'], className)}>{label.trim().charAt(0).toUpperCase() || '♪'}</div>;
};

export const TrackRow = ({ track, number, current, onPlay }: { track: MusicTrack, number?: number, current?: boolean, onPlay: () => void }) => (
    <div
        className={classnames(styles['track'], { [styles['current']]: current })}
        role={'button'}
        tabIndex={0}
        onClick={onPlay}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onPlay(); } }}
    >
        {number !== undefined ? <span className={styles['track-number']}>{number}</span> : <Cover src={track.artworkURL} label={track.title} />}
        <div className={styles['track-text']}>
            <div className={styles['track-title']}>{track.title}</div>
            <div className={styles['track-sub']}>{[track.artist, number === undefined ? track.album : null].filter(Boolean).join(' · ')}</div>
        </div>
        <div className={styles['track-actions']}>
            <button className={styles['mini-button']} onClick={(e) => { e.stopPropagation(); playNext(track); }}>Play next</button>
            <button className={styles['mini-button']} onClick={(e) => { e.stopPropagation(); addToQueue(track); }}>Add to queue</button>
        </div>
        <span className={styles['track-time']}>{track.durationMs ? formatTime(track.durationMs / 1000) : ''}</span>
    </div>
);

export const Tile = ({ title, sub, art, round, onClick }: { title: string, sub?: string | null, art?: string | null, round?: boolean, onClick: () => void }) => (
    <button className={classnames(styles['tile'], { [styles['round']]: round })} onClick={onClick} title={title}>
        <Cover src={art} label={title} />
        <div className={styles['tile-title']}>{title}</div>
        {sub ? <div className={styles['tile-sub']}>{sub}</div> : null}
    </button>
);
