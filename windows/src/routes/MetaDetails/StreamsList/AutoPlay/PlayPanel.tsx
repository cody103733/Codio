// Shown instead of the source list when "Hide sources" is on.

import React from 'react';
import classnames from 'classnames';
import Icon from '@stremio/stremio-icons/react';
import { Button } from 'stremio/components';
import type { AutoPlayPhase } from './useAutoPlay';
import type { AutoPlaySettings } from 'stremio/common/AutoPlay';
import styles from './PlayPanel.less';

type Props = {
    className?: string,
    align?: 'center' | 'left',
    phase: AutoPlayPhase,
    settings: AutoPlaySettings,
    ready: boolean,
    loading: boolean,
    noStreams: boolean,
    onPlay: () => void,
    onShowSources: () => void,
};

const PlayPanel = ({ className, align = 'center', phase, settings, ready, loading, noStreams, onPlay, onShowSources }: Props) => {
    const candidates = 'candidates' in phase ? phase.candidates : [];
    const best = candidates[0] ?? null;
    const starting = phase.type === 'starting' || phase.type === 'countdown';
    const searching = !starting && best === null && (!ready || loading) && !noStreams;
    const nothing = !starting && best === null && !searching;

    return (
        <div className={classnames(className, styles['play-panel'], { [styles['left']]: align === 'left' })}>
            {
                starting ?
                    <div className={styles['status']}>
                        <div className={styles['spinner']} />
                        <div className={styles['label']}>Starting{best?.quality ? ` in ${best.quality}` : ''}…</div>
                    </div>
                    :
                    searching ?
                        <div className={styles['status']}>
                            <div className={styles['spinner']} />
                            <div className={styles['label']}>Finding the best {settings.preferredQuality} version…</div>
                        </div>
                        :
                        nothing ?
                            <div className={styles['status']}>
                                <div className={styles['label']}>
                                    {noStreams ? 'Not available to stream right now.' : `No ${settings.preferredQuality} version found.`}
                                </div>
                            </div>
                            :
                            <React.Fragment>
                                <Button className={styles['play-button']} title={'Play'} onClick={onPlay}>
                                    <Icon className={styles['icon']} name={'play'} />
                                    <div className={styles['label']}>Play</div>
                                </Button>
                                <div className={styles['quality']}>
                                    {best?.quality ?? 'Best available'}{best?.cached ? ' · instant' : ''}
                                </div>
                            </React.Fragment>
            }
            <Button className={styles['sources-link']} title={'Choose a source'} onClick={onShowSources}>
                Choose a source
            </Button>
        </div>
    );
};

export default PlayPanel;
