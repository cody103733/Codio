import Foundation

/// What the video player reports (time, duration, pause, tracks), mirrored from its mpv-style property changes.
@MainActor
final class PlayerState: ObservableObject {
    struct Track: Identifiable, Hashable {
        var id: Int
        var type: String
        var lang: String?
        var title: String?
        var selected: Bool

        var label: String {
            if let t = title, !t.isEmpty { return t }
            if let l = lang, !l.isEmpty { return l }
            return "Track \(id)"
        }
    }

    @Published var time: Double = 0
    @Published var duration: Double = 0
    @Published var paused = false
    @Published var buffering = true
    @Published var started = false
    @Published var audio: [Track] = []
    @Published var subs: [Track] = []
    @Published var aid = -1
    @Published var sid = -1
    /// Set when playback ended with an error.
    @Published var error: String?
    private var timeAt = Date()

    func reset() {
        time = 0
        duration = 0
        paused = false
        buffering = true
        started = false
        audio = []
        subs = []
        aid = -1
        sid = -1
        error = nil
    }

    /// Playback position now, moving smoothly between the player's updates.
    var position: Double {
        if paused || buffering || !started { return time }
        return time + min(Date().timeIntervalSince(timeAt), 1.0)
    }

    func prop(_ name: String, _ data: Any) {
        switch name {
        case "time-pos":
            if let n = data as? NSNumber {
                time = n.doubleValue
                timeAt = Date()
                if time > 0 { started = true }
            }
        case "duration":
            if let n = data as? NSNumber { duration = n.doubleValue }
        case "pause":
            paused = (data as? Bool) ?? false
            timeAt = Date()
        case "paused-for-cache":
            buffering = (data as? Bool) ?? false
            timeAt = Date()
        case "aid":
            aid = (data as? NSNumber)?.intValue ?? -1
        case "sid":
            sid = (data as? NSNumber)?.intValue ?? -1
        case "track-list":
            guard let list = data as? [JSON] else { return }
            var a: [Track] = [], s: [Track] = []
            for t in list {
                guard let id = jint(t, "id"), let type = jstr(t, "type") else { continue }
                let tr = Track(id: id, type: type, lang: jstr(t, "lang"), title: jstr(t, "title"), selected: jbool(t, "selected") ?? false)
                if type == "audio" { a.append(tr) } else if type == "sub" { s.append(tr) }
            }
            audio = a
            subs = s
        default:
            break
        }
    }
}

/// Intro / recap / credits times for an episode, from the community database IntroDB (no account needed).
enum SkipTimes {
    struct Segment {
        let start: Double
        let end: Double
        func contains(_ t: Double) -> Bool { t >= start && t < end - 0.5 }
    }

    struct Times {
        var intro: Segment?
        var recap: Segment?
        var outro: Segment?
    }

    @MainActor private static var cache: [String: Times?] = [:]

    static func imdb(of meta: Meta?, _ vid: String?) -> String? {
        func isImdb(_ s: String?) -> Bool { s?.range(of: "^tt[0-9]+$", options: .regularExpression) != nil }
        if let id = meta?.id, isImdb(id) { return id }
        if let head = vid?.split(separator: ":").first.map(String.init), isImdb(head) { return head }
        if let raw = meta?.raw {
            for k in ["imdb_id", "imdbId", "imdb"] where isImdb(jstr(raw, k)) { return jstr(raw, k) }
            if let h = raw["behaviorHints"] as? JSON, isImdb(jstr(h, "imdbId")) { return jstr(h, "imdbId") }
        }
        return nil
    }

    static func episode(of meta: Meta?, _ vid: String?) -> (Int, Int)? {
        guard let vid = vid else { return nil }
        if let v = meta?.video(vid), v.season > 0, v.episode > 0 { return (v.season, v.episode) }
        let p = vid.split(separator: ":")
        if p.count >= 3, let s = Int(p[p.count - 2]), let e = Int(p[p.count - 1]), s > 0, e > 0 { return (s, e) }
        return nil
    }

    @MainActor
    static func load(meta: Meta?, vid: String?) async -> Times? {
        guard let imdb = imdb(of: meta, vid), let (s, e) = episode(of: meta, vid) else { return nil }
        let key = "\(imdb):\(s):\(e)"
        if let c = cache[key] { return c }
        for attempt in 0..<3 {
            if attempt > 0 { try? await Task.sleep(nanoseconds: UInt64(4_000_000_000 * attempt)) }
            var req = URLRequest(url: URL(string: "https://api.introdb.app/segments?imdb_id=\(imdb)&season=\(s)&episode=\(e)")!)
            req.timeoutInterval = 15
            req.setValue("Codio-iOS (+https://introdb.app)", forHTTPHeaderField: "User-Agent")
            req.setValue("application/json", forHTTPHeaderField: "Accept")
            guard let r = try? await URLSession.shared.data(for: req),
                  let code = (r.1 as? HTTPURLResponse)?.statusCode else { continue }
            let data = r.0
            if code == 404 {
                cache[key] = .some(nil)
                return nil
            }
            guard code / 100 == 2, let o = (try? JSONSerialization.jsonObject(with: data)) as? JSON else { continue }
            func seg(_ x: Any?) -> Segment? {
                guard let x = x as? JSON else { return nil }
                let st = jdouble(x, "start_sec") ?? ((jdouble(x, "start_ms") ?? -1000) / 1000)
                let en = jdouble(x, "end_sec") ?? ((jdouble(x, "end_ms") ?? -1000) / 1000)
                return st >= 0 && en > st + 2 ? Segment(start: st, end: en) : nil
            }
            let t = Times(intro: seg(o["intro"]), recap: seg(o["recap"]), outro: seg(o["outro"]))
            let result: Times? = (t.intro == nil && t.recap == nil && t.outro == nil) ? nil : t
            cache[key] = .some(result)
            return result
        }
        return nil
    }
}
