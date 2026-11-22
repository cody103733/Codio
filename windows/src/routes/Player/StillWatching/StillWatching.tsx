// "Are you still watching?" (Settings > Playback, off unless turned on). After that many episodes in a row that
// started by themselves (Up Next, the end of an episode, auto-skipped credits) with no key, click or touch, the
// next one waits for the viewer instead. Same rule on every Codio app (see DESIGN.md).

import React from 'react';
import styles from './StillWatching.less';

// Episodes in a row that started by themselves. Kept outside the player, which mounts again for every episode.
let run = 0;

export const touched = () => {
    run = 0;
};

if (typeof window !== 'undefined') {
    for (const ev of ['keydown', 'mousedown', 'wheel', 'touchstart']) {
        window.addEventListener(ev, touched, { capture: true, passive: true });
    }
}

// The next episode is about to start by itself: true = go ahead (it's counted), false = ask first.
export const mayContinue = (after: number) => {
    if (after > 0 && run >= after) {
        run = 0;
        return false;
    }
    run += 1;
    return true;
};

type Props = {
    name: string,
    video: { title?: string, name?: string, season?: number, episode?: number, thumbnail?: string | null } | null,
    fallbackPicture?: string | null,
    onContinue: () => void,
    onDone: () => void,
};

const stop = (e: React.SyntheticEvent) => e.stopPropagation();

const StillWatching = ({ name, video, fallbackPicture, onContinue, onDone }: Props) => {
    const [failed, setFailed] = React.useState(false);
    const picture = (!failed && video?.thumbnail) || fallbackPicture || null;
    const title = video ? (video.title || video.name || '') : '';
    const episode = video && typeof video.season === 'number' && typeof video.episode === 'number' ? `S${video.season} E${video.episode}` : '';
    const next = [episode, title].filter(Boolean).join('  ·  ');
    return (
        <div className={styles['still-watching']} onMouseDown={stop} onDoubleClick={stop} onClick={stop}>
            <div className={styles['card']}>
                {picture ? <img className={styles['picture']} src={picture} alt={''} onError={() => setFailed(true)} /> : null}
                <div className={styles['question']}>Are you still watching {name || 'this show'}?</div>
                {next ? <div className={styles['next']}>Next: {next}</div> : null}
                <button className={styles['continue']} autoFocus onClick={(e) => { e.stopPropagation(); onContinue(); }}>
                    <span className={styles['triangle']} />Continue watching
                </button>
                <button className={styles['done']} onClick={(e) => { e.stopPropagation(); onDone(); }}>
                    I'm done for now
                </button>
            </div>
        </div>
    );
};

export default StillWatching;
