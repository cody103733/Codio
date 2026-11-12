// Codio sign-in form (Settings and the start screen).

import React, { useState } from 'react';
import classnames from 'classnames';
import { useCore } from 'stremio/core';
import { styles } from './components';

const SignIn = ({ onDone, cancelLabel = 'Cancel' }: { onDone: () => void, cancelLabel?: string }) => {
    const core = useCore() as any;
    const [email, setEmail] = useState('');
    const [password, setPassword] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    React.useEffect(() => {
        const onEvent = (name: string) => { if (name === 'UserAuthenticated') { setBusy(false); onDone(); } };
        const onError = (source: any) => { if (source?.event === 'UserAuthenticated') { setBusy(false); setError('Couldn\'t sign in. Check the email and password.'); } };
        core.on('event', onEvent);
        core.on('error', onError);
        return () => { core.off('event', onEvent); core.off('error', onError); };
    }, []);
    const submit = () => {
        if (!email.includes('@') || password.length === 0) {
            setError('Enter your email and password.');
            return;
        }
        setError(null);
        setBusy(true);
        core.transport.dispatch({ action: 'Ctx', args: { action: 'Authenticate', args: { type: 'Login', email, password } } });
    };
    return (
        <div className={styles['sign-in']} onKeyDown={(e) => { if (e.key === 'Enter') submit(); }}>
            <input className={styles['sign-in-input']} type={'email'} placeholder={'Email'} value={email} autoFocus onChange={(e) => setEmail(e.target.value)} />
            <input className={styles['sign-in-input']} type={'password'} placeholder={'Password'} value={password} onChange={(e) => setPassword(e.target.value)} />
            <div className={styles['sign-in-row']}>
                <button className={classnames(styles['button'], styles['primary'])} onClick={submit} disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
                <button className={styles['button']} onClick={onDone}>{cancelLabel}</button>
            </div>
            {error ? <div className={styles['sign-in-error']}>{error}</div> : null}
            <div className={styles['body']}>Use the same account as on your TV. Signing in on the TV and here keeps both in sync.</div>
        </div>
    );
};

export default SignIn;
