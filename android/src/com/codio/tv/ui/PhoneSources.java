package com.codio.tv.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Picks what to download from a source list: one source per quality (same rules as auto-play's quality detection). */
final class PhoneSources {
    private PhoneSources() {}

    static final String[] QUALITIES = {"2160p", "1440p", "1080p", "720p", "480p"};

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(^|[^a-z0-9])(2160p|4k|uhd)([^a-z0-9]|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|[^a-z0-9])(1440p|2k|qhd)([^a-z0-9]|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|[^a-z0-9])(1080p|1080i|fhd)([^a-z0-9]|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|[^a-z0-9])(720p)([^a-z0-9]|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(^|[^a-z0-9])(576p|480p|360p|240p|sd|dvdrip)([^a-z0-9]|$)", Pattern.CASE_INSENSITIVE),
    };
    private static final Pattern SIZE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(TB|GB|MB|TiB|GiB|MiB)(?![a-z])", Pattern.CASE_INSENSITIVE);
    private static final Pattern CACHED = Pattern.compile("\\[(RD|AD|PM|DL|TB|OC|ED|EN|PKP|DLS|ST|TRB)\\+\\]|⚡|(^|[^a-z])(instant|cached)([^a-z]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNCACHED = Pattern.compile("\\[(RD|AD|PM|DL|TB|OC|ED|EN|PKP|DLS|ST|TRB) ?download\\]|⏳|(^|[^a-z])uncached([^a-z]|$)", Pattern.CASE_INSENSITIVE);

    /** One downloadable choice. */
    static final class Option {
        String quality;
        long bytes;
        Store.Source source;
    }

    static String text(Store.Source s) {
        return (s.name != null ? s.name : "") + " \n " + (s.description != null ? s.description : "");
    }

    static String quality(Store.Source s) {
        if (s.quality != null) return s.quality;
        String t = text(s);
        for (int i = 0; i < PATTERNS.length; i++) if (PATTERNS[i].matcher(t).find()) return QUALITIES[i];
        return null;
    }

    static long bytes(Store.Source s) {
        Matcher m = SIZE.matcher(text(s));
        if (!m.find()) return -1;
        double v;
        try {
            v = Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
        String u = m.group(2).toUpperCase();
        double mult = u.startsWith("T") ? 1e12 : u.startsWith("G") ? 1e9 : 1e6;
        return (long) (v * mult);
    }

    static boolean downloadable(Store.Source s) {
        return s.url != null && (s.url.startsWith("http://") || s.url.startsWith("https://"))
                && !s.url.startsWith("http://127.0.0.1");
    }

    /** Instant (debrid-ready) sources first; ones marked "download"/uncached would only start caching. */
    private static int score(Store.Source s) {
        String t = text(s);
        if (UNCACHED.matcher(t).find()) return 2;
        if (s.cached || CACHED.matcher(t).find()) return 0;
        return 1;
    }

    /** Best source per quality, best quality first. */
    static List<Option> options(Store.Sources src) {
        Map<String, Option> by = new LinkedHashMap<>();
        for (String q : QUALITIES) by.put(q, null);
        Option unknown = null;
        for (Store.Source s : src.list) {
            if (!downloadable(s)) continue;
            String q = quality(s);
            Option cur = q != null ? by.get(q) : unknown;
            if (cur != null && !better(s, cur.source)) continue;
            Option o = new Option();
            o.quality = q;
            o.source = s;
            o.bytes = bytes(s);
            if (q != null) by.put(q, o);
            else unknown = o;
        }
        List<Option> out = new ArrayList<>();
        for (Option o : by.values()) if (o != null) out.add(o);
        if (out.isEmpty() && unknown != null) out.add(unknown);
        return out;
    }

    /** Is a better than b (both the same quality)? Instant first, then auto-play's ranking, then list order. */
    private static boolean better(Store.Source a, Store.Source b) {
        int sa = score(a), sb = score(b);
        if (sa != sb) return sa < sb;
        if (a.rank >= 0 && (b.rank < 0 || a.rank < b.rank)) return true;
        return false;
    }

    /** For a whole season: the wanted quality, else the closest lower one, else the closest higher one. */
    static Option pick(Store.Sources src, String wanted) {
        List<Option> all = options(src);
        if (all.isEmpty()) return null;
        int w = indexOf(wanted);
        for (int i = w; i < QUALITIES.length; i++) for (Option o : all) if (QUALITIES[i].equals(o.quality)) return o;
        for (int i = w - 1; i >= 0; i--) for (Option o : all) if (QUALITIES[i].equals(o.quality)) return o;
        return all.get(0);
    }

    static int indexOf(String q) {
        for (int i = 0; i < QUALITIES.length; i++) if (QUALITIES[i].equals(q)) return i;
        return 2;
    }

    /** File extension from the link or the source's text (mkv/mp4/...), mkv when unknown. */
    static String extension(Store.Source s) {
        String[] known = {"mkv", "mp4", "m4v", "avi", "webm", "mov", "ts"};
        String u = s.url != null ? s.url.toLowerCase() : "";
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        for (String k : known) if (u.endsWith("." + k)) return k;
        String t = text(s).toLowerCase();
        for (String k : known) if (t.contains("." + k)) return k;
        return "mkv";
    }
}
