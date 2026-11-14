// Codio title page (as on Android TV): artwork, Play / Resume and "Choose a source", seasons and episodes.
// Sources start loading as soon as the page opens (and for the episode you rest on), and the best
// source's link is warmed up, so Play starts with little or no searching.
// Play shows Codio's own "finding the best version" screen; "Choose a source" is Codio's own list.

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import classnames from 'classnames';
import { useLocation, useNavigate, useParams } from 'react-router';
import { withCoreSuspender, useProfile } from 'stremio/common';
import { useRouteActive } from 'stremio/common/useRouteFocused';
import { useAutoPlaySettings, rankStreams, startQueue, clearQueue, toPath, type Candidate } from 'stremio/common/AutoPlay';
import useMetaDetails from 'stremio/routes/MetaDetails/useMetaDetails';
import { useCore } from 'stremio/core';
import { backgroundOf, logoOf, infoLine, qualityLabel, type Meta, type Video } from './api';
import { Backdrop, TitleArt, CodioButton, Chip, Spinner, Strip, useEscape, styles, DownloadBadge } from './components';
import {
    QUALITIES, dlKey, formatBytes, optionsFrom, progressOf, queueDownloads, startDownload, useDownloadMap, usePlayDownload,
    type DlOption, type Download,
} from './downloads';

type Source = {
    key: string,
    link: string,
    url: string | null,
    addon: string,
    name: string | null,
    description: string | null,
    rank: number,
    quality: string | null,
    cached: boolean,
    mbps: number | null,
};

const toMeta = (item: any): Meta => {
    const links: any[] = Array.isArray(item?.links) ? item.links : [];
    const byCategory = (category: string) => links.filter((l) => l && l.category === category).map((l) => String(l.name));
    const rating = links.find((l) => l && l.category === 'imdb');
    const videos: Video[] = (Array.isArray(item?.videos) ? item.videos : []).map((v: any) => ({
        id: String(v.id),
        title: typeof v.title === 'string' ? v.title : '',
        thumbnail: typeof v.thumbnail === 'string' && v.thumbnail.length > 0 ? v.thumbnail : null,
        overview: typeof v.overview === 'string' && v.overview.length > 0 ? v.overview : null,
        season: typeof v.season === 'number' ? v.season : 0,
        episode: typeof v.episode === 'number' ? v.episode : 0,
    }));
    videos.sort((a, b) => a.season !== b.season ? a.season - b.season : a.episode - b.episode);
    return {
        id: String(item?.id ?? ''),
        type: String(item?.type ?? 'movie'),
        name: String(item?.name ?? ''),
        poster: item?.poster ?? null,
        background: item?.background ?? null,
        logo: item?.logo ?? null,
        description: item?.description ?? null,
        releaseInfo: item?.releaseInfo ?? null,
        runtime: item?.runtime ?? null,
        imdbRating: rating && rating.name ? String(rating.name) : null,
        genres: byCategory('Genres').slice(0, 4),
        cast: byCategory('Cast').slice(0, 5),
        videos,
    };
};

const warmed = new Set<string>();

// Ask the debrid service for the first bytes of the best source, so the player's request is answered fast.
const warmUp = (source: Source | undefined) => {
    if (!source || !source.cached || !source.url || !/^https?:/.test(source.url) || warmed.has(source.url)) return;
    warmed.add(source.url);
    const controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    const timer = setTimeout(() => controller?.abort(), 20000);
    fetch(source.url, { mode: 'no-cors', headers: { Range: 'bytes=0-1' }, signal: controller?.signal, cache: 'no-store' })
        .then(() => controller?.abort(), () => { /* best effort */ })
        .finally(() => clearTimeout(timer));
};

type PlayState = null | { videoId: string, phase: 'searching' | 'starting' | 'failed', text: string };

type DlPick = null | { kind: 'one', videoId: string } | { kind: 'season', season: number };

// Without internet the title's page is built from what was saved with its downloads.
const offlineMeta = (list: Download[], type: string, id: string): Meta | null => {
    if (list.length === 0) return null;
    const first = list[0];
    const videos: Video[] = type === 'movie' ? [] : list.map((d) => ({
        id: d.videoId,
        title: d.episodeTitle ?? '',
        thumbnail: d.thumb ?? null,
        overview: null,
        season: d.season ?? 0,
        episode: d.episode ?? 0,
    })).sort((a, b) => a.season !== b.season ? a.season - b.season : a.episode - b.episode);
    return {
        id, type, name: first.name, poster: first.poster ?? null, background: type === 'movie' ? first.thumb ?? null : null, logo: null, description: null,
        releaseInfo: null, runtime: null, imdbRating: null, genres: [], cast: [], videos,
    };
};

const Detail = () => {
    const params = useParams();
    const location = useLocation();
    const navigate = useNavigate();
    const routeActive = useRouteActive();
    const profile = useProfile() as any;
    const [settings] = useAutoPlaySettings();
    const type = params.type ?? 'movie';
    const id = params.id ?? '';
    const urlVideoId = typeof params.videoId === 'string' && params.videoId.length > 0 ? params.videoId : null;
    const search = useMemo(() => new URLSearchParams(location.search), [location.search]);

    // Which video's sources to load: the movie, the episode Play would start, or the episode you rest on.
    const [target, setTarget] = useState<string | null>(urlVideoId ?? (type === 'movie' ? id : null));
    useEffect(() => {
        setTarget(urlVideoId ?? (type === 'movie' ? id : null));
    }, [type, id, urlVideoId]);
    const urlParams = useMemo(() => ({ type, id, videoId: target ?? undefined }), [type, id, target]);
    const metaDetails = useMetaDetails(urlParams as any) as any;

    const ready = metaDetails.metaItem?.content?.type === 'Ready' ? metaDetails.metaItem.content.content : null;
    const metaBase: string | null = metaDetails.metaItem?.addon?.transportUrl ?? null;
    const downloads = useDownloadMap();
    const playDownload = usePlayDownload();
    const myDownloads = useMemo(() => downloads.entries.filter((d) => d.type === type && d.metaId === id), [downloads.entries, type, id]);
    const offline = useMemo(() => metaDetails.metaItem?.content?.type === 'Err' ? offlineMeta(myDownloads.filter((d) => d.state === 'done'), type, id) : null, [metaDetails.metaItem?.content?.type, myDownloads, type, id]);
    const metaFailed = metaDetails.metaItem?.content?.type === 'Err' && !offline;
    const meta = useMemo<Meta>(() => ready ? toMeta(ready) : offline ?? {
        id, type, name: '', poster: null, background: null, logo: null, description: null, releaseInfo: null,
        runtime: null, imdbRating: null, genres: [], cast: [], videos: [],
    }, [ready, offline, id, type]);
    const isSeries = meta.type !== 'movie' && meta.videos.length > 0;
    const library = metaDetails.libraryItem;
    const core = useCore() as any;
    const inList = ready ? !!ready.inLibrary : false;
    const toggleList = useCallback(() => {
        if (!ready) return;
        core.transport.dispatch({ action: 'Ctx', args: inList ? { action: 'RemoveFromLibrary', args: ready.id } : { action: 'AddToLibrary', args: ready } });
    }, [ready, inList]);

    const resumeVideo = useMemo<Video | null>(() => {
        if (meta.videos.length === 0) return null;
        const videoId = library?.state?.video_id;
        if (videoId) {
            const index = meta.videos.findIndex((v) => v.id === videoId);
            if (index >= 0) {
                const duration = library.state.duration;
                const done = duration > 0 && library.state.timeOffset / duration > 0.92;
                return done && index + 1 < meta.videos.length ? meta.videos[index + 1] : meta.videos[index];
            }
        }
        return meta.videos.find((v) => v.season > 0) ?? meta.videos[0];
    }, [meta.videos, library]);

    // ---- Seasons and episodes
    const seasons = useMemo(() => {
        const list = Array.from(new Set(meta.videos.map((v) => v.season)));
        if (list.length > 1 && list[0] === 0) list.push(list.shift() as number);
        return list;
    }, [meta.videos]);
    const [season, setSeason] = useState<number | null>(null);
    useEffect(() => {
        if (seasons.length === 0) return;
        setSeason((current) => current !== null && seasons.includes(current) ? current : (resumeVideo ? resumeVideo.season : seasons[0]));
    }, [seasons, resumeVideo]);
    const episodes = useMemo(() => meta.videos.filter((v) => v.season === season), [meta.videos, season]);
    const [episodeFocus, setEpisodeFocus] = useState<Video | null>(null);
    const [lifted, setLifted] = useState(false);

    // Series: load the sources of the episode Play would start, or of the one you rest on for a moment.
    useEffect(() => {
        if (!isSeries || urlVideoId) return;
        const want = lifted && episodeFocus ? episodeFocus.id : resumeVideo?.id ?? null;
        if (!want || want === target) return;
        const timer = setTimeout(() => setTarget(want), lifted ? 900 : 0);
        return () => clearTimeout(timer);
    }, [isSeries, urlVideoId, lifted, episodeFocus, resumeVideo, target]);

    // ---- Sources, in auto-play's order first
    const runtime = useMemo(() => {
        const v = Array.isArray(ready?.videos) ? ready.videos.find((x: any) => x.id === target) : null;
        return typeof v?.runtime === 'string' ? v.runtime : meta.runtime;
    }, [ready, target, meta.runtime]);
    const streamsFor = metaDetails.selected?.streamPath?.id ?? null;
    const loadingAddons = (metaDetails.streams ?? []).filter((s: any) => s.content.type === 'Loading').length;
    const allStreams = useMemo(() => (metaDetails.streams ?? [])
        .filter((s: any) => s.content.type === 'Ready')
        .flatMap((s: any) => s.content.content.map((stream: any) => ({ ...stream, addonName: s.addon.manifest.name, addonUrl: s.addon.transportUrl }))), [metaDetails.streams]);
    const ranked = useMemo<Candidate[]>(() => rankStreams(allStreams, settings, { runtime, audioLanguage: profile?.settings?.audioLanguage }), [allStreams, settings, runtime, profile?.settings?.audioLanguage]);
    const sources = useMemo<Source[]>(() => {
        const byLink = new Map<string, any>();
        allStreams.forEach((s: any) => {
            const link = s.deepLinks?.player;
            if (typeof link === 'string' && !byLink.has(link)) byLink.set(link, s);
        });
        const make = (s: any, rank: number, c?: Candidate): Source => ({
            key: s.deepLinks.player,
            link: s.deepLinks.player,
            url: typeof s.url === 'string' ? s.url : null,
            addon: s.addonName,
            name: typeof s.name === 'string' ? s.name : null,
            description: typeof s.description === 'string' ? s.description : typeof s.title === 'string' ? s.title : null,
            rank,
            quality: c?.quality ?? null,
            cached: c?.cached ?? false,
            mbps: typeof c?.mbps === 'number' ? c.mbps : null,
        });
        const list: Source[] = [];
        const used = new Set<string>();
        ranked.forEach((c, index) => {
            const s = byLink.get(c.link);
            if (s && !used.has(c.link)) {
                used.add(c.link);
                list.push(make(s, index, c));
            }
        });
        byLink.forEach((s, link) => {
            if (!used.has(link)) list.push(make(s, -1));
        });
        return list;
    }, [allStreams, ranked]);
    const best = sources.length > 0 && sources[0].rank === 0 ? sources[0] : undefined;
    const sourcesReady = streamsFor !== null && streamsFor === target;
    useEffect(() => {
        if (sourcesReady && settings.enabled) warmUp(best);
    }, [sourcesReady, best, settings.enabled]);

    // ---- Play
    const [play, setPlay] = useState<PlayState>(null);
    const [showSources, setShowSources] = useState<string | null>(null);
    const playKey = (videoId: string) => [type, id, videoId].join('/');
    const preferred = settings.preferredQuality === '2160p' ? '4K' : settings.preferredQuality;

    const downloadOf = useCallback((videoId: string) => downloads.map.get(dlKey(type, id, videoId)) ?? null, [downloads.map, type, id]);
    const startPlay = useCallback((videoId: string) => {
        const local = downloadOf(videoId);
        if (local && local.state === 'done') {
            playDownload(local);
            return;
        }
        const playerType = profile?.settings?.playerType;
        const externalPlayer = typeof playerType === 'string' && playerType.length > 0 && playerType !== 'internal';
        if (!settings.enabled || externalPlayer) {
            setShowSources(videoId);
            setTarget(videoId);
            return;
        }
        setTarget(videoId);
        setPlay({ videoId, phase: 'searching', text: `Finding the best ${preferred} version…` });
    }, [settings.enabled, profile.settings.playerType, preferred, downloadOf, playDownload]);

    // ?autoplay=1 (Play on Home, an episode, Continue Watching) starts as soon as the page opens.
    const autoplayHandled = useRef<string | null>(null);
    useEffect(() => {
        if (search.get('autoplay') !== '1') return;
        const handledKey = location.key;
        if (autoplayHandled.current === handledKey) return;
        const videoId = urlVideoId ?? (type === 'movie' ? id : null);
        if (videoId) {
            autoplayHandled.current = handledKey;
            startPlay(videoId);
        } else if (isSeries && resumeVideo) {
            autoplayHandled.current = handledKey;
            startPlay(resumeVideo.id);
        }
    }, [search, location.key, urlVideoId, type, id, isSeries, resumeVideo, startPlay]);
    useEffect(() => {
        if (search.get('sources') === '1') setShowSources(urlVideoId ?? (type === 'movie' ? id : resumeVideo?.id ?? null));
    }, [search]);

    // Wait for slow addons, but not forever (same rules as auto-play on the old page).
    const [waited, setWaited] = useState(false);
    useEffect(() => {
        setWaited(false);
        if (!play || play.phase !== 'searching' || !sourcesReady || allStreams.length === 0) return;
        const timer = setTimeout(() => setWaited(true), Math.max(0, settings.addonWaitSeconds) * 1000);
        return () => clearTimeout(timer);
    }, [play?.phase, play?.videoId, sourcesReady, allStreams.length > 0, settings.addonWaitSeconds]);
    // When the sources in so far don't fit (only torrents, say) while a slower addon (debrid) is still
    // answering, keep waiting for it instead of giving up, but not forever.
    const [waitedLong, setWaitedLong] = useState(false);
    useEffect(() => {
        setWaitedLong(false);
        if (!play || play.phase !== 'searching') return;
        const timer = setTimeout(() => setWaitedLong(true), Math.max(25, settings.addonWaitSeconds * 3) * 1000);
        return () => clearTimeout(timer);
    }, [play?.phase, play?.videoId, settings.addonWaitSeconds]);

    useEffect(() => {
        if (!play || play.phase !== 'searching' || !sourcesReady || streamsFor !== play.videoId) return;
        const doneLoading = loadingAddons === 0;
        const goodEnough = ranked.length > 0 && ranked[0].quality === settings.preferredQuality;
        const stillHoping = ranked.length === 0 && !doneLoading && !waitedLong;
        if (!(doneLoading || waited || goodEnough) || stillHoping) {
            if (allStreams.length > 0) setPlay((p) => p && p.phase === 'searching' ? { ...p, text: `Checking ${allStreams.length} sources…` } : p);
            return;
        }
        if (ranked.length === 0) {
            setPlay({ ...play, phase: 'failed', text: allStreams.length > 0 ? `None of the ${allStreams.length} sources match your auto-play settings.` : 'Couldn\'t find a source.' });
            return;
        }
        startQueue(playKey(play.videoId), ranked);
        const q = qualityLabel(ranked[0].quality);
        setPlay({ ...play, phase: 'starting', text: `Starting${q ? ` in ${q}` : ''}…` });
        navigate(toPath(ranked[0].link));
    }, [play, sourcesReady, streamsFor, loadingAddons, ranked, waited, waitedLong, allStreams.length, settings.preferredQuality]);

    // Back from the player: the page is in front again, so drop the loading screen.
    const wasActive = useRef(routeActive);
    useEffect(() => {
        if (routeActive && !wasActive.current) {
            setPlay(null);
            setShowSources(null);
        }
        wasActive.current = routeActive;
    }, [routeActive]);

    const playSource = (source: Source, videoId: string) => {
        clearQueue();
        const q = qualityLabel(source.quality);
        setShowSources(null);
        setPlay({ videoId, phase: 'starting', text: `Starting${q ? ` in ${q}` : ''}…` });
        navigate(toPath(source.link));
    };

    const goBack = useCallback(() => {
        if (window.history.state?.idx > 0) navigate(-1);
        else navigate('/', { replace: true });
    }, [navigate]);

    useEscape(() => {
        if (!routeActive) return false;
        if (play) { setPlay(null); return true; }
        if (showSources) { setShowSources(null); return true; }
        if (dlPick) { setDlPick(null); return true; }
        if (lifted) { setLifted(false); return true; }
        goBack();
        return true;
    }, routeActive);

    // ---- Downloads (debrid / direct links only)
    const [dlPick, setDlPick] = useState<DlPick>(null);
    const addToLibrary = () => {
        if (ready && !inList) core.transport.dispatch({ action: 'Ctx', args: { action: 'AddToLibrary', args: ready } });
    };
    const entryFor = (videoId: string) => {
        const v = meta.videos.find((x) => x.id === videoId && videoId !== meta.id);
        return {
            key: dlKey(type, id, videoId),
            type,
            metaId: id,
            videoId,
            name: meta.name,
            poster: meta.poster,
            season: v ? v.season : undefined,
            episode: v ? v.episode : undefined,
            episodeTitle: v ? v.title || null : null,
            thumb: v ? v.thumbnail ?? backgroundOf(meta) : backgroundOf(meta),
            metaBase,
        };
    };
    const downloadOne = (videoId: string, option: DlOption) => {
        addToLibrary();
        setDlPick(null);
        startDownload({ ...entryFor(videoId), url: option.url, quality: option.quality, wantQuality: option.quality, bytes: option.bytes, streamBase: option.streamBase });
    };
    const downloadSeason = (seasonNumber: number, quality: string) => {
        addToLibrary();
        setDlPick(null);
        const list = meta.videos.filter((v) => v.season === seasonNumber).map((v) => ({ ...entryFor(v.id), wantQuality: quality }));
        queueDownloads(list);
    };
    const pickOne = (videoId: string) => {
        setTarget(videoId);
        setDlPick({ kind: 'one', videoId });
    };
    const dlOptions = useMemo(() => dlPick?.kind === 'one' && streamsFor === dlPick.videoId ? optionsFrom(allStreams) : [], [dlPick, streamsFor, allStreams]);
    const movieDownload = !isSeries ? downloadOf(urlVideoId ?? id) : null;
    const seasonDownloads = useMemo(() => isSeries && season !== null ? episodes.map((v) => downloadOf(v.id)).filter((d): d is Download => !!d && d.state !== 'failed') : [], [isSeries, season, episodes, downloadOf]);
    const downloadLabel = (d: Download | null) => {
        if (!d || d.state === 'failed') return '⬇ Download';
        if (d.state === 'done') return '✓ Downloaded';
        if (d.state === 'waiting') return 'Finding a source…';
        return `Downloading ${Math.round(progressOf(d) * 100)}%`;
    };
    const seasonLabel = () => {
        const name = season === 0 ? 'Specials' : `Season ${season}`;
        if (seasonDownloads.length === 0) return `⬇ Download ${name}`;
        const done = seasonDownloads.filter((d) => d.state === 'done').length;
        if (done === episodes.length) return `✓ ${name} downloaded`;
        if (seasonDownloads.length < episodes.length && done === seasonDownloads.length) return `⬇ Download the rest of ${name}`;
        return `Downloading ${name}  ·  ${done} of ${seasonDownloads.length}`;
    };
    const onDownloadMain = () => {
        if (!isSeries) {
            if (!movieDownload || movieDownload.state === 'failed') pickOne(urlVideoId ?? id);
            else navigate('/library?downloads=1');
        } else if (season !== null) {
            const pending = seasonDownloads.some((d) => d.state !== 'done');
            if (pending || seasonDownloads.length === episodes.length) navigate('/library?downloads=1');
            else setDlPick({ kind: 'season', season });
        }
    };
    const canDownload = downloads.available && (ready || (!isSeries && movieDownload));

    // ---- Labels
    const playLabel = useMemo(() => {
        if (!isSeries) {
            const offset = library?.state?.timeOffset ?? 0;
            const duration = library?.state?.duration ?? 0;
            return offset > 60000 && duration > 0 && offset / duration < 0.92 ? 'Resume' : 'Play';
        }
        if (!resumeVideo) return 'Play';
        const resuming = library?.state?.video_id === resumeVideo.id && (library?.state?.timeOffset ?? 0) > 0;
        return `${resuming ? 'Resume' : 'Play'}  S${resumeVideo.season} E${resumeVideo.episode}`;
    }, [isSeries, resumeVideo, library]);

    const statusText = !sourcesReady ? (target ? 'Finding sources…' : '') :
        best ? `${qualityLabel(best.quality) ?? 'Ready'}${best.cached ? '  ·  instant' : ''}` :
            loadingAddons > 0 ? 'Finding sources…' : allStreams.length === 0 ? 'No sources found' : '';

    const onPlayMain = () => {
        if (isSeries) {
            if (resumeVideo) startPlay(resumeVideo.id);
        } else {
            startPlay(urlVideoId ?? meta.id ?? id);
        }
    };
    const onChooseSource = () => {
        const videoId = isSeries ? (lifted && episodeFocus ? episodeFocus.id : resumeVideo?.id ?? null) : (urlVideoId ?? id);
        if (videoId) {
            setTarget(videoId);
            setShowSources(videoId);
        }
    };

    const episodeLabel = (videoId: string) => {
        const v = meta.videos.find((x) => x.id === videoId);
        return v && videoId !== meta.id ? `S${v.season} E${v.episode}${v.title ? `  ·  ${v.title}` : ''}` : null;
    };

    const lift = lifted && isSeries ? -150 : 0;
    const shownEpisode = lifted && episodeFocus ? episodeFocus : null;

    return (
        <div className={classnames(styles['codio'], styles['detail'])} onWheel={(e) => {
            if (!isSeries || play || showSources || e.shiftKey) return;
            if (e.deltaY > 0) setLifted(true);
            else if (e.deltaY < 0) setLifted(false);
        }}>
            <Backdrop src={backgroundOf(meta)} />
            <div className={styles['detail-body']} style={{ transform: `translateY(calc(var(--u) * ${lift}))` }}>
                <div className={styles['detail-top']}>
                    <button className={styles['back']} onClick={goBack} aria-label={'Back'} tabIndex={-1} />
                    {ready || offline ? <TitleArt meta={meta} logo={logoOf(meta)} width={640} height={200} textSize={72} className={styles['detail-art']} /> : null}
                    <div className={styles['meta-line']}>{ready ? infoLine(meta) : ''}</div>
                    {
                        shownEpisode ?
                            <div>
                                <div className={styles['episode-heading']}>{`S${shownEpisode.season} E${shownEpisode.episode}${shownEpisode.title ? `  ·  ${shownEpisode.title}` : ''}`}</div>
                                {shownEpisode.overview ? <div className={classnames(styles['body'], styles['lines-3'])}>{shownEpisode.overview}</div> : null}
                            </div>
                            :
                            <div>
                                {meta.description ? <div className={classnames(styles['body'], styles['lines-4'])}>{meta.description}</div> : null}
                                {meta.cast.length > 0 ? <div className={styles['starring']}>{`Starring ${meta.cast.slice(0, 4).join(', ')}`}</div> : null}
                            </div>
                    }
                </div>
                <div className={styles['detail-buttons']} onFocus={() => setLifted(false)}>
                    <CodioButton label={playLabel} play primary autoFocus onClick={onPlayMain} />
                    {offline ? null : <CodioButton label={'Choose a source'} onClick={onChooseSource} />}
                    {canDownload ? <CodioButton label={isSeries ? seasonLabel() : downloadLabel(movieDownload)} onClick={onDownloadMain} /> : null}
                    {ready ? <CodioButton label={inList ? '✓ My List' : '+ My List'} onClick={toggleList} /> : null}
                    <span className={styles['source-status']}>{movieDownload?.state === 'done' ? `Downloaded${movieDownload.quality ? `  ·  ${qualityLabel(movieDownload.quality)}` : ''}  ·  plays without internet` : movieDownload?.state === 'failed' ? `Download failed: ${movieDownload.error ?? ''}` : offline ? 'Offline  ·  showing your downloads' : statusText}</span>
                </div>
                {!ready && !metaFailed ? <div className={styles['detail-spinner']}><Spinner size={22} /></div> : null}
                {metaFailed ? <div className={styles['detail-error']}>Couldn't load this title.</div> : null}
                {
                    isSeries ?
                        <div className={styles['episodes-zone']} onFocus={(e) => { if (e.target.matches(':focus-visible')) setLifted(true); }}>
                            <Strip className={styles['season-strip']}>
                                {seasons.map((s) => <Chip key={s} label={s === 0 ? 'Specials' : `Season ${s}`} selected={s === season} onClick={() => setSeason(s)} />)}
                            </Strip>
                            <Strip className={styles['episode-strip']} resetKey={String(season)}>
                                {
                                    episodes.map((v) => {
                                        const progress = library?.state?.video_id === v.id && library.state.duration > 0 ? Math.min(1, library.state.timeOffset / library.state.duration) : 0;
                                        const d = downloadOf(v.id);
                                        return (
                                            <button
                                                key={v.id}
                                                className={styles['episode']}
                                                onMouseEnter={() => setEpisodeFocus(v)}
                                                onFocus={() => setEpisodeFocus(v)}
                                                onClick={() => startPlay(v.id)}
                                            >
                                                <div className={styles['episode-thumb']} style={{ backgroundImage: `url("${String(v.thumbnail ?? backgroundOf(meta) ?? '').replace(/"/g, '%22')}")` }}>
                                                    {progress > 0 ? <div className={styles['progress']}><div style={{ width: `${progress * 100}%` }} /></div> : null}
                                                    {
                                                        d && d.state !== 'failed' ?
                                                            <DownloadBadge done={d.state === 'done'} progress={progressOf(d)} className={styles['episode-badge']} />
                                                            : downloads.available && ready ?
                                                                <span
                                                                    className={styles['episode-download']}
                                                                    role={'button'}
                                                                    title={d?.state === 'failed' ? `Download failed: ${d.error ?? ''}. Download again` : 'Download this episode'}
                                                                    onClick={(event) => { event.stopPropagation(); pickOne(v.id); }}
                                                                >⬇</span>
                                                                : null
                                                    }
                                                </div>
                                                <div className={styles['episode-title']}>{`${v.episode}.  ${v.title || `Episode ${v.episode}`}`}</div>
                                            </button>
                                        );
                                    })
                                }
                            </Strip>
                        </div>
                        : null
                }
            </div>
            {
                showSources ?
                    <SourcesScreen
                        meta={meta}
                        title={meta.name + (episodeLabel(showSources) ? `  ·  ${episodeLabel(showSources)}` : '')}
                        sources={streamsFor === showSources ? sources : []}
                        loading={streamsFor !== showSources || loadingAddons > 0}
                        onPick={(s) => playSource(s, showSources)}
                        onClose={() => setShowSources(null)}
                    />
                    : null
            }
            {
                dlPick ?
                    <DownloadPicker
                        meta={meta}
                        title={dlPick.kind === 'one' ? meta.name + (episodeLabel(dlPick.videoId) ? `  ·  ${episodeLabel(dlPick.videoId)}` : '') : `${meta.name}  ·  ${dlPick.season === 0 ? 'Specials' : `Season ${dlPick.season}`}  ·  ${episodes.length} episodes`}
                        kind={dlPick.kind}
                        options={dlOptions}
                        loading={dlPick.kind === 'one' && (streamsFor !== dlPick.videoId || loadingAddons > 0)}
                        free={downloads.free}
                        want={settings.preferredQuality}
                        onPick={(o) => { if (dlPick.kind === 'one') downloadOne(dlPick.videoId, o); }}
                        onPickQuality={(q) => { if (dlPick.kind === 'season') downloadSeason(dlPick.season, q); }}
                        onClose={() => setDlPick(null)}
                    />
                    : null
            }
            {
                play ?
                    <LoadingScreen
                        meta={meta}
                        episode={episodeLabel(play.videoId)}
                        state={play}
                        onCancel={() => setPlay(null)}
                        onChoose={() => {
                            const videoId = play.videoId;
                            setPlay(null);
                            setShowSources(videoId);
                        }}
                    />
                    : null
            }
        </div>
    );
};

const SourcesScreen = ({ meta, title, sources, loading, onPick, onClose }: { meta: Meta, title: string, sources: Source[], loading: boolean, onPick: (s: Source) => void, onClose: () => void }) => {
    const first = useRef<HTMLButtonElement>(null);
    const hadSources = useRef(false);
    useEffect(() => {
        if (sources.length > 0 && !hadSources.current) {
            hadSources.current = true;
            first.current?.focus();
        }
    }, [sources.length]);
    const count = sources.length === 0 ? (loading ? 'Looking for sources…' : 'No sources found.') :
        `${sources.length} source${sources.length === 1 ? '' : 's'}${loading ? '  ·  still looking…' : ''}`;
    return (
        <div className={styles['overlay']}>
            <Backdrop src={backgroundOf(meta)} dim={0.8} />
            <div className={styles['sources']}>
                <div className={styles['sources-head']}>
                    <button className={styles['back']} onClick={onClose} aria-label={'Back'} tabIndex={-1} />
                    <div className={styles['sources-title']}>{title}</div>
                    <div className={styles['meta-line']}>{count}</div>
                    {loading ? <Spinner size={22} className={styles['sources-spinner']} /> : null}
                </div>
                <div className={styles['sources-list']}>
                    {
                        sources.map((s, index) => {
                            const q = qualityLabel(s.quality);
                            let description = s.description ? s.description.replace(/\r/g, '').replace(/\n+/g, '  ·  ') : '';
                            if (s.mbps !== null && s.mbps > 0) description = `${Math.round(s.mbps)} Mbit/s${description ? `  ·  ${description}` : ''}`;
                            return (
                                <button key={s.key} ref={index === 0 ? first : undefined} className={styles['source']} onClick={() => onPick(s)}>
                                    <div className={styles['source-badges']}>
                                        {q ? <span className={styles['quality']}>{q}</span> : null}
                                        {s.rank === 0 ? <span className={styles['best']}>Best match</span> : s.cached ? <span className={styles['instant']}>Instant</span> : null}
                                    </div>
                                    <div className={styles['source-text']}>
                                        <div className={styles['source-name']}>{[s.addon, s.name ? s.name.replace(/\n/g, ' ') : null].filter(Boolean).join('  ·  ')}</div>
                                        {description ? <div className={styles['source-description']}>{description}</div> : null}
                                    </div>
                                </button>
                            );
                        })
                    }
                </div>
            </div>
        </div>
    );
};

const DownloadPicker = ({ meta, title, kind, options, loading, free, want, onPick, onPickQuality, onClose }: {
    meta: Meta, title: string, kind: 'one' | 'season', options: DlOption[], loading: boolean, free: number | null, want: string,
    onPick: (o: DlOption) => void, onPickQuality: (q: string) => void, onClose: () => void,
}) => {
    const first = useRef<HTMLButtonElement>(null);
    const focused = useRef(false);
    useEffect(() => {
        if (!focused.current && (kind === 'season' || options.length > 0)) {
            focused.current = true;
            first.current?.focus();
        }
    }, [kind, options.length]);
    const space = free !== null ? `${formatBytes(free)} free on this PC` : '';
    const count = kind === 'season' ? 'Pick a quality. Episodes without it get the closest lower one.' :
        options.length === 0 ? (loading ? 'Looking for downloadable sources…' : 'No downloadable source. Only debrid and direct links can be downloaded.') :
            `Pick a quality${loading ? '  ·  still looking…' : ''}`;
    return (
        <div className={styles['overlay']}>
            <Backdrop src={backgroundOf(meta)} dim={0.8} />
            <div className={styles['sources']}>
                <div className={styles['sources-head']}>
                    <button className={styles['back']} onClick={onClose} aria-label={'Back'} tabIndex={-1} />
                    <div className={styles['sources-title']}>{`Download  ·  ${title}`}</div>
                    <div className={styles['meta-line']}>{[count, space].filter(Boolean).join('  ·  ')}</div>
                    {loading ? <Spinner size={22} className={styles['sources-spinner']} /> : null}
                </div>
                <div className={styles['sources-list']}>
                    {
                        kind === 'season' ?
                            QUALITIES.map((q, index) => (
                                <button key={q} ref={index === Math.max(0, QUALITIES.indexOf(want)) ? first : undefined} className={styles['source']} onClick={() => onPickQuality(q)}>
                                    <div className={styles['source-badges']}>
                                        <span className={styles['quality']}>{qualityLabel(q)}</span>
                                        {q === want ? <span className={styles['best']}>Your quality</span> : null}
                                    </div>
                                    <div className={styles['source-text']}>
                                        <div className={styles['source-name']}>{q === '2160p' ? 'Best picture, biggest files' : q === '480p' ? 'Smallest files' : q === '720p' ? 'Small files' : 'Good picture'}</div>
                                    </div>
                                </button>
                            ))
                            :
                            options.map((o, index) => {
                                const size = formatBytes(o.bytes);
                                const tooBig = free !== null && o.bytes > 0 && o.bytes > free;
                                return (
                                    <button key={o.url} ref={index === 0 ? first : undefined} className={styles['source']} onClick={() => onPick(o)}>
                                        <div className={styles['source-badges']}>
                                            <span className={styles['quality']}>{qualityLabel(o.quality) ?? 'Other'}</span>
                                            {o.cached ? <span className={styles['instant']}>Instant</span> : null}
                                        </div>
                                        <div className={styles['source-text']}>
                                            <div className={styles['source-name']}>{size ? `${size}${tooBig ? '  ·  not enough free space' : ''}` : 'Size unknown'}</div>
                                            {o.description ? <div className={styles['source-description']}>{o.description}</div> : null}
                                        </div>
                                    </button>
                                );
                            })
                    }
                </div>
            </div>
        </div>
    );
};

const LoadingScreen = ({ meta, episode, state, onCancel, onChoose }: { meta: Meta, episode: string | null, state: NonNullable<PlayState>, onCancel: () => void, onChoose: () => void }) => (
    <div className={styles['overlay']}>
        <Backdrop src={backgroundOf(meta)} dim={0.5} />
        <div className={styles['loading']}>
            <TitleArt meta={meta} logo={logoOf(meta)} width={640} height={200} textSize={72} />
            {episode ? <div className={styles['meta-line']}>{episode}</div> : <div className={styles['meta-line']} />}
            {
                state.phase !== 'failed' ?
                    <div>
                        <div className={styles['loading-status']}><Spinner size={24} /><span>{state.text}</span></div>
                        <button className={styles['hint-button']} onClick={onCancel}>Esc to cancel</button>
                    </div>
                    :
                    <div>
                        <div className={styles['loading-status']}><span>{state.text}</span></div>
                        <div className={styles['loading-actions']}>
                            <CodioButton label={'Choose a source'} primary autoFocus onClick={onChoose} />
                            <button className={styles['hint-button']} onClick={onCancel}>Esc to return</button>
                        </div>
                    </div>
            }
        </div>
    </div>
);

export default withCoreSuspender(Detail);
