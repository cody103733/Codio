// Codio logo: a "C" wrapped around a play button, plus the "codio" wordmark.
// Drawn with plain strokes so it looks the same everywhere (no font needed).

import React, { useId } from 'react';

type Props = {
    className?: string,
    compact?: boolean,
};

const Gradient = ({ id }: { id: string }) => (
    <defs>
        <linearGradient id={id} x1={'0'} y1={'0'} x2={'1'} y2={'1'}>
            <stop offset={'0'} stopColor={'#FF4B2B'} />
            <stop offset={'1'} stopColor={'#C3001D'} />
        </linearGradient>
    </defs>
);

// Each logo gets its own gradient id: with a shared id, a hidden copy of the logo elsewhere on the
// page can "own" the gradient and the C disappears from the visible one.
const BrandLogo = ({ className, compact = false }: Props) => {
    const uid = useId().replace(/[^a-zA-Z0-9_-]/g, '');
    const markId = `codio-mark-gradient-${uid}`;
    const wordId = `codio-word-gradient-${uid}`;
    return compact ?
        <svg className={className} viewBox={'0 0 64 64'} role={'img'} aria-label={'Codio'}>
            <Gradient id={markId} />
            <path d={'M44.71 46.12 A19 19 0 1 1 44.71 17.88'} fill={'none'} stroke={`url(#${markId})`} strokeWidth={'8'} strokeLinecap={'round'} />
            <path d={'M27 22.5 L42.5 32 L27 41.5 Z'} fill={'#fff'} stroke={'#fff'} strokeWidth={'3'} strokeLinejoin={'round'} />
        </svg>
        :
        <svg className={className} viewBox={'0 0 129 40'} role={'img'} aria-label={'Codio'}>
            <Gradient id={wordId} />
            <g fill={'none'} strokeWidth={'4.6'} strokeLinecap={'round'}>
                <path d={'M26.90 29.50 A14.25 14.25 0 1 1 26.90 9.00'} stroke={`url(#${wordId})`} />
                <path d={'M12.5 12.8 L24 19.25 L12.5 25.7 Z'} fill={'#fff'} stroke={'#fff'} strokeWidth={'2'} strokeLinejoin={'round'} />
                <g stroke={'#fff'}>
                    <circle cx={'45'} cy={'24'} r={'9.5'} />
                    <circle cx={'70'} cy={'24'} r={'9.5'} />
                    <path d={'M79.5 5 V33.5'} />
                    <path d={'M93 16 V33.5'} />
                    <circle cx={'114'} cy={'24'} r={'9.5'} />
                </g>
            </g>
            <circle cx={'93'} cy={'7.5'} r={'3'} fill={`url(#${wordId})`} />
        </svg>;
};

export default BrandLogo;
