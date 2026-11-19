import Foundation

/// The Calendar (Library > Calendar): when the next episodes and movies of what you follow come out.
///
/// Follows the movies and shows in the Library plus the shows watched in the last six months (at most 60, the most
/// recent first). Dates come from each title's page at the catalog addon (Cinemeta has every episode, also the ones
/// still to come). The same rules as android/src/com/codio/tv/ui/ReleaseCalendar.java; see DESIGN.md "Calendar".
@MainActor
final class ReleaseCalendar: ObservableObject {
    static let shared = ReleaseCalendar()

    /// One row: a movie, an episode, or several episodes of a show that come out the same day.
    struct Item: Identifiable {
        var id: String { showId + "|" + videoId }
        var showId: String
        var type: String
        var name: String
        var poster: String?
        var videoId: String
        var title: String
        var season: Int
        var episode: Int
        /// Episodes of the show out that day (1 for a movie).
        var count: Int
        var lastEpisode: Int
        /// The local day it comes out, as "yyyy-MM-dd".
        var day: String
        var released: Double

        /// "S2 E5 · Title", "S2 E1–E8 · 8 episodes" or "Movie".
        var line: String {
            if type == "movie" { return "Movie" }
            if count > 1 { return "S\(season) E\(episode)–E\(lastEpisode) · \(count) episodes" }
            return "S\(season) E\(episode)" + (title.isEmpty ? "" : " · " + title)
        }
    }

    struct Section: Identifiable {
        var id: String { day }
        var day: String
        var heading: String
        var items: [Item]
    }

    @Published private(set) var items: [Item] = []
    @Published private(set) var loading = false
    @Published private(set) var loaded = false

    private static let day = 86400.0
    /// How far back ("Out this past week") and ahead the page looks.
    private static let back = 7 * day, ahead = 365 * day
    private static let watching = 180 * day
    private static let maxTitles = 60
    private static let refreshEvery = 6 * 3600.0
    private var sig = ""
    private var checked = 0.0

    /// Called when the Calendar or the Library shows: reads the titles again when what's followed changed or the
    /// last read is older than six hours.
    func refresh(_ store: Store, force: Bool = false) {
        guard store.profileLoaded, !loading else { return }
        let titles = ReleaseCalendar.followed(store)
        let s = titles.map { $0.type + "/" + $0.id }.joined(separator: ",") + "|" + store.addons.map { $0.base }.joined(separator: ",")
        let now = Date().timeIntervalSince1970
        if !force, s == sig, now - checked < ReleaseCalendar.refreshEvery { return }
        sig = s
        loading = true
        Task {
            var found: [Item] = []
            let addons = store.addons
            await withTaskGroup(of: [Item].self) { group in
                var next = 0
                while next < min(5, titles.count) {
                    let t = titles[next]
                    next += 1
                    group.addTask { await ReleaseCalendar.items(for: t, addons: addons) }
                }
                while let list = await group.next() {
                    found += list
                    if next < titles.count {
                        let t = titles[next]
                        next += 1
                        group.addTask { await ReleaseCalendar.items(for: t, addons: addons) }
                    }
                }
            }
            items = found.sorted { $0.released != $1.released ? $0.released < $1.released : $0.name < $1.name }
            checked = Date().timeIntervalSince1970
            loading = false
            loaded = true
        }
    }

    /// The days to show: today and later first, then what came out in the past week (newest first).
    var sections: [Section] {
        let today = ReleaseCalendar.dayKey(Date())
        var byDay: [String: [Item]] = [:]
        for i in items { byDay[i.day, default: []].append(i) }
        let days = byDay.keys.sorted()
        let ahead = days.filter { $0 >= today }.map { Section(day: $0, heading: ReleaseCalendar.heading($0, today: today), items: byDay[$0]!) }
        let past = days.filter { $0 < today }.reversed().flatMap { byDay[$0]! }
        return past.isEmpty ? ahead : ahead + [Section(day: "past", heading: "Out this past week", items: past)]
    }

    /// The first thing still to come, for the Calendar row in the Library.
    var next: Item? {
        let today = ReleaseCalendar.dayKey(Date())
        return items.first { $0.day >= today }
    }

    // MARK: - Reading

    struct Title: Sendable {
        var id: String, type: String, name: String, poster: String?
    }

    private static func followed(_ store: Store) -> [Title] {
        let now = Date().timeIntervalSince1970
        var list: [(String, Title)] = []
        for e in store.library.values where e.type == "movie" || e.type == "series" {
            let inLibrary = !e.removed && !e.temp
            let watched = NewEpisodes.parseIso(e.lastWatched)
            let watching = e.type == "series" && watched > now - ReleaseCalendar.watching && (!e.removed || e.temp)
            guard inLibrary || watching else { continue }
            list.append((max(e.lastWatched ?? "", e.mtime), Title(id: e.id, type: e.type, name: e.meta.name, poster: e.meta.poster)))
        }
        return list.sorted { $0.0 > $1.0 }.prefix(ReleaseCalendar.maxTitles).map { $0.1 }
    }

    private nonisolated static func items(for t: Title, addons: [Addon]) async -> [Item] {
        for a in addons where a.supports("meta", type: t.type, id: t.id) {
            guard let r = try? await Net.json(a.base + "/meta/" + urlEnc(t.type) + "/" + urlEnc(t.id) + ".json") as? JSON,
                  let m = r["meta"] as? JSON else { continue }
            return parse(m, t)
        }
        return []
    }

    private nonisolated static func parse(_ m: JSON, _ t: Title) -> [Item] {
        let now = Date().timeIntervalSince1970
        let name = jstr(m, "name") ?? t.name
        let poster = jstr(m, "poster") ?? t.poster
        func inWindow(_ r: Double) -> Bool { r > 0 && r >= now - back && r <= now + ahead }
        if t.type == "movie" {
            let r = parseIso(jstr(m, "released"))
            guard inWindow(r) else { return [] }
            return [Item(showId: t.id, type: "movie", name: name, poster: poster, videoId: t.id, title: "", season: 0, episode: 0,
                         count: 1, lastEpisode: 0, day: localDay(jstr(m, "released")), released: r)]
        }
        var byDay: [String: Item] = [:]
        for v in (m["videos"] as? [JSON]) ?? [] {
            let season = jint(v, "season") ?? 0
            let r = parseIso(jstr(v, "released"))
            guard season > 0, inWindow(r) else { continue }
            let ep = jint(v, "episode") ?? jint(v, "number") ?? 0
            let day = localDay(jstr(v, "released"))
            if var have = byDay[day] {
                have.count += 1
                if season * 10000 + ep < have.season * 10000 + have.episode {
                    have.season = season; have.episode = ep
                    have.videoId = jstr(v, "id") ?? have.videoId
                    have.title = jstr(v, "title") ?? jstr(v, "name") ?? ""
                }
                if season == have.season { have.lastEpisode = max(have.lastEpisode, ep) }
                byDay[day] = have
            } else {
                byDay[day] = Item(showId: t.id, type: "series", name: name, poster: poster, videoId: jstr(v, "id") ?? "",
                                  title: jstr(v, "title") ?? jstr(v, "name") ?? "", season: season, episode: ep, count: 1,
                                  lastEpisode: ep, day: day, released: r)
            }
        }
        return Array(byDay.values)
    }

    // MARK: - Days

    /// The local day of an addon date: a date with a time is moved into this phone's time zone, a bare date stays.
    nonisolated static func localDay(_ s: String?) -> String {
        guard let s = s, s.count >= 10 else { return "" }
        if s.count >= 19, Array(s)[10] == "T" { return dayKey(Date(timeIntervalSince1970: parseIso(s))) }
        return String(s.prefix(10))
    }

    /// Seconds since 1970 for "2026-11-10T03:12:45.123Z" (also without the time), 0 when it isn't a date.
    nonisolated static func parseIso(_ s: String?) -> Double {
        guard let s = s, s.count >= 10 else { return 0 }
        let chars = Array(s)
        let time = chars.count >= 19 && chars[10] == "T"
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = time ? "yyyy-MM-dd'T'HH:mm:ss" : "yyyy-MM-dd"
        return f.date(from: String(chars.prefix(time ? 19 : 10)))?.timeIntervalSince1970 ?? 0
    }

    nonisolated static func dayKey(_ d: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: d)
    }

    /// "Today", "Tomorrow", "Friday" (this week), else "Sat 13 Dec" ("Sat 13 Dec 2027" in another year).
    nonisolated static func heading(_ day: String, today: String) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        guard let d = f.date(from: day), let t = f.date(from: today) else { return day }
        let days = Int((d.timeIntervalSince(t) / 86400).rounded())
        if days == 0 { return "Today" }
        if days == 1 { return "Tomorrow" }
        let out = DateFormatter()
        out.locale = Locale(identifier: "en_US")
        if days > 1, days < 7 {
            out.dateFormat = "EEEE"
        } else {
            out.dateFormat = day.prefix(4) == today.prefix(4) ? "EEE d MMM" : "EEE d MMM yyyy"
        }
        return out.string(from: d)
    }

    /// "Fri" / "Today" / "Tomorrow" / "13 Dec", short, for the Library row.
    nonisolated static func shortDay(_ day: String) -> String {
        let today = dayKey(Date())
        let h = heading(day, today: today)
        if h == "Today" || h == "Tomorrow" || !h.contains(" ") { return h }
        return h.split(separator: " ").dropFirst().prefix(2).joined(separator: " ")
    }
}
