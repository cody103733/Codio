package com.codio.tv;

import java.util.Locale;

/**
 * Works out which language an audio track is in, from its language tag or (when the tag is missing) its name, so
 * Codio plays the viewer's audio language however the file orders or labels its tracks. The same rules live in the
 * iPhone app (AudioLang.swift) and the Windows app (useAudio.ts).
 */
public final class AudioLang {
    private AudioLang() {}

    /** The audio-language setting's codes, each with every other way a file may write it. */
    private static final String[][] TABLE = {
            {"eng", "en", "english"},
            {"spa", "es", "spanish", "español", "espanol", "castellano", "latino"},
            {"fre", "fra", "fr", "french", "français", "francais"},
            {"ger", "deu", "de", "german", "deutsch"},
            {"ita", "it", "italian", "italiano"},
            {"por", "pt", "portuguese", "português", "portugues"},
            {"nld", "dut", "nl", "dutch", "nederlands", "flemish"},
            {"pol", "pl", "polish", "polski"},
            {"rus", "ru", "russian", "русский"},
            {"tur", "tr", "turkish", "türkçe", "turkce"},
            {"ara", "ar", "arabic", "العربية"},
            {"hin", "hi", "hindi", "हिन्दी"},
            {"jpn", "ja", "japanese", "日本語"},
            {"kor", "ko", "korean", "한국어"},
            {"zho", "chi", "zh", "chinese", "mandarin", "cantonese", "中文"},
    };

    private static String lookup(String word) {
        for (String[] row : TABLE) for (String a : row) if (a.equals(word)) return row[0];
        return null;
    }

    /** The setting's code (e.g. "eng") for a tag like "en", "eng", "en-US" or "English"; null when unknown. */
    public static String code(String tag) {
        if (tag == null) return null;
        String t = tag.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "": case "und": case "unk": case "unknown": case "mul": case "zxx": case "mis": case "qaa": case "none":
                return null;
            default:
                break;
        }
        int cut = -1;
        for (int i = 0; i < t.length() && i <= 3; i++) if (t.charAt(i) == '-' || t.charAt(i) == '_') { cut = i; break; }
        if (cut > 0) t = t.substring(0, cut); // "en-US" -> "en"
        return lookup(t);
    }

    /**
     * The language a track's name mentions, e.g. "English - DTS-HD MA 5.1" or "Track 2 - [Français]". Two-letter
     * codes are too easy to misread in a name, so only names and three-letter codes count.
     */
    public static String codeFromName(String name) {
        if (name == null) return null;
        for (String w : name.toLowerCase(Locale.ROOT).split("[^\\p{L}]+")) {
            if (w.length() < 3) continue;
            String c = lookup(w);
            if (c != null) return c;
        }
        return null;
    }

    /** The track's language: its tag first, then its name. */
    public static String trackCode(String lang, String name) {
        String c = code(lang);
        return c != null ? c : codeFromName(name);
    }

    /** Commentary and audio-description tracks are only picked when nothing else is in the language. */
    public static boolean isExtra(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("comment") || n.contains("descriptive") || n.contains("audio description") || n.contains("visually impaired");
    }

    /** The viewer's audio language as a setting code ("eng" when not set). */
    public static String preferred() {
        String c = code(com.codio.tv.ui.Store.audioLanguage());
        return c != null ? c : "eng";
    }

    /** The viewer's language as mpv's alang list, e.g. "eng,en". */
    public static String mpvTags() {
        String pref = preferred();
        StringBuilder b = new StringBuilder();
        for (String[] row : TABLE) {
            if (!row[0].equals(pref)) continue;
            for (String t : row) {
                if (t.length() > 3 || !t.matches("[a-z]+")) continue;
                if (b.length() > 0) b.append(',');
                b.append(t);
            }
        }
        return b.length() > 0 ? b.toString() : "eng,en";
    }

    /** True when the track is in the viewer's language. */
    public static boolean isPreferred(String lang, String name) {
        return preferred().equals(trackCode(lang, name));
    }
}
