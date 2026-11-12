// Codio Settings (as on Android TV): one list, left/right (or click) changes a value, help on the right.
// Holds the auto-play options plus subtitles, audio, player and account, so nothing opens the old pages.

import React, { useMemo, useState } from 'react';
import classnames from 'classnames';
import { useNavigate } from 'react-router';
import { useProfile } from 'stremio/common';
import { useCore } from 'stremio/core';
import { useAutoPlaySettings, type AutoPlaySettings } from 'stremio/common/AutoPlay';
import { NavBar, styles } from './components';
import SignIn from './SignIn';

declare const process: { env: Record<string, string | undefined> };

type Choice = {
    kind: 'choice',
    label: string,
    help: string,
    store: 'autoplay' | 'profile',
    key: string,
    values: unknown[],
    labels: string[],
};

type Link = { kind: 'link', label: string, help: string, route: string };
type Account = { kind: 'account', label: string, help: string };
type Heading = { kind: 'heading', label: string };

type Item = Choice | Link | Account | Heading;

const BITRATES = [0, 25, 40, 60, 70, 80, 100, 150];

const LANGUAGES: [string, string][] = [
    ['eng', 'English'], ['spa', 'Spanish'], ['fre', 'French'], ['ger', 'German'], ['ita', 'Italian'],
    ['por', 'Portuguese'], ['pob', 'Portuguese (Brazil)'], ['nld', 'Dutch'], ['swe', 'Swedish'], ['pol', 'Polish'],
    ['rus', 'Russian'], ['tur', 'Turkish'], ['ara', 'Arabic'], ['hin', 'Hindi'], ['jpn', 'Japanese'],
    ['kor', 'Korean'], ['zho', 'Chinese'],
];

const SUB_SIZES = [75, 100, 125, 150, 175, 200, 250];
const NEXT_POPUP = [0, 10000, 20000, 30000, 35000, 45000, 60000];

const ITEMS: Item[] = [
    { kind: 'heading', label: 'Playback' },
    { kind: 'choice', store: 'autoplay', label: 'Smart auto-play', help: 'Press Play and Codio picks the best source for you, and moves on to the next one if a source fails.', key: 'enabled', values: [true, false], labels: ['On', 'Off'] },
    { kind: 'choice', store: 'autoplay', label: 'Preferred quality', help: 'The quality auto-play aims for.', key: 'preferredQuality', values: ['2160p', '1440p', '1080p', '720p', '480p'], labels: ['4K (2160p)', '1440p', '1080p', '720p', '480p'] },
    { kind: 'choice', store: 'autoplay', label: 'If that quality isn\'t available', help: 'What auto-play falls back to.', key: 'fallbackMode', values: ['closest', 'lower', 'exact'], labels: ['Closest quality', 'Lower quality only', 'Don\'t play'] },
    { kind: 'choice', store: 'autoplay', label: 'Skip sources above', help: 'Very large 4K files need a fast connection. Sources above this bitrate are tried last, so playback doesn\'t stall. Pick what your internet can carry.', key: 'maxBitrateMbps', values: BITRATES, labels: BITRATES.map((v) => v === 0 ? 'No limit' : `${v} Mbit/s`) },
    { kind: 'choice', store: 'autoplay', label: 'Source list', help: 'Hidden: movies just get a Play button. Shown: you see every source and pick one.', key: 'hideSources', values: [true, false], labels: ['Hidden', 'Shown'] },
    { kind: 'choice', store: 'autoplay', label: 'Prefer instant sources', help: 'Among sources of the same quality, start with ones your debrid service already has ready.', key: 'preferCached', values: [true, false], labels: ['On', 'Off'] },
    { kind: 'choice', store: 'autoplay', label: 'Wait for slow addons', help: 'How long Play waits for addons that are still answering before it picks from the sources it has.', key: 'addonWaitSeconds', values: [3, 5, 8, 12, 20], labels: ['3 seconds', '5 seconds', '8 seconds', '12 seconds', '20 seconds'] },
    { kind: 'choice', store: 'autoplay', label: 'Skip a source that won\'t start', help: 'If a source hasn\'t started playing after this long, Codio moves on to the next one.', key: 'startupTimeoutSeconds', values: [15, 30, 45, 60, 0], labels: ['15 seconds', '30 seconds', '45 seconds', '60 seconds', 'Never'] },
    { kind: 'choice', store: 'profile', label: 'Play the next episode', help: 'When an episode ends, start the next one.', key: 'bingeWatching', values: [true, false], labels: ['Automatically', 'Ask me'] },
    { kind: 'choice', store: 'autoplay', label: 'Skip Intro button', help: 'Shows Skip Intro, Skip Recap and Skip Credits in the player when the episode\'s times are known (from the community database IntroDB).', key: 'skipButtons', values: [true, false], labels: ['On', 'Off'] },
    { kind: 'choice', store: 'profile', label: 'Next episode prompt', help: 'How long before the end of an episode the "Next episode" prompt appears.', key: 'nextVideoNotificationDuration', values: NEXT_POPUP, labels: NEXT_POPUP.map((v) => v === 0 ? 'Off' : `${v / 1000} seconds`) },

    { kind: 'heading', label: 'Subtitles & audio' },
    { kind: 'choice', store: 'profile', label: 'Subtitles', help: 'The subtitle language Codio turns on by itself. Off: no subtitles unless you pick them in the player.', key: 'subtitlesLanguage', values: [null, ...LANGUAGES.map(([code]) => code)], labels: ['Off', ...LANGUAGES.map(([, name]) => name)] },
    { kind: 'choice', store: 'profile', label: 'Subtitle size', help: 'How big subtitles are.', key: 'subtitlesSize', values: SUB_SIZES, labels: SUB_SIZES.map((v) => `${v}%`) },
    { kind: 'choice', store: 'profile', label: 'Audio language', help: 'The audio track Codio picks when a video has several.', key: 'audioLanguage', values: LANGUAGES.map(([code]) => code), labels: LANGUAGES.map(([, name]) => name) },
    { kind: 'choice', store: 'profile', label: 'Surround sound', help: 'Send 5.1 / 7.1 audio as it is, for a surround system. Off mixes it down to stereo.', key: 'surroundSound', values: [false, true], labels: ['Off', 'On'] },

    { kind: 'heading', label: 'Player' },
    { kind: 'choice', store: 'profile', label: 'Hardware decoding', help: 'Uses the graphics card to decode video. Turn off only if videos show green or black pictures.', key: 'hardwareDecoding', values: [true, false], labels: ['On', 'Off'] },
    { kind: 'choice', store: 'profile', label: 'Pause when minimized', help: 'Pause the video when you minimize the Codio window.', key: 'pauseOnMinimize', values: [false, true], labels: ['Off', 'On'] },
    { kind: 'choice', store: 'profile', label: 'Skip forward and back', help: 'How far the arrow keys jump in the player.', key: 'seekTimeDuration', values: [5000, 10000, 15000, 20000, 30000], labels: ['5 seconds', '10 seconds', '15 seconds', '20 seconds', '30 seconds'] },

    { kind: 'heading', label: 'Account & addons' },
    { kind: 'account', label: 'Account', help: 'Signing in keeps your addons, library and progress in sync between Codio on this PC and on your TV.' },
    { kind: 'link', label: 'Addons', help: 'Install or remove addons (catalogs and sources).', route: '/addons' },
];

const indexOf = (item: Choice, value: unknown) => item.values.findIndex((v) => v === value);

const Settings = () => {
    const [settings, update] = useAutoPlaySettings();
    const profile = useProfile() as any;
    const core = useCore() as any;
    const navigate = useNavigate();
    const [focus, setFocus] = useState(1);
    const [signingIn, setSigningIn] = useState(false);
    const version = useMemo(() => {
        try {
            return process.env.CODIO_VERSION || '';
        } catch (_e) {
            return '';
        }
    }, []);
    const email: string | null = profile?.auth?.user?.email ?? null;

    const current = (item: Choice): unknown => item.store === 'autoplay' ? (settings as any)[item.key] : profile?.settings?.[item.key];

    const change = (item: Item, step: number) => {
        if (item.kind !== 'choice') return;
        const index = indexOf(item, current(item));
        const next = item.values[index < 0 ? 0 : (index + step + item.values.length) % item.values.length];
        if (item.store === 'autoplay') {
            update({ [item.key]: next } as Partial<AutoPlaySettings>);
        } else if (profile?.settings) {
            core.transport.dispatch({ action: 'Ctx', args: { action: 'UpdateSettings', args: { ...profile.settings, [item.key]: next } } });
        }
    };

    const activate = (item: Item) => {
        if (item.kind === 'link') navigate(item.route);
        else if (item.kind === 'account') {
            if (email) core.transport.dispatch({ action: 'Ctx', args: { action: 'Logout' } });
            else setSigningIn(true);
        } else change(item, 1);
    };

    const valueOf = (item: Item) => {
        if (item.kind === 'link') return '›';
        if (item.kind === 'account') return email ? 'Sign out' : 'Sign in';
        if (item.kind !== 'choice') return '';
        const value = current(item);
        const index = indexOf(item, value);
        return index >= 0 ? item.labels[index] : String(value ?? '');
    };

    const helpItem = ITEMS[focus] ?? ITEMS[1];

    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div className={styles['settings']}>
                <div className={styles['settings-list']}>
                    {
                        ITEMS.map((item, index) => item.kind === 'heading' ?
                            <div key={item.label} className={styles['setting-heading']}>{item.label}</div>
                            : (
                                <div
                                    key={item.label}
                                    role={'button'}
                                    tabIndex={0}
                                    className={classnames(styles['setting'], { [styles['link']]: item.kind === 'link' })}
                                    onMouseEnter={() => setFocus(index)}
                                    onFocus={() => setFocus(index)}
                                    onClick={() => activate(item)}
                                    onKeyDown={(event) => {
                                        if (event.key === 'ArrowLeft' && item.kind === 'choice') { event.preventDefault(); event.stopPropagation(); change(item, -1); }
                                        if (event.key === 'ArrowRight' && item.kind === 'choice') { event.preventDefault(); event.stopPropagation(); change(item, 1); }
                                        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); activate(item); }
                                    }}
                                >
                                    <span className={styles['setting-label']}>{item.kind === 'account' && email ? `Signed in as ${email}` : item.kind === 'account' ? 'Not signed in' : item.label}</span>
                                    <span className={styles['setting-value']}>
                                        {item.kind === 'choice' ? <span className={styles['arrow']} onClick={(e) => { e.stopPropagation(); change(item, -1); }}>‹</span> : null}
                                        <span>{valueOf(item)}</span>
                                        {item.kind === 'choice' ? <span className={styles['arrow']} onClick={(e) => { e.stopPropagation(); change(item, 1); }}>›</span> : null}
                                    </span>
                                </div>
                            ))
                    }
                </div>
                <div className={styles['settings-help']}>
                    {
                        signingIn ?
                            <>
                                <div className={styles['help-title']}>Sign in</div>
                                <SignIn onDone={() => setSigningIn(false)} />
                            </>
                            :
                            <>
                                <div className={styles['help-title']}>{helpItem.label}</div>
                                {'help' in helpItem ? <div className={styles['body']}>{helpItem.help}</div> : null}
                            </>
                    }
                </div>
                <div className={styles['version']}>{`Codio ${version}`}</div>
            </div>
            <NavBar current={6} />
        </div>
    );
};

export default Settings;
