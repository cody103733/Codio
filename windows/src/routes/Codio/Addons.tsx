// Codio Addons: your addons (catalogs and sources), more addons to install, and install by link.

import React, { useEffect, useMemo, useState } from 'react';
import classnames from 'classnames';
import { withCoreSuspender, useModelState, useProfile, usePlatform } from 'stremio/common';
import { useCore } from 'stremio/core';
import { typeLabel } from './api';
import { NavBar, Chip, CodioButton, Spinner, Strip, styles } from './components';

type Descriptor = {
    transportUrl: string,
    manifest: any,
    flags?: { official?: boolean, protected?: boolean },
};

type Source = { key: string, label: string, base: string | null, type: string, id: string };

const describe = (manifest: any) => {
    const types = Array.isArray(manifest?.types) ? manifest.types.map((t: string) => typeLabel(t)) : [];
    return [manifest?.version ? `v${manifest.version}` : null, types.length > 0 ? types.join(', ') : null].filter(Boolean).join(' · ');
};

const AddonRow = ({ addon, installed, onInstall, onUninstall, onConfigure }: { addon: Descriptor, installed: boolean, onInstall: () => void, onUninstall: () => void, onConfigure: (() => void) | null }) => {
    const [logoFailed, setLogoFailed] = useState(false);
    const manifest = addon.manifest ?? {};
    const locked = !!addon.flags?.protected;
    return (
        <div className={styles['addon-row']}>
            <div className={styles['addon-logo']}>
                {
                    typeof manifest.logo === 'string' && !logoFailed ?
                        <img src={manifest.logo} alt={''} loading={'lazy'} onError={() => setLogoFailed(true)} />
                        : <span>{String(manifest.name ?? '?').charAt(0).toUpperCase()}</span>
                }
            </div>
            <div className={styles['addon-text']}>
                <div className={styles['addon-name']}>{manifest.name ?? addon.transportUrl}</div>
                <div className={styles['addon-meta']}>{describe(manifest)}</div>
                {manifest.description ? <div className={classnames(styles['addon-description'], styles['lines-2'])}>{manifest.description}</div> : null}
            </div>
            <div className={styles['addon-actions']}>
                {onConfigure ? <CodioButton label={'Configure'} onClick={onConfigure} /> : null}
                {
                    installed ?
                        locked ? <span className={styles['addon-locked']}>Built in</span> : <CodioButton label={'Remove'} onClick={onUninstall} />
                        : <CodioButton label={'Install'} primary onClick={onInstall} />
                }
            </div>
        </div>
    );
};

const Addons = () => {
    const core = useCore() as any;
    const platform = usePlatform() as any;
    const profile = useProfile() as any;
    const installed: Descriptor[] = useMemo(() => (Array.isArray(profile?.addons) ? profile.addons : []).filter((a: any) => a && a.manifest), [profile?.addons]);
    const installedUrls = useMemo(() => new Set(installed.map((a) => a.transportUrl)), [installed]);

    // Lists of more addons: the addon catalogs your addons offer (e.g. Cinemeta's community list).
    const sources: Source[] = useMemo(() => {
        const list: Source[] = [{ key: 'installed', label: 'My addons', base: null, type: '', id: '' }];
        installed.forEach((a) => (Array.isArray(a.manifest.addonCatalogs) ? a.manifest.addonCatalogs : []).forEach((c: any) => {
            if (!c || typeof c.id !== 'string' || typeof c.type !== 'string') return;
            const name = typeof c.name === 'string' && c.name.length > 0 ? c.name : 'More addons';
            list.push({ key: `${a.transportUrl}|${c.type}|${c.id}`, label: `${name} · ${typeLabel(c.type)}`, base: a.transportUrl, type: c.type, id: c.id });
        }));
        return list;
    }, [installed]);
    const [sourceKey, setSourceKey] = useState('installed');
    const source = sources.find((s) => s.key === sourceKey) ?? sources[0];

    const remoteAction = useMemo(() => source.base ? {
        action: 'Load',
        args: { model: 'CatalogWithFilters', args: { request: { base: source.base, path: { resource: 'addon_catalog', type: source.type, id: source.id, extra: [] } } } },
    } : { action: 'Unload' }, [source]);
    const remote = (useModelState as any)({ model: 'remote_addons', action: remoteAction, deps: ['ctx'] }) as any;
    const remoteContent = remote?.catalog?.content ?? null;

    // Install by link.
    const [link, setLink] = useState('');
    const [linkUrl, setLinkUrl] = useState<string | null>(null);
    const [linkError, setLinkError] = useState<string | null>(null);
    const detailsAction = useMemo(() => linkUrl ? { action: 'Load', args: { model: 'AddonDetails', args: { transportUrl: linkUrl } } } : { action: 'Unload' }, [linkUrl]);
    const details = (useModelState as any)({ model: 'addon_details', action: detailsAction }) as any;
    const linkAddon = details?.remoteAddon?.content ?? null;

    const install = (addon: Descriptor) => core.transport.dispatch({ action: 'Ctx', args: { action: 'InstallAddon', args: addon } });
    const uninstall = (addon: Descriptor) => core.transport.dispatch({ action: 'Ctx', args: { action: 'UninstallAddon', args: addon } });
    const configure = (addon: Descriptor) => addon.manifest?.behaviorHints?.configurable ?
        () => platform.openExternal(addon.transportUrl.replace('manifest.json', 'configure')) : null;

    const submitLink = () => {
        let text = link.trim();
        if (text.startsWith('stremio://')) text = `https://${text.slice('stremio://'.length)}`;
        try {
            const url = new URL(text);
            if (url.protocol !== 'https:' && url.protocol !== 'http:') throw new Error('protocol');
            setLinkError(null);
            setLinkUrl(url.toString());
        } catch (_e) {
            setLinkError('That doesn\'t look like an addon link. It usually ends in manifest.json.');
        }
    };

    useEffect(() => {
        if (linkAddon?.type === 'Err') setLinkError('Couldn\'t open that addon link. Check it and try again.');
    }, [linkAddon?.type]);

    const linkReady: Descriptor | null = linkAddon?.type === 'Ready' ? linkAddon.content : null;

    const list: Descriptor[] = source.base ?
        (remoteContent?.type === 'Ready' ? remoteContent.content : [])
        : installed;

    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div className={styles['page-scroll']}>
                <div className={styles['addons']}>
                    <div className={styles['addon-link']}>
                        <input
                            className={styles['addon-link-input']}
                            placeholder={'Paste an addon link to install it'}
                            value={link}
                            spellCheck={false}
                            onChange={(e) => { setLink(e.target.value); setLinkError(null); }}
                            onKeyDown={(e) => { if (e.key === 'Enter') submitLink(); }}
                        />
                        <CodioButton label={'Open'} onClick={submitLink} />
                    </div>
                    {linkError ? <div className={styles['addon-link-error']}>{linkError}</div> : null}
                    {linkUrl && !linkReady && !linkError ? <div className={styles['addon-link-error']}><Spinner size={22} /></div> : null}
                    {
                        linkReady ?
                            <div className={styles['addon-list']}>
                                <AddonRow
                                    addon={linkReady}
                                    installed={installedUrls.has(linkReady.transportUrl)}
                                    onInstall={() => { install(linkReady); setLink(''); setLinkUrl(null); }}
                                    onUninstall={() => uninstall(linkReady)}
                                    onConfigure={configure(linkReady)}
                                />
                            </div>
                            : null
                    }
                    {
                        sources.length > 1 ?
                            <Strip className={styles['chip-strip']}>
                                {sources.map((s) => <Chip key={s.key} label={s.label} selected={s.key === source.key} onClick={() => setSourceKey(s.key)} />)}
                            </Strip>
                            : null
                    }
                    <div className={styles['addon-list']}>
                        {
                            list.map((addon) => (
                                <AddonRow
                                    key={addon.transportUrl}
                                    addon={addon}
                                    installed={installedUrls.has(addon.transportUrl)}
                                    onInstall={() => install(addon)}
                                    onUninstall={() => uninstall(installed.find((a) => a.transportUrl === addon.transportUrl) ?? addon)}
                                    onConfigure={configure(addon)}
                                />
                            ))
                        }
                    </div>
                    {
                        list.length === 0 ?
                            <div className={styles['empty']}>
                                {
                                    source.base ?
                                        remoteContent?.type === 'Err' ? 'Couldn\'t load this list. Check the connection and try again.' : <Spinner />
                                        : 'No addons yet. Paste an addon link above to add one.'
                                }
                            </div>
                            : null
                    }
                </div>
            </div>
            <NavBar current={5} solid />
        </div>
    );
};

export default withCoreSuspender(Addons);
