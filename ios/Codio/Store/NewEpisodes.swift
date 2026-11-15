import BackgroundTasks
import Foundation
import UserNotifications

/// New episode alerts (Settings, Playback): shows you're watching get a "New episode" mark on Home and in the
/// Library when an episode came out after you last watched them, and the phone gets a notification.
///
/// The shows come from the library (watched in the last six months, or added to the Library); the episode dates
/// come from the catalog addons that answer "lastVideosIds" (Cinemeta does). The same rules as
/// android/src/com/codio/tv/ui/NewEpisodes.java.
@MainActor
final class NewEpisodes: NSObject, ObservableObject, UNUserNotificationCenterDelegate {
    static let shared = NewEpisodes()
    static let pref = "new_episode_alerts"
    static let taskId = "com.codio.ios.newepisodes"

    /// The newest new episode of one show.
    struct Alert {
        var showId: String
        var type: String
        var name: String
        var poster: String?
        var videoId: String
        var title: String
        var season: Int
        var episode: Int
        /// How many new episodes the show has.
        var count: Int
        var released: Double

        var episodeLine: String { "S\(season) E\(episode)" + (title.isEmpty ? "" : " · " + title) }
    }

    /// Shows with a new episode, by show id.
    @Published private(set) var alerts: [String: Alert] = [:]
    /// A show whose notification was tapped: RootView opens its page and clears this.
    @Published var openShow: String?

    private static let hour = 3600.0, day = 86400.0
    private static let checkEvery = 3 * hour
    /// An episode counts as new for this long after it came out.
    private static let fresh = 30 * day
    private static let watching = 180 * day
    private static let maxShows = 50
    private static let stateKey = "new_episodes_state"

    private var state: JSON
    private var checking = false
    private var asked = false

    static var enabled: Bool { UserDefaults.standard.object(forKey: pref) as? Bool ?? true }

    private override init() {
        state = (parseJSON(UserDefaults.standard.string(forKey: NewEpisodes.stateKey)) as? JSON) ?? [:]
        super.init()
        rebuild()
    }

    private func save() {
        UserDefaults.standard.set(toJSONString(state), forKey: NewEpisodes.stateKey)
    }

    // MARK: - What the screens ask

    func alert(_ showId: String) -> Alert? { alerts[showId] }

    /// Continue Watching with the shows that have a new episode first (the newest episode leading).
    func ordered(_ list: [Meta], library: [String: LibraryEntry]) -> [Meta] {
        var out = list
        for a in alerts.values.sorted(by: { $0.released < $1.released }) {
            if let i = out.firstIndex(where: { $0.id == a.showId }) {
                out.insert(out.remove(at: i), at: 0)
            } else if let e = library[a.showId], !e.removed || e.temp {
                out.insert(e.meta, at: 0)
            }
        }
        return out
    }

    /// The viewer opened the show's page: its mark goes away until another episode comes out.
    func seen(_ showId: String) {
        guard alerts[showId] != nil else { return }
        var seen = (state["seen"] as? JSON) ?? [:]
        seen[showId] = Date().timeIntervalSince1970
        state["seen"] = seen
        save()
        rebuild()
    }

    /// The setting was switched.
    func settingChanged(_ store: Store) {
        rebuild()
        NewEpisodes.scheduleBackground()
        if NewEpisodes.enabled {
            asked = false
            sync(store)
        }
    }

    // MARK: - Keeping it up to date

    /// Called whenever the library was read: remembers which shows to follow and where to ask, and asks the addons
    /// when that changed or the last answer is old.
    func sync(_ store: Store) {
        guard store.profileLoaded else { return }
        let now = Date().timeIntervalSince1970
        var followed: [(String, JSON)] = []
        for e in store.library.values where e.type == "series" && (!e.removed || e.temp) {
            let watched = NewEpisodes.parseIso(e.lastWatched)
            if watched > 0 ? watched < now - NewEpisodes.watching : e.temp { continue }
            // New means: out after you last watched the show (or, never watched, after it went into the Library).
            var o: JSON = ["id": e.id, "type": e.type, "name": e.meta.name,
                           "since": watched > 0 ? watched : NewEpisodes.parseIso(e.mtime)]
            if let p = e.meta.poster { o["poster"] = p }
            followed.append((e.lastWatched ?? e.mtime, o))
        }
        let watch = followed.sorted { $0.0 > $1.0 }.prefix(NewEpisodes.maxShows).map { $0.1 }
        var sources: [JSON] = []
        for a in store.addons {
            for c in (a.manifest["catalogs"] as? [JSON]) ?? [] {
                let limit = NewEpisodes.lastVideosLimit(c)
                guard limit > 0, let type = jstr(c, "type"), let id = jstr(c, "id") else { continue }
                var o: JSON = ["base": a.base, "type": type, "id": id, "limit": limit]
                if let p = a.manifest["idPrefixes"] as? [String] { o["prefixes"] = p }
                sources.append(o)
            }
        }
        let sig = watch.compactMap { jstr($0, "id") }.joined(separator: ",") + "|" + sources.map { (jstr($0, "base") ?? "") + (jstr($0, "id") ?? "") }.joined(separator: ",")
        let stale = sig != jstr(state, "sig") || now - (jdouble(state, "checked") ?? 0) > NewEpisodes.checkEvery
        state["watch"] = watch
        state["sources"] = sources
        state["sig"] = sig
        save()
        rebuild()
        guard NewEpisodes.enabled else { return }
        if !watch.isEmpty { askForNotifications() }
        if stale { Task { _ = await check() } }
    }

    /// How many ids one request may carry, when the catalog answers lastVideosIds; 0 when it doesn't.
    private static func lastVideosLimit(_ cat: JSON) -> Int {
        for e in (cat["extra"] as? [JSON]) ?? [] where jstr(e, "name") == "lastVideosIds" {
            return max(1, min(100, jint(e, "optionsLimit") ?? 1))
        }
        if let sup = cat["extraSupported"] as? [String], sup.contains("lastVideosIds") { return 1 }
        return 0
    }

    /// Asks the addons for the followed shows' latest episodes. Returns the alerts nobody was told about yet, and
    /// remembers them as told.
    @discardableResult
    func check() async -> [Alert] {
        guard !checking else { return [] }
        let watch = (state["watch"] as? [JSON]) ?? []
        let sources = (state["sources"] as? [JSON]) ?? []
        guard !watch.isEmpty, !sources.isEmpty else { return [] }
        checking = true
        defer { checking = false }
        var found: JSON = [:]
        var answered = false
        let now = Date().timeIntervalSince1970
        for src in sources {
            guard let base = jstr(src, "base"), let type = jstr(src, "type"), let cid = jstr(src, "id") else { continue }
            let prefixes = (src["prefixes"] as? [String]) ?? []
            var ids: [String] = []
            for w in watch {
                guard let id = jstr(w, "id"), found[id] == nil, jstr(w, "type") == type else { continue }
                if !prefixes.isEmpty, !prefixes.contains(where: { id.hasPrefix($0) }) { continue }
                ids.append(id)
            }
            let limit = max(1, jint(src, "limit") ?? 1)
            // An addon that takes one id at a time is only asked about the ten most recent shows.
            if limit < 5, ids.count > 10 { ids = Array(ids.prefix(10)) }
            var from = 0
            while from < ids.count {
                let chunk = ids[from..<min(ids.count, from + limit)].joined(separator: ",")
                from += limit
                let url = base + "/catalog/" + urlEnc(type) + "/" + urlEnc(cid) + "/lastVideosIds=" + urlEnc(chunk) + ".json"
                guard let r = try? await Net.json(url) as? JSON else { continue }
                answered = true
                let metas = (r["metasDetailed"] as? [JSON]) ?? (r["metas"] as? [JSON]) ?? []
                for m in metas {
                    guard let mid = jstr(m, "id") ?? jstr(m, "_id") else { continue }
                    var keep: [JSON] = []
                    for v in (m["videos"] as? [JSON]) ?? [] {
                        let released = NewEpisodes.parseIso(jstr(v, "released"))
                        let season = jint(v, "season") ?? 0
                        // Only what could still be news: no specials, nothing older than a month, nothing unaired.
                        if season <= 0 || released <= 0 || released > now || released < now - NewEpisodes.fresh { continue }
                        keep.append(["id": jstr(v, "id") ?? "", "season": season,
                                     "episode": jint(v, "episode") ?? jint(v, "number") ?? 0,
                                     "title": jstr(v, "title") ?? jstr(v, "name") ?? "", "released": released])
                    }
                    found[mid] = keep
                }
            }
        }
        guard answered else { return [] }
        state["found"] = found
        state["checked"] = now
        rebuild()
        var told = (state["told"] as? JSON) ?? [:]
        // Forget what was told long ago, so this stays small.
        for (k, v) in told where now - ((v as? NSNumber)?.doubleValue ?? 0) > 3 * NewEpisodes.fresh { told[k] = nil }
        var out: [Alert] = []
        for a in alerts.values.sorted(by: { $0.released > $1.released }) where told[a.videoId] == nil {
            told[a.videoId] = now
            out.append(a)
        }
        state["told"] = told
        save()
        return out
    }

    /// Works the alerts out again from what was found, what was watched and what was seen.
    private func rebuild() {
        var out: [String: Alert] = [:]
        let found = (state["found"] as? JSON) ?? [:]
        let seen = (state["seen"] as? JSON) ?? [:]
        let now = Date().timeIntervalSince1970
        if NewEpisodes.enabled {
            for w in (state["watch"] as? [JSON]) ?? [] {
                guard let id = jstr(w, "id"), let videos = found[id] as? [JSON] else { continue }
                let since = max(jdouble(w, "since") ?? 0, jdouble(seen, id) ?? 0)
                if since <= 0 { continue }
                var best: JSON?
                var count = 0
                for v in videos {
                    let released = jdouble(v, "released") ?? 0
                    if released <= since || released < now - NewEpisodes.fresh || released > now { continue }
                    count += 1
                    if let b = best, released < (jdouble(b, "released") ?? 0) { continue }
                    best = v
                }
                guard let b = best else { continue }
                out[id] = Alert(showId: id, type: jstr(w, "type") ?? "series", name: jstr(w, "name") ?? "", poster: jstr(w, "poster"),
                                videoId: jstr(b, "id") ?? "", title: jstr(b, "title") ?? "", season: jint(b, "season") ?? 0,
                                episode: jint(b, "episode") ?? 0, count: count, released: jdouble(b, "released") ?? 0)
            }
        }
        if out.keys.sorted() != alerts.keys.sorted() || out.contains(where: { alerts[$0.key]?.videoId != $0.value.videoId }) {
            alerts = out
        }
    }

    /// Seconds since 1970 for "2026-11-10T03:12:45.123Z" (also without the time), 0 when it isn't a date.
    static func parseIso(_ s: String?) -> Double {
        guard let s = s, s.count >= 10 else { return 0 }
        let chars = Array(s)
        let time = chars.count >= 19 && chars[10] == "T"
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = time ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd"
        return f.date(from: String(chars.prefix(time ? 19 : 10)))?.timeIntervalSince1970 ?? 0
    }

    // MARK: - Notifications

    /// Asks once for permission to notify, the first time there is a show to follow.
    private func askForNotifications() {
        guard !asked else { return }
        asked = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    private func notify(_ a: Alert) {
        let c = UNMutableNotificationContent()
        c.title = a.name.isEmpty ? "New episode" : a.name
        c.body = (a.count > 1 ? "\(a.count) new episodes, the latest: " : "New episode: ") + a.episodeLine
        c.sound = .default
        c.userInfo = ["show": a.showId]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "new-episode-" + a.showId, content: c, trigger: nil))
    }

    /// Tapping a notification opens the show.
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                            withCompletionHandler completionHandler: @escaping () -> Void) {
        let show = response.notification.request.content.userInfo["show"] as? String
        Task { @MainActor in
            if let show = show { NewEpisodes.shared.openShow = show }
            completionHandler()
        }
    }

    // MARK: - Background check

    /// Call once while the app is launching.
    static func registerBackground() {
        UNUserNotificationCenter.current().delegate = shared
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            Task { @MainActor in
                scheduleBackground()
                let work = Task { @MainActor in
                    let fresh = enabled ? await shared.check() : []
                    for a in fresh { shared.notify(a) }
                    task.setTaskCompleted(success: true)
                }
                task.expirationHandler = { work.cancel() }
            }
        }
        scheduleBackground()
    }

    /// iOS decides when it really runs (a few times a day when Codio is used regularly).
    static func scheduleBackground() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskId)
        guard enabled else { return }
        let r = BGAppRefreshTaskRequest(identifier: taskId)
        r.earliestBeginDate = Date(timeIntervalSinceNow: 6 * hour)
        try? BGTaskScheduler.shared.submit(r)
    }
}
