// Does a source look like the episode (and show) it was found for?
//
// Addons that search by name sometimes return files of another episode, another season or a different show with a
// similar name. With the source list hidden nobody sees the file name, so auto-play would start the wrong thing (and,
// binge watching overnight, carry on from there). Pure functions, no React. The same rules are in the Android/iPhone
// shim (android/assets/codio-android.js, __codioSourceFit): keep them in step.

export type Want = {
    // The show's name, e.g. "The Office". Optional: without it only the episode is checked.
    name?: string | null,
    season?: number | null,
    episode?: number | null,
};

// 0 = fits (or can't tell), 1 = probably another show (try it last), 2 = another episode or season (never auto-play).
export type Fit = 0 | 1 | 2;

const SMALL_WORDS = new Set(['the', 'a', 'an', 'and', 'of', 'in', 'on', 'to', 'at', 'for', 'de', 'la', 'le', 'el', 'us', 'uk']);

const words = (text: string): string[] => text
    .toLowerCase()
    .replace(/&/g, ' and ')
    .replace(/['’]/g, '')
    .split(/[^a-z0-9]+/)
    .filter((w) => w.length > 0);

// "S02E05", "s2e5", "S02E05-E06", "S02E05E06", "2x05", "S02 E05"
const EPISODE = /(^|[^a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})(?:(?:[ ._]?-[ ._]?e?|[ ._]?e)(\d{1,3}))?(?=[^0-9]|$)|(^|[^a-z0-9])(\d{1,2})x(\d{2,3})(?=[^0-9]|$)/gi;
// Season packs: "S02", "S01-S05", "S01-05", "Season 2", "Seasons 1-3", "Complete Series"
const SEASON = /(^|[^a-z0-9])(?:s(\d{1,2})(?:[ ._-]*-[ ._-]*s?(\d{1,2}))?|seasons?[ ._-]*(\d{1,2})(?:[ ._-]*(?:-|to|&)[ ._-]*(\d{1,2}))?)(?=[^a-z0-9]|$)/gi;

type Found = { season: number, from: number, to: number, at: number, end: number };

const episodesIn = (text: string): Found[] => {
    const found: Found[] = [];
    EPISODE.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = EPISODE.exec(text)) !== null) {
        if (m[2] !== undefined) {
            const from = parseInt(m[3], 10);
            const to = m[4] !== undefined ? Math.max(from, parseInt(m[4], 10)) : from;
            found.push({ season: parseInt(m[2], 10), from, to, at: m.index + m[1].length, end: m.index + m[0].length });
        } else {
            const e = parseInt(m[7], 10);
            found.push({ season: parseInt(m[6], 10), from: e, to: e, at: m.index + m[5].length, end: m.index + m[0].length });
        }
    }
    return found;
};

const seasonsIn = (text: string): [number, number][] => {
    const found: [number, number][] = [];
    SEASON.lastIndex = 0;
    let m: RegExpExecArray | null;
    while ((m = SEASON.exec(text)) !== null) {
        const a = m[2] ?? m[4];
        const b = m[3] ?? m[5];
        const from = parseInt(a, 10);
        const to = b !== undefined ? Math.max(from, parseInt(b, 10)) : from;
        found.push([from, to]);
    }
    return found;
};

// The show's name as the file gives it: the words before the first episode tag on that line.
const titleBefore = (text: string, at: number): string[] => {
    const lineStart = text.lastIndexOf('\n', at) + 1;
    return words(text.slice(lineStart, at));
};

export const sourceFit = (text: string, want: Want | null | undefined): Fit => {
    if (!want || typeof want.season !== 'number' || typeof want.episode !== 'number' || want.season <= 0) return 0;
    const season = want.season, episode = want.episode;
    const found = episodesIn(text);
    if (found.length > 0) {
        const hit = found.find((f) => f.season === season && episode >= f.from && episode <= f.to);
        if (!hit) return 2;
        return titleFits(text, hit.at, want.name) ? 0 : 1;
    }
    if (/complete|integrale|all[ ._-]seasons/i.test(text)) return 0;
    const packs = seasonsIn(text);
    if (packs.length > 0 && !packs.some(([from, to]) => season >= from && season <= to)) return 2;
    return 0;
};

const titleFits = (text: string, at: number, name: string | null | undefined): boolean => {
    if (typeof name !== 'string') return true;
    const show = words(name).filter((w) => !SMALL_WORDS.has(w));
    if (show.length === 0) return true; // a name in another alphabet, or only small words: can't tell
    const file = titleBefore(text, at).filter((w) => !SMALL_WORDS.has(w));
    if (file.length === 0) return true; // nothing before the tag ("S02E05 - Pilot.mkv")
    return file.some((w) => show.includes(w));
};

// "tt0386676:2:5" -> season 2, episode 5 (null for movies and ids without a season).
export const wantFromVideoId = (videoId: string | null | undefined, name?: string | null): Want | null => {
    if (typeof videoId !== 'string') return null;
    // IMDb episodes ("tt…:season:episode") and TMDB ones ("tmdb:…:season:episode"). Others (Kitsu "kitsu:id:episode")
    // count episodes without seasons, so they aren't checked.
    const parts = videoId.split(':');
    if (!(parts.length === 3 && /^tt\d+$/.test(parts[0])) && !(parts.length === 4 && parts[0] === 'tmdb')) return null;
    const season = parseInt(parts[parts.length - 2], 10);
    const episode = parseInt(parts[parts.length - 1], 10);
    if (!isFinite(season) || !isFinite(episode) || season <= 0) return null;
    return { name: name ?? null, season, episode };
};
