import Foundation

/// Your stats: watching from the profile's library, listening from a play log on this phone. Same rules as
/// android/.../ui/Stats.java and windows/.../Codio/stats.ts (DESIGN.md "Your stats"). Nothing is sent anywhere.
@MainActor
enum WatchStats {
    private static let key = "stats_watch_v1"
    private static var lastYear: [String: Int] = [:]

    static var thisYear: Int { Calendar.current.component(.year, from: Date()) }

    private static func storeKey(_ profile: String?) -> String { key + "|" + (profile ?? "").lowercased() }

    /// { "seen": year, "base": { "2027": { id: ms } } }
    private static func load(_ profile: String?) -> [String: Any] {
        guard let d = UserDefaults.standard.data(forKey: storeKey(profile)),
              let o = (try? JSONSerialization.jsonObject(with: d)) as? [String: Any] else { return [:] }
        return o
    }

    /// Called whenever the library is read: the first time in a new year, every title's time is saved as that year's start.
    static func observe(_ lib: [String: LibraryEntry], profile: String?) {
        if lib.isEmpty { return }
        let year = thisYear
        let sk = storeKey(profile)
        if lastYear[sk] == year { return }
        var o = load(profile)
        let seen = o["seen"] as? Int
        if seen == nil || seen! < year {
            if seen != nil {
                var start: [String: Double] = [:]
                for e in lib.values where e.overall > 0 { start[e.id] = e.overall }
                var base = (o["base"] as? [String: Any]) ?? [:]
                base[String(year)] = start
                // Only the last few years are needed.
                for k in base.keys where (Int(k) ?? 0) < year - 4 { base[k] = nil }
                o["base"] = base
            }
            o["seen"] = year
            if let d = try? JSONSerialization.data(withJSONObject: o) { UserDefaults.standard.set(d, forKey: sk) }
        }
        lastYear[sk] = year
    }

    struct Title: Identifiable {
        var id: String { entry.id }
        var entry: LibraryEntry
        var ms: Double
    }

    struct Summary {
        var ms: Double = 0
        var shows: [Title] = []
        var movies: [Title] = []
    }

    /// year nil = all time.
    static func summary(_ lib: [String: LibraryEntry], profile: String?, year: Int?) -> Summary {
        var start: [String: Double]?
        if let y = year { start = ((load(profile)["base"] as? [String: Any])?[String(y)] as? [String: Any])?.compactMapValues { ($0 as? NSNumber)?.doubleValue } }
        var out = Summary()
        for e in lib.values where (e.type == "movie" || e.type == "series") && e.overall > 0 {
            var ms = e.overall
            if let y = year {
                guard let lw = e.lastWatched, lw.count >= 4, Int(lw.prefix(4)) == y else { continue }
                if let s = start { ms -= s[e.id] ?? 0 }
            }
            if ms < 60_000 { continue }
            out.ms += ms
            let t = Title(entry: e, ms: ms)
            if e.type == "series" { out.shows.append(t) } else { out.movies.append(t) }
        }
        out.shows.sort { $0.ms > $1.ms }
        out.movies.sort { $0.ms > $1.ms }
        return out
    }
}

/// Hours with one decimal under 10 ("2.5", "14").
func statsHours(_ ms: Double) -> String {
    let h = ms / 3_600_000
    if h < 10 { return String(format: "%.1f", h) }
    return String(Int(h.rounded()))
}

struct ListenSong: Codable { var track: MusicTrack; var count: Int }
struct ListenArtist: Codable { var name: String; var count: Int }
struct ListenYear: Codable {
    var seconds: Double = 0
    var plays: Int = 0
    var songs: [String: ListenSong] = [:]
    var artists: [String: ListenArtist] = [:]
}

@MainActor
final class ListenStats: ObservableObject {
    static let shared = ListenStats()
    private let key = "stats_listen_v1"
    static let maxSongs = 2000

    /// Profile (lower-case email, "" when signed out) -> year -> log.
    @Published private(set) var data: [String: [String: ListenYear]] = [:]

    init() {
        if let d = UserDefaults.standard.data(forKey: key),
           let v = try? JSONDecoder().decode([String: [String: ListenYear]].self, from: d) { data = v }
    }

    static func norm(_ s: String) -> String { s.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) }

    private var profile: String { Profiles.shared.currentEmail ?? "" }

    func record(_ t: MusicTrack) {
        let year = String(WatchStats.thisYear)
        var all = data[profile] ?? [:]
        var y = all[year] ?? ListenYear()
        y.plays += 1
        y.seconds += Double(t.durationMs ?? 0) / 1000
        let sk = ListenStats.norm(t.artist) + "|" + ListenStats.norm(t.title)
        var song = y.songs[sk] ?? ListenSong(track: t, count: 0)
        song.count += 1
        if t.artworkURL != nil { song.track = t }
        y.songs[sk] = song
        let ak = ListenStats.norm(t.artist)
        if !ak.isEmpty { y.artists[ak] = ListenArtist(name: t.artist, count: (y.artists[ak]?.count ?? 0) + 1) }
        if y.songs.count > ListenStats.maxSongs {
            let keep = y.songs.sorted { $0.value.count > $1.value.count }.prefix(ListenStats.maxSongs)
            y.songs = Dictionary(uniqueKeysWithValues: keep.map { ($0.key, $0.value) })
        }
        all[year] = y
        data[profile] = all
        if let d = try? JSONEncoder().encode(data) { UserDefaults.standard.set(d, forKey: key) }
    }

    /// year nil = all years added together.
    func summary(year: Int?) -> ListenYear {
        let all = data[profile] ?? [:]
        if let y = year { return all[String(y)] ?? ListenYear() }
        var out = ListenYear()
        for y in all.values {
            out.seconds += y.seconds
            out.plays += y.plays
            for (k, s) in y.songs {
                var e = out.songs[k] ?? ListenSong(track: s.track, count: 0)
                e.count += s.count
                out.songs[k] = e
            }
            for (k, a) in y.artists { out.artists[k] = ListenArtist(name: a.name, count: (out.artists[k]?.count ?? 0) + a.count) }
        }
        return out
    }
}
