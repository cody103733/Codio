// Codio for Windows: update check and install (the server side is desktop/codio-update.js).
import { useCallback, useEffect, useRef, useState } from 'react';

export type UpdateState =
    | { phase: 'checking' }
    | { phase: 'current' }
    | { phase: 'available', version: string, notes: string }
    | { phase: 'downloading', version: string, percent: number }
    | { phase: 'installing', version: string }
    | { phase: 'error', message: string, version?: string };

const newer = (a: string, b: string) => {
    const x = a.split('.').map((n) => parseInt(n, 10) || 0);
    const y = b.split('.').map((n) => parseInt(n, 10) || 0);
    for (let i = 0; i < Math.max(x.length, y.length); i++) {
        if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
    }
    return false;
};

const call = async (path: string, method = 'GET') => {
    const response = await fetch(path, { method, headers: { 'X-Codio': '1' }, cache: 'no-store' });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(body?.error || `Codio answered ${response.status}`);
    return body;
};

const shellInstall = (version: string) => {
    const ipc = (window as any).chrome?.webview;
    if (!ipc) return false;
    ipc.postMessage(JSON.stringify({ id: 0, type: 6, args: ['install-update', version] }));
    return true;
};

export const useUpdate = (current: string) => {
    const [state, setState] = useState<UpdateState>({ phase: 'checking' });
    const timer = useRef<number | null>(null);

    const stopPolling = () => {
        if (timer.current !== null) window.clearInterval(timer.current);
        timer.current = null;
    };

    const check = useCallback(() => {
        setState({ phase: 'checking' });
        call('/api/update')
            .then((body) => {
                const latest = body?.latest;
                if (latest && current && newer(latest.version, current)) setState({ phase: 'available', version: latest.version, notes: latest.notes || '' });
                else setState({ phase: 'current' });
            })
            .catch(() => setState({ phase: 'error', message: 'Couldn\'t reach the update list. Check your internet and try again.' }));
    }, [current]);

    const install = useCallback(() => {
        if (state.phase !== 'available' && !(state.phase === 'error' && state.version)) {
            check();
            return;
        }
        const version = String((state as { version?: string }).version);
        setState({ phase: 'downloading', version, percent: 0 });
        stopPolling();
        const poll = () => {
            call('/api/update/status')
                .then((status) => {
                    if (status.phase === 'ready') {
                        stopPolling();
                        setState({ phase: 'installing', version });
                        if (!shellInstall(status.version || version)) setState({ phase: 'error', version, message: 'Updating only works in the Codio app.' });
                    } else if (status.phase === 'error') {
                        stopPolling();
                        setState({ phase: 'error', version, message: `The download failed (${status.error}). Press Enter to try again.` });
                    } else if (status.phase === 'downloading') {
                        const percent = status.total ? Math.floor(status.received * 100 / status.total) : 0;
                        setState({ phase: 'downloading', version, percent });
                    }
                })
                .catch(() => undefined);
        };
        call('/api/update/download', 'POST')
            .then(() => { timer.current = window.setInterval(poll, 500); poll(); })
            .catch((error) => setState({ phase: 'error', version, message: String(error?.message || error) }));
    }, [state, check]);

    useEffect(() => {
        check();
        const ipc = (window as any).chrome?.webview;
        const onMessage = (event: MessageEvent) => {
            try {
                const data = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
                if (data?.type === 1 && Array.isArray(data.args) && data.args[0] === 'codio-update-failed') {
                    setState({ phase: 'error', message: `The installer didn't start (${data.args[1]}).` });
                }
            } catch (_e) { /* not for us */ }
        };
        ipc?.addEventListener('message', onMessage);
        return () => {
            stopPolling();
            ipc?.removeEventListener('message', onMessage);
        };
    }, [check]);

    return { state, check, install };
};

export const updateLabel = (state: UpdateState) => {
    switch (state.phase) {
        case 'checking': return 'Checking…';
        case 'current': return 'Up to date';
        case 'available': return `Update to ${state.version}`;
        case 'downloading': return `Downloading ${state.percent}%`;
        case 'installing': return 'Installing…';
        case 'error': return state.version ? 'Try again' : 'Check again';
    }
};

export const updateHelp = (state: UpdateState) => {
    switch (state.phase) {
        case 'checking': return 'Looking for a newer Codio.';
        case 'current': return 'You have the newest Codio. Press Enter to check again.';
        case 'available': return `Codio ${state.version} is ready to install. Press Enter: it downloads, Codio closes, updates itself and opens again in a few seconds.${state.notes ? `\n\nWhat's new: ${state.notes}` : ''}`;
        case 'downloading': return `Downloading Codio ${state.version}. Codio closes and updates itself when the download is done.`;
        case 'installing': return 'Codio is closing to update and opens again by itself in a few seconds.';
        case 'error': return state.message;
    }
};
