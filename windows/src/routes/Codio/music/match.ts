// Finding the audio for a track: ask each music addon, in the order you put them, and trust only an exact answer.
//   1. The track came from this addon: use its own id.
//   2. The track has an ISRC and the addon lists "isrc": /resolve-isrc.
//   3. The addon lists "resolve": /resolve with title, artist and length.
//   4. Otherwise search "artist title" and take a result only if it passes the strict check below.
// A wrong song is worse than none, so a doubtful match counts as a miss and the next addon is tried.

import type { MusicAddon, MusicTrack } from './types';
import { has, resolveIsrc, resolveTrack, searchAddon, streamFor, type Stream, type Waiting } from './addons';

export const DURATION_TOLERANCE_MS = 3000;

// Lower case, no accents, no "(Remastered 2011)" / "- Live" style tails, no punctuation.
export const normalise = (text: string) => text
    .normalize('NFD').replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/\s*[([][^)\]]*(remaster|version|edit|mix|mono|stereo|deluxe|bonus|feat|ft\.|with)[^)\]]*[)\]]/g, '')
    .replace(/\s+-\s+(remaster(ed)?( \d{4})?|\d{4} remaster(ed)?|mono|stereo|single version|radio edit)$/g, '')
    .replace(/&/g, ' and ')
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();

const artistMatches = (wanted: string, got: string) => {
    const a = normalise(wanted).replace(/^the /, ''), b = normalise(got).replace(/^the /, '');
    if (a.length === 0 || b.length === 0) return false;
    return a === b || a.startsWith(`${b} `) || b.startsWith(`${a} `) || a.includes(` ${b} `) || b.includes(` ${a} `);
};

// Is `candidate` the recording `wanted`? An equal ISRC says yes outright; otherwise title and artist must agree and the
// length must be within a few seconds (when both are known).
export const isSameRecording = (wanted: MusicTrack, candidate: MusicTrack) => {
    if (wanted.isrc && candidate.isrc) return wanted.isrc.toUpperCase() === candidate.isrc.toUpperCase();
    if (normalise(wanted.title) !== normalise(candidate.title) || normalise(wanted.title).length === 0) return false;
    if (!artistMatches(wanted.artist, candidate.artist)) return false;
    if (wanted.durationMs && candidate.durationMs && Math.abs(wanted.durationMs - candidate.durationMs) > DURATION_TOLERANCE_MS) return false;
    return true;
};

export type Playable = { url: string, addon: MusicAddon, quality: string | null, format: string | null };

const viaId = async (addon: MusicAddon, id: string, direct?: string | null, waiting?: Waiting): Promise<Playable | null> => {
    if (direct) return { url: direct, addon, quality: null, format: null };
    const s: Stream | null = await streamFor(addon, id, waiting);
    return s ? { url: s.url, addon, quality: s.quality, format: s.format } : null;
};

export const findAudio = async (track: MusicTrack, addons: MusicAddon[], isrcLookup?: () => Promise<string | null>, waiting?: Waiting): Promise<Playable | null> => {
    let wanted = track;
    let isrcTried = !!track.isrc;
    for (const addon of addons) {
        if (waiting?.cancelled?.()) return null;
        try {
            if (track.addon === addon.url && !track.id.startsWith('mbid:')) {
                const own = await viaId(addon, track.id, track.streamURL, waiting);
                if (own) return own;
            }
            if (!wanted.isrc && !isrcTried && isrcLookup && (has(addon, 'isrc') || has(addon, 'resolve'))) {
                isrcTried = true;
                const code = await isrcLookup().catch(() => null);
                if (code) wanted = { ...track, isrc: code };
            }
            if (wanted.isrc && has(addon, 'isrc')) {
                const id = await resolveIsrc(addon, wanted.isrc);
                if (id) {
                    const hit = await viaId(addon, id, null, waiting);
                    if (hit) return hit;
                }
                continue;
            }
            if (has(addon, 'resolve')) {
                const item = await resolveTrack(addon, wanted);
                if (item) {
                    const hit = await viaId(addon, item.id, item.streamURL, waiting);
                    if (hit) return hit;
                }
                continue;
            }
            if (has(addon, 'search')) {
                const found = await searchAddon(addon, `${wanted.artist} ${wanted.title}`.trim());
                const best = found.tracks.find((t) => isSameRecording(wanted, t));
                if (best) {
                    const hit = await viaId(addon, best.id, best.streamURL, waiting);
                    if (hit) return hit;
                }
            }
        } catch (_e) { /* this addon failed: try the next one */ }
    }
    return null;
};
