// Big featured banner at the top of the home screen (Stremio AutoPlay fork).

import React, { useEffect, useMemo, useRef, useState } from 'react';
import classnames from 'classnames';
import Icon from '@stremio/stremio-icons/react';
import { Button } from 'stremio/components';
import styles from './Hero.less';

type HeroItem = {
    id: string,
    type: string,
    name: string,
    description?: string | null,
    background?: string | null,
    poster?: string | null,
    logo?: string | null,
    releaseInfo?: string | null,
    deepLinks?: { metaDetailsStreams?: string | null, metaDetailsVideos?: string | null } | null,
};

type Props = {
    className?: string,
    catalogs: any[],
};

const ROTATE_MS = 9000;

const withQuery = (href: string, query: string) => href + (href.includes('?') ? '&' : '?') + query;

const Hero = ({ className, catalogs }: Props) => {
    const items = useMemo<HeroItem[]>(() => {
        const ready = catalogs.find((catalog) => catalog?.content?.type === 'Ready' && Array.isArray(catalog.content.content) && catalog.content.content.length > 0);
        if (!ready) return [];
        return (ready.content.content as HeroItem[])
            .filter((item) => typeof (item.background ?? item.poster) === 'string' && item.deepLinks)
            .slice(0, 6);
    }, [catalogs]);

    const [index, setIndex] = useState(0);
    const indexRef = useRef(0);
    indexRef.current = index;
    useEffect(() => {
        if (items.length < 2) return;
        // Rotating swaps a full-screen picture, which is heavy work on a TV: never do it while the viewer is moving
        // around, and only once the next picture is downloaded and decoded (so the swap doesn't stall a frame).
        let lastKey = 0;
        let cancelled = false;
        let busy = false;
        const onKey = () => { lastKey = Date.now(); };
        window.addEventListener('keydown', onKey, true);
        const timer = setInterval(() => {
            if (busy || Date.now() - lastKey < 6000 || document.hidden) return;
            busy = true;
            const next = (indexRef.current + 1) % items.length;
            const img = new Image();
            img.src = (items[next].background ?? items[next].poster) as string;
            const show = () => {
                busy = false;
                if (!cancelled && Date.now() - lastKey >= 6000) setIndex(next);
            };
            (typeof img.decode === 'function' ? img.decode() : Promise.resolve()).then(show, show);
        }, ROTATE_MS);
        return () => {
            cancelled = true;
            clearInterval(timer);
            window.removeEventListener('keydown', onKey, true);
        };
    }, [items]);

    const item = items.length > 0 ? items[index % items.length] : null;
    // Without a banner, keep the first row clear of the floating top bar.
    if (item === null) return <div className={styles['spacer']} />;

    const detailsHref = item.deepLinks?.metaDetailsVideos ?? item.deepLinks?.metaDetailsStreams ?? null;
    const playHref = item.type === 'movie' && item.deepLinks?.metaDetailsStreams ?
        withQuery(item.deepLinks.metaDetailsStreams, 'autoplay=1')
        :
        detailsHref;

    return (
        <div className={classnames(className, styles['hero'])}>
            <div
                key={item.id}
                className={classnames(styles['backdrop'], { [styles['poster-only']]: !item.background })}
                style={{ backgroundImage: `url("${item.background ?? item.poster}")` }}
            />
            <div className={styles['shade']} />
            <div className={styles['content']}>
                {
                    item.logo ?
                        <img className={styles['logo']} src={item.logo} alt={item.name} />
                        :
                        <div className={styles['title']}>{item.name}</div>
                }
                {item.releaseInfo ? <div className={styles['meta']}>{item.releaseInfo}</div> : null}
                {item.description ? <div className={styles['description']}>{item.description}</div> : null}
                <div className={styles['buttons']}>
                    {
                        playHref ?
                            <Button className={classnames(styles['button'], styles['play'])} title={'Play'} href={playHref}>
                                <Icon className={styles['icon']} name={'play'} />
                                Play
                            </Button>
                            :
                            null
                    }
                    {
                        detailsHref ?
                            <Button className={classnames(styles['button'], styles['info'])} title={'More info'} href={detailsHref}>
                                <Icon className={styles['icon']} name={'about'} />
                                More info
                            </Button>
                            :
                            null
                    }
                </div>
            </div>
            {
                items.length > 1 ?
                    <div className={styles['dots']}>
                        {items.map((it, i) => (
                            <div key={it.id} className={classnames(styles['dot'], { [styles['active']]: i === index % items.length })} onClick={() => setIndex(i)} />
                        ))}
                    </div>
                    :
                    null
            }
        </div>
    );
};

export default Hero;
