// Codio browse screens: addon catalogs and metadata, fetched straight from the addons
// (same data layer as Codio for Android TV, so both apps show the same rows).

export type Addon = {
    transportUrl: string,
    base: string,
    name: string,
    manifest: any,
};

export type Catalog = {
    addon: Addon,
    type: string,
    id: string,
    name: string,
    genres: string[],
    search: boolean,
    skip: boolean,
    genreRequired: boolean,
    otherRequired: boolean,
};

export type Video = {
    id: string,
    title: string,
    thumbnail: string | null,
    overview: string | null,
    season: number,
    episode: number,
};

export type Meta = {
    id: string,
    type: string,
    name: string,
    poster: string | null,
    background: string | null,
    logo: string | null,
    description: string | null,
    releaseInfo: string | null,
    runtime: string | null,
    imdbRating: string | null,
    genres: string[],
    cast: string[],
    videos: Video[],
    progress?: number,
};

export const CINEMETA = 'https://v3-cinemeta.strem.io/manifest.json';

const str = (value: unknown): string | null => {
    if (value === null || value === undefined) return null;
    const text = String(value).trim();
    return text.length > 0 ? text : null;
};

export const toAddon = (descriptor: { transportUrl: string, manifest: any }): Addon => ({
    transportUrl: descriptor.transportUrl,
    base: descriptor.transportUrl.replace(/\/manifest\.json$/, ''),
    name: descriptor.manifest?.name ?? 'Addon',
    manifest: descriptor.manifest ?? {},
});

const toCatalog = (addon: Addon, json: any): Catalog => {
    let search = false, skip = false, genreRequired = false, otherRequired = false;
    const genres: string[] = [];
    if (Array.isArray(json.extra)) {
        for (const extra of json.extra) {
            if (!extra || typeof extra !== 'object') continue;
            const required = extra.isRequired === true;
            if (extra.name === 'search') search = true;
            else if (extra.name === 'skip') skip = true;
            else if (extra.name === 'genre') {
                genreRequired = genreRequired || required;
                if (Array.isArray(extra.options)) genres.push(...extra.options.map(String));
            }
            if (required && extra.name !== 'genre') otherRequired = true;
        }
    }
    if (Array.isArray(json.extraSupported)) {
        search = search || json.extraSupported.includes('search');
        skip = skip || json.extraSupported.includes('skip');
    }
    if (Array.isArray(json.extraRequired)) {
        for (const name of json.extraRequired) {
            if (name === 'genre') genreRequired = true;
            else otherRequired = true;
        }
    }
    return {
        addon,
        type: String(json.type ?? ''),
        id: String(json.id ?? ''),
        name: str(json.name) ?? addon.name,
        genres, search, skip, genreRequired, otherRequired,
    };
};

const catalogsOf = (addons: Addon[]) => addons.flatMap((addon) => {
    const list = Array.isArray(addon.manifest.catalogs) ? addon.manifest.catalogs : [];
    return list.filter((c: any) => c && typeof c === 'object').map((c: any) => toCatalog(addon, c));
});

export const browsable = (c: Catalog) => !(c.otherRequired || (c.genreRequired && c.genres.length === 0));

export const homeCatalogs = (addons: Addon[]) => catalogsOf(addons).filter((c) => !c.otherRequired && !c.genreRequired);

export const discoverCatalogs = (addons: Addon[], type: string) => catalogsOf(addons).filter((c) => c.type === type && browsable(c));

export const searchCatalogs = (addons: Addon[]) => {
    const rank = (type: string) => type === 'movie' ? 0 : type === 'series' ? 1 : 2;
    return catalogsOf(addons).filter((c) => c.search && !c.genreRequired).sort((a, b) => rank(a.type) - rank(b.type));
};

const enc = (value: string) => encodeURIComponent(value);

export const catalogUrl = (c: Catalog, genre: string | null, skip: number, search: string | null) => {
    const extra: string[] = [];
    if (search !== null) extra.push(`search=${enc(search)}`);
    if (genre === null && c.genreRequired && c.genres.length > 0) genre = c.genres[0];
    if (genre !== null) extra.push(`genre=${enc(genre)}`);
    if (skip > 0) extra.push(`skip=${skip}`);
    return `${c.addon.base}/catalog/${enc(c.type)}/${enc(c.id)}${extra.length > 0 ? '/' + extra.join('&') : ''}.json`;
};

export const typeLabel = (type: string) => {
    switch (type) {
        case 'movie': return 'Movies';
        case 'series': return 'Series';
        case 'channel': return 'Channels';
        case 'tv': return 'Live TV';
        default: return type.length > 0 ? type.charAt(0).toUpperCase() + type.slice(1) : '';
    }
};

export const rowTitle = (c: Catalog) => {
    const label = typeLabel(c.type);
    return label.length === 0 || c.name.toLowerCase().includes(label.toLowerCase()) ? c.name : `${c.name} ${label}`;
};

export const parseMeta = (json: any, fallbackType?: string): Meta => {
    const genres = Array.isArray(json.genres) ? json.genres : Array.isArray(json.genre) ? json.genre : [];
    const videos: Video[] = Array.isArray(json.videos) ? json.videos.filter((v: any) => v && typeof v === 'object').map((v: any) => ({
        id: String(v.id ?? ''),
        title: String(v.title ?? v.name ?? ''),
        thumbnail: str(v.thumbnail),
        overview: str(v.overview) ?? str(v.description),
        season: typeof v.season === 'number' ? v.season : 0,
        episode: typeof v.episode === 'number' ? v.episode : typeof v.number === 'number' ? v.number : 0,
    })) : [];
    videos.sort((a, b) => a.season !== b.season ? a.season - b.season : a.episode - b.episode);
    return {
        id: String(json.id ?? json._id ?? ''),
        type: str(json.type) ?? fallbackType ?? 'movie',
        name: String(json.name ?? ''),
        poster: str(json.poster),
        background: str(json.background),
        logo: str(json.logo),
        description: str(json.description),
        releaseInfo: str(json.releaseInfo) ?? str(json.year),
        runtime: str(json.runtime),
        imdbRating: str(json.imdbRating),
        genres: genres.slice(0, 4).map(String),
        cast: Array.isArray(json.cast) ? json.cast.slice(0, 5).map(String) : [],
        videos,
    };
};

export const metaLine = (m: Meta) => [m.releaseInfo, m.runtime, m.imdbRating ? `★ ${m.imdbRating}` : null].filter(Boolean).join('  ·  ');

export const infoLine = (m: Meta) => [metaLine(m), m.genres.join('  ·  ')].filter((s) => s.length > 0).join('  ·  ');

export const backgroundOf = (m: Partial<Meta> | null | undefined): string | null => {
    if (!m) return null;
    if (m.background) return m.background;
    if (m.id && m.id.startsWith('tt')) return `https://images.metahub.space/background/medium/${m.id}/img`;
    return m.poster ?? null;
};

export const logoOf = (m: Partial<Meta> | null | undefined): string | null => {
    if (!m) return null;
    if (m.logo) return m.logo;
    if (m.id && m.id.startsWith('tt')) return `https://images.metahub.space/logo/medium/${m.id}/img`;
    return null;
};

// ---- Fetching, with a small in-memory cache so going back and forth is instant.
const cache = new Map<string, Promise<any>>();

const getJson = (url: string): Promise<any> => {
    const hit = cache.get(url);
    if (hit) return hit;
    const request = fetch(url).then((response) => {
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        return response.json();
    });
    cache.set(url, request);
    request.catch(() => cache.delete(url));
    if (cache.size > 300) cache.delete(cache.keys().next().value as string);
    return request;
};

export const loadCatalog = async (c: Catalog, genre: string | null, skip: number, search: string | null): Promise<Meta[]> => {
    const json = await getJson(catalogUrl(c, genre, skip, search));
    const metas = Array.isArray(json?.metas) ? json.metas : [];
    return metas
        .filter((m: any) => m && typeof m === 'object')
        .map((m: any) => parseMeta(m, c.type))
        .filter((m: Meta) => m.id.length > 0)
        .map((m: Meta) => (typeof m.type !== 'string' || m.type.length === 0 ? { ...m, type: c.type } : m));
};

let defaultAddons: Promise<Addon[]> | null = null;

// No addons yet (signed out, first start): show Cinemeta's catalogs like Android does.
export const fallbackAddons = (): Promise<Addon[]> => {
    if (!defaultAddons) {
        defaultAddons = getJson(CINEMETA).then((manifest) => [toAddon({ transportUrl: CINEMETA, manifest })]);
        defaultAddons.catch(() => { defaultAddons = null; });
    }
    return defaultAddons;
};

export const dedupe = (list: Meta[], add: Meta[], key: (m: Meta) => string = (m) => m.id) => {
    const seen = new Set(list.map(key));
    return list.concat(add.filter((m) => {
        const k = key(m);
        if (seen.has(k)) return false;
        seen.add(k);
        return true;
    }));
};

export const detailHref = (m: { type: string, id: string }, videoId?: string | null) =>
    `/detail/${enc(m.type)}/${enc(m.id)}${videoId ? `/${enc(videoId)}` : ''}`;

export const qualityLabel = (quality: string | null | undefined) => quality ? (quality === '2160p' ? '4K' : quality) : null;
