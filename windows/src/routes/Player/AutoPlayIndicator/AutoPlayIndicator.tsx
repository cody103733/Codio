import React from 'react';
import classnames from 'classnames';
import Icon from '@stremio/stremio-icons/react';
import { Button } from 'stremio/components';
import type { AutoPlayStatus } from '../useAutoPlayFallback';
import styles from './AutoPlayIndicator.less';

type Props = {
    className?: string,
    status: NonNullable<AutoPlayStatus>,
    hasNext: boolean,
    // Hide-sources mode: only offer "Try another source", no source details.
    compact?: boolean,
    onNext: () => void,
};

const AutoPlayIndicator = ({ className, status, hasNext, compact = false, onNext }: Props) => {
    if (compact) {
        return hasNext ?
            <Button className={classnames(className, styles['next-button'], styles['compact'])} title={'Try another source'} onClick={onNext}>
                <Icon className={styles['icon']} name={'next'} />
                Try another source
            </Button>
            :
            null;
    }
    return (
        <div className={classnames(className, styles['auto-play-indicator'])} title={status.label}>
            <div className={styles['info']}>
                <div className={styles['heading']}>
                    Auto-play · source {status.position}/{status.total}
                    {status.quality ? <span className={styles['quality']}>{status.quality}</span> : null}
                </div>
                <div className={styles['label']}>{status.label}</div>
            </div>
            {
                hasNext ?
                    <Button className={styles['next-button']} title={'Try next source'} onClick={onNext}>
                        <Icon className={styles['icon']} name={'next'} />
                        Next source
                    </Button>
                    :
                    null
            }
        </div>
    );
};

export default AutoPlayIndicator;
