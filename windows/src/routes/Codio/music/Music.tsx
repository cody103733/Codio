// Codio Music: search, what you played lately, the rows your music addons offer, and the addons themselves.

import React, { useEffect, useMemo, useRef, useState } from 'react';
import classnames from 'classnames';
import { useNavigate } from 'react-router';
import { NavBar, Chip, CodioButton, Spinner, Strip, styles } from '../components';
import { musicStyles as m, Tile, TrackRow } from './parts';
import { fetchManifest, loadAddons, loadCatalogs, saveAddons, searchAddon, has, type CatalogRow } from './addons';
import { loadMusicSettings, saveMusicSettings, searchMusicBrainz } from './musicbrainz';
import { moreFrom, newReleases, popularAlbums, popularArtists, popularSongs, similarArtists } from './discovery';
import { clearAll, clearHistory, dayShuffle, getTaste, hideAlbum, hideArtist, isLikedAlbum, isLikedArtist, mergeSimilar, toggleLikeAlbum, toggleLikeArtist, topSeeds, unhide, unhideAll, useTaste, visibleAlbums, visibleArtists, visibleTracks, type Seed } from './taste';
import { getTopTracks } from './musicbrainz';
import { playQueue, recentTracks, usePlayer } from './player';
import { hideTrack, toggleLikeTrack } from './taste';
import type { MusicAddon, MusicAlbum, MusicArtist, MusicTrack, SearchResults } from './types';

export const albumHref = (album: { id: string, addon?: string | null }, addons: MusicAddon[]) => {
    const owner = album.addon ? addons.find((a) => a.url === album.addon) : null;
    return `/music/album/${encodeURIComponent(album.id)}${owner ? `?addon=${encodeURIComponent(owner.manifest.id)}` : ''}`;
};

export const artistHref = (artist: { id: string, addon?: string | null }, addons: MusicAddon[]) => {
    const owner = artist.addon ? addons.find((a) => a.url === artist.addon) : null;
    return `/music/artist/${encodeURIComponent(artist.id)}${owner ? `?addon=${encodeURIComponent(owner.manifest.id)}` : ''}`;
};

// Opening an album or artist from a tile hands over what the tile knows, so the page shows its heading at once.
export const openAlbum = (navigate: (to: string, o?: any) => void, album: MusicAlbum, addons: MusicAddon[]) =>
    navigate(albumHref(album, addons), { state: { title: album.title, artist: album.artist, art: album.artworkURL, year: album.year } });
export const openArtist = (navigate: (to: string, o?: any) => void, artist: MusicArtist, addons: MusicAddon[]) =>
    navigate(artistHref(artist, addons), { state: { name: artist.name } });

// Tiles that carry the heart and "not interested" buttons.
const AlbumTile = ({ album, addons, taste }: { album: MusicAlbum, addons: MusicAddon[], taste: ReturnType<typeof useTaste> }) => {
    const navigate = useNavigate();
    return <Tile
        title={album.title} sub={[album.artist, album.year].filter(Boolean).join(' · ')} art={album.artworkURL}
        onClick={() => openAlbum(navigate, album, addons)}
        liked={isLikedAlbum(taste, album)} onLike={() => toggleLikeAlbum(album)} onHide={() => hideAlbum(album)}
    />;
};

// MusicBrainz has no artist photos, so an artist is shown by the cover of their most played album, looked up when the tile
// comes into view (Codio's server keeps the answers).
const artistArtCache = new Map<string, Promise<string | null>>();
const artistArt = (id: string) => {
    if (!artistArtCache.has(id)) artistArtCache.set(id, moreFrom(id).then((albums) => albums.find((a) => a.artworkURL)?.artworkURL ?? null, () => null));
    return artistArtCache.get(id)!;
};

const ArtistTile = ({ artist, addons, taste }: { artist: MusicArtist, addons: MusicAddon[], taste: ReturnType<typeof useTaste> }) => {
    const navigate = useNavigate();
    const box = useRef<HTMLDivElement>(null);
    const [art, setArt] = useState<string | null>(artist.artworkURL ?? null);
    useEffect(() => {
        setArt(artist.artworkURL ?? null);
        if (artist.artworkURL || artist.addon || !box.current) return undefined;
        let cancelled = false;
        const load = () => { artistArt(artist.id).then((url) => { if (!cancelled && url) setArt(url); }); };
        if (typeof IntersectionObserver === 'undefined') { load(); return undefined; }
        const watcher = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) { watcher.disconnect(); load(); } }, { rootMargin: '300px' });
        watcher.observe(box.current);
        return () => { cancelled = true; watcher.disconnect(); };
    }, [artist.id, artist.artworkURL, artist.addon]);
    return <Tile
        boxRef={box} round title={artist.name} sub={artist.detail} art={art}
        onClick={() => openArtist(navigate, artist, addons)}
        liked={isLikedArtist(taste, artist)} onLike={() => toggleLikeArtist(artist)} onHide={() => hideArtist(artist)}
    />;
};

// A row that loads by itself and keeps its answer for the rest of the run.
const rowCache = new Map<string, unknown>();
const useRow = <T,>(key: string, load: () => Promise<T[]>): T[] | null => {
    const [rows, setRows] = useState<T[] | null>(() => (rowCache.get(key) as T[] | undefined) ?? null);
    useEffect(() => {
        if (rowCache.has(key)) { setRows(rowCache.get(key) as T[]); return; }
        let cancelled = false;
        load().then((r) => { rowCache.set(key, r); if (!cancelled) setRows(r); }, () => { if (!cancelled) setRows([]); });
        return () => { cancelled = true; };
    }, [key]);
    return rows;
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

// What Codio has learned about your taste, and how to change it. It lives on this computer only.
const TastePanel = () => {
    const taste = useTaste();
    const [sure, setSure] = useState(false);
    const hidden: { kind: 'artist' | 'album' | 'track', key: string, name: string }[] = [
        ...Object.entries(taste.hiddenArtists).map(([key, name]) => ({ kind: 'artist' as const, key, name })),
        ...Object.entries(taste.hiddenAlbums).map(([key, name]) => ({ kind: 'album' as const, key, name })),
        ...Object.entries(taste.hiddenTracks).map(([key, name]) => ({ kind: 'track' as const, key, name })),
    ];
    return (
        <div>
            <div className={m['section-title']}>Your taste</div>
            <div className={m['note']}>
                Music's rows follow what you play, heart and hide. It all stays on this computer. Play counts so far: {Object.keys(taste.plays).length} artists. Hearted: {taste.likedTracks.length} songs, {taste.likedAlbums.length} albums, {taste.likedArtists.length} artists.
            </div>
            <div className={m['addon-box']}>
                <button className={m['mini-button']} onClick={clearHistory}>Forget what I've played</button>
                <button className={m['mini-button']} onClick={() => { if (sure) { clearAll(); setSure(false); } else setSure(true); }}>{sure ? 'Tap again to forget everything' : 'Start over'}</button>
                {hidden.length > 0 ? <button className={m['mini-button']} onClick={unhideAll}>Show all hidden again</button> : null}
            </div>
            {hidden.length > 0 ? <div className={m['note']}>Hidden with "Not interested":</div> : null}
            {hidden.map((h) => (
                <div key={`${h.kind}/${h.key}`} className={m['taste-line']}>
                    <span className={m['taste-name']}>{`${h.name} (${h.kind})`}</span>
                    <button className={m['mini-button']} onClick={() => unhide(h.kind, h.key)}>Show again</button>
                </div>
            ))}
        </div>
    );
};

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
            <TastePanel />
        </div>
    );
};

const Results = ({ results, addons, onPlay, current }: { results: SearchResults, addons: MusicAddon[], onPlay: (tracks: MusicTrack[], index: number) => void, current: string | null }) => {
    const taste = useTaste();
    return (
        <div>
            {
                results.artists.length > 0 ?
                    <>
                        <div className={m['section-title']}>Artists</div>
                        <Strip className={m['row-strip']}>
                            {results.artists.map((a: MusicArtist) => <ArtistTile key={`${a.addon}/${a.id}`} artist={a} addons={addons} taste={taste} />)}
                        </Strip>
                    </>
                    : null
            }
            {
                results.albums.length > 0 ?
                    <>
                        <div className={m['section-title']}>Albums</div>
                        <Strip className={m['row-strip']}>
                            {results.albums.map((a: MusicAlbum) => <AlbumTile key={`${a.addon}/${a.id}`} album={a} addons={addons} taste={taste} />)}
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

// The Music home's rows. First the ones made from you (what you play, heart and hide), then what is popular and new.
const Discover = ({ addons, recent }: { addons: MusicAddon[], recent: MusicTrack[] }) => {
    const navigate = useNavigate();
    const taste = useTaste();
    const player = usePlayer();
    const current = player.queue[player.index]?.id ?? null;
    // Your favorite artists, best first (plays, hearts). The key changes only when the order of the top ones does.
    const seeds = useMemo(() => topSeeds(taste, 5), [taste.plays, taste.likedArtists, taste.likedTracks, taste.likedAlbums, taste.hiddenArtists]);
    const seedKey = seeds.map((s) => s.id).join(',');

    // Artists like each favorite (one call each; Codio's server keeps the answers for a week).
    const sims = [0, 1, 2, 3, 4].map((i) => useRow(`similar-${seeds[i]?.id ?? `none${i}`}`, () => seeds[i] ? similarArtists(seeds[i].id) : Promise.resolve([])));
    const more = useRow(`more-${seeds[0]?.id ?? ''}`, () => seeds[0] ? moreFrom(seeds[0].id) : Promise.resolve([]));
    const merged = useMemo(() => mergeSimilar(seeds, sims.map((r) => r ?? []), taste), [seedKey, ...sims, taste.hiddenArtists]);

    // Daily mix: a few songs each from your favorites and artists like them, shuffled the same all day.
    const mixSources = useMemo(() => [...seeds.slice(0, 4).map((s) => ({ id: s.id, name: s.name })), ...merged.slice(0, 4).map((a) => ({ id: a.id, name: a.name }))], [seedKey, merged.map((a) => a.id).join(',')]);
    const waiting = sims.slice(0, seeds.length).some((r) => r === null);
    const mixKey = waiting ? 'mix-wait' : `mix-${new Date().toDateString()}-${mixSources.map((x) => x.id).join(',')}`;
    const mixRaw = useRow(mixKey, async () => {
        if (mixSources.length < 2) return [];
        const lists = await Promise.all(mixSources.map((x) => getTopTracks(x.id, x.name)));
        const out: MusicTrack[] = [];
        for (let r = 0; r < 5; r++) lists.forEach((l) => { if (l[r]) out.push(l[r]); });
        return out;
    });
    const mix = useMemo(() => dayShuffle(visibleTracks(taste, mixRaw ?? []), 'mix').slice(0, 30), [mixRaw, taste.hiddenTracks, taste.hiddenArtists]);

    const albums = useRow('popular-albums', popularAlbums);
    const freshAll = useRow('new-releases', newReleases);
    const songs = useRow('popular-songs', popularSongs);
    const artists = useRow('popular-artists', popularArtists);
    const loading = [albums, freshAll, songs, artists].every((r) => r === null);

    // Artists you like and ones like them: popular and new rows lead with their music.
    const liked = useMemo(() => new Set([...seeds.map((s) => s.id), ...taste.likedArtists.map((a) => a.id), ...merged.map((a) => a.id)]), [seedKey, taste.likedArtists, merged]);
    const favor = (list: MusicAlbum[] | null) => {
        const visible = visibleAlbums(taste, list ?? []);
        return [...visible.filter((a) => a.artistMbid && liked.has(a.artistMbid)), ...visible.filter((a) => !(a.artistMbid && liked.has(a.artistMbid)))];
    };
    const forYou = useMemo(() => visibleAlbums(taste, freshAll ?? []).filter((a) => a.artistMbid && liked.has(a.artistMbid)).slice(0, 24), [freshAll, liked, taste.hiddenAlbums, taste.hiddenArtists]);
    const fresh = useMemo(() => favor(freshAll).slice(0, 30), [freshAll, liked, taste.hiddenAlbums, taste.hiddenArtists]);
    const popular = useMemo(() => favor(albums), [albums, liked, taste.hiddenAlbums, taste.hiddenArtists]);
    const topSongs = visibleTracks(taste, songs ?? []);
    const recentShown = visibleTracks(taste, recent);
    const likedSongs = taste.likedTracks;

    const artistRow = (title: string, list: MusicArtist[] | null) => {
        const shown = visibleArtists(taste, list ?? []);
        return shown.length > 0 ? (
            <div key={title}>
                <div className={m['section-title']}>{title}</div>
                <Strip className={m['row-strip']}>
                    {shown.map((a) => <ArtistTile key={a.id} artist={a} addons={addons} taste={taste} />)}
                </Strip>
            </div>
        ) : null;
    };
    const albumRow = (title: string, list: MusicAlbum[] | null) => list && list.length > 0 ? (
        <div key={title}>
            <div className={m['section-title']}>{title}</div>
            <Strip className={m['row-strip']}>
                {list.map((a) => <AlbumTile key={a.id} album={a} addons={addons} taste={taste} />)}
            </Strip>
        </div>
    ) : null;
    const trackRow = (title: string, list: MusicTrack[], withPlay = false) => list.length > 0 ? (
        <div key={title}>
            <div className={m['section-head']}>
                <div className={m['section-title']}>{title}</div>
                {withPlay ? <button className={m['mini-button']} onClick={() => playQueue(list, 0, false)}>Play all</button> : null}
            </div>
            <Strip className={m['row-strip']}>
                {list.map((t, i) => <Tile key={`${t.addon}/${t.id}`} title={t.title} sub={t.artist} art={t.artworkURL} onClick={() => playQueue(list, i)}
                    liked={taste.likedTracks.some((x) => x.id === t.id && x.addon === t.addon)} onLike={() => toggleLikeTrack(t)} onHide={() => hideTrack(t)} />)}
            </Strip>
        </div>
    ) : null;

    return (
        <>
            {trackRow('Daily mix', mix, true)}
            {trackRow('Recently played', recentShown)}
            {seeds[0] ? artistRow(`Because you played ${seeds[0].name}`, sims[0]) : null}
            {seeds[0] ? albumRow(`More from ${seeds[0].name}`, visibleAlbums(taste, more ?? [])) : null}
            {merged.length > 0 ? artistRow('Similar to your favorites', merged) : null}
            {albumRow('New from artists you like', forYou)}
            {seeds[1] ? artistRow(`Because you played ${seeds[1].name}`, sims[1]) : null}
            {trackRow('Liked songs', likedSongs, true)}
            {albumRow('Popular right now', popular)}
            {albumRow('New releases', fresh)}
            {topSongs.length > 0 ? (
                <>
                    <div className={m['section-title']}>Songs people are playing</div>
                    <div className={m['tracks']}>
                        {topSongs.slice(0, 10).map((t, i) => <TrackRow key={t.id} track={t} number={i + 1} canHide current={current === t.id} onPlay={() => playQueue(topSongs, i)} />)}
                    </div>
                </>
            ) : null}
            {artistRow('Popular artists', artists)}
            {loading ? <div className={m['center']}><Spinner /></div> : null}
            {!loading && [albums, freshAll, songs, artists].every((r) => r !== null && r.length === 0) && recent.length === 0 ?
                <div className={m['note']}>Couldn't load ideas right now (no connection?). Search for an artist, an album or a song to start.</div> : null}
            {seeds.length === 0 && !loading ?
                <div className={m['note']}>Play and heart songs, and this page starts to fit you: daily mixes, artists like the ones you play, new releases from them.</div> : null}
        </>
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
            // Results show as each source answers: addons (fast) and MusicBrainz (artists, albums, songs in turn).
            const none = (): SearchResults => ({ artists: [], albums: [], tracks: [] });
            let mb = none();
            const fromAddons: SearchResults[] = [];
            const show = () => {
                if (id !== request.current) return;
                setResults({
                    artists: [...mb.artists, ...fromAddons.flatMap((r) => r.artists)],
                    albums: [...mb.albums, ...fromAddons.flatMap((r) => r.albums)],
                    // Songs from MusicBrainz play through any addon; an addon's own songs play from that addon first.
                    tracks: [...mb.tracks, ...fromAddons.flatMap((r) => r.tracks)],
                });
            };
            Promise.all([
                searchMusicBrainz(q, (partial) => { mb = partial; show(); }).catch(none),
                ...addons.filter((a) => has(a, 'search')).map((a) => searchAddon(a, q).catch(none).then((r) => { fromAddons.push(r); show(); })),
            ]).then(() => { if (id === request.current) setSearching(false); });
        }, 350);
        return () => clearTimeout(timer);
    }, [query, addons]);

    const current = player.queue[player.index]?.id ?? null;
    const none = results !== null && !searching && results.artists.length + results.albums.length + results.tracks.length === 0;
    const hasResults = results !== null && results.artists.length + results.albums.length + results.tracks.length > 0;

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
                                {searching && !hasResults ? <div className={m['center']}><Spinner /></div> : null}
                                {none ? <div className={m['note']}>{`Nothing found for “${query.trim()}”.`}</div> : null}
                                {results ? <Results results={results} addons={addons} current={current} onPlay={(tracks, i) => playQueue(tracks, i)} /> : null}
                                {
                                    !results ?
                                        <>
                                            <Discover addons={addons} recent={recent} />
                                            {rows.map((row) => (
                                                <div key={`${row.addon.url}/${row.id}`}>
                                                    <div className={m['section-title']}>{row.name}</div>
                                                    <Strip className={m['row-strip']}>
                                                        {row.items.map((item: any) => row.type === 'album' ?
                                                            <Tile key={item.id} title={item.title} sub={item.artist} art={item.artworkURL} onClick={() => openAlbum(navigate, item, addons)} />
                                                            : row.type === 'artist' ?
                                                                <Tile key={item.id} round title={item.name} sub={item.detail} art={item.artworkURL} onClick={() => openArtist(navigate, item, addons)} />
                                                                : <Tile key={item.id} title={item.title} sub={item.artist} art={item.artworkURL} onClick={() => playQueue(row.items as MusicTrack[], row.items.indexOf(item))} />)}
                                                    </Strip>
                                                </div>
                                            ))}
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
