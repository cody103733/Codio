// Small building blocks of the Music screens: artwork, a song row, a tile.

import React, { useState } from 'react';
import classnames from 'classnames';
import styles from './Music.less';
import { formatTime, type MusicTrack } from './types';
import { addToQueue, playNext } from './player';
import { hideTrack, isLikedTrack, toggleLikeTrack, useTaste } from './taste';

export { styles as musicStyles };

export const Cover = ({ src, label, className }: { src?: string | null, label: string, className?: string }) => {
    const [failed, setFailed] = useState(false);
    return src && !failed ?
        <img className={classnames(styles['cover'], className)} src={src} alt={''} loading={'lazy'} decoding={'async'} onError={() => setFailed(true)} />
        : <div className={classnames(styles['cover'], styles['cover-empty'], className)}>{label.trim().charAt(0).toUpperCase() || '♪'}</div>;
};

export const HeartButton = ({ liked, onClick, className }: { liked: boolean, onClick: () => void, className?: string }) => (
    <button
        className={classnames(styles['heart'], { [styles['on']]: liked }, className)}
        title={liked ? 'Remove from your likes' : 'Like'}
        onClick={(e) => { e.stopPropagation(); onClick(); }}
    >
        {liked ? '♥' : '♡'}
    </button>
);

export const TrackRow = ({ track, number, current, onPlay, canHide = false }: { track: MusicTrack, number?: number, current?: boolean, onPlay: () => void, canHide?: boolean }) => {
    const taste = useTaste();
    const liked = isLikedTrack(taste, track);
    return (
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
                {canHide ? <button className={styles['mini-button']} onClick={(e) => { e.stopPropagation(); hideTrack(track); }}>Not interested</button> : null}
            </div>
            <HeartButton liked={liked} onClick={() => toggleLikeTrack(track)} className={styles['row-heart']} />
            <span className={styles['track-time']}>{track.durationMs ? formatTime(track.durationMs / 1000) : ''}</span>
        </div>
    );
};

// A tile in a row. Hover it for a heart and "not interested".
export const Tile = ({ title, sub, art, round, onClick, liked, onLike, onHide }: {
    title: string, sub?: string | null, art?: string | null, round?: boolean, onClick: () => void,
    liked?: boolean, onLike?: () => void, onHide?: () => void,
}) => (
    <div className={styles['tile-wrap']}>
        <button className={classnames(styles['tile'], { [styles['round']]: round })} onClick={onClick} title={title}>
            <Cover src={art} label={title} />
            <div className={styles['tile-title']}>{title}</div>
            {sub ? <div className={styles['tile-sub']}>{sub}</div> : null}
        </button>
        {onLike || onHide ? (
            <div className={classnames(styles['tile-actions'], { [styles['always']]: liked })}>
                {onLike ? <HeartButton liked={!!liked} onClick={onLike} /> : null}
                {onHide ? <button className={styles['heart']} title={'Not interested'} onClick={(e) => { e.stopPropagation(); onHide(); }}>✕</button> : null}
            </div>
        ) : null}
    </div>
);
