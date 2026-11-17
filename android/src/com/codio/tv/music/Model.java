package com.codio.tv.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The shapes the Music code shares (the same fields as windows/src/routes/Codio/music/types.ts). */
public final class Model {
    private Model() {}

    /** A string value, or null when missing or empty. */
    public static String str(JSONObject o, String key) {
        if (o == null || o.isNull(key)) return null;
        String s = o.optString(key, "");
        return s.length() > 0 ? s : null;
    }

    public static final class Track {
        /** The id the track has where it came from: an addon's own id, or "mbid:&lt;recording id&gt;" from MusicBrainz. */
        public String id;
        public String title = "", artist = "", album, artworkURL, isrc, mbid, artistMbid;
        /** The addon (its base link) this track came from. Playing asks that addon first. */
        public String addon;
        /** A direct audio link the addon gave with the track. */
        public String streamURL;
        public long durationMs;

        public String key() {
            return (addon == null ? "" : addon) + "|" + id;
        }

        public boolean same(Track o) {
            return o != null && key().equals(o.key());
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("title", title).put("artist", artist);
                if (album != null) o.put("album", album);
                if (artworkURL != null) o.put("artworkURL", artworkURL);
                if (isrc != null) o.put("isrc", isrc);
                if (mbid != null) o.put("mbid", mbid);
                if (artistMbid != null) o.put("artistMbid", artistMbid);
                if (addon != null) o.put("addon", addon);
                if (streamURL != null) o.put("streamURL", streamURL);
                if (durationMs > 0) o.put("durationMs", durationMs);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Track fromJson(JSONObject o) {
            if (o == null || str(o, "id") == null) return null;
            Track t = new Track();
            t.id = o.optString("id");
            t.title = o.optString("title", "");
            t.artist = o.optString("artist", "");
            t.album = str(o, "album");
            t.artworkURL = str(o, "artworkURL");
            t.isrc = str(o, "isrc");
            t.mbid = str(o, "mbid");
            t.artistMbid = str(o, "artistMbid");
            t.addon = str(o, "addon");
            t.streamURL = str(o, "streamURL");
            t.durationMs = o.optLong("durationMs", 0);
            return t;
        }
    }

    public static final class Artist {
        public String id, name = "", detail, artworkURL, addon;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("name", name);
                if (detail != null) o.put("detail", detail);
                if (artworkURL != null) o.put("artworkURL", artworkURL);
                if (addon != null) o.put("addon", addon);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Artist fromJson(JSONObject o) {
            if (o == null || str(o, "id") == null) return null;
            Artist a = new Artist();
            a.id = o.optString("id");
            a.name = o.optString("name", "");
            a.detail = str(o, "detail");
            a.artworkURL = str(o, "artworkURL");
            a.addon = str(o, "addon");
            return a;
        }
    }

    public static final class Album {
        public String id, title = "", artist = "", year, kind, artworkURL, addon, artistMbid;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("title", title).put("artist", artist);
                if (year != null) o.put("year", year);
                if (kind != null) o.put("kind", kind);
                if (artworkURL != null) o.put("artworkURL", artworkURL);
                if (addon != null) o.put("addon", addon);
                if (artistMbid != null) o.put("artistMbid", artistMbid);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Album fromJson(JSONObject o) {
            if (o == null || str(o, "id") == null) return null;
            Album a = new Album();
            a.id = o.optString("id");
            a.title = o.optString("title", "");
            a.artist = o.optString("artist", "");
            a.year = str(o, "year");
            a.kind = str(o, "kind");
            a.artworkURL = str(o, "artworkURL");
            a.addon = str(o, "addon");
            a.artistMbid = str(o, "artistMbid");
            return a;
        }
    }

    /** A music addon: its base link (a personal token in it is part of it) and its manifest. */
    public static final class Addon {
        public String url;
        public JSONObject manifest;

        public String id() {
            return manifest.optString("id");
        }

        public String name() {
            return manifest.optString("name", "Addon");
        }

        public boolean has(String resource) {
            JSONArray r = manifest.optJSONArray("resources");
            if (r == null) return false;
            for (int i = 0; i < r.length(); i++) if (resource.equals(r.optString(i))) return true;
            return false;
        }
    }

    public static final class Results {
        public List<Artist> artists = new ArrayList<>();
        public List<Album> albums = new ArrayList<>();
        public List<Track> tracks = new ArrayList<>();

        public int size() {
            return artists.size() + albums.size() + tracks.size();
        }
    }

    public static String formatTime(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) return "0:00";
        int s = (int) seconds;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    public static <T> List<T> list(List<T> in) {
        return in == null ? new ArrayList<T>() : in;
    }
}
