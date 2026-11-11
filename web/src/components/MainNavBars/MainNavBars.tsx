// Copyright (C) 2017-2023 Smart code 203358507

import React, { memo } from 'react';
import classnames from 'classnames';
import { VerticalNavBar, HorizontalNavBar } from 'stremio/components/NavBar';
import { useContentGamepadNavigation, useVerticalNavGamepadNavigation } from 'stremio/services/GamepadNavigation';
import styles from './MainNavBars.less';

// Text tabs shown in the top bar on desktop (streaming-app layout).
const TOP_TABS = [
    { id: 'board', title: 'Home', href: '/' },
    { id: 'discover', title: 'Discover', href: '/discover' },
    { id: 'library', title: 'My List', href: '/library' },
    { id: 'calendar', title: 'Calendar', href: '/calendar' },
    { id: 'addons', title: 'Addons', href: '/addons' },
    { id: 'settings', title: 'Settings', href: '/settings' },
];

const TABS = [
    { id: 'board', label: 'Board', icon: 'home', href: '/' },
    { id: 'discover', label: 'Discover', icon: 'discover', href: '/discover' },
    { id: 'library', label: 'Library', icon: 'library', href: '/library' },
    { id: 'calendar', label: 'Calendar', icon: 'calendar', href: '/calendar' },
    { id: 'addons', label: 'ADDONS', icon: 'addons', href: '/addons' },
    { id: 'settings', label: 'SETTINGS', icon: 'settings', href: '/settings' },
];

type Props = {
    className: string,
    route?: string,
    query?: string,
    children?: React.ReactNode,
};

const MainNavBars = memo(({ className, route, query, children }: Props) => {
    const navRef = React.useRef(null);
    const contentRef = React.useRef(null);

    const navRoute = route === 'continue_watching' ? 'library' : (route ?? '');
    useContentGamepadNavigation(contentRef, navRoute);
    useVerticalNavGamepadNavigation(navRef, navRoute);

    // Track whether the page (not a poster row) has scrolled down.
    const [scrolled, setScrolled] = React.useState(false);
    const onScrollCapture = React.useCallback((event: React.UIEvent<HTMLDivElement>) => {
        const target = event.target as HTMLElement;
        if (!target || target.clientHeight < window.innerHeight * 0.5) return;
        setScrolled(target.scrollTop > 30);
    }, []);

    return (
        <div className={classnames(className, styles['main-nav-bars-container'], { [styles['overlay-header']]: route === 'board', [styles['scrolled']]: scrolled })} onScrollCapture={onScrollCapture}>
            <HorizontalNavBar
                className={styles['horizontal-nav-bar']}
                route={route}
                query={query}
                backButton={false}
                searchBar={true}
                fullscreenButton={true}
                navMenu={true}
                tabs={TOP_TABS}
                selected={navRoute}
            />
            <VerticalNavBar
                ref={navRef}
                className={styles['vertical-nav-bar']}
                selected={route}
                tabs={TABS}
            />
            <div ref={contentRef} className={styles['nav-content-container']}>{children}</div>
        </div>
    );
});

export default MainNavBars;

