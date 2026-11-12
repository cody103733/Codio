// Netflix-style "Skip Intro" / "Skip Recap" / "Skip Credits" button, bottom right.

import React from 'react';
import classnames from 'classnames';
import styles from './SkipButton.less';

type Props = {
    className?: string,
    label: string,
    raised: boolean,
    onSkip: () => void,
};

const SkipButton = ({ className, label, raised, onSkip }: Props) => (
    <button
        className={classnames(className, styles['skip-button'], { [styles['raised']]: raised })}
        onClick={(e) => { e.stopPropagation(); onSkip(); }}
        onMouseDown={(e) => e.stopPropagation()}
        onDoubleClick={(e) => e.stopPropagation()}
        tabIndex={-1}
    >
        {label}
    </button>
);

export default SkipButton;
