// Settings > Sync passcode: turn sync on, unlock after a change elsewhere, change the passcode, or start over.

import React, { useState } from 'react';
import classnames from 'classnames';
import { styles } from './components';
import { useSyncStatus, setPasscode, changePasscode, startOver, turnOff, maybeSync, MIN_PASSCODE, signedInForSync } from './vault';

export const SYNC_HELP = 'One passcode unlocks your private settings on every device: the TMDB key, music addon links, '
    + 'your ListenBrainz sign-in and the MusicBrainz server. They\'re locked with the passcode before they go to your '
    + 'Stremio account, so nobody without it (Stremio included) can read them. Enter the same passcode once on each device.';

export const syncLabel = (s: ReturnType<typeof useSyncStatus>, signedIn: boolean) => {
    if (!signedIn) return 'Sign in first';
    if (!s.on) return 'Off · Set passcode';
    if (s.problem === 'locked') return 'Enter the new passcode';
    if (s.busy) return 'Syncing…';
    if (s.problem === 'offline') return 'On · Offline';
    return 'On';
};

const ago = (t: number | null) => {
    if (!t) return 'not yet';
    const m = Math.round((Date.now() - t) / 60000);
    if (m < 1) return 'just now';
    if (m < 60) return `${m} min ago`;
    const h = Math.round(m / 60);
    return h < 24 ? `${h} h ago` : new Date(t).toLocaleDateString();
};

const SyncPanel = () => {
    const status = useSyncStatus();
    const [text, setText] = useState('');
    const [mode, setMode] = useState<'none' | 'change' | 'over'>('none');
    const [error, setError] = useState<string | null>(null);
    const [note, setNote] = useState<string | null>(null);
    const [working, setWorking] = useState(false);

    if (!signedInForSync()) return <div className={styles['body']}>Sign in to your Stremio account first. The locked settings are kept with it.</div>;

    const go = async (job: (p: string) => Promise<void>, done: string) => {
        setError(null);
        setNote(null);
        setWorking(true);
        try {
            await job(text);
            setText('');
            setMode('none');
            setNote(done);
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Something went wrong');
        } finally {
            setWorking(false);
        }
    };

    const field = (placeholder: string, submit: () => void) => (
        <input
            className={styles['sign-in-input']}
            type={'password'}
            placeholder={placeholder}
            value={text}
            autoFocus
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.stopPropagation(); submit(); } }}
        />
    );

    let body: React.ReactNode;
    if (!status.on) {
        const submit = () => go(setPasscode, 'Sync is on. Enter the same passcode on your other devices.');
        body = (
            <>
                {field(`Sync passcode (at least ${MIN_PASSCODE} characters)`, submit)}
                <div className={styles['sign-in-row']}>
                    <button className={classnames(styles['button'], styles['primary'])} disabled={working} onClick={submit}>{working ? 'Unlocking…' : 'Turn on'}</button>
                </div>
                <div className={styles['body']}>Already set a passcode on another device? Enter that one. Otherwise pick a new one and remember it: it can't be recovered.</div>
            </>
        );
    } else if (status.problem === 'locked') {
        const submit = () => go(setPasscode, 'Unlocked. Sync is on again.');
        body = (
            <>
                <div className={styles['sign-in-error']}>The sync passcode was changed on another device. Enter the new one.</div>
                {field('New sync passcode', submit)}
                <div className={styles['sign-in-row']}>
                    <button className={classnames(styles['button'], styles['primary'])} disabled={working} onClick={submit}>{working ? 'Unlocking…' : 'Unlock'}</button>
                    <button className={styles['button']} disabled={working} onClick={() => go(startOver, 'Started over. Enter the new passcode on your other devices.')}>Start over with this passcode</button>
                    <button className={styles['button']} disabled={working} onClick={() => { turnOff(); setNote('Sync is off on this PC.'); }}>Turn off</button>
                </div>
                <div className={styles['body']}>Start over replaces the synced settings with this PC's, locked with the passcode you typed. Your other devices then ask for it.</div>
            </>
        );
    } else if (mode === 'change') {
        const submit = () => go(changePasscode, 'Passcode changed. Your other devices will ask for the new one.');
        body = (
            <>
                {field('New sync passcode', submit)}
                <div className={styles['sign-in-row']}>
                    <button className={classnames(styles['button'], styles['primary'])} disabled={working} onClick={submit}>{working ? 'Changing…' : 'Change passcode'}</button>
                    <button className={styles['button']} onClick={() => { setMode('none'); setText(''); setError(null); }}>Cancel</button>
                </div>
            </>
        );
    } else {
        body = (
            <>
                <div className={styles['body']}>{status.problem === 'offline' ? 'Couldn\'t reach your Stremio account. Codio tries again on its own.' : `On. Last synced ${ago(status.lastSync)}.`}</div>
                <div className={styles['sign-in-row']}>
                    <button className={classnames(styles['button'], styles['primary'])} disabled={status.busy} onClick={() => maybeSync(true)}>{status.busy ? 'Syncing…' : 'Sync now'}</button>
                    <button className={styles['button']} onClick={() => { setMode('change'); setNote(null); }}>Change passcode</button>
                    <button className={styles['button']} onClick={() => { turnOff(); setNote('Sync is off on this PC. Your settings stay here.'); }}>Turn off</button>
                </div>
            </>
        );
    }

    return (
        <div className={styles['sign-in']}>
            {body}
            {error ? <div className={styles['sign-in-error']}>{error}</div> : null}
            {note ? <div className={styles['body']}>{note}</div> : null}
        </div>
    );
};

export default SyncPanel;
