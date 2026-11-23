import Foundation
import Network

/// Watching a download counts toward the account like streaming does. A download plays straight from the phone, so
/// the core (which saves progress for streams) never hears about it. This keeps a small queue on the phone
/// (UserDefaults offline_progress_v1: profile -> library id -> where you stopped) and, as soon as there is internet
/// and a signed-in account, writes it into the account's library item (Continue Watching, resume point, watched
/// time), then asks the core to pull the library. Same rules as android/.../ui/OfflineProgress.java.
@MainActor
enum OfflineProgress {
    private static let key = "offline_progress_v1"
    private static let api = "https://api.strem.io/api/"

    /// After the account was updated: the engine asks the core to sync its library.
    static var onPushed: (() -> Void)?
    /// Gives the signed-in account's key when the network comes back.
    static var authKey: (() -> String?)?

    // MARK: - Counting what really plays (forward steps of up to 3 s)

    private static var playing: String?
    private static var lastPos = -1.0
    private static var playedMs = 0.0
    private static var lastSaved = -100.0

    /// The player's clock while a download plays. Saves where it is every 15 s, in case the app is closed.
    static func tick(_ m: Meta, _ videoId: String, _ pos: Double, _ dur: Double) {
        if videoId != playing {
            playing = videoId
            playedMs = 0
            lastPos = pos
            lastSaved = pos
            return
        }
        let d = pos - lastPos
        lastPos = pos
        if d > 0, d <= 3 { playedMs += d * 1000 }
        if abs(pos - lastSaved) > 15 { record(m, videoId, pos, dur, ended: false) }
    }

    private static func takePlayed(_ videoId: String) -> Double {
        guard videoId == playing else { return 0 }
        let ms = playedMs
        playedMs = 0
        return ms
    }

    // MARK: - The queue

    private static func load() -> JSON {
        guard let d = UserDefaults.standard.data(forKey: key), let o = (try? JSONSerialization.jsonObject(with: d)) as? JSON else { return [:] }
        return o
    }

    private static func save(_ o: JSON) {
        if let d = try? JSONSerialization.data(withJSONObject: o) { UserDefaults.standard.set(d, forKey: key) }
    }

    private static var profile: String { Profiles.shared.currentEmail ?? "" }

    /// Where a download stopped (seconds): while it plays, when it's closed and when it ends. The latest wins; the
    /// played time adds up until it reaches the account.
    static func record(_ m: Meta, _ videoId: String, _ pos: Double, _ dur: Double, ended: Bool) {
        guard !m.id.isEmpty, pos.isFinite, dur.isFinite else { return }
        lastSaved = pos
        let add = takePlayed(videoId)
        var all = load()
        var mine = all[profile] as? JSON ?? [:]
        var before = 0.0
        var done = ended || (dur > 0 && pos / dur > 0.9)
        if let q = mine[m.id] as? JSON {
            if jstr(q, "video") == videoId {
                before = jdouble(q, "add") ?? 0
                if jbool(q, "done") == true { done = true }
            } else {
                mine[m.id + "#" + (jstr(q, "video") ?? "")] = q // an earlier episode: still sent, first
            }
        }
        mine[m.id] = [
            "type": m.type, "name": m.name, "poster": m.poster ?? NSNull(), "video": videoId,
            "offset": done ? 0 : (max(0, pos) * 1000).rounded(), "dur": (max(0, dur) * 1000).rounded(),
            "add": (before + add).rounded(), "done": done, "at": (Date().timeIntervalSince1970 * 1000).rounded(),
        ] as JSON
        all[profile] = mine
        save(all)
    }

    static var pending: Bool { !((load()[profile] as? JSON) ?? [:]).isEmpty }

    // MARK: - Into the account

    private static var busy = false
    private static var monitor: NWPathMonitor?

    /// Back online: what was watched from downloads meanwhile goes to the account.
    static func watchNetwork() {
        guard monitor == nil else { return }
        let m = NWPathMonitor()
        m.pathUpdateHandler = { path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: 3_000_000_000)
                if let k = authKey?() { await flush(k) }
            }
        }
        m.start(queue: DispatchQueue(label: "codio.offline-progress"))
        monitor = m
    }

    /// Puts everything waiting into the account. Quietly keeps it for later when there is no internet.
    static func flush(_ auth: String?) async {
        guard let auth = auth, !auth.isEmpty, !busy, pending else { return }
        busy = true
        defer { busy = false }
        let who = profile
        guard let mine = load()[who] as? JSON else { return }
        let keys = mine.keys.sorted { (jdouble(mine[$0] as? JSON, "at") ?? 0) < (jdouble(mine[$1] as? JSON, "at") ?? 0) }
        let ids = Array(Set(keys.map(itemId)))
        do {
            let r = try await call("datastoreGet", ["authKey": auth, "collection": "libraryItem", "ids": ids, "all": false])
            var remote: [String: JSON] = [:]
            for it in r["result"] as? [JSON] ?? [] { if let id = jstr(it, "_id") { remote[id] = it } }
            var changed = Set<String>()
            for k in keys {
                let id = itemId(k)
                if let q = mine[k] as? JSON, let item = apply(remote[id], id, q) {
                    remote[id] = item
                    changed.insert(id)
                }
            }
            let changes = changed.compactMap { remote[$0] }
            if !changes.isEmpty {
                _ = try await call("datastorePut", ["authKey": auth, "collection": "libraryItem", "changes": changes])
            }
            // Drop what was sent, unless it was watched further meanwhile.
            var all = load()
            var now = all[who] as? JSON ?? [:]
            for k in keys where jdouble(now[k] as? JSON, "at") == jdouble(mine[k] as? JSON, "at") { now[k] = nil }
            all[who] = now
            save(all)
            NSLog("Codio: downloads' progress is in the account (\(changes.count))")
            if !changes.isEmpty { onPushed?() }
        } catch {
            NSLog("Codio: downloads' progress waits for internet")
        }
    }

    private static func itemId(_ k: String) -> String {
        if let i = k.firstIndex(of: "#") { return String(k[..<i]) }
        return k
    }

    private static func iso(_ ms: Double) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: Date(timeIntervalSince1970: ms / 1000))
    }

    private static func parse(_ s: String?) -> Double {
        guard let s = s, !s.isEmpty else { return 0 }
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d.timeIntervalSince1970 * 1000 }
        f.formatOptions = [.withInternetDateTime]
        return (f.date(from: s)?.timeIntervalSince1970 ?? 0) * 1000
    }

    private static func num(_ o: JSON, _ k: String) -> Double { jdouble(o, k) ?? 0 }

    /// The library item with this progress in it, or nil to leave the account's as it is.
    static func apply(_ existing: JSON?, _ id: String, _ q: JSON) -> JSON? {
        let at = num(q, "at")
        let now = iso(Date().timeIntervalSince1970 * 1000)
        var item: JSON = existing ?? [
            // Like playing something that isn't in the library: it shows in Continue Watching only.
            "_id": id, "name": jstr(q, "name") ?? "", "type": jstr(q, "type") ?? "movie", "poster": q["poster"] ?? NSNull(),
            "posterShape": "poster", "removed": true, "temp": true, "_ctime": now, "_mtime": now,
            "state": ["lastWatched": NSNull(), "timeWatched": 0, "timeOffset": 0, "overallTimeWatched": 0,
                      "timesWatched": 0, "flaggedWatched": 0, "duration": 0, "video_id": NSNull(),
                      "watched": NSNull(), "noNotif": false] as JSON,
            "behaviorHints": ["defaultVideoId": NSNull(), "featuredVideoId": NSNull(), "hasScheduledVideos": false] as JSON,
        ]
        var st = item["state"] as? JSON ?? [:]
        let add = num(q, "add")
        st["overallTimeWatched"] = (num(st, "overallTimeWatched") + add).rounded()
        if parse(jstr(st, "lastWatched")) <= at {
            let video = jstr(q, "video") ?? id
            let same = jstr(st, "video_id") == video
            var watched = (same ? num(st, "timeWatched") : 0) + add
            let dur = num(q, "dur")
            if dur > 0 { st["duration"] = dur }
            if jbool(q, "done") == true {
                if !same || num(st, "timeOffset") > 0 { st["timesWatched"] = num(st, "timesWatched") + 1 }
                if jstr(item, "type") == "movie" { st["flaggedWatched"] = 1 }
                watched = 0
            }
            st["video_id"] = video
            st["timeOffset"] = num(q, "offset")
            st["timeWatched"] = watched.rounded()
            st["lastWatched"] = iso(at)
            if jbool(item, "removed") == true, jbool(item, "temp") != true { item["temp"] = true }
        } else if add <= 0 {
            return nil // watched somewhere else since, and no new time to add
        }
        item["state"] = st
        item["_mtime"] = now
        return item
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
}
