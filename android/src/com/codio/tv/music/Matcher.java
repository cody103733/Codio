package com.codio.tv.music;

import com.codio.tv.music.Model.Addon;
import com.codio.tv.music.Model.Results;
import com.codio.tv.music.Model.Track;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Finding the audio for a track (windows/src/routes/Codio/music/match.ts): ask each music addon, in the order you put them,
 * and trust only an exact answer.
 *   1. The track came from this addon: use its own id.
 *   2. The track has an ISRC and the addon lists "isrc": /resolve-isrc.
 *   3. The addon lists "resolve": /resolve with title, artist and length.
 *   4. Otherwise search "artist title" and take a result only if it passes the strict check.
 * A wrong song is worse than none, so a doubtful match counts as a miss and the next addon is tried.
 */
public final class Matcher {
    private Matcher() {}

    public static final long DURATION_TOLERANCE_MS = 3000;

    /** Lower case, no accents, no "(Remastered 2011)" / "- Live" style tails, no punctuation. */
    public static String normalise(String text) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.US);
        s = s.replaceAll("\\s*[(\\[][^)\\]]*(remaster|version|edit|mix|mono|stereo|deluxe|bonus|feat|ft\\.|with)[^)\\]]*[)\\]]", "");
        s = s.replaceAll("\\s+-\\s+(remaster(ed)?( \\d{4})?|\\d{4} remaster(ed)?|mono|stereo|single version|radio edit)$", "");
        s = s.replace("&", " and ").replaceAll("[^a-z0-9]+", " ").trim();
        return s;
    }

    private static boolean artistMatches(String wanted, String got) {
        String a = normalise(wanted).replaceFirst("^the ", ""), b = normalise(got).replaceFirst("^the ", "");
        if (a.length() == 0 || b.length() == 0) return false;
        return a.equals(b) || a.startsWith(b + " ") || b.startsWith(a + " ") || a.contains(" " + b + " ") || b.contains(" " + a + " ");
    }

    /**
     * Is candidate the recording wanted? An equal ISRC says yes outright; otherwise title and artist must agree and the
     * length must be within a few seconds (when both are known).
     */
    public static boolean isSameRecording(Track wanted, Track candidate) {
        if (wanted.isrc != null && candidate.isrc != null) return wanted.isrc.equalsIgnoreCase(candidate.isrc);
        String wt = normalise(wanted.title);
        if (!wt.equals(normalise(candidate.title)) || wt.length() == 0) return false;
        if (!artistMatches(wanted.artist, candidate.artist)) return false;
        if (wanted.durationMs > 0 && candidate.durationMs > 0 && Math.abs(wanted.durationMs - candidate.durationMs) > DURATION_TOLERANCE_MS) return false;
        return true;
    }

    public static final class Playable {
        public String url, quality, format;
        public Addon addon;
    }

    /** Looks up an ISRC for a track that has none (the track's MusicBrainz recording). */
    public interface IsrcLookup {
        String find() throws Exception;
    }

    private static Playable via(Addon addon, String id, String direct, Addons.Waiting waiting) throws Exception {
        Playable p = new Playable();
        p.addon = addon;
        if (direct != null) {
            p.url = direct;
            return p;
        }
        Addons.Stream s = Addons.streamFor(addon, id, waiting);
        if (s == null) return null;
        p.url = s.url;
        p.quality = s.quality;
        p.format = s.format;
        return p;
    }

    /** Blocking: run it on a background thread. Null when no addon has the song. */
    public static Playable findAudio(Track track, List<Addon> addons, IsrcLookup isrcLookup) {
        return findAudio(track, addons, isrcLookup, null);
    }

    /** As above; waiting (may be null) hears "still preparing" texts and can stop the search when the song is skipped. */
    public static Playable findAudio(Track track, List<Addon> addons, IsrcLookup isrcLookup, Addons.Waiting waiting) {
        Track wanted = track;
        boolean isrcTried = track.isrc != null;
        for (Addon addon : addons) {
            if (waiting != null && waiting.cancelled()) return null;
            try {
                if (addon.url.equals(track.addon) && !track.id.startsWith("mbid:")) {
                    Playable own = via(addon, track.id, track.streamURL, waiting);
                    if (own != null) return own;
                }
                if (wanted.isrc == null && !isrcTried && isrcLookup != null && (addon.has("isrc") || addon.has("resolve"))) {
                    isrcTried = true;
                    String code = null;
                    try {
                        code = isrcLookup.find();
                    } catch (Exception ignored) {
                    }
                    if (code != null) {
                        wanted = copy(track);
                        wanted.isrc = code;
                    }
                }
                if (wanted.isrc != null && addon.has("isrc")) {
                    String id = Addons.resolveIsrc(addon, wanted.isrc);
                    if (id != null) {
                        Playable hit = via(addon, id, null, waiting);
                        if (hit != null) return hit;
                    }
                    continue;
                }
                if (addon.has("resolve")) {
                    Track item = Addons.resolveTrack(addon, wanted);
                    if (item != null) {
                        Playable hit = via(addon, item.id, item.streamURL, waiting);
                        if (hit != null) return hit;
                    }
                    continue;
                }
                if (addon.has("search")) {
                    Results found = Addons.search(addon, (wanted.artist + " " + wanted.title).trim());
                    for (Track t : found.tracks) {
                        if (!isSameRecording(wanted, t)) continue;
                        Playable hit = via(addon, t.id, t.streamURL, waiting);
                        if (hit != null) return hit;
                        break;
                    }
                }
            } catch (Exception ignored) {
                // this addon failed: try the next one
            }
        }
        return null;
    }

    private static Track copy(Track t) {
        return Track.fromJson(t.toJson());
    }
}
