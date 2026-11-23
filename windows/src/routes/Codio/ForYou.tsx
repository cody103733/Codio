// For You: rows picked for this profile from what it watches (DESIGN.md "For You"). The picks are worked out on this
// PC by foryou.ts and kept for the day; the page shows the saved ones at once and refreshes them in the background.

import React, { useEffect, useState } from 'react';
import classnames from 'classnames';
import { useNavigate } from 'react-router';
import { withCoreSuspender } from 'stremio/common';
import { backgroundOf, logoOf, infoLine, type Meta } from './api';
import { NavBar, Backdrop, TitleArt, Spinner, CodioButton, styles } from './components';
import { Row } from './Home';
import { useOpen } from './useCodio';
import { build, cached, needsBuild, tmdbKey, type BuildState, type ForYouResult, type Pick } from './foryou';

let running: Promise<{ state: BuildState, result: ForYouResult | null }> | null = null;
const rebuild = () => {
    if (!running) running = build().finally(() => { running = null; });
    return running;
};

const ForYou = () => {
    const navigate = useNavigate();
    const { open, play } = useOpen();
    const [result, setResult] = useState<ForYouResult | null>(() => (tmdbKey() ? cached() : null));
    const [state, setState] = useState<BuildState | 'loading'>(() => (!tmdbKey() ? 'nokey' : cached() ? 'ok' : 'loading'));
    const [active, setActive] = useState<Meta | null>(null);
    const [scrollY, setScrollY] = useState(0);

    const refresh = (force: boolean) => {
        if (!tmdbKey()) { setState('nokey'); return; }
        if (!force && !needsBuild()) return;
        if (!result) setState('loading');
        rebuild().then(({ state: s, result: r }) => {
            if (r) setResult(r);
            setState(r || !result ? s : 'ok');
        }, () => setState(result ? 'ok' : 'failed'));
    };
    useEffect(() => { refresh(false); }, []);

    const rows = result?.rows ?? [];
    const hero = (rows[0]?.items[0] ?? null) as Pick | null;
    const viewport = typeof window !== 'undefined' ? window.innerHeight : 1080;
    const inRows = scrollY > viewport * 0.35;
    const shown = (inRows && active ? active : hero) as Pick | null;

    const message = state === 'nokey' ? 'For You uses TMDB to find titles like the ones you watch. Add your free TMDB key in Settings to turn it on.'
        : state === 'nohistory' ? 'Watch a few movies or shows and your picks show up here.'
            : state === 'failed' ? 'Couldn\'t reach TMDB for your picks. Check the connection and try again.' : null;

    return (
        <div className={classnames(styles['codio'], styles['home'], styles['foryou'])}>
            <Backdrop src={backgroundOf(shown)} delay={inRows ? 300 : 0} />
            <div className={styles['home-dim']} style={{ opacity: Math.min(0.55, scrollY / (viewport * 1.2)) }} />
            <div className={styles['page-scroll']} data-scroller={''} onScroll={(e) => setScrollY((e.target as HTMLDivElement).scrollTop)}>
                <div className={styles['hero']}>
                    {
                        hero && message === null ?
                            <div className={classnames(styles['info'], styles['hero-info'])}>
                                <div className={styles['foryou-reason']}>{hero.reason}</div>
                                <button className={styles['hero-art']} onClick={() => open(hero)} title={hero.name}>
                                    <TitleArt meta={hero} logo={logoOf(hero)} width={680} height={220} textSize={76} className={styles['info-art']} />
                                </button>
                                <div className={styles['meta-line']}>{infoLine(hero)}</div>
                                {hero.description ? <div className={classnames(styles['body'], styles['lines-3'])} style={{ width: 'calc(var(--u) * 860)' }}>{hero.description}</div> : null}
                                <div className={styles['buttons']}>
                                    <CodioButton label={'Play'} play primary autoFocus onClick={() => (hero.type === 'movie' ? play(hero) : open(hero))} />
                                    <CodioButton label={'More info'} onClick={() => open(hero)} />
                                    <CodioButton label={'New picks'} onClick={() => refresh(true)} />
                                </div>
                            </div>
                            :
                            <div className={styles['foryou-empty']}>
                                <div className={styles['foryou-title']}>For You</div>
                                {message ? <div className={styles['foryou-text']}>{message}</div> : <Spinner />}
                                {message === null ? <div className={styles['foryou-text']}>Finding titles that fit what you watch…</div> : null}
                                {state === 'nokey' ? <CodioButton label={'Open Settings'} primary autoFocus onClick={() => navigate('/preferences')} /> : null}
                                {state === 'failed' ? <CodioButton label={'Try again'} primary autoFocus onClick={() => refresh(true)} /> : null}
                            </div>
                    }
                </div>
                <div className={styles['rows']}>
                    {
                        message === null ? rows.map((row) => (
                            <Row key={row.id} eager title={row.title} catalog={null} items={row.items} onFocusItem={setActive} onOpen={open} onPlay={play} />
                        )) : null
                    }
                </div>
            </div>
            <NavBar current={1} solid={scrollY > 40} />
        </div>
    );
};

export default withCoreSuspender(ForYou);
