import Foundation

/// Works out which language an audio track is in, from its language tag or (when the tag is missing) its name,
/// so Codio picks the viewer's audio language however the file orders or labels its tracks.
/// The same rules live in the Android app (AudioLang.java) and the Windows app (useAudio.ts).
enum AudioLang {
    /// The audio-language setting's codes, with every other way a file may write them.
    private static let table: [(code: String, aliases: [String])] = [
        ("eng", ["eng", "en", "english"]),
        ("spa", ["spa", "es", "spanish", "español", "espanol", "castellano", "latino"]),
        ("fre", ["fre", "fra", "fr", "french", "français", "francais"]),
        ("ger", ["ger", "deu", "de", "german", "deutsch"]),
        ("ita", ["ita", "it", "italian", "italiano"]),
        ("por", ["por", "pt", "portuguese", "português", "portugues"]),
        ("nld", ["nld", "dut", "nl", "dutch", "nederlands", "flemish"]),
        ("pol", ["pol", "pl", "polish", "polski"]),
        ("rus", ["rus", "ru", "russian", "русский"]),
        ("tur", ["tur", "tr", "turkish", "türkçe", "turkce"]),
        ("ara", ["ara", "ar", "arabic", "العربية"]),
        ("hin", ["hin", "hi", "hindi", "हिन्दी"]),
        ("jpn", ["jpn", "ja", "japanese", "日本語"]),
        ("kor", ["kor", "ko", "korean", "한국어"]),
        ("zho", ["zho", "chi", "zh", "chinese", "mandarin", "cantonese", "中文"]),
    ]

    /// Tags that say nothing about the language.
    private static let unknown: Set<String> = ["", "und", "unk", "unknown", "mul", "zxx", "mis", "qaa", "none"]

    /// The setting's code (e.g. "eng") for a tag like "en", "eng", "en-US" or "English"; nil when unknown.
    static func code(_ tag: String?) -> String? {
        guard var t = tag?.trimmingCharacters(in: .whitespaces).lowercased(), !unknown.contains(t) else { return nil }
        if let cut = t.firstIndex(where: { $0 == "-" || $0 == "_" }), t.distance(from: t.startIndex, to: cut) <= 3 {
            t = String(t[..<cut]) // "en-US" -> "en"
        }
        return table.first { $0.aliases.contains(t) }?.code
    }

    /// The language a track's name mentions, e.g. "English - DTS-HD MA 5.1" or "Track 2 - [Français]".
    /// Two-letter codes are too easy to misread in a name, so only names and three-letter codes count.
    static func codeFromName(_ name: String?) -> String? {
        guard let n = name?.lowercased(), !n.isEmpty else { return nil }
        let words = n.components(separatedBy: CharacterSet.letters.inverted).filter { $0.count >= 3 }
        for w in words {
            if let entry = table.first(where: { $0.aliases.contains(w) }) { return entry.code }
        }
        return nil
    }

    /// The track's language: its tag first, then its name.
    static func trackCode(lang: String?, name: String?) -> String? {
        code(lang) ?? codeFromName(name)
    }

    /// Commentary and audio-description tracks are only picked when nothing else is in the language.
    static func isExtra(_ name: String?) -> Bool {
        guard let n = name?.lowercased() else { return false }
        return n.contains("comment") || n.contains("descriptive") || n.contains("audio description")
            || n.contains("visually impaired")
    }

    /// The viewer's audio language as a setting code ("eng" when not set).
    static func preferred(_ setting: Any?) -> String {
        code(setting as? String) ?? "eng"
    }

    /// Index of the track to play for `preferred`, or nil when no track is in that language.
    /// Keeps `current` when it already fits; otherwise the first main (non-commentary) track in the language.
    static func best(_ tracks: [(lang: String?, name: String?)], preferred: String, current: Int?) -> Int? {
        let fits = tracks.indices.filter { trackCode(lang: tracks[$0].lang, name: tracks[$0].name) == preferred }
        guard !fits.isEmpty else { return nil }
        let main = fits.filter { !isExtra(tracks[$0].name) }
        if let c = current, (main.isEmpty ? fits : main).contains(c) { return c }
        return main.first ?? fits.first
    }
}
