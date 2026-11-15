// The Up Next card, bottom right: near the end of an episode it shows the next one with a countdown, and the
// player starts it when the countdown runs out. The same rules on every Codio app (see DESIGN.md):
//
//  - Credits timed (IntroDB): the card comes up as the credits start and counts down 10 seconds.
//  - Credits not timed: the card comes up "Up Next" seconds before the end (Settings) and counts down to the end.
//  - "Next episode" set to Off: the card still offers the next episode, but nothing starts by itself.
//  - "Up Next" set to Don't show: no card.
//
// The countdown follows the video's position, so it stops while paused and goes away after seeking back.

import React from 'react';
import classnames from 'classnames';
import styles from './UpNext.less';

export type UpNextState = {
    // A countdown is running: `left` seconds to go, `fraction` 0..1 of it done.
    counting: boolean,
    left: number,
    fraction: number,
    // The countdown ran out: start the next episode now.
    fire: boolean,
};

const COUNTDOWN = 10;

const clamp = (v: number) => Math.max(0, Math.min(1, v));

// The card for position t (seconds), or null when it isn't time yet (or never is).
export const upNextAt = (outro: { start: number, end: number } | null, t: number, duration: number, beforeEndMs: number, playsByItself: boolean): UpNextState | null => {
    if (!(beforeEndMs > 0)) return null;
    if (duration > 0 && t >= duration - 1) return null;
    if (outro) {
        if (t < outro.start) return null;
        const end = duration > 0 ? Math.min(outro.start + COUNTDOWN, duration - 1) : outro.start + COUNTDOWN;
        const left = end - t;
        return {
            counting: playsByItself && left > 0,
            left,
            fraction: end > outro.start ? clamp((t - outro.start) / (end - outro.start)) : 1,
            fire: playsByItself && left <= 0,
        };
    }
    if (!(duration > 0)) return null;
    const left = duration - t;
    const window = beforeEndMs / 1000;
    if (left <= 0 || left > window) return null;
    // The end of the video starts the next episode (the player does that itself), so nothing fires here.
    return { counting: playsByItself, left, fraction: clamp(1 - left / window), fire: false };
};

type Props = {
    className?: string,
    state: UpNextState,
    video: { title?: string, name?: string, season?: number, episode?: number, thumbnail?: string | null },
    fallbackPicture?: string | null,
    raised: boolean,
    onPlay: () => void,
    onDismiss: () => void,
};

const stop = (e: React.SyntheticEvent) => e.stopPropagation();

const UpNext = ({ className, state, video, fallbackPicture, raised, onPlay, onDismiss }: Props) => {
    const [failed, setFailed] = React.useState(false);
    const picture = (!failed && video.thumbnail) || fallbackPicture || null;
    const title = video.title || video.name || '';
    const episode = typeof video.season === 'number' && typeof video.episode === 'number' ? `S${video.season} E${video.episode}` : '';
    return (
        <div className={classnames(className, styles['up-next'], { [styles['raised']]: raised })} onMouseDown={stop} onDoubleClick={stop} onClick={stop}>
            <div className={styles['picture']}>
                {picture ? <img src={picture} alt={''} onError={() => setFailed(true)} /> : null}
                {
                    state.counting || state.fraction >= 1 ?
                        <div className={styles['countdown']}><div style={{ width: `${state.fraction * 100}%` }} /></div>
                        : null
                }
            </div>
            <div className={styles['text']}>
                <div className={classnames(styles['heading'], { [styles['counting']]: state.counting })}>
                    {state.counting ? `Up Next in ${Math.max(1, Math.ceil(state.left))}` : 'Up Next'}
                </div>
                <div className={styles['episode']}>{[episode, title].filter(Boolean).join('  ·  ')}</div>
                <div className={styles['buttons']}>
                    <button className={styles['play']} tabIndex={-1} onClick={(e) => { e.stopPropagation(); onPlay(); }}>
                        <span className={styles['triangle']} />Play now
                    </button>
                    <button className={styles['dismiss']} tabIndex={-1} onClick={(e) => { e.stopPropagation(); onDismiss(); }}>
                        Watch credits
                    </button>
                </div>
            </div>
        </div>
    );
};

export default UpNext;
