// For You: picks made for you, on this PC, from what you watch. Same rules on every app (DESIGN.md "For You"):
// android/.../ui/ForYou.java and ios/Codio/Store/ForYou.swift do exactly this too.
//
// 1. Taste profile: the movies and shows you watched (finished counts more than started, long binges more, recent
//    more than old) are the seeds.
// 2. Candidates: TMDB's "recommendations" and "similar" lists for the seeds, each one scored by how many of your
//    seeds point at it and how high.
// 3. Embeddings: every title becomes a 512-number vector from its genres, keywords, cast, director, language, decade
//    and the words of its plot (feature hashing, weighted by how rare each feature is). Your taste is the weighted sum
//    of your seeds' vectors; a candidate close to it (cosine) is more "you" than its genre alone says.
// 4. A little randomness: scores get a stable daily shake (same order all day, new mix tomorrow), and a few titles
//    a bit outside your usual taste ("Something different") are mixed in.
// Nothing leaves the PC except the TMDB lookups made with your own key.

import type { Meta } from './api';
import { profileId } from './stats';

export type Pick = Meta & { reason: string };
export type PickRow = { id: string, title: string, items: Pick[] };
export type ForYouResult = { day: string, sig: string, builtAt: number, rows: PickRow[] };

const API = 'https://api.themoviedb.org/3';
const STORE = 'codioForYou';
const DIM = 512;
const MAX_SEEDS = 12;
const MAX_NEG = 4;
const POOL = 60;
const PARALLEL = 6;

const STOP = new Set(('with that this from they their them there have when what where which while into about after '
    + 'before over under again their than then these those will would could should being been were your yours more most '
    + 'some such only also just very each other into onto upon must find finds found life lives world years year young '
    + 'take takes back begin begins becomes become along new old one two three first last story film series show '
    + 'season episode family man woman men women himself herself itself make makes made way ways time times get gets '
    + 'help helps both even ever every between through during against among around because however while former').split(' '));

export const tmdbKey = () => {
    try { return (localStorage.getItem('codio.tmdbKey') ?? '').trim(); } catch (_e) { return ''; }
};

// ---- Small helpers, the same in all three languages.

// FNV-1a, 32 bit, over the UTF-8 bytes.
export const fnv = (s: string): number => {
    const bytes = new TextEncoder().encode(s);
    let h = 0x811c9dc5;
    for (let i = 0; i < bytes.length; i++) {
        h ^= bytes[i];
        h = Math.imul(h, 0x01000193);
    }
    return h >>> 0;
};

// The same number in [0, 1) all day for a title, a new one tomorrow.
const daily = (day: string, id: string) => fnv(`${day}|${id}`) / 4294967296;

export const today = () => {
    const d = new Date();
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

// ---- Your history

type Seed = { imdb: string, kind: 'movie' | 'tv', name: string, weight: number };

const readJson = (key: string): any => {
    try { return JSON.parse(localStorage.getItem(key) ?? '{}') ?? {}; } catch (_e) { return {}; }
};

type Entry = { id: string, type: string, name: string, lastWatched: number, overall: number, finished: boolean, started: boolean, inLibrary: boolean };

const entries = (): Entry[] => {
    const out = new Map<string, Entry>();
    ['library', 'library_recent'].forEach((key) => {
        const items = readJson(key)?.items;
        if (!items || typeof items !== 'object') return;
        Object.keys(items).forEach((k) => {
            const it = items[k];
            if (!it || typeof it !== 'object') return;
            const st = it.state ?? {};
            const offset = Number(st.timeOffset) || 0;
            const duration = Number(st.duration) || 0;
            const lw = typeof st.lastWatched === 'string' ? Date.parse(st.lastWatched) : NaN;
            out.set(String(it._id ?? k), {
                id: String(it._id ?? k),
                type: String(it.type ?? ''),
                name: String(it.name ?? ''),
                lastWatched: Number.isFinite(lw) ? lw : 0,
                overall: Number(st.overallTimeWatched) || 0,
                finished: (Number(st.timesWatched) || 0) > 0 || st.flaggedWatched === 1 || (duration > 0 && offset / duration > 0.9),
                started: offset > 0 || (Number(st.overallTimeWatched) || 0) > 0,
                inLibrary: !it.removed && !it.temp,
            });
        });
    });
    return Array.from(out.values());
};

// Finished = 1, started = 0.45, only in the Library = 0.3; up to +0.6 for 10 hours or more of watching;
// then faded by age (half after 60 days, never under 35 %).
export const seedWeight = (finished: boolean, started: boolean, inLibrary: boolean, overallMs: number, lastMs: number, now: number) => {
    let base = finished ? 1 : started ? 0.45 : inLibrary ? 0.3 : 0;
    if (base <= 0) return 0;
    base += Math.min(1, overallMs / 36e6) * 0.6;
    const days = lastMs > 0 ? Math.max(0, (now - lastMs) / 864e5) : 365;
    return base * (0.35 + 0.65 * Math.pow(0.5, days / 60));
};

const seedsOf = (list: Entry[]): Seed[] => {
    const now = Date.now();
    return list
        .filter((e) => e.id.startsWith('tt') && (e.type === 'movie' || e.type === 'series'))
        .map((e) => ({ imdb: e.id, kind: e.type === 'movie' ? 'movie' : 'tv', name: e.name, weight: seedWeight(e.finished, e.started, e.inLibrary, e.overall, e.lastWatched, now) } as Seed))
        .filter((s) => s.weight > 0)
        .sort((a, b) => b.weight - a.weight)
        .slice(0, MAX_SEEDS);
};

// ---- TMDB

const memo = new Map<string, Promise<any>>();
const get = (path: string): Promise<any> => {
    const key = tmdbKey();
    const url = `${API}${path}${path.includes('?') ? '&' : '?'}api_key=${key}`;
    const hit = memo.get(url);
    if (hit) return hit;
    const p = fetch(url).then((r) => (r.ok ? r.json() : null)).catch(() => null);
    memo.set(url, p);
    if (memo.size > 400) memo.delete(memo.keys().next().value as string);
    return p;
};

const pool = async <T, R>(list: T[], fn: (t: T) => Promise<R>): Promise<R[]> => {
    const out: R[] = new Array(list.length);
    let next = 0;
    const worker = async () => {
        while (next < list.length) {
            const i = next++;
            out[i] = await fn(list[i]);
        }
    };
    await Promise.all(Array.from({ length: Math.min(PARALLEL, list.length) }, worker));
    return out;
};

const findTmdb = async (imdb: string, kind: 'movie' | 'tv'): Promise<{ kind: 'movie' | 'tv', id: number } | null> => {
    const f = await get(`/find/${imdb}?external_source=imdb_id`);
    const movie = f?.movie_results?.[0];
    const tv = f?.tv_results?.[0];
    const first = kind === 'tv' ? (tv ? { kind: 'tv', r: tv } : movie ? { kind: 'movie', r: movie } : null) : (movie ? { kind: 'movie', r: movie } : tv ? { kind: 'tv', r: tv } : null);
    return first && typeof first.r.id === 'number' ? { kind: first.kind as 'movie' | 'tv', id: first.r.id } : null;
};

// ---- Embeddings

// The features of one title (TMDB details with keywords and credits), each with its weight.
export const features = (d: any, kind: string): Map<string, number> => {
    const f = new Map<string, number>();
    const add = (k: string, w: number) => f.set(k, Math.max(f.get(k) ?? 0, w));
    (d?.genres ?? []).forEach((g: any) => add(`g${g.id}`, 1));
    (d?.keywords?.keywords ?? d?.keywords?.results ?? []).slice(0, 20).forEach((k: any) => add(`k${k.id}`, 1.2));
    (d?.credits?.cast ?? []).slice(0, 6).forEach((c: any) => add(`c${c.id}`, 0.5));
    (d?.credits?.crew ?? []).filter((c: any) => c.job === 'Director').slice(0, 2).forEach((c: any) => add(`d${c.id}`, 0.9));
    (d?.created_by ?? []).slice(0, 2).forEach((c: any) => add(`d${c.id}`, 0.9));
    if (d?.original_language) add(`l${d.original_language}`, 0.4);
    const year = parseInt(String(d?.release_date ?? d?.first_air_date ?? '').slice(0, 4), 10);
    if (year > 1800) add(`y${Math.floor(year / 10)}`, 0.3);
    add(`t${kind}`, 0.3);
    let words = 0;
    String(d?.overview ?? '').toLowerCase().split(/[^a-z0-9]+/).forEach((w) => {
        if (w.length < 4 || STOP.has(w) || words >= 40 || f.has(`w${w}`)) return;
        add(`w${w}`, 0.35);
        words++;
    });
    return f;
};

// Feature hashing into DIM numbers, each feature weighted by how rare it is among the titles looked at (IDF).
export const embed = (f: Map<string, number>, df: Map<string, number>, n: number): Float64Array => {
    const v = new Float64Array(DIM);
    f.forEach((w, k) => {
        const h = fnv(k);
        const idf = Math.log(1 + n / (df.get(k) ?? 1));
        v[h % DIM] += (h & 0x80000000 ? -1 : 1) * w * idf;
    });
    let norm = 0;
    for (let i = 0; i < DIM; i++) norm += v[i] * v[i];
    norm = Math.sqrt(norm);
    if (norm > 0) for (let i = 0; i < DIM; i++) v[i] /= norm;
    return v;
};

const dot = (a: Float64Array, b: Float64Array) => {
    let s = 0;
    for (let i = 0; i < DIM; i++) s += a[i] * b[i];
    return s;
};

// ---- Building

type Cand = { key: string, kind: 'movie' | 'tv', id: number, cf: number, best: Seed | null, bestCf: number, seeds: Set<string>, brief: any, trending?: boolean };

const toPick = (d: any, kind: 'movie' | 'tv', imdb: string, reason: string): Pick => ({
    id: imdb,
    type: kind === 'tv' ? 'series' : 'movie',
    name: String(d.title ?? d.name ?? ''),
    poster: d.poster_path ? `https://image.tmdb.org/t/p/w342${d.poster_path}` : null,
    background: d.backdrop_path ? `https://image.tmdb.org/t/p/w1280${d.backdrop_path}` : null,
    logo: null,
    description: d.overview || null,
    releaseInfo: String(d.release_date ?? d.first_air_date ?? '').slice(0, 4) || null,
    runtime: null,
    imdbRating: typeof d.vote_average === 'number' && d.vote_count >= 20 ? d.vote_average.toFixed(1) : null,
    genres: (d.genres ?? []).slice(0, 3).map((g: any) => String(g.name)),
    cast: [],
    videos: [],
    reason,
});

const signature = (seeds: Seed[]) => seeds.slice(0, 6).map((s) => s.imdb).join(',');

const storeKey = () => `${STORE}|${profileId()}`;

export const cached = (): ForYouResult | null => {
    const v = readJson(storeKey());
    return Array.isArray(v?.rows) ? v as ForYouResult : null;
};

// Whether the saved picks are stale: a new day, or something new watched (after half an hour).
export const needsBuild = (): boolean => {
    const c = cached();
    if (!c) return true;
    if (c.day !== today()) return true;
    return c.sig !== signature(seedsOf(entries())) && Date.now() - c.builtAt > 30 * 60e3;
};

export type BuildState = 'nokey' | 'nohistory' | 'ok' | 'failed';

export const build = async (): Promise<{ state: BuildState, result: ForYouResult | null }> => {
    if (!tmdbKey()) return { state: 'nokey', result: null };
    const all = entries();
    const seen = new Set(all.filter((e) => e.lastWatched > 0 || e.inLibrary).map((e) => e.id));
    const seeds = seedsOf(all);
    if (seeds.length === 0) return { state: 'nohistory', result: null };
    const day = today();

    // Seeds: their details, keywords, credits and both candidate lists in one call each.
    const seedInfo = await pool(seeds, async (s) => {
        const t = await findTmdb(s.imdb, s.kind);
        if (!t) return null;
        const d = await get(`/${t.kind}/${t.id}?append_to_response=keywords,credits,recommendations,similar`);
        return d ? { seed: { ...s, kind: t.kind }, key: `${t.kind}:${t.id}`, d } : null;
    });
    const seedsFound = seedInfo.filter((x): x is { seed: Seed, key: string, d: any } => !!x);
    if (seedsFound.length === 0) return { state: 'failed', result: null };
    const seedKeys = new Set(seedsFound.map((s) => s.key));

    const cands = new Map<string, Cand>();
    seedsFound.forEach(({ seed, d }) => {
        const lists: [any[], number][] = [[d.recommendations?.results ?? [], 1], [d.similar?.results ?? [], 0.6]];
        lists.forEach(([list, factor]) => list.slice(0, 20).forEach((r: any, rank: number) => {
            if (typeof r?.id !== 'number') return;
            const key = `${seed.kind}:${r.id}`;
            if (seedKeys.has(key)) return;
            const c = cands.get(key) ?? { key, kind: seed.kind, id: r.id, cf: 0, best: null, bestCf: 0, seeds: new Set<string>(), brief: r };
            const add = seed.weight * factor * (1 - rank / 25);
            c.cf += add;
            c.seeds.add(seed.imdb);
            if (add > c.bestCf) { c.bestCf = add; c.best = seed; }
            cands.set(key, c);
        }));
    });

    // Trending this week, for the wildcards.
    const trending = (await get('/trending/all/week'))?.results ?? [];
    trending.slice(0, 20).forEach((r: any) => {
        const kind = r?.media_type === 'tv' ? 'tv' : r?.media_type === 'movie' ? 'movie' : null;
        if (!kind || typeof r.id !== 'number') return;
        const key = `${kind}:${r.id}`;
        if (seedKeys.has(key) || cands.has(key)) return;
        cands.set(key, { key, kind, id: r.id, cf: 0, best: null, bestCf: 0, seeds: new Set(), brief: r, trending: true });
    });

    const ranked = Array.from(cands.values()).filter((c) => c.cf > 0).sort((a, b) => b.cf - a.cf).slice(0, POOL);
    const wildTrend = Array.from(cands.values()).filter((c) => c.trending).sort((a, b) => daily(day, a.key) - daily(day, b.key)).slice(0, 6);
    const toFetch = ranked.concat(wildTrend);
    const details = await pool(toFetch, (c) => get(`/${c.kind}/${c.id}?append_to_response=keywords,credits,external_ids`));

    // Embeddings for seeds and candidates.
    const feats: Map<string, number>[] = [];
    const df = new Map<string, number>();
    const note = (f: Map<string, number>) => { f.forEach((_w, k) => df.set(k, (df.get(k) ?? 0) + 1)); feats.push(f); return f; };
    const seedFeats = seedsFound.map((s) => note(features(s.d, s.seed.kind)));
    const candFeats = details.map((d, i) => (d ? note(features(d, toFetch[i].kind)) : null));
    const n = feats.length;
    const taste = new Float64Array(DIM);
    seedsFound.forEach((s, i) => {
        const v = embed(seedFeats[i], df, n);
        for (let j = 0; j < DIM; j++) taste[j] += s.seed.weight * v[j];
    });
    let tn = 0;
    for (let j = 0; j < DIM; j++) tn += taste[j] * taste[j];
    tn = Math.sqrt(tn) || 1;
    for (let j = 0; j < DIM; j++) taste[j] /= tn;

    type Scored = { c: Cand, d: any, imdb: string, sim: number, score: number };
    const scored: Scored[] = [];
    const usedImdb = new Set<string>();
    details.forEach((d, i) => {
        const imdb = d?.external_ids?.imdb_id ?? d?.imdb_id;
        if (!d || typeof imdb !== 'string' || !imdb.startsWith('tt') || seen.has(imdb) || usedImdb.has(imdb)) return;
        if (d.adult) return;
        usedImdb.add(imdb);
        const f = candFeats[i];
        scored.push({ c: toFetch[i], d, imdb, sim: f ? dot(taste, embed(f, df, n)) : 0, score: 0 });
    });
    const maxCf = Math.max(1e-6, ...scored.map((s) => s.c.cf));
    const maxSim = Math.max(1e-6, ...scored.map((s) => s.sim));
    scored.forEach((s) => {
        const quality = s.d.vote_count >= 50 ? s.d.vote_average / 10 : 0.5;
        const base = 0.45 * (s.c.cf / maxCf) + 0.45 * (Math.max(0, s.sim) / maxSim) + 0.1 * quality;
        s.score = base * (0.85 + 0.3 * daily(day, s.imdb));
    });

    const reasonOf = (s: Scored) => (s.c.best ? `Because you watched ${s.c.best.name}` : s.c.trending ? 'Trending this week' : 'Something a bit different');
    const wildReason = (s: Scored) => (s.c.trending ? 'Trending this week' : 'Something a bit different');
    const shown = new Set<string>();
    const recs = scored.filter((s) => !s.c.trending).sort((a, b) => b.score - a.score);

    // Wildcards: recommended by your seeds but less like your usual (lower half by closeness), plus trending titles
    // that share a genre with your top genres. Picked by the day's random order.
    const sims = recs.map((s) => s.sim).sort((a, b) => a - b);
    const median = sims.length > 0 ? sims[Math.floor(sims.length / 2)] : 0;
    const topGenres = new Set<number>();
    const gw = new Map<number, number>();
    seedsFound.forEach((s) => (s.d.genres ?? []).forEach((g: any) => gw.set(g.id, (gw.get(g.id) ?? 0) + s.seed.weight)));
    Array.from(gw.entries()).sort((a, b) => b[1] - a[1]).slice(0, 6).forEach(([g]) => topGenres.add(g));
    const wildRec = recs.slice(20).filter((s) => s.sim < median).sort((a, b) => daily(day, `w${a.imdb}`) - daily(day, `w${b.imdb}`));
    const wildTr = scored.filter((s) => s.c.trending && (s.d.genres ?? []).some((g: any) => topGenres.has(g.id)))
        .sort((a, b) => daily(day, `w${a.imdb}`) - daily(day, `w${b.imdb}`));
    const wild: Scored[] = [];
    for (let i = 0; wild.length < 12 && (i < wildRec.length || i < wildTr.length); i++) {
        if (i < wildRec.length) wild.push(wildRec[i]);
        if (i < wildTr.length) wild.push(wildTr[i]);
    }

    const rows: PickRow[] = [];
    // Top picks: the best 20 of the day, with two wildcards slipped in (6th and 13th).
    const top: Scored[] = recs.slice(0, 20);
    const slip = wild.slice(0, 2);
    if (slip[0] && top.length >= 6) top.splice(5, 0, slip[0]);
    if (slip[1] && top.length >= 13) top.splice(12, 0, slip[1]);
    const topItems = top.slice(0, 20).map((s) => { shown.add(s.imdb); return toPick(s.d, s.c.kind, s.imdb, slip.includes(s) ? wildReason(s) : reasonOf(s)); });
    if (topItems.length >= 4) rows.push({ id: 'top', title: 'Top picks for you', items: topItems });

    // "Because you watched …" for your three strongest seeds.
    seedsFound.slice(0, 3).forEach(({ seed }) => {
        const items = recs.filter((s) => !shown.has(s.imdb) && s.c.seeds.has(seed.imdb)).slice(0, 15);
        if (items.length < 4) return;
        items.forEach((s) => shown.add(s.imdb));
        rows.push({ id: `because-${seed.imdb}`, title: `Because you watched ${seed.name}`, items: items.map((s) => toPick(s.d, s.c.kind, s.imdb, `Because you watched ${seed.name}`)) });
    });

    const different = wild.slice(2).filter((s) => !shown.has(s.imdb)).slice(0, 10);
    if (different.length >= 4) {
        different.forEach((s) => shown.add(s.imdb));
        rows.push({ id: 'different', title: 'Something different', items: different.map((s) => toPick(s.d, s.c.kind, s.imdb, wildReason(s))) });
    }

    // The rest of the good ones.
    const more = recs.filter((s) => !shown.has(s.imdb)).slice(0, 20);
    if (more.length >= 4) rows.push({ id: 'more', title: 'More you might like', items: more.map((s) => toPick(s.d, s.c.kind, s.imdb, reasonOf(s))) });

    const result: ForYouResult = { day, sig: signature(seeds), builtAt: Date.now(), rows };
    try { localStorage.setItem(storeKey(), JSON.stringify(result)); } catch (_e) { /* storage full */ }
    return { state: rows.length > 0 ? 'ok' : 'failed', result };
};
