// The words of the song that is playing, above the mini player. Timed lyrics follow the song: the line being sung is
// white and kept in view (unless you just scrolled); click a line to jump there. DESIGN.md "Lyrics".

import React, { useEffect, useRef } from 'react';
import classnames from 'classnames';
import styles from './Music.less';
import { seek, usePlayer } from './player';
import { lineAt, LYRICS_CREDIT, useLyrics } from './lyrics';

const LyricsPanel = () => {
    const player = usePlayer();
    const track = player.queue[player.index] ?? null;
    const { lyrics, loading, failed } = useLyrics(track, player.duration, !player.loading);
    const box = useRef<HTMLDivElement | null>(null);
    const touched = useRef(0);
    const at = lyrics && lyrics.lines.length > 0 ? lineAt(lyrics, player.position) : -1;

    useEffect(() => {
        const el = box.current;
        if (!el || at < 0 || Date.now() - touched.current < 4000) return;
        const line = el.querySelector(`[data-line="${at}"]`) as HTMLElement | null;
        if (line) el.scrollTo({ top: Math.max(0, line.offsetTop - el.clientHeight / 3), behavior: 'smooth' });
    }, [at, lyrics]);

    let body: React.ReactNode;
    if (loading) body = <div className={styles['lyrics-note']}>{'Looking for the lyrics…'}</div>;
    else if (!lyrics) body = <div className={styles['lyrics-note']}>{failed ? 'Couldn\'t reach the lyrics. Check your connection.' : 'No lyrics found for this song.'}</div>;
    else if (lyrics.lines.length > 0) {
        body = lyrics.lines.map((l, i) => (
            <div
                key={i}
                data-line={i}
                className={classnames(styles['lyrics-line'], { [styles['current']]: i === at, [styles['past']]: i < at })}
                onClick={() => { touched.current = 0; seek(l.ms / 1000); }}
            >
                {l.text || '♪'}
            </div>
        ));
    } else if (lyrics.plain) {
        body = <>
            <div className={styles['lyrics-plain']}>{lyrics.plain}</div>
            <div className={styles['lyrics-note']}>{'These lyrics aren\'t timed to the song.'}</div>
        </>;
    } else body = <div className={styles['lyrics-line']}>{'♪  Instrumental'}</div>;

    return (
        <div className={styles['lyrics']} ref={box} onWheel={() => { touched.current = Date.now(); }}>
            <div className={styles['lyrics-title']}>{track ? `Lyrics · ${track.title}` : 'Lyrics'}</div>
            {body}
            {lyrics ? <div className={styles['lyrics-credit']}>{LYRICS_CREDIT}</div> : null}
        </div>
    );
};

export default LyricsPanel;
