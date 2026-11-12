// Building blocks shared by the Codio screens (ported from Codio for Android TV's canvas UI).

import React, { useEffect, useRef, useState } from 'react';
import classnames from 'classnames';
import { useNavigate } from 'react-router';
import BrandLogo from 'stremio/components/BrandLogo';
import type { Meta } from './api';
import styles from './Codio.less';

export { styles };

// ---- Top navigation: the logo's C is the C of "Codio" (one wordmark, never an icon beside the word).
export const NAV_ITEMS: { label: string, path: string }[] = [
    { label: 'Home', path: '/' },
    { label: 'Movies', path: '/movies' },
    { label: 'Series', path: '/series' },
    { label: 'Search', path: '/search' },
    { label: 'Library', path: '/library' },
    { label: 'Addons', path: '/addons' },
    { label: 'Settings', path: '/preferences' },
];

export const NavBar = ({ current, solid = false }: { current: number, solid?: boolean }) => {
    const navigate = useNavigate();
    return (
        <div className={classnames(styles['nav'], { [styles['solid']]: solid })}>
            <button className={styles['nav-logo']} tabIndex={-1} onClick={() => navigate('/')} aria-label={'Codio home'}>
                <BrandLogo className={styles['logo']} />
            </button>
            {
                NAV_ITEMS.map((item, index) => (
                    <button
                        key={item.path}
                        className={classnames(styles['nav-item'], { [styles['current']]: index === current })}
                        onClick={() => { if (index !== current) navigate(item.path); }}
                    >
                        {item.label}
                    </button>
                ))
            }
        </div>
    );
};

// ---- Full-screen artwork that cross-fades when it changes (after an optional dwell, so skimming stays calm).
export const Backdrop = ({ src, delay = 0, dim = 0, shaded = true }: { src: string | null, delay?: number, dim?: number, shaded?: boolean }) => {
    const [layers, setLayers] = useState<{ src: string, key: number }[]>([]);
    const counter = useRef(0);
    useEffect(() => {
        if (!src) return;
        let cancelled = false;
        const timer = setTimeout(() => {
            const img = new Image();
            img.src = src;
            const show = () => {
                if (cancelled) return;
                setLayers((current) => {
                    if (current.length > 0 && current[current.length - 1].src === src) return current;
                    counter.current += 1;
                    return [...current.slice(-1), { src, key: counter.current }];
                });
            };
            (typeof img.decode === 'function' ? img.decode() : Promise.resolve()).then(show, () => {
                // A missing picture keeps the last one.
            });
        }, delay);
        return () => {
            cancelled = true;
            clearTimeout(timer);
        };
    }, [src, delay]);
    return (
        <div className={styles['backdrop']}>
            {
                layers.map((layer) => (
                    <div key={layer.key} className={styles['backdrop-image']} style={{ backgroundImage: `url("${layer.src.replace(/"/g, '%22')}")` }} />
                ))
            }
            {shaded ? <div className={styles['shade-left']} /> : null}
            {shaded ? <div className={styles['shade-bottom']} /> : null}
            {shaded ? <div className={styles['shade-top']} /> : null}
            {dim > 0 ? <div className={styles['dim']} style={{ opacity: dim }} /> : null}
        </div>
    );
};

// ---- Title logo picture (falls back to the name in big type).
export const TitleArt = ({ meta, logo, width, height, textSize, className }: { meta: Meta, logo: string | null, width: number, height: number, textSize: number, className?: string }) => {
    const [failed, setFailed] = useState(false);
    const [loaded, setLoaded] = useState(false);
    useEffect(() => {
        setFailed(false);
        setLoaded(false);
    }, [logo]);
    const box = { width: `calc(var(--u) * ${width})`, height: `calc(var(--u) * ${height})` };
    return (
        <div className={classnames(styles['title-art'], className)} style={box}>
            {
                logo && !failed ?
                    <img
                        className={classnames(styles['title-logo'], { [styles['shown']]: loaded })}
                        src={logo}
                        alt={meta.name}
                        onLoad={() => setLoaded(true)}
                        onError={() => setFailed(true)}
                    />
                    : null
            }
            {
                !logo || failed ?
                    <div className={styles['title-text']} style={{ fontSize: `calc(var(--u) * ${textSize})` }}>{meta.name}</div>
                    : null
            }
        </div>
    );
};

// ---- Download state on a poster or episode: a ring while downloading, a ✓ when it's saved.
export const DownloadBadge = ({ done, progress, className }: { done: boolean, progress: number, className?: string }) => {
    const r = 15;
    const c = 2 * Math.PI * r;
    return (
        <span className={classnames(styles['dl-badge'], { [styles['done']]: done }, className)} title={done ? 'Downloaded' : `Downloading ${Math.round(progress * 100)}%`}>
            {
                done ? '✓' :
                    <svg viewBox={'0 0 36 36'}>
                        <circle cx={18} cy={18} r={r} className={styles['dl-ring-bg']} />
                        <circle cx={18} cy={18} r={r} className={styles['dl-ring']} strokeDasharray={`${c * Math.max(0.02, progress)} ${c}`} transform={'rotate(-90 18 18)'} />
                    </svg>
            }
        </span>
    );
};

// ---- A poster card: grows and gets a white ring when focused or hovered.
type PosterProps = {
    meta: Meta,
    badge?: { done: boolean, progress: number } | null,
    onOpen: (meta: Meta) => void,
    onPlay?: (meta: Meta) => void,
    onFocusItem?: (meta: Meta) => void,
    caption?: boolean,
};

export const Poster = React.memo(({ meta, badge, onOpen, onPlay, onFocusItem, caption = false }: PosterProps) => {
    const [failed, setFailed] = useState(false);
    const focus = () => onFocusItem && onFocusItem(meta);
    return (
        <div className={styles['poster-cell']}>
            <button
                className={styles['poster']}
                onClick={() => onOpen(meta)}
                onFocus={focus}
                onMouseEnter={focus}
                onKeyDown={(event) => {
                    if ((event.key === 'MediaPlayPause' || event.key === 'p') && onPlay && meta.type === 'movie') {
                        event.preventDefault();
                        onPlay(meta);
                    }
                }}
                title={meta.name}
            >
                {
                    meta.poster && !failed ?
                        <img className={styles['poster-image']} src={meta.poster} alt={''} loading={'lazy'} decoding={'async'} onError={() => setFailed(true)} />
                        :
                        <div className={styles['poster-placeholder']}>{meta.name}</div>
                }
                {
                    typeof meta.progress === 'number' && meta.progress > 0 ?
                        <div className={styles['progress']}><div style={{ width: `${Math.max(3, meta.progress * 100)}%` }} /></div>
                        : null
                }
                {badge ? <DownloadBadge done={badge.done} progress={badge.progress} className={styles['poster-badge']} /> : null}
                {
                    meta.releaseInfo || meta.imdbRating ?
                        <span className={styles['poster-hover']}>
                            <span className={styles['poster-hover-meta']}>{[meta.releaseInfo, meta.imdbRating ? `★ ${meta.imdbRating}` : null].filter(Boolean).join(' · ')}</span>
                        </span>
                        : null
                }
                {
                    onPlay && meta.type === 'movie' ?
                        <span
                            className={styles['poster-play']}
                            role={'button'}
                            title={'Play'}
                            onClick={(event) => {
                                event.stopPropagation();
                                onPlay(meta);
                            }}
                        />
                        : null
                }
            </button>
            {
                caption ?
                    <div className={styles['poster-caption']}>{meta.name}{meta.releaseInfo ? `  (${meta.releaseInfo})` : ''}</div>
                    : null
            }
        </div>
    );
});

Poster.displayName = 'Poster';

export const Spinner = ({ size = 34, className }: { size?: number, className?: string }) => (
    <div className={classnames(styles['spinner'], className)} style={{ width: `calc(var(--u) * ${size * 2})`, height: `calc(var(--u) * ${size * 2})` }} />
);

type ButtonProps = {
    label: string,
    play?: boolean,
    primary?: boolean,
    onClick: () => void,
    autoFocus?: boolean,
    className?: string,
    onFocus?: () => void,
};

export const CodioButton = React.forwardRef<HTMLButtonElement, ButtonProps>(({ label, play = false, primary = false, onClick, autoFocus, className, onFocus }, ref) => (
    <button
        ref={ref}
        className={classnames(styles['button'], { [styles['primary']]: primary }, className)}
        onClick={onClick}
        autoFocus={autoFocus}
        onFocus={onFocus}
    >
        {play ? <span className={styles['play-icon']} /> : null}
        {label}
    </button>
));

CodioButton.displayName = 'CodioButton';

export const Chip = ({ label, selected, onClick, onFocus }: { label: string, selected: boolean, onClick: () => void, onFocus?: () => void }) => (
    <button className={classnames(styles['chip'], { [styles['selected']]: selected })} onClick={onClick} onFocus={onFocus}>{label}</button>
);

// Horizontal strip with arrows on hover (mouse) and focus-follow scrolling (keyboard).
export const Strip = ({ children, className, resetKey }: { children: React.ReactNode, className?: string, resetKey?: string }) => {
    const ref = useRef<HTMLDivElement>(null);
    const [edges, setEdges] = useState({ left: false, right: false });
    const update = () => {
        const el = ref.current;
        if (!el) return;
        const left = el.scrollLeft > 4;
        const right = el.scrollLeft + el.clientWidth < el.scrollWidth - 4;
        setEdges((current) => current.left === left && current.right === right ? current : { left, right });
    };
    useEffect(() => {
        if (ref.current) ref.current.scrollLeft = 0;
        update();
    }, [resetKey]);
    useEffect(() => {
        update();
        const el = ref.current;
        if (!el || typeof ResizeObserver === 'undefined') return;
        // Watch the strip and its content, so arrows appear once items arrive.
        const observer = new ResizeObserver(update);
        observer.observe(el);
        if (el.firstElementChild) observer.observe(el.firstElementChild);
        return () => observer.disconnect();
    }, []);
    const page = (direction: number) => {
        const el = ref.current;
        if (el) el.scrollBy({ left: direction * el.clientWidth * 0.8, behavior: 'smooth' });
    };
    return (
        <div className={classnames(styles['strip-wrap'], className)}>
            <div
                ref={ref}
                className={styles['strip']}
                onScroll={update}
                onWheel={(event) => {
                    // A plain mouse wheel over a row scrolls the row sideways only with Shift (vertical wheel belongs to the page).
                    if (event.shiftKey && ref.current) ref.current.scrollLeft += event.deltaY;
                }}
            >
                {children}
            </div>
            {edges.left ? <button tabIndex={-1} className={classnames(styles['strip-arrow'], styles['left'])} onClick={() => page(-1)} aria-label={'Scroll left'} /> : null}
            {edges.right ? <button tabIndex={-1} className={classnames(styles['strip-arrow'], styles['right'])} onClick={() => page(1)} aria-label={'Scroll right'} /> : null}
        </div>
    );
};

// Grid of posters with a "load more" trigger near the end.
export const PosterGrid = ({ items, cols, onOpen, onPlay, onNearEnd, badgeOf }: { items: Meta[], cols: number, onOpen: (m: Meta) => void, onPlay?: (m: Meta) => void, onNearEnd?: () => void, badgeOf?: (m: Meta) => { done: boolean, progress: number } | null }) => {
    const sentinel = useRef<HTMLDivElement>(null);
    const nearEnd = useRef(onNearEnd);
    nearEnd.current = onNearEnd;
    useEffect(() => {
        const el = sentinel.current;
        if (!el || typeof IntersectionObserver === 'undefined') return;
        const observer = new IntersectionObserver((entries) => {
            if (entries.some((e) => e.isIntersecting) && nearEnd.current) nearEnd.current();
        }, { rootMargin: '0px 0px 900px 0px' });
        observer.observe(el);
        return () => observer.disconnect();
    }, [items.length]);
    return (
        <div className={styles['grid']} style={{ gridTemplateColumns: `repeat(${cols}, calc(var(--u) * 200))` }}>
            {items.map((meta) => <Poster key={`${meta.type}/${meta.id}`} meta={meta} badge={badgeOf ? badgeOf(meta) : null} onOpen={onOpen} onPlay={onPlay} caption />)}
            <div ref={sentinel} className={styles['grid-sentinel']} />
        </div>
    );
};

export const useEscape = (handler: () => boolean | void, active = true) => {
    const ref = useRef(handler);
    ref.current = handler;
    useEffect(() => {
        if (!active) return;
        const onKey = (event: KeyboardEvent) => {
            if (event.key !== 'Escape' && event.key !== 'BrowserBack' && event.key !== 'Backspace') return;
            if (event.key === 'Backspace' && (event.target as HTMLElement)?.tagName === 'INPUT') return;
            if (ref.current() === true) {
                event.preventDefault();
                event.stopPropagation();
            }
        };
        // The mouse's back button does the same (closes an overlay first, then goes back).
        const onMouse = (event: MouseEvent) => {
            if (event.button !== 3) return;
            if (ref.current() === true) {
                event.preventDefault();
                event.stopPropagation();
            }
        };
        window.addEventListener('keydown', onKey, true);
        window.addEventListener('mouseup', onMouse, true);
        return () => {
            window.removeEventListener('keydown', onKey, true);
            window.removeEventListener('mouseup', onMouse, true);
        };
    }, [active]);
};
