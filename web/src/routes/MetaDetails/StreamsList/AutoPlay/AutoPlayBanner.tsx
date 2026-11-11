import React from 'react';
import classnames from 'classnames';
import Icon from '@stremio/stremio-icons/react';
import { Button } from 'stremio/components';
import type { AutoPlayPhase } from './useAutoPlay';
import type { AutoPlaySettings } from 'stremio/common/AutoPlay';
import styles from './AutoPlayBanner.less';

type Props = {
    className?: string,
    phase: AutoPlayPhase,
    settings: AutoPlaySettings,
    onCancel: () => void,
    onPlayNow: () => void,
};

const AutoPlayBanner = ({ className, phase, settings, onCancel, onPlayNow }: Props) => {
    if (phase.type === 'disabled') return null;

    let text: React.ReactNode = null;
    let actions: React.ReactNode = null;

    switch (phase.type) {
        case 'waiting':
            text = <>Auto-play is looking for a <b>{settings.preferredQuality}</b> source…</>;
            break;
        case 'countdown': {
            const first = phase.candidates[0];
            text = (
                <>
                    Auto-playing <b>{first.quality ?? 'best available'}</b> in {phase.remaining}s
                    <span className={styles['source']}>{first.label}</span>
                </>
            );
            actions = (
                <>
                    <Button className={classnames(styles['button'], styles['primary'])} title={'Play now'} onClick={onPlayNow}>Play now</Button>
                    <Button className={styles['button']} title={'Cancel'} onClick={onCancel}>Cancel</Button>
                </>
            );
            break;
        }
        case 'starting':
            text = <>Starting <b>{phase.candidates[0]?.quality ?? 'best available'}</b> source…</>;
            break;
        case 'cancelled':
        case 'handled':
            if (phase.candidates.length === 0) return null;
            text = <>{phase.candidates.length} source{phase.candidates.length === 1 ? '' : 's'} match your auto-play settings</>;
            actions = (
                <Button className={classnames(styles['button'], styles['primary'])} title={'Auto-play'} onClick={onPlayNow}>Auto-play</Button>
            );
            break;
        case 'no-match':
            text = <>No source matches <b>{settings.preferredQuality}</b> with your auto-play settings. Pick one below.</>;
            break;
    }

    return (
        <div className={classnames(className, styles['auto-play-banner'])}>
            <Icon className={styles['icon']} name={'play'} />
            <div className={styles['text']}>{text}</div>
            {actions ? <div className={styles['actions']}>{actions}</div> : null}
        </div>
    );
};

export default AutoPlayBanner;
