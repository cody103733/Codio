import Foundation

/// Song lyrics from LRCLIB (lrclib.net, free, no key), timed line by line when LRCLIB has them. The same rules as
/// android/.../music/Lyrics.java and windows/.../music/lyrics.ts (DESIGN.md "Lyrics"): an exact lookup by artist, title,
/// album and length first, then a search whose answer must be the same recording. Answers are cached a month; downloaded
/// songs keep theirs next to the song (MusicDownloads).
struct LyricLine: Hashable {
    let ms: Int
    let text: String
}

struct SongLyrics {
    var lines: [LyricLine] = []
    var plain: String?
    var instrumental = false

    var synced: Bool { !lines.isEmpty }
    var isEmpty: Bool { !instrumental && lines.isEmpty && (plain?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true) }

    /// The line playing at this point of the song, or -1 before the first one.
    func line(at seconds: Double) -> Int {
        let ms = Int(seconds * 1000) + 250
        var found = -1
        for (i, l) in lines.enumerated() {
            if l.ms <= ms { found = i } else { break }
        }
        return found
    }

    init() {}

    init(json o: [String: Any]) {
        instrumental = o["instrumental"] as? Bool ?? false
        if let p = o["plainLyrics"] as? String, !p.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { plain = p }
        lines = Lyrics.parse(o["syncedLyrics"] as? String)
    }

    var json: [String: Any] {
        var o: [String: Any] = [:]
        if instrumental { o["instrumental"] = true }
        if let p = plain { o["plainLyrics"] = p }
        if !lines.isEmpty {
            o["syncedLyrics"] = lines.map { l -> String in
                let cs = l.ms / 10
                return String(format: "[%02d:%02d.%02d]", cs / 6000, (cs / 100) % 60, cs % 100) + l.text
            }.joined(separator: "\n")
        }
        return o
    }
}

enum Lyrics {
    static let credit = "Lyrics from LRCLIB"
    private static let base = "https://lrclib.net"
    private static let tag = try! NSRegularExpression(pattern: "^\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)\\]")
    private static let offsetTag = try! NSRegularExpression(pattern: "^\\[offset:\\s*([+-]?\\d+)\\]", options: .caseInsensitive)

    /// Turns LRC text ("[01:02.50]words") into timed lines. A line with several times appears once per time.
    static func parse(_ lrc: String?) -> [LyricLine] {
        guard let lrc = lrc else { return [] }
        var out: [LyricLine] = []
        var offset = 0
        for raw in lrc.components(separatedBy: .newlines) {
            var line = raw.trimmingCharacters(in: .whitespaces)
            let ns = line as NSString
            if let m = offsetTag.firstMatch(in: line, range: NSRange(location: 0, length: ns.length)) {
                offset = Int(ns.substring(with: m.range(at: 1)).replacingOccurrences(of: "+", with: "")) ?? 0
                continue
            }
            var times: [Int] = []
            while true {
                let s = line as NSString
                guard let m = tag.firstMatch(in: line, range: NSRange(location: 0, length: s.length)) else { break }
                let minutes = Double(s.substring(with: m.range(at: 1))) ?? 0
                let seconds = Double(s.substring(with: m.range(at: 2)).replacingOccurrences(of: ":", with: ".")) ?? 0
                times.append(Int(((minutes * 60 + seconds) * 1000).rounded()))
                line = s.substring(from: m.range.location + m.range.length)
            }
            let text = line.trimmingCharacters(in: .whitespaces)
            for t in times { out.append(LyricLine(ms: max(0, t - offset), text: text)) }
        }
        return out.sorted { $0.ms < $1.ms }
    }

    private static func enc(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? ""
    }

    struct Unreachable: Error {}

    /// The lyrics for a song, or nil when LRCLIB has none. Throws when LRCLIB can't be reached.
    static func find(_ t: MusicTrack, durationMs: Int) async throws -> SongLyrics? {
        if let saved = await MainActor.run(body: { MusicDownloads.shared.savedLyrics(t) }) { return saved.isEmpty ? nil : saved }
        let length = durationMs > 0 ? durationMs : (t.durationMs ?? 0)
        if length > 0 {
            var url = "\(base)/api/get?artist_name=\(enc(t.artist))&track_name=\(enc(t.title))"
            if let a = t.album { url += "&album_name=\(enc(a))" }
            url += "&duration=\(Int((Double(length) / 1000).rounded()))"
            let r = await MusicNet.metadata(url, ttl: 30 * MusicNet.day, server: "lrclib", gap: 0.3)
            if r.status == 200, let o = r.json as? [String: Any] {
                let l = SongLyrics(json: o)
                if !l.isEmpty { return l }
            } else if r.status != 404 {
                throw Unreachable()
            }
        }
        let r = await MusicNet.metadata("\(base)/api/search?artist_name=\(enc(t.artist))&track_name=\(enc(t.title))", ttl: 30 * MusicNet.day, server: "lrclib", gap: 0.3)
        if r.status == 404 { return nil }
        guard r.status == 200, let list = r.json as? [[String: Any]] else { throw Unreachable() }
        let wanted = MusicTrack(id: "", title: t.title, artist: t.artist, durationMs: length > 0 ? length : nil)
        var plainOnly: SongLyrics?
        for o in list {
            let seconds = (o["duration"] as? Double) ?? Double(o["duration"] as? Int ?? 0)
            let candidate = MusicTrack(id: "", title: o["trackName"] as? String ?? "", artist: o["artistName"] as? String ?? "",
                                       durationMs: seconds > 0 ? Int(seconds * 1000) : nil)
            guard MusicMatch.isSameRecording(wanted, candidate) else { continue }
            let l = SongLyrics(json: o)
            if l.isEmpty { continue }
            if l.synced { return l }
            if plainOnly == nil { plainOnly = l }
        }
        return plainOnly
    }
}

/// Lyrics for the screens: each song is looked up once a run (a failed lookup is tried again next time).
@MainActor
final class LyricsStore: ObservableObject {
    static let shared = LyricsStore()

    enum State { case loading, found(SongLyrics), none, failed }

    private var memo: [String: SongLyrics?] = [:]
    private var order: [String] = []

    func get(_ t: MusicTrack, durationMs: Int) async -> State {
        if let hit = memo[t.key] { return hit.map { .found($0) } ?? .none }
        do {
            let l = try await Lyrics.find(t, durationMs: durationMs)
            memo[t.key] = .some(l)
            order.append(t.key)
            if order.count > 40 { memo[order.removeFirst()] = nil }
            return l.map { .found($0) } ?? .none
        } catch {
            return .failed
        }
    }
}
