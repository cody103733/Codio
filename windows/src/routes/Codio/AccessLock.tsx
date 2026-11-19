// First-launch access password (lock/README.md). Only active when the build carries access-lock.json
// (assemble.js copies lock/access-lock.json here, or writes null): a salt and a PBKDF2-SHA256 hash, never the password.
// Once the right password is typed this PC stays unlocked; the saved value is the lock's hash, so a new password
// locks it again. To remove: delete lock/access-lock.json, or drop this file and the AccessGate wrapper in App.js.
import React, { useState } from 'react';
import { normalisePasscode } from './vault';

type Lock = { iter?: number, salt: string, hash: string } | null;
const LOCK: Lock = (() => { try { const j = require('./access-lock.json'); return j && j.hash ? j : null; } catch { return null; } })();
const KEY = 'codio_access_unlocked';

const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

const check = async (typed: string): Promise<boolean> => {
    if (!LOCK) return true;
    const base = await crypto.subtle.importKey('raw', new TextEncoder().encode(normalisePasscode(typed)), 'PBKDF2', false, ['deriveBits']);
    const bits = new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: unb64(LOCK.salt), iterations: LOCK.iter || 300000 }, base, 256));
    const want = unb64(LOCK.hash);
    let diff = bits.length ^ want.length;
    for (let i = 0; i < Math.min(bits.length, want.length); i++) diff |= bits[i] ^ want[i];
    if (diff !== 0) return false;
    try { localStorage.setItem(KEY, LOCK.hash); } catch { /* still opens this time */ }
    return true;
};

const isOpen = () => { try { return !LOCK || localStorage.getItem(KEY) === LOCK.hash; } catch { return !LOCK; } };

const box: React.CSSProperties = { width: 360, maxWidth: '90vw', display: 'flex', flexDirection: 'column', alignItems: 'stretch', textAlign: 'center' };
const field: React.CSSProperties = { fontSize: 17, color: '#fff', background: '#1C1C1E', border: '1px solid #38383A', borderRadius: 10, padding: 14, outline: 'none', marginTop: 24 };
const button: React.CSSProperties = { fontSize: 17, fontWeight: 700, color: '#fff', background: '#E50914', border: 'none', borderRadius: 10, height: 48, marginTop: 16, cursor: 'pointer' };

export const AccessGate = ({ children }: { children: React.ReactNode }) => {
    const [open, setOpen] = useState(isOpen);
    const [typed, setTyped] = useState('');
    const [checking, setChecking] = useState(false);
    const [wrong, setWrong] = useState(false);
    if (open) return <>{children}</>;
    const submit = async (e?: React.FormEvent) => {
        e?.preventDefault();
        if (checking || !normalisePasscode(typed)) return;
        setChecking(true);
        setWrong(false);
        const ok = await check(typed).catch(() => false);
        setChecking(false);
        if (ok) setOpen(true); else { setWrong(true); setTyped(''); }
    };
    return (
        <div style={{ position: 'fixed', inset: 0, zIndex: 100000, background: '#000', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: 'system-ui, sans-serif' }}>
            <form style={box} onSubmit={submit}>
                <div style={{ fontSize: 26, fontWeight: 700, color: '#fff' }}>Enter the access password</div>
                <div style={{ fontSize: 15, color: '#B3B3B3', marginTop: 8 }}>You only need to do this once on this PC.</div>
                <input style={field} type="password" placeholder="Password" autoFocus value={typed}
                    onChange={(e) => setTyped(e.target.value)} onKeyDown={(e) => e.stopPropagation()} />
                <button style={{ ...button, opacity: checking ? 0.7 : 1 }} type="submit" disabled={checking}>{checking ? 'Checking…' : 'Unlock'}</button>
                <div style={{ fontSize: 15, color: '#FF5A5F', marginTop: 16, minHeight: 20 }}>{wrong ? 'That password isn\'t right.' : ''}</div>
            </form>
        </div>
    );
};
