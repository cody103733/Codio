import Foundation

/// For You: picks made on this iPhone from what you watch and rate (DESIGN.md "For You"). Same rules as
/// android/.../ui/ForYou.java and windows/.../Codio/foryou.ts:
/// a taste profile from your history and stars (seeds), TMDB recommendations/similar for the seeds (candidates),
/// a 512-number feature-hashing embedding per title (genres, keywords, cast, director, language, decade, plot words,
/// IDF weighted) to re-rank candidates by closeness to your taste, and a stable daily shake plus a few wildcards.
/// Nothing leaves the phone except the TMDB lookups made with your own key.
@MainActor
final class ForYou: ObservableObject {
    static let shared = ForYou()

    struct Row: Identifiable {
        let id: String
        let title: String
        let items: [Meta]
    }

    enum State { case noKey, noHistory, ok, failed, loading }

    @Published private(set) var rows: [Row] = []
    @Published private(set) var state: State = .loading
    /// Why each title is there, by IMDb id ("Because you watched …").
    @Published private(set) var reasons: [String: String] = [:]
    private var building = false
    private var loadedKey: String?

    static let dim = 512
    private static let api = "https://api.themoviedb.org/3"
    private static let maxSeeds = 12, poolSize = 60

    private static let stop: Set<String> = Set(("with that this from they their them there have when what where which while into about after "
        + "before over under again their than then these those will would could should being been were your yours more most "
        + "some such only also just very each other into onto upon must find finds found life lives world years year young "
        + "take takes back begin begins becomes become along new old one two three first last story film series show "
        + "season episode family man woman men women himself herself itself make makes made way ways time times get gets "
        + "help helps both even ever every between through during against among around because however while former").split(separator: " ").map(String.init))

    // MARK: - Helpers (same in all three languages)

    /// FNV-1a, 32 bit, over the UTF-8 bytes.
    nonisolated static func fnv(_ s: String) -> UInt32 {
        var h: UInt32 = 0x811c9dc5
        for b in s.utf8 {
            h ^= UInt32(b)
            h = h &* 0x01000193
        }
        return h
    }

    /// The same number in [0, 1) all day for a title, a new one tomorrow.
    nonisolated static func daily(_ day: String, _ id: String) -> Double { Double(fnv(day + "|" + id)) / 4294967296.0 }

    nonisolated static func today() -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Finished 1, started 0.45, only in the Library 0.3; up to +0.6 for 10 h of watching; faded by age
    /// (half after 60 days, never under 35 %).
    nonisolated static func seedWeight(finished: Bool, started: Bool, inLibrary: Bool, overallMs: Double, last: Date?, now: Date) -> Double {
        var base = finished ? 1 : started ? 0.45 : inLibrary ? 0.3 : 0
        if base <= 0 { return 0 }
        base += min(1, overallMs / 36e6) * 0.6
        let days = last.map { max(0, now.timeIntervalSince($0) / 86400) } ?? 365
        return base * (0.35 + 0.65 * pow(0.5, days / 60))
    }

    // MARK: - Seeds

    private struct Seed {
        let imdb: String
        var kind: String
        let name: String
        let weight: Double
    }

    private func seeds(_ store: Store, seen: inout Set<String>) -> [Seed] {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        let now = Date()
        var out: [String: Seed] = [:]
        for e in store.library.values where e.id.hasPrefix("tt") && (e.type == "movie" || e.type == "series") {
            let last = e.lastWatched.flatMap { iso.date(from: $0) ?? plain.date(from: $0) }
            let inLib = !e.removed && !e.temp
            if last != nil || inLib { seen.insert(e.id) }
            let finished = e.watched || (e.duration > 0 && e.offset / e.duration > 0.9)
            let w = Self.seedWeight(finished: finished, started: e.offset > 0 || e.overall > 0, inLibrary: inLib, overallMs: e.overall, last: last, now: now)
            if w > 0 { out[e.id] = Seed(imdb: e.id, kind: e.type == "movie" ? "movie" : "tv", name: e.meta.name, weight: w) }
        }
        // Your stars (iPhone only) count most: 5 = 2.2, 4 = 1.4, 3 = 0.5, 1-2 push similar titles down.
        for (id, r) in Ratings.shared.all where id.hasPrefix("tt") {
            seen.insert(id)
            let base: Double = r.stars >= 5 ? 2.2 : r.stars == 4 ? 1.4 : r.stars == 3 ? 0.5 : -1.5
            let days = max(0, now.timeIntervalSince(r.date) / 86400)
            let w = base > 0 ? base * (0.35 + 0.65 * pow(0.5, days / 60)) : base
            out[id] = Seed(imdb: id, kind: r.type == "movie" ? "movie" : "tv", name: r.name, weight: w)
        }
        let pos = out.values.filter { $0.weight > 0 }.sorted { $0.weight > $1.weight }.prefix(Self.maxSeeds)
        let neg = out.values.filter { $0.weight < 0 }.prefix(4)
        return Array(pos) + Array(neg)
    }

    private static func signature(_ seeds: [Seed]) -> String {
        seeds.filter { $0.weight > 0 }.prefix(6).map { $0.imdb }.joined(separator: ",")
    }

    // MARK: - Saved picks

    private var storeKey: String { "foryou_v1|" + (Profiles.shared.currentEmail ?? "") }

    /// A different profile, a new key or new stars: look again next time.
    func invalidate() { loadedKey = nil }

    func reset() {
        loadedKey = nil
        rows = []
        reasons = [:]
        state = .loading
    }

    private func loadSaved() {
        guard loadedKey != storeKey else { return }
        loadedKey = storeKey
        rows = []
        reasons = [:]
        guard let d = UserDefaults.standard.string(forKey: storeKey), let o = parseJSON(d) as? JSON, let list = o["rows"] as? [JSON] else { return }
        apply(list)
    }

    private func apply(_ list: [JSON]) {
        var why: [String: String] = [:]
        rows = list.map { r in
            let items = (r["items"] as? [JSON] ?? []).map { o -> Meta in
                let m = Meta.parse(o)
                if let w = jstr(o, "reason") { why[m.id] = w }
                return m
            }
            return Row(id: jstr(r, "id") ?? "", title: jstr(r, "title") ?? "", items: items)
        }
        reasons = why
        if !rows.isEmpty { state = .ok }
    }

    private func needsBuild(_ store: Store) -> Bool {
        guard let d = UserDefaults.standard.string(forKey: storeKey), let o = parseJSON(d) as? JSON, o["rows"] != nil else { return true }
        if jstr(o, "day") != Self.today() { return true }
        var seen = Set<String>()
        let sig = Self.signature(seeds(store, seen: &seen))
        let built = jdouble(o, "builtAt") ?? 0
        return sig != (jstr(o, "sig") ?? "") && Date().timeIntervalSince1970 * 1000 - built > 30 * 60_000
    }

    /// Shows the saved picks at once, then builds new ones when they're stale (or when asked).
    func refresh(_ store: Store, force: Bool = false) async {
        guard TMDB.key != nil else { rows = []; state = .noKey; return }
        loadSaved()
        guard force || needsBuild(store), !building else { return }
        building = true
        defer { building = false }
        if rows.isEmpty { state = .loading }
        let (s, list) = await make(store)
        if let list = list, !list.isEmpty {
            apply(list)
            state = .ok
        } else if rows.isEmpty {
            state = s
        }
    }

    // MARK: - TMDB

    private var memo: [String: JSON] = [:]

    private func get(_ path: String) async -> JSON? {
        guard let key = TMDB.key else { return nil }
        let url = Self.api + path + (path.contains("?") ? "&" : "?") + "api_key=" + key
        if let hit = memo[url] { return hit }
        guard let o = try? await Net.json(url) as? JSON else { return nil }
        if memo.count > 200 { memo.removeAll() }
        memo[url] = o
        return o
    }

    /// Six at a time, keeping the order.
    private func parallel<T>(_ list: [T], _ fn: @escaping (T) async -> JSON?) async -> [JSON?] {
        var out = [JSON?](repeating: nil, count: list.count)
        for start in stride(from: 0, to: list.count, by: 6) {
            let end = min(start + 6, list.count)
            await withTaskGroup(of: (Int, JSON?).self) { g in
                for i in start..<end {
                    let t = list[i]
                    g.addTask { @MainActor in (i, await fn(t)) }
                }
                for await (i, r) in g { out[i] = r }
            }
        }
        return out
    }

    // MARK: - Embeddings

    nonisolated static func features(_ d: JSON, _ kind: String) -> [String: Double] {
        var f: [String: Double] = [:]
        func add(_ k: String, _ w: Double) { f[k] = max(f[k] ?? 0, w) }
        for g in d["genres"] as? [JSON] ?? [] { if let id = jint(g, "id") { add("g\(id)", 1) } }
        let kw = d["keywords"] as? JSON
        for k in ((kw?["keywords"] ?? kw?["results"]) as? [JSON] ?? []).prefix(20) { if let id = jint(k, "id") { add("k\(id)", 1.2) } }
        let cr = d["credits"] as? JSON
        for c in (cr?["cast"] as? [JSON] ?? []).prefix(6) { if let id = jint(c, "id") { add("c\(id)", 0.5) } }
        for c in (cr?["crew"] as? [JSON] ?? []).filter({ jstr($0, "job") == "Director" }).prefix(2) { if let id = jint(c, "id") { add("d\(id)", 0.9) } }
        for c in (d["created_by"] as? [JSON] ?? []).prefix(2) { if let id = jint(c, "id") { add("d\(id)", 0.9) } }
        if let l = jstr(d, "original_language") { add("l" + l, 0.4) }
        let date = jstr(d, "release_date") ?? jstr(d, "first_air_date") ?? ""
        if let y = Int(date.prefix(4)), y > 1800 { add("y\(y / 10)", 0.3) }
        add("t" + kind, 0.3)
        var words = 0
        let text = (jstr(d, "overview") ?? "").lowercased()
        var cur = ""
        func word() {
            defer { cur = "" }
            guard cur.count >= 4, !stop.contains(cur), words < 40, f["w" + cur] == nil else { return }
            add("w" + cur, 0.35)
            words += 1
        }
        for ch in text.unicodeScalars {
            if (ch >= "a" && ch <= "z") || (ch >= "0" && ch <= "9") { cur.unicodeScalars.append(ch) } else { word() }
        }
        word()
        return f
    }

    nonisolated static func embed(_ f: [String: Double], _ df: [String: Int], _ n: Int) -> [Double] {
        var v = [Double](repeating: 0, count: dim)
        for (k, w) in f {
            let h = fnv(k)
            let idf = log(1 + Double(n) / Double(df[k] ?? 1))
            v[Int(h % UInt32(dim))] += ((h & 0x8000_0000) != 0 ? -1 : 1) * w * idf
        }
        return normalized(v)
    }

    nonisolated static func normalized(_ v: [Double]) -> [Double] {
        let s = sqrt(v.reduce(0) { $0 + $1 * $1 })
        return s > 0 ? v.map { $0 / s } : v
    }

    nonisolated static func dot(_ a: [Double], _ b: [Double]) -> Double {
        var s = 0.0
        for i in 0..<dim { s += a[i] * b[i] }
        return s
    }

    // MARK: - Building

    private final class Cand {
        let key: String, kind: String, id: Int
        var cf = 0.0, bestCf = 0.0
        var best: Seed?
        var seeds = Set<String>()
        var trending = false
        var d: JSON = [:]
        var imdb = ""
        var sim = 0.0, score = 0.0
        init(_ key: String, _ kind: String, _ id: Int) { self.key = key; self.kind = kind; self.id = id }
    }

    private func make(_ store: Store) async -> (State, [JSON]?) {
        var seen = Set<String>()
        let seeds = seeds(store, seen: &seen)
        guard seeds.contains(where: { $0.weight > 0 }) else { return (.noHistory, nil) }
        let day = Self.today()

        // Seeds: details with keywords, credits and both candidate lists in one call each.
        let seedDetails = await parallel(seeds) { [weak self] s -> JSON? in
            guard let self = self, let f = await self.get("/find/\(s.imdb)?external_source=imdb_id") else { return nil }
            let movie = (f["movie_results"] as? [JSON])?.first, tv = (f["tv_results"] as? [JSON])?.first
            let pick: (String, JSON)? = s.kind == "tv" ? (tv.map { ("tv", $0) } ?? movie.map { ("movie", $0) }) : (movie.map { ("movie", $0) } ?? tv.map { ("tv", $0) })
            guard let (kind, r) = pick, let id = jint(r, "id"),
                  var d = await self.get("/\(kind)/\(id)?append_to_response=keywords,credits,recommendations,similar") else { return nil }
            d["_kind"] = kind
            return d
        }
        var found: [(Seed, JSON)] = []
        var seedKeys = Set<String>()
        for (i, d) in seedDetails.enumerated() {
            guard let d = d, let kind = jstr(d, "_kind"), let id = jint(d, "id") else { continue }
            var s = seeds[i]
            s.kind = kind
            found.append((s, d))
            seedKeys.insert("\(kind):\(id)")
        }
        guard !found.isEmpty else { return (.failed, nil) }

        var cands: [String: Cand] = [:]
        var order: [String] = []
        for (s, d) in found {
            for (name, factor) in [("recommendations", 1.0), ("similar", 0.6)] {
                let list = ((d[name] as? JSON)?["results"] as? [JSON]) ?? []
                for (rank, r) in list.prefix(20).enumerated() {
                    guard let id = jint(r, "id") else { continue }
                    let key = "\(s.kind):\(id)"
                    if seedKeys.contains(key) { continue }
                    let c: Cand
                    if let x = cands[key] { c = x } else { c = Cand(key, s.kind, id); cands[key] = c; order.append(key) }
                    // Positive seeds pull candidates up, titles you rated low push theirs down (half as hard).
                    let add = s.weight * factor * (1 - Double(rank) / 25) * (s.weight < 0 ? 0.5 : 1)
                    c.cf += add
                    if s.weight > 0 { c.seeds.insert(s.imdb) }
                    if add > c.bestCf { c.bestCf = add; c.best = s }
                }
            }
        }
        var wildTrend: [Cand] = []
        for r in ((await get("/trending/all/week"))?["results"] as? [JSON] ?? []).prefix(20) {
            guard let mt = jstr(r, "media_type"), mt == "tv" || mt == "movie", let id = jint(r, "id") else { continue }
            let key = "\(mt):\(id)"
            if seedKeys.contains(key) || cands[key] != nil { continue }
            let c = Cand(key, mt, id)
            c.trending = true
            cands[key] = c
            wildTrend.append(c)
        }
        let ranked = Array(order.compactMap { cands[$0] }.filter { $0.cf > 0 }.sorted { $0.cf > $1.cf }.prefix(Self.poolSize))
        wildTrend = Array(wildTrend.sorted { Self.daily(day, $0.key) < Self.daily(day, $1.key) }.prefix(6))
        let toFetch = ranked + wildTrend
        let details = await parallel(toFetch) { [weak self] c in await self?.get("/\(c.kind)/\(c.id)?append_to_response=keywords,credits,external_ids") }

        // Embeddings.
        var df: [String: Int] = [:]
        var n = 0
        let seedF = found.map { Self.features($0.1, $0.0.kind) }
        for f in seedF { for k in f.keys { df[k, default: 0] += 1 }; n += 1 }
        let candF: [[String: Double]?] = details.enumerated().map { i, d in d.map { Self.features($0, toFetch[i].kind) } }
        for f in candF { if let f = f { for k in f.keys { df[k, default: 0] += 1 }; n += 1 } }
        var taste = [Double](repeating: 0, count: Self.dim)
        for (i, (s, _)) in found.enumerated() {
            let v = Self.embed(seedF[i], df, n)
            for j in 0..<Self.dim { taste[j] += s.weight * v[j] }
        }
        taste = Self.normalized(taste)

        var scored: [Cand] = []
        var used = Set<String>()
        for (i, d) in details.enumerated() {
            guard let d = d, jbool(d, "adult") != true else { continue }
            let imdb = jstr(d["external_ids"] as? JSON, "imdb_id") ?? jstr(d, "imdb_id") ?? ""
            guard imdb.hasPrefix("tt"), !seen.contains(imdb), used.insert(imdb).inserted else { continue }
            let c = toFetch[i]
            c.d = d
            c.imdb = imdb
            c.sim = candF[i].map { Self.dot(taste, Self.embed($0, df, n)) } ?? 0
            scored.append(c)
        }
        let maxCf = max(1e-6, scored.map { $0.cf }.max() ?? 0)
        let maxSim = max(1e-6, scored.map { $0.sim }.max() ?? 0)
        for c in scored {
            let quality = (jint(c.d, "vote_count") ?? 0) >= 50 ? (jdouble(c.d, "vote_average") ?? 5) / 10 : 0.5
            let base = 0.45 * (c.cf / maxCf) + 0.45 * (max(0, c.sim) / maxSim) + 0.1 * quality
            c.score = base * (0.85 + 0.3 * Self.daily(day, c.imdb))
        }
        let recs = scored.filter { !$0.trending }.sorted { $0.score > $1.score }

        // Wildcards: still recommended but less like your usual, plus trending titles in your top genres.
        let sims = recs.map { $0.sim }.sorted()
        let median = sims.isEmpty ? 0 : sims[sims.count / 2]
        var gw: [Int: Double] = [:]
        for (s, d) in found where s.weight > 0 {
            for g in d["genres"] as? [JSON] ?? [] { if let id = jint(g, "id") { gw[id, default: 0] += s.weight } }
        }
        let topGenres = Set(gw.sorted { $0.value > $1.value }.prefix(6).map { $0.key })
        let wildRec = recs.dropFirst(20).filter { $0.sim < median }.sorted { Self.daily(day, "w" + $0.imdb) < Self.daily(day, "w" + $1.imdb) }
        let wildTr = scored.filter { c in c.trending && (c.d["genres"] as? [JSON] ?? []).contains { topGenres.contains(jint($0, "id") ?? -1) } }
            .sorted { Self.daily(day, "w" + $0.imdb) < Self.daily(day, "w" + $1.imdb) }
        var wild: [Cand] = []
        var i = 0
        while wild.count < 12 && (i < wildRec.count || i < wildTr.count) {
            if i < wildRec.count { wild.append(wildRec[i]) }
            if i < wildTr.count { wild.append(wildTr[i]) }
            i += 1
        }

        func wildReason(_ c: Cand) -> String { c.trending ? "Trending this week" : "Something a bit different" }
        func reasonOf(_ c: Cand) -> String { c.best.map { "Because you " + (Ratings.shared.stars($0.imdb) >= 4 ? "liked " : "watched ") + $0.name } ?? wildReason(c) }

        var shown = Set<String>()
        var out: [JSON] = []
        func row(_ id: String, _ title: String, _ list: [Cand], _ why: (Cand) -> String) {
            guard list.count >= 4 else { return }
            for c in list { shown.insert(c.imdb) }
            out.append(["id": id, "title": title, "items": list.map { Self.toMeta($0.d, $0.kind, $0.imdb, why($0)) }])
        }

        // Top picks: the best 20 of the day, with two wildcards slipped in (6th and 13th).
        var top = Array(recs.prefix(20))
        let slip = Array(wild.prefix(2))
        if slip.count > 0 && top.count >= 6 { top.insert(slip[0], at: 5) }
        if slip.count > 1 && top.count >= 13 { top.insert(slip[1], at: 12) }
        row("top", "Top picks for you", Array(top.prefix(20))) { c in slip.contains { $0 === c } ? wildReason(c) : reasonOf(c) }

        for (s, _) in found.filter({ $0.0.weight > 0 }).prefix(3) {
            let items = Array(recs.filter { !shown.contains($0.imdb) && $0.seeds.contains(s.imdb) }.prefix(15))
            let title = "Because you " + (Ratings.shared.stars(s.imdb) >= 4 ? "liked " : "watched ") + s.name
            row("because-" + s.imdb, title, items) { _ in title }
        }
        row("different", "Something different", Array(wild.dropFirst(2).filter { !shown.contains($0.imdb) }.prefix(10)), wildReason)
        row("more", "More you might like", Array(recs.filter { !shown.contains($0.imdb) }.prefix(20)), reasonOf)

        let saved: JSON = ["day": day, "sig": Self.signature(seeds), "builtAt": Date().timeIntervalSince1970 * 1000, "rows": out]
        if !out.isEmpty, let data = try? JSONSerialization.data(withJSONObject: saved), let text = String(data: data, encoding: .utf8) {
            UserDefaults.standard.set(text, forKey: storeKey)
        }
        return (out.isEmpty ? .failed : .ok, out)
    }

    /// A TMDB title as the addon-style JSON Codio's pages read (IMDb id, so the addons know it).
    nonisolated private static func toMeta(_ d: JSON, _ kind: String, _ imdb: String, _ reason: String) -> JSON {
        var o: JSON = ["id": imdb, "type": kind == "tv" ? "series" : "movie", "name": jstr(d, "title") ?? jstr(d, "name") ?? "", "reason": reason]
        if let p = jstr(d, "poster_path") { o["poster"] = "https://image.tmdb.org/t/p/w342" + p }
        if let b = jstr(d, "backdrop_path") { o["background"] = "https://image.tmdb.org/t/p/w1280" + b }
        if let ov = jstr(d, "overview") { o["description"] = ov }
        if let date = jstr(d, "release_date") ?? jstr(d, "first_air_date"), date.count >= 4 { o["releaseInfo"] = String(date.prefix(4)) }
        if (jint(d, "vote_count") ?? 0) >= 20, let v = jdouble(d, "vote_average") { o["imdbRating"] = String(format: "%.1f", v) }
        o["genres"] = (d["genres"] as? [JSON] ?? []).prefix(3).compactMap { jstr($0, "name") }
        return o
    }
}
