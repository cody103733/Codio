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
import { useProfiles, profileColor } from './profiles';
import { useUpdate, updateLabel, updateHelp } from './update';
import SyncPanel, { SYNC_HELP, syncLabel } from './SyncPanel';
import { useSyncStatus } from './vault';

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
type Profiles = { kind: 'profiles', label: string, help: string };
type Heading = { kind: 'heading', label: string };
type Update = { kind: 'update', label: string, help: string };
type Sync = { kind: 'sync', label: string, help: string };

type Item = Choice | Link | Account | Profiles | Heading | Update | Sync;

const BITRATES = [0, 25, 40, 60, 70, 80, 100, 150];

const LANGUAGES: [string, string][] = [
    ['eng', 'English'], ['spa', 'Spanish'], ['fre', 'French'], ['ger', 'German'], ['ita', 'Italian'],
    ['por', 'Portuguese'], ['pob', 'Portuguese (Brazil)'], ['nld', 'Dutch'], ['swe', 'Swedish'], ['pol', 'Polish'],
    ['rus', 'Russian'], ['tur', 'Turkish'], ['ara', 'Arabic'], ['hin', 'Hindi'], ['jpn', 'Japanese'],
    ['kor', 'Korean'], ['zho', 'Chinese'],
];

const SUB_SIZES = [75, 100, 125, 150, 175, 200, 250];
const NEXT_POPUP = [0, 10000, 15000, 20000, 30000, 35000, 45000, 60000, 90000];

const ITEMS: Item[] = [
    { kind: 'heading', label: 'Playback' },
    { kind: 'choice', store: 'autoplay', label: 'Smart auto-play', help: 'Play picks the best source for you, and moves on to the next one if a source fails.', key: 'enabled', values: [true, false], labels: ['On', 'Off'] },
    { kind: 'choice', store: 'autoplay', label: 'Preferred quality', help: 'The quality auto-play aims for.', key: 'preferredQuality', values: ['2160p', '1440p', '1080p', '720p', '480p'], labels: ['4K (2160p)', '1440p', '1080p', '720p', '480p'] },
    { kind: 'choice', store: 'autoplay', label: 'If that quality isn\'t available', help: 'What auto-play falls back to.', key: 'fallbackMode', values: ['closest', 'lower', 'exact'], labels: ['Closest quality', 'Lower quality only', 'Don\'t play'] },
    { kind: 'choice', store: 'autoplay', label: 'Skip sources above', help: 'Sources above this bitrate are tried last, so playback doesn\'t stall. Pick what your connection can carry.', key: 'maxBitrateMbps', values: BITRATES, labels: BITRATES.map((v) => v === 0 ? 'No limit' : `${v} Mbit/s`) },
    { kind: 'choice', store: 'autoplay', label: 'Source list', help: 'Hidden: movies just get a Play button. Shown: you see every source and pick one.', key: 'hideSources', values: [true, false], labels: ['Hidden', 'Shown'] },
    { kind: 'choice', store: 'autoplay', label: 'Prefer instant sources', help: 'Among sources of the same quality, start with ones your debrid service already has ready.', key: 'preferCached', values: [true, false], labels: ['On', 'Off'] },
    { kind: 'choice', store: 'autoplay', label: 'Wait for slow addons', help: 'How long Play waits for addons that are still answering before it picks from the sources it has.', key: 'addonWaitSeconds', values: [3, 5, 8, 12, 20], labels: ['3 seconds', '5 seconds', '8 seconds', '12 seconds', '20 seconds'] },
    { kind: 'choice', store: 'autoplay', label: 'Skip a source that won\'t start', help: 'If a source hasn\'t started playing after this long, Codio moves on to the next one.', key: 'startupTimeoutSeconds', values: [15, 30, 45, 60, 0], labels: ['15 seconds', '30 seconds', '45 seconds', '60 seconds', 'Never'] },
    { kind: 'choice', store: 'profile', label: 'Next episode', help: 'At the end of an episode, play the next one automatically.', key: 'bingeWatching', values: [true, false], labels: ['Plays automatically', 'Off'] },
    { kind: 'choice', store: 'autoplay', label: 'Skip Intro button', help: 'Shows Skip Intro, Skip Recap and Skip Credits in the player. Times come from IntroDB, a community database, so not every show has them.', key: 'skipButtons', values: [true, false], labels: ['On', 'Off'] },
    { kind: 'choice', store: 'autoplay', label: 'Skip automatically', help: 'Jumps past intros and recaps by itself when their times are known. With credits too, the next episode starts as the credits begin.', key: 'autoSkip', values: [0, 1, 2], labels: ['Off', 'Intros and recaps', 'Intros, recaps and credits'] },
    { kind: 'choice', store: 'profile', label: 'Up Next', help: 'Shows the next episode with a countdown as the credits start. On episodes whose credits aren\'t timed, it appears this long before the end.', key: 'nextVideoNotificationDuration', values: NEXT_POPUP, labels: NEXT_POPUP.map((v) => v === 0 ? 'Don\'t show' : `${v / 1000} seconds before the end`) },
    { kind: 'choice', store: 'autoplay', label: 'New episode alerts', help: 'Marks shows you\'re watching when a new episode is out, and puts them first in Continue Watching.', key: 'newEpisodeAlerts', values: [true, false], labels: ['On', 'Off'] },
    { kind: 'choice', store: 'profile', label: 'Skip forward and back', help: 'How far the arrow keys jump in the player.', key: 'seekTimeDuration', values: [5000, 10000, 15000, 20000, 30000], labels: ['5 seconds', '10 seconds', '15 seconds', '20 seconds', '30 seconds'] },

    { kind: 'heading', label: 'Picture & sound' },
    { kind: 'choice', store: 'profile', label: 'Hardware decoding', help: 'Uses the graphics card to decode video. Turn off only if videos show green or black pictures.', key: 'hardwareDecoding', values: [true, false], labels: ['On', 'Off'] },
    { kind: 'choice', store: 'profile', label: 'Surround sound', help: 'Send 5.1 / 7.1 audio as it is, for a surround system. Off mixes it down to stereo.', key: 'surroundSound', values: [false, true], labels: ['Off', 'On'] },
    { kind: 'choice', store: 'profile', label: 'Pause when minimized', help: 'Pause the video when you minimize the Codio window.', key: 'pauseOnMinimize', values: [false, true], labels: ['Off', 'On'] },

    { kind: 'heading', label: 'Languages & subtitles' },
    { kind: 'choice', store: 'profile', label: 'Audio language', help: 'The audio track Codio picks when a video has several.', key: 'audioLanguage', values: LANGUAGES.map(([code]) => code), labels: LANGUAGES.map(([, name]) => name) },
    { kind: 'choice', store: 'profile', label: 'Subtitles', help: 'The subtitle language Codio turns on by itself. Off: no subtitles unless you pick them in the player.', key: 'subtitlesLanguage', values: [null, ...LANGUAGES.map(([code]) => code)], labels: ['Off', ...LANGUAGES.map(([, name]) => name)] },
    { kind: 'choice', store: 'profile', label: 'Subtitle size', help: 'How big subtitles are.', key: 'subtitlesSize', values: SUB_SIZES, labels: SUB_SIZES.map((v) => `${v}%`) },
    { kind: 'choice', store: 'profile', label: 'Subtitle colour', help: 'The colour of the words.', key: 'subtitlesTextColor', values: ['#FFFFFFFF', '#FFFF00FF', '#00FFFFFF', '#00FF00FF'], labels: ['White', 'Yellow', 'Cyan', 'Green'] },
    { kind: 'choice', store: 'profile', label: 'Subtitle background', help: 'A box behind the words makes them easier to read on bright scenes.', key: 'subtitlesBackgroundColor', values: ['#00000000', '#00000080', '#000000FF'], labels: ['None', 'Shaded box', 'Black box'] },

    { kind: 'heading', label: 'Account' },
    { kind: 'account', label: 'Account', help: 'Signing in keeps your addons, library and progress in sync between Codio on this PC and on your TV.' },
    { kind: 'profiles', label: 'Who\'s watching', help: 'Profiles you made on the iPhone. Each has its own library, Continue Watching and addons. Pick one on the right to switch, or use Left and Right, then Enter.' },
    { kind: 'sync', label: 'Sync passcode', help: SYNC_HELP },

    { kind: 'heading', label: 'Addons' },
    { kind: 'link', label: 'Addons', help: 'Install or remove addons (catalogs and sources).', route: '/addons' },

    { kind: 'heading', label: 'About' },
    { kind: 'update', label: 'Updates', help: '' },
];

const indexOf = (item: Choice, value: unknown) => item.values.findIndex((v) => v === value);

const Settings = () => {
    const [settings, update] = useAutoPlaySettings();
    const profile = useProfile() as any;
    const core = useCore() as any;
    const navigate = useNavigate();
    const [focus, setFocus] = useState(1);
    const [signingIn, setSigningIn] = useState(false);
    // The passcode form stays open while the mouse wanders over other rows.
    const [syncOpen, setSyncOpen] = useState(false);
    const syncStatus = useSyncStatus();
    const version = useMemo(() => {
        try {
            return process.env.CODIO_VERSION || '';
        } catch (_e) {
            return '';
        }
    }, []);
    const update_ = useUpdate(version);
    const email: string | null = profile?.auth?.user?.email ?? null;
    const profiles = useProfiles();
    const [pick, setPick] = useState(-1);
    const currentIndex = profiles.current ? profiles.list.indexOf(profiles.current) : -1;
    const picked = pick >= 0 && pick < profiles.list.length ? pick : Math.max(0, currentIndex);

    const current = (item: Choice): unknown => item.store === 'autoplay' ? (settings as any)[item.key] : profile?.settings?.[item.key];

    const change = (item: Item, step: number) => {
        if (item.kind === 'profiles') {
            const n = profiles.list.length;
            if (n > 0) setPick((picked + step + n) % n);
            return;
        }
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
        if (item.kind !== 'sync') setSyncOpen(false);
        if (item.kind === 'link') navigate(item.route);
        else if (item.kind === 'account') {
            if (email) core.transport.dispatch({ action: 'Ctx', args: { action: 'Logout' } });
            else setSigningIn(true);
        } else if (item.kind === 'profiles') {
            const p = profiles.list[picked];
            if (p) profiles.switchTo(p);
        } else if (item.kind === 'sync') {
            setSyncOpen(true);
        } else if (item.kind === 'update') {
            if (update_.state.phase === 'current') update_.check();
            else update_.install();
        } else change(item, 1);
    };

    const valueOf = (item: Item) => {
        if (item.kind === 'link') return '›';
        if (item.kind === 'account') return email ? 'Sign out' : 'Sign in';
        if (item.kind === 'update') return updateLabel(update_.state);
        if (item.kind === 'sync') return syncLabel(syncStatus, !!email);
        if (item.kind === 'profiles') {
            if (profiles.list.length === 0) return email ? 'Make profiles on the iPhone' : 'Sign in first';
            if (profiles.switching) return 'Switching…';
            const p = profiles.list[picked];
            return picked === currentIndex ? `${p.name} (watching)` : `${p.name} · Enter to switch`;
        }
        if (item.kind !== 'choice') return '';
        const value = current(item);
        const index = indexOf(item, value);
        return index >= 0 ? item.labels[index] : String(value ?? '');
    };

    const syncItem = ITEMS.find((i) => i.kind === 'sync')!;
    const helpItem = syncOpen ? syncItem : (ITEMS[focus] ?? ITEMS[1]);

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
                                    onFocus={() => { setFocus(index); if (item.kind !== 'sync') setSyncOpen(false); }}
                                    onClick={() => activate(item)}
                                    onKeyDown={(event) => {
                                        if (event.key === 'ArrowLeft' && (item.kind === 'choice' || item.kind === 'profiles')) { event.preventDefault(); event.stopPropagation(); change(item, -1); }
                                        if (event.key === 'ArrowRight' && (item.kind === 'choice' || item.kind === 'profiles')) { event.preventDefault(); event.stopPropagation(); change(item, 1); }
                                        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); activate(item); }
                                    }}
                                >
                                    <span className={styles['setting-label']}>{item.kind === 'account' && email ? `Signed in as ${email}` : item.kind === 'account' ? 'Not signed in' : item.label}</span>
                                    <span className={styles['setting-value']}>
                                        {item.kind === 'choice' || (item.kind === 'profiles' && profiles.list.length > 1) ? <span className={styles['arrow']} onClick={(e) => { e.stopPropagation(); change(item, -1); }}>‹</span> : null}
                                        <span>{valueOf(item)}</span>
                                        {item.kind === 'choice' || (item.kind === 'profiles' && profiles.list.length > 1) ? <span className={styles['arrow']} onClick={(e) => { e.stopPropagation(); change(item, 1); }}>›</span> : null}
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
                                {helpItem.kind === 'update' ? <div className={classnames(styles['body'], styles['lines'])}>{`You have Codio ${version}.\n\n${updateHelp(update_.state)}`}</div>
                                    : 'help' in helpItem ? <div className={styles['body']}>{helpItem.help}</div> : null}
                                {
                                    helpItem.kind === 'profiles' && profiles.list.length > 0 ?
                                        <div className={styles['profile-picks']}>
                                            {profiles.list.map((p, index) => (
                                                <button
                                                    key={p.email}
                                                    title={p.name}
                                                    className={classnames(styles['profile-pick'], { [styles['selected']]: index === currentIndex })}
                                                    onClick={() => { setPick(index); profiles.switchTo(p); }}
                                                >
                                                    <span className={styles['profile-face']} style={{ background: profileColor(p) }}>{p.name.trim().charAt(0).toUpperCase() || '?'}</span>
                                                    <span>{p.name}</span>
                                                </button>
                                            ))}
                                        </div>
                                        : null
                                }
                                {helpItem.kind === 'sync' ? <SyncPanel /> : null}
                                {helpItem.kind === 'profiles' && profiles.error ? <div className={styles['sign-in-error']}>{profiles.error}</div> : null}
                            </>
                    }
                </div>
                <div className={styles['version']}>{`Codio ${version} for Windows`}</div>
            </div>
            <NavBar current={7} />
        </div>
    );
};

export default Settings;
