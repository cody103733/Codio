// The mini player: stays along the bottom of every page while music is queued, with the queue one click away.
// It pauses the music when a video starts.

import React, { useEffect, useState } from 'react';
import classnames from 'classnames';
import { useLocation, useNavigate } from 'react-router';
import styles from './Music.less';
import { Cover, HeartButton } from './parts';
import { isLikedTrack, toggleLikeTrack, useTaste } from './taste';
import { cycleRepeat, next, pause, playIndex, previous, removeFromQueue, seek, setVolume, stop, toggle, toggleMute, toggleShuffle, usePlayer } from './player';
import { formatTime } from './types';

const MiniPlayer = () => {
    const player = usePlayer();
    const taste = useTaste();
    const location = useLocation();
    const navigate = useNavigate();
    const [queueOpen, setQueueOpen] = useState(false);
    const inVideo = location.pathname.startsWith('/player');

    useEffect(() => {
        if (inVideo) pause();
    }, [inVideo]);

    // Space plays and pauses, as long as you're not typing.
    useEffect(() => {
        if (player.queue.length === 0 || inVideo) return;
        const onKey = (e: KeyboardEvent) => {
            const tag = (e.target as HTMLElement)?.tagName;
            if (e.code === 'MediaPlayPause' || (e.key === ' ' && tag !== 'INPUT' && tag !== 'BUTTON' && tag !== 'TEXTAREA' && (e.target as HTMLElement)?.getAttribute?.('role') !== 'button')) {
                e.preventDefault();
                toggle();
            }
        };
        window.addEventListener('keydown', onKey);
        return () => window.removeEventListener('keydown', onKey);
    }, [player.queue.length, inVideo]);

    const track = player.queue[player.index] ?? null;
    if (player.queue.length === 0 || inVideo) return null;
    const fraction = player.duration > 0 ? Math.min(1, player.position / player.duration) : 0;

    return (
        <div className={styles['mini-root']}>
            {player.message ? <div className={styles['mini-message']}>{player.message}</div> : null}
            {
                queueOpen ?
                    <div className={styles['queue']}>
                        <div className={styles['queue-title']}>Up next</div>
                        {player.queue.map((t, i) => (
                            <button key={`${t.id}/${i}`} className={classnames(styles['queue-row'], { [styles['current']]: i === player.index })} onClick={() => playIndex(i)}>
                                <span className={styles['queue-text']}>{`${t.title} · ${t.artist}`}</span>
                                {i !== player.index ? <span onClick={(e) => { e.stopPropagation(); removeFromQueue(i); }} title={'Remove'}>✕</span> : null}
                            </button>
                        ))}
                    </div>
                    : null
            }
            <div className={styles['mini']}>
                <div
                    className={styles['mini-progress']}
                    onClick={(e) => {
                        const box = e.currentTarget.getBoundingClientRect();
                        if (player.duration > 0) seek(((e.clientX - box.left) / box.width) * player.duration);
                    }}
                >
                    <div className={styles['mini-progress-bar']} style={{ width: `${fraction * 100}%` }} />
                </div>
                <div className={styles['mini-now']} onClick={() => navigate('/music')}>
                    <Cover src={track?.artworkURL} label={track?.title ?? ''} />
                    <div className={styles['mini-text']}>
                        <div className={styles['mini-title']}>{track?.title}</div>
                        <div className={styles['mini-sub']}>
                            {player.loading ? 'Finding a source…' : [track?.artist, player.source, player.quality].filter(Boolean).join(' · ')}
                        </div>
                    </div>
                </div>
                {track ? <HeartButton liked={isLikedTrack(taste, track)} onClick={() => toggleLikeTrack(track)} /> : null}
                <div className={styles['mini-controls']}>
                    <button className={classnames(styles['round'], { [styles['active']]: player.shuffle })} onClick={toggleShuffle} title={'Shuffle'}>⇄</button>
                    <button className={styles['round']} onClick={previous} title={'Previous'}>⏮</button>
                    <button className={classnames(styles['round'], styles['big'])} onClick={toggle} title={player.playing ? 'Pause' : 'Play'}>{player.playing ? '❚❚' : '▶'}</button>
                    <button className={styles['round']} onClick={next} title={'Next'}>⏭</button>
                    <button className={classnames(styles['round'], { [styles['active']]: player.repeat !== 'off' })} onClick={cycleRepeat} title={`Repeat: ${player.repeat}`}>{player.repeat === 'one' ? '↻1' : '↻'}</button>
                </div>
                <div className={styles['mini-time']}>{`${formatTime(player.position)} / ${formatTime(player.duration)}`}</div>
                <div className={styles['volume']}>
                    <button className={styles['round']} onClick={toggleMute} title={player.volume > 0 ? 'Mute' : 'Unmute'}>{player.volume === 0 ? '🔇' : player.volume < 0.5 ? '🔉' : '🔊'}</button>
                    <input
                        type={'range'} min={0} max={100} value={Math.round(player.volume * 100)} title={'Volume'}
                        onChange={(e) => setVolume(Number(e.target.value) / 100)}
                    />
                </div>
                <button className={classnames(styles['round'], { [styles['active']]: queueOpen })} onClick={() => setQueueOpen(!queueOpen)} title={'Queue'}>☰</button>
                <button className={styles['round']} onClick={stop} title={'Close'}>✕</button>
            </div>
        </div>
    );
};

export default MiniPlayer;
