import Foundation

/// Your stats. Codio counts it itself: the seconds a video really played (from the player's clock, so pauses and jumps
/// don't count) and the songs that started, on this phone, for each profile. Every device keeps its own counts in the
/// account (hidden library entry codio:stats, one slot per device), so all of them show the same totals. Same rules and
/// format as android/.../ui/Stats.java and windows/.../Codio/stats.ts (DESIGN.md "Your stats").
@MainActor
enum WatchStats {
    static let itemId = "codio:stats"
    private static let watchKey = "stats_watch_v2", remoteKey = "stats_remote_v1", deviceKey = "stats_device_id"
    private static let api = "https://api.strem.io/api/"
    static let syncTitles = 400, syncSongs = 150, syncArtists = 100

    static var thisYear: Int { Calendar.current.component(.year, from: Date()) }

    /// The profile the counts belong to (lower-case email, "" signed out).
    static var profile: String { Profiles.shared.currentEmail ?? "" }

    static var deviceId: String {
        if let id = UserDefaults.standard.string(forKey: deviceKey) { return id }
        let id = "iphone-" + UUID().uuidString.prefix(8).lowercased()
        UserDefaults.standard.set(id, forKey: deviceKey)
        return id
    }

    static func load(_ key: String) -> JSON {
        guard let d = UserDefaults.standard.data(forKey: key), let o = (try? JSONSerialization.jsonObject(with: d)) as? JSON else { return [:] }
        return o
    }

    static func save(_ key: String, _ o: JSON) {
        if let d = try? JSONSerialization.data(withJSONObject: o) { UserDefaults.standard.set(d, forKey: key) }
    }

    // MARK: - Counting what plays

    private static var playingId: String?
    private static var lastPos = -1.0
    private static var pendingMs = 0.0
    private static var pendingMeta: Meta?
    private static var lastFlush = Date()
    static var dirty = true

    /// The player's clock moved to pos (seconds) while m plays. Only forward steps of up to 3 s count, so a pause, a
    /// seek or a source switch adds nothing. Saved every 30 s of watching and when the video stops.
    static func tick(_ m: Meta, _ pos: Double) {
        guard !m.id.isEmpty else { return }
        if m.id != playingId {
            flush()
            playingId = m.id
            lastPos = pos
            return
        }
        let d = pos - lastPos
        lastPos = pos
        if d > 0, d <= 3 {
            pendingMs += d * 1000
            pendingMeta = m
        }
        if pendingMs >= 30_000 || (pendingMs > 0 && Date().timeIntervalSince(lastFlush) > 60) { flush() }
    }

    static func stopped() {
        flush()
        playingId = nil
        lastPos = -1
    }

    private static func flush() {
        lastFlush = Date()
        guard pendingMs > 0, let m = pendingMeta else { pendingMs = 0; return }
        let add = pendingMs
        pendingMs = 0
        var all = load(watchKey)
        var mine = (all[profile] as? JSON) ?? [:]
        if mine["since"] == nil { mine["since"] = Date().timeIntervalSince1970 * 1000 }
        var years = (mine["years"] as? JSON) ?? [:]
        var year = (years[String(thisYear)] as? JSON) ?? [:]
        let prev = ((year[m.id] as? [Any])?.first as? NSNumber)?.doubleValue ?? 0
        year[m.id] = [(prev + add).rounded(), m.type, m.name, m.poster ?? NSNull()] as [Any]
        years[String(thisYear)] = year
        mine["years"] = years
        all[profile] = mine
        save(watchKey, all)
        dirty = true
    }

    // MARK: - This phone's slot in the account, and everyone's together

    static func mySlot() -> JSON {
        var slot: JSON = ["kind": "iphone", "at": Date().timeIntervalSince1970 * 1000]
        let w = (load(watchKey)[profile] as? JSON) ?? [:]
        if let s = w["since"] { slot["since"] = s }
        var watch: JSON = [:]
        for (y, v) in (w["years"] as? JSON) ?? [:] {
            guard let titles = v as? JSON else { continue }
            let ms = { (k: String) -> Double in (((titles[k] as? [Any])?.first) as? NSNumber)?.doubleValue ?? 0 }
            let keep = titles.keys.sorted { ms($0) > ms($1) }.prefix(syncTitles)
            var out: JSON = [:]
            for k in keep { out[k] = titles[k] }
            watch[y] = out
        }
        slot["watch"] = watch
        var listen: JSON = [:]
        for (y, v) in ListenStats.shared.mine {
            let songs = v.songs.sorted { $0.value.count > $1.value.count }.prefix(syncSongs)
            var s2: JSON = [:]
            for (k, s) in songs {
                if let d = try? JSONEncoder().encode(s.track), let t = try? JSONSerialization.jsonObject(with: d) { s2[k] = [s.count, t] as [Any] }
            }
            var a2: JSON = [:]
            for (k, a) in v.artists.sorted(by: { $0.value.count > $1.value.count }).prefix(syncArtists) { a2[k] = [a.count, a.name] as [Any] }
            listen[y] = ["s": v.seconds.rounded(), "p": v.plays, "songs": s2, "artists": a2] as JSON
        }
        slot["listen"] = listen
        return slot
    }

    /// Every device's slot for the profile: the others as last read from the account, this phone's as it is now.
    static func slots() -> [JSON] {
        var out: [JSON] = []
        let me = deviceId
        for (id, v) in (load(remoteKey)[profile] as? JSON) ?? [:] where id != me {
            if let s = v as? JSON { out.append(s) }
        }
        out.append(mySlot())
        return out
    }

    // MARK: - Account sync

    private static var busy = false
    private static var lastSync = Date.distantPast

    /// Puts this phone's counts in the account and reads the other devices'. At most every 10 minutes unless forced.
    static func sync(_ store: Store, force: Bool = false) async {
        guard !busy, let auth = jstr(store.profile["auth"] as? JSON, "key"), !auth.isEmpty else { return }
        if !force, Date().timeIntervalSince(lastSync) < 600 { return }
        busy = true
        lastSync = Date()
        defer { busy = false }
        flush()
        let who = profile
        do {
            let r = try await call("datastoreGet", ["authKey": auth, "collection": "libraryItem", "ids": [itemId], "all": false])
            var devices: JSON = [:]
            if let items = r["result"] as? [JSON], let it = items.first(where: { ($0["_id"] as? String) == itemId }),
               let name = it["name"] as? String, let box = parseJSON(name) as? JSON, let d = box["devices"] as? JSON {
                devices = d
            }
            var remote = load(remoteKey)
            remote[who] = devices
            save(remoteKey, remote)
            if dirty || devices[deviceId] == nil {
                devices[deviceId] = mySlot()
                let box: JSON = ["v": 1, "devices": devices]
                _ = try await call("datastorePut", ["authKey": auth, "collection": "libraryItem", "changes": [hidden(toJSONString(box))]])
                dirty = false
            }
        } catch {
            lastSync = .distantPast
        }
    }

    private nonisolated static func call(_ method: String, _ body: JSON) async throws -> JSON {
        struct Failure: Error {}
        guard let url = URL(string: api + method) else { throw Failure() }
        var req = URLRequest(url: url, timeoutInterval: 20)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (d, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200, let o = (try? JSONSerialization.jsonObject(with: d)) as? JSON else { throw Failure() }
        if let e = o["error"], !(e is NSNull) { throw Failure() }
        return o
    }

    private nonisolated static func hidden(_ name: String) -> JSON {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let now = f.string(from: Date())
        return [
            "_id": itemId, "name": name, "type": "other", "poster": NSNull(), "posterShape": "poster",
            "removed": true, "temp": true, "_ctime": now, "_mtime": now,
            "state": ["lastWatched": NSNull(), "timeWatched": 0, "timeOffset": 0, "overallTimeWatched": 0,
                      "timesWatched": 0, "flaggedWatched": 0, "duration": 0, "video_id": NSNull(),
                      "watched": NSNull(), "noNotif": true] as JSON,
            "behaviorHints": ["defaultVideoId": NSNull(), "featuredVideoId": NSNull(), "hasScheduledVideos": false] as JSON,
        ]
    }

    // MARK: - What the stats page shows

    struct Title: Identifiable {
        var id: String { entry.id }
        var entry: LibraryEntry
        var ms: Double
    }

    struct Summary {
        var ms: Double = 0
        /// All time only: the Stremio library's own totals beyond what Codio counted (viewing before Codio).
        var before: Double = 0
        /// When Codio started counting on the first of the profile's devices.
        var since: Date?
        var shows: [Title] = []
        var movies: [Title] = []
    }

    /// year nil = all time. lib gives names, posters and the older totals for "before Codio".
    static func summary(_ lib: [String: LibraryEntry], profile _: String? = nil, year: Int?) -> Summary {
        var out = Summary()
        var ms: [String: Double] = [:]
        var info: [String: [Any]] = [:]
        for slot in slots() {
            if let s = (slot["since"] as? NSNumber)?.doubleValue, s > 0 {
                let d = Date(timeIntervalSince1970: s / 1000)
                if out.since == nil || d < out.since! { out.since = d }
            }
            for (y, v) in (slot["watch"] as? JSON) ?? [:] {
                if let year = year, y != String(year) { continue }
                for (id, e) in (v as? JSON) ?? [:] {
                    guard let a = e as? [Any] else { continue }
                    ms[id, default: 0] += (a.first as? NSNumber)?.doubleValue ?? 0
                    info[id] = a
                }
            }
        }
        for (id, t) in ms where t >= 60_000 {
            let a = info[id] ?? []
            let type = lib[id]?.type ?? (a.count > 1 ? a[1] as? String : nil) ?? "movie"
            guard type == "movie" || type == "series" else { continue }
            let entry = lib[id] ?? {
                var o: JSON = ["id": id, "type": type, "name": (a.count > 2 ? a[2] as? String : nil) ?? ""]
                if a.count > 3, let p = a[3] as? String { o["poster"] = p }
                return LibraryEntry(id: id, type: type, meta: Meta.parse(o))
            }()
            out.ms += t
            if type == "series" { out.shows.append(Title(entry: entry, ms: t)) } else { out.movies.append(Title(entry: entry, ms: t)) }
        }
        if year == nil {
            for e in lib.values where (e.type == "movie" || e.type == "series") && e.overall > 0 {
                out.before += max(0, e.overall - (ms[e.id] ?? 0))
            }
        }
        out.shows.sort { $0.ms > $1.ms }
        out.movies.sort { $0.ms > $1.ms }
        return out
    }

    static func sinceLine(_ s: Summary) -> String {
        guard let d = s.since else { return "Codio counts what plays on all your devices, from now on." }
        let f = DateFormatter()
        f.dateFormat = "d MMM yyyy"
        return "Counted by Codio on all your devices since \(f.string(from: d))."
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

    /// Profile (lower-case email, "" when signed out) -> year -> this phone's log.
    @Published private(set) var data: [String: [String: ListenYear]] = [:]

    init() {
        if let d = UserDefaults.standard.data(forKey: key),
           let v = try? JSONDecoder().decode([String: [String: ListenYear]].self, from: d) { data = v }
    }

    static func norm(_ s: String) -> String { s.lowercased().trimmingCharacters(in: .whitespacesAndNewlines) }

    private var profile: String { Profiles.shared.currentEmail ?? "" }

    /// This phone's log for the current profile.
    var mine: [String: ListenYear] { data[profile] ?? [:] }

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
        WatchStats.dirty = true
    }

    /// year nil = all years added together; every device of the profile (from the account).
    func summary(year: Int?) -> ListenYear {
        var out = ListenYear()
        for slot in WatchStats.slots() {
            for (k, v) in (slot["listen"] as? JSON) ?? [:] {
                if let y = year, k != String(y) { continue }
                guard let y = v as? JSON else { continue }
                out.seconds += (y["s"] as? NSNumber)?.doubleValue ?? 0
                out.plays += (y["p"] as? NSNumber)?.intValue ?? 0
                for (sk, sv) in (y["songs"] as? JSON) ?? [:] {
                    guard let a = sv as? [Any], a.count > 1, let n = (a[0] as? NSNumber)?.intValue else { continue }
                    if var e = out.songs[sk] {
                        e.count += n
                        out.songs[sk] = e
                    } else if let d = try? JSONSerialization.data(withJSONObject: a[1]), let t = try? JSONDecoder().decode(MusicTrack.self, from: d) {
                        out.songs[sk] = ListenSong(track: t, count: n)
                    }
                }
                for (ak, av) in (y["artists"] as? JSON) ?? [:] {
                    guard let a = av as? [Any], a.count > 1, let n = (a[0] as? NSNumber)?.intValue else { continue }
                    out.artists[ak] = ListenArtist(name: out.artists[ak]?.name ?? (a[1] as? String) ?? ak, count: (out.artists[ak]?.count ?? 0) + n)
                }
            }
        }
        return out
    }
}
