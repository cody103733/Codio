// Codio Music: search, what you played lately, the rows your music addons offer, and the addons themselves.

import React, { useEffect, useMemo, useRef, useState } from 'react';
import classnames from 'classnames';
import { useNavigate } from 'react-router';
import { NavBar, Chip, CodioButton, Spinner, Strip, styles } from '../components';
import { musicStyles as m, Tile, TrackRow } from './parts';
import { fetchManifest, loadAddons, loadCatalogs, saveAddons, searchAddon, has, type CatalogRow } from './addons';
import { loadMusicSettings, saveMusicSettings, searchMusicBrainz } from './musicbrainz';
import { playQueue, recentTracks, usePlayer } from './player';
import type { MusicAddon, MusicAlbum, MusicArtist, MusicTrack, SearchResults } from './types';

export const albumHref = (album: { id: string, addon?: string | null }, addons: MusicAddon[]) => {
    const owner = album.addon ? addons.find((a) => a.url === album.addon) : null;
    return `/music/album/${encodeURIComponent(album.id)}${owner ? `?addon=${encodeURIComponent(owner.manifest.id)}` : ''}`;
};

export const artistHref = (artist: { id: string, addon?: string | null }, addons: MusicAddon[]) => {
    const owner = artist.addon ? addons.find((a) => a.url === artist.addon) : null;
    return `/music/artist/${encodeURIComponent(artist.id)}${owner ? `?addon=${encodeURIComponent(owner.manifest.id)}` : ''}`;
};

export const useMusicAddons = () => {
    const [list, setList] = useState<MusicAddon[]>(loadAddons);
    useEffect(() => {
        const refresh = () => setList(loadAddons());
        window.addEventListener('codio-music-addons', refresh);
        return () => window.removeEventListener('codio-music-addons', refresh);
    }, []);
    return list;
};

const describe = (addon: MusicAddon) => [
    addon.manifest.version ? `v${addon.manifest.version}` : null,
    ['search', 'stream', 'catalog', 'isrc', 'resolve'].filter((r) => has(addon, r)).join(', '),
].filter(Boolean).join(' · ');

const AddonsPanel = () => {
    const addons = useMusicAddons();
    const [link, setLink] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [server, setServer] = useState(loadMusicSettings().mbBase);

    const add = async () => {
        if (link.trim().length === 0 || busy) return;
        setBusy(true);
        setError(null);
        try {
            const addon = await fetchManifest(link);
            const current = loadAddons();
            if (current.some((a) => a.manifest.id === addon.manifest.id)) throw new Error('Already installed');
            saveAddons([...current, addon]);
            setLink('');
        } catch (e) {
            setError(e instanceof Error ? e.message : 'Couldn\'t add that addon');
        }
        setBusy(false);
    };
    const move = (index: number, by: number) => {
        const list = loadAddons();
        const target = index + by;
        if (target < 0 || target >= list.length) return;
        [list[index], list[target]] = [list[target], list[index]];
        saveAddons(list);
    };

    return (
        <div>
            <div className={m['section-title']}>Music addons</div>
            <div className={m['note']}>
                A music addon finds the audio for a song. Paste its link to add it. Codio asks them in this order, and plays from the first one that has the song.
                Your addon links stay on this computer and are only sent to the addon itself.
            </div>
            <div className={m['addon-box']}>
                <input
                    className={m['search-input']}
                    placeholder={'Paste a music addon link'}
                    value={link}
                    spellCheck={false}
                    onChange={(e) => { setLink(e.target.value); setError(null); }}
                    onKeyDown={(e) => { if (e.key === 'Enter') void add(); }}
                />
                <CodioButton label={busy ? 'Checking…' : 'Add'} primary onClick={() => void add()} />
            </div>
            {error ? <div className={m['error']}>{error}</div> : null}
            {addons.length === 0 ? <div className={m['note']}>No music addons yet.</div> : null}
            {addons.map((addon, index) => (
                <div key={addon.manifest.id} className={m['addon-item']}>
                    <div className={m['addon-text']}>
                        <div className={m['addon-name']}>{`${index + 1}. ${addon.manifest.name}`}</div>
                        <div className={m['addon-meta']}>{describe(addon)}</div>
                    </div>
                    <button className={m['mini-button']} disabled={index === 0} onClick={() => move(index, -1)}>Up</button>
                    <button className={m['mini-button']} disabled={index === addons.length - 1} onClick={() => move(index, 1)}>Down</button>
                    <button className={m['mini-button']} onClick={() => saveAddons(loadAddons().filter((a) => a.manifest.id !== addon.manifest.id))}>Remove</button>
                </div>
            ))}
            <div className={m['section-title']}>Music information</div>
            <div className={m['note']}>
                Artists, albums and covers come from MusicBrainz. If you run your own MusicBrainz server, put its address here. Leave it empty to use musicbrainz.org.
            </div>
            <div className={m['addon-box']}>
                <input
                    className={m['search-input']}
                    placeholder={'https://musicbrainz.org'}
                    value={server}
                    spellCheck={false}
                    onChange={(e) => setServer(e.target.value)}
                    onBlur={() => saveMusicSettings({ mbBase: server.trim() })}
                />
            </div>
        </div>
    );
};

const Results = ({ results, addons, onPlay, current }: { results: SearchResults, addons: MusicAddon[], onPlay: (tracks: MusicTrack[], index: number) => void, current: string | null }) => {
    const navigate = useNavigate();
    return (
        <div>
            {
                results.artists.length > 0 ?
                    <>
                        <div className={m['section-title']}>Artists</div>
                        <Strip className={m['row-strip']}>
                            {results.artists.map((a: MusicArtist) => <Tile key={`${a.addon}/${a.id}`} round title={a.name} sub={a.detail} art={a.artworkURL} onClick={() => navigate(artistHref(a, addons))} />)}
                        </Strip>
                    </>
                    : null
            }
            {
                results.albums.length > 0 ?
                    <>
                        <div className={m['section-title']}>Albums</div>
                        <Strip className={m['row-strip']}>
                            {results.albums.map((a: MusicAlbum) => <Tile key={`${a.addon}/${a.id}`} title={a.title} sub={[a.artist, a.year].filter(Boolean).join(' · ')} art={a.artworkURL} onClick={() => navigate(albumHref(a, addons))} />)}
                        </Strip>
                    </>
                    : null
            }
            {
                results.tracks.length > 0 ?
                    <>
                        <div className={m['section-title']}>Songs</div>
                        <div className={m['tracks']}>
                            {results.tracks.map((t, i) => <TrackRow key={`${t.addon}/${t.id}`} track={t} current={current === t.id} onPlay={() => onPlay(results.tracks, i)} />)}
                        </div>
                    </>
                    : null
            }
        </div>
    );
};

const Music = () => {
    const addons = useMusicAddons();
    const navigate = useNavigate();
    const player = usePlayer();
    const [query, setQuery] = useState('');
    const [view, setView] = useState<'browse' | 'addons'>('browse');
    const [results, setResults] = useState<SearchResults | null>(null);
    const [searching, setSearching] = useState(false);
    const [rows, setRows] = useState<CatalogRow[]>([]);
    const request = useRef(0);
    const recent = useMemo(() => recentTracks(), [player.index]);
    const input = useRef<HTMLInputElement>(null);

    useEffect(() => { input.current?.focus(); }, []);

    useEffect(() => {
        let cancelled = false;
        loadCatalogs(addons).then((list) => { if (!cancelled) setRows(list); }, () => undefined);
        return () => { cancelled = true; };
    }, [addons]);

    useEffect(() => {
        const q = query.trim();
        const timer = setTimeout(() => {
            const id = ++request.current;
            if (q.length === 0) {
                setResults(null);
                setSearching(false);
                return;
            }
            setSearching(true);
            Promise.all([
                searchMusicBrainz(q).catch((): SearchResults => ({ artists: [], albums: [], tracks: [] })),
                ...addons.filter((a) => has(a, 'search')).map((a) => searchAddon(a, q).catch((): SearchResults => ({ artists: [], albums: [], tracks: [] }))),
            ]).then((all) => {
                if (id !== request.current) return;
                const [mb, ...fromAddons] = all;
                setResults({
                    artists: [...mb.artists, ...fromAddons.flatMap((r) => r.artists)],
                    albums: [...mb.albums, ...fromAddons.flatMap((r) => r.albums)],
                    // Songs from MusicBrainz play through any addon; an addon's own songs play from that addon first.
                    tracks: [...mb.tracks, ...fromAddons.flatMap((r) => r.tracks)],
                });
                setSearching(false);
            });
        }, 500);
        return () => clearTimeout(timer);
    }, [query, addons]);

    const current = player.queue[player.index]?.id ?? null;
    const empty = results !== null && results.artists.length + results.albums.length + results.tracks.length === 0;

    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div className={styles['page-scroll']}>
                <div className={m['music']}>
                    <div className={m['head']}>
                        <div className={m['title']}>Music</div>
                        <input
                            ref={input}
                            className={m['search-input']}
                            value={query}
                            placeholder={'Search artists, albums and songs'}
                            spellCheck={false}
                            maxLength={80}
                            onChange={(e) => { setQuery(e.target.value); if (view !== 'browse') setView('browse'); }}
                        />
                        <Chip label={'Addons'} selected={view === 'addons'} onClick={() => setView(view === 'addons' ? 'browse' : 'addons')} />
                    </div>
                    {
                        view === 'addons' ? <AddonsPanel /> : (
                            <>
                                {addons.length === 0 ?
                                    <div className={m['note']}>
                                        Search works now. To play songs, add a music addon first.
                                        <span className={classnames(m['link'])} onClick={() => setView('addons')}>{' Add one'}</span>.
                                    </div>
                                    : null}
                                {searching && !results ? <div className={m['center']}><Spinner /></div> : null}
                                {empty ? <div className={m['note']}>{`Nothing found for “${query.trim()}”.`}</div> : null}
                                {results ? <Results results={results} addons={addons} current={current} onPlay={(tracks, i) => playQueue(tracks, i)} /> : null}
                                {
                                    !results ?
                                        <>
                                            {
                                                recent.length > 0 ?
                                                    <>
                                                        <div className={m['section-title']}>Recently played</div>
                                                        <Strip className={m['row-strip']}>
                                                            {recent.map((t) => <Tile key={`${t.addon}/${t.id}`} title={t.title} sub={t.artist} art={t.artworkURL} onClick={() => playQueue(recent, recent.indexOf(t))} />)}
                                                        </Strip>
                                                    </>
                                                    : null
                                            }
                                            {rows.map((row) => (
                                                <div key={`${row.addon.url}/${row.id}`}>
                                                    <div className={m['section-title']}>{row.name}</div>
                                                    <Strip className={m['row-strip']}>
                                                        {row.items.map((item: any) => row.type === 'album' ?
                                                            <Tile key={item.id} title={item.title} sub={item.artist} art={item.artworkURL} onClick={() => navigate(albumHref(item, addons))} />
                                                            : row.type === 'artist' ?
                                                                <Tile key={item.id} round title={item.name} sub={item.detail} art={item.artworkURL} onClick={() => navigate(artistHref(item, addons))} />
                                                                : <Tile key={item.id} title={item.title} sub={item.artist} art={item.artworkURL} onClick={() => playQueue(row.items as MusicTrack[], row.items.indexOf(item))} />)}
                                                    </Strip>
                                                </div>
                                            ))}
                                            {recent.length === 0 && rows.length === 0 ? <div className={m['note']}>Search for an artist, an album or a song to start.</div> : null}
                                        </>
                                        : null
                                }
                            </>
                        )
                    }
                </div>
            </div>
            <NavBar current={3} solid />
        </div>
    );
};

export default Music;
