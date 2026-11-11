import SwiftUI

/// Your 1 to 5 star ratings, kept on this iPhone.
@MainActor
final class Ratings: ObservableObject {
    static let shared = Ratings()

    struct Rated: Codable {
        var stars: Int
        var type: String
        var name: String
        var poster: String?
        var date: Date
    }

    @Published private(set) var all: [String: Rated] = [:]
    /// Titles we already asked "How was it?" about, so we only ask once.
    private var asked: Set<String> = []
    private let key = "ratings_v1"

    init() {
        if let d = UserDefaults.standard.data(forKey: key), let r = try? JSONDecoder().decode([String: Rated].self, from: d) { all = r }
        asked = Set(UserDefaults.standard.stringArray(forKey: "ratings_asked") ?? [])
    }

    func stars(_ id: String) -> Int { all[id]?.stars ?? 0 }

    func set(_ m: Meta, _ stars: Int) {
        if stars <= 0 { all[m.id] = nil } else {
            all[m.id] = Rated(stars: stars, type: m.type, name: m.name, poster: m.poster, date: Date())
        }
        if let d = try? JSONEncoder().encode(all) { UserDefaults.standard.set(d, forKey: key) }
        Recommender.shared.invalidate()
    }

    func shouldAsk(_ id: String) -> Bool { all[id] == nil && !asked.contains(id) }

    func markAsked(_ id: String) {
        asked.insert(id)
        UserDefaults.standard.set(Array(asked), forKey: "ratings_asked")
    }
}

/// Personal picks from TMDB recommendations, weighted by your stars and what you've watched.
@MainActor
final class Recommender: ObservableObject {
    static let shared = Recommender()

    struct Row: Identifiable {
        let id: String
        let title: String
        let items: [Meta]
    }

    @Published private(set) var rows: [Row] = []
    private var builtAt: Date?
    private var building = false
    private var tmdbIds: [String: (Bool, Int)?] = [:]       // imdb -> (isTV, tmdb id)
    private var imdbIds: [String: String] = [:]             // "tv:123" -> imdb id
    private var recs: [String: [JSON]] = [:]                // "tv:123" -> TMDB results

    func invalidate() { builtAt = nil }

    private struct Seed {
        let imdb: String
        let name: String
        let weight: Double
        let recent: Date
    }

    /// Ratings count most (5 = 2, 4 = 1, 3 = 0.3, 1-2 push similar titles down); finishing something = 0.7, starting it = 0.4.
    private func seeds(_ store: Store) -> [Seed] {
        var out: [String: Seed] = [:]
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        for e in store.library.values where e.id.hasPrefix("tt") && (e.type == "movie" || e.type == "series") {
            guard let lw = e.lastWatched, !lw.isEmpty else { continue }
            let when = iso.date(from: lw) ?? ISO8601DateFormatter().date(from: lw) ?? .distantPast
            let finished = e.watched || (e.duration > 0 && e.offset / e.duration > 0.9)
            out[e.id] = Seed(imdb: e.id, name: e.meta.name, weight: finished ? 0.7 : 0.4, recent: when)
        }
        for (id, r) in Ratings.shared.all where id.hasPrefix("tt") {
            let w: Double = r.stars >= 5 ? 2 : r.stars == 4 ? 1 : r.stars == 3 ? 0.3 : -1.5
            out[id] = Seed(imdb: id, name: r.name, weight: w, recent: r.date)
        }
        // The strongest and most recent first; positive seeds capped so lookups stay quick.
        let pos = out.values.filter { $0.weight > 0 }.sorted { ($0.weight, $0.recent) > ($1.weight, $1.recent) }.prefix(14)
        let neg = out.values.filter { $0.weight < 0 }.prefix(6)
        return Array(pos) + Array(neg)
    }

    func refresh(_ store: Store) async {
        guard TMDB.key != nil, !building else { if TMDB.key == nil { rows = [] }; return }
        if let b = builtAt, Date().timeIntervalSince(b) < 3 * 3600 { return }
        building = true
        defer { building = false }
        let list = seeds(store)
        guard !list.isEmpty else { rows = []; builtAt = Date(); return }

        var seen = Set(store.library.keys).union(Ratings.shared.all.keys)  // imdb ids to leave out
        var seedKeys = Set<String>()
        var scores: [String: Double] = [:]
        var info: [String: JSON] = [:]
        var perSeed: [(Seed, [String])] = []

        for s in list {
            guard let (tv, tid) = await tmdbId(s.imdb) else { continue }
            let k = (tv ? "tv:" : "movie:") + "\(tid)"
            seedKeys.insert(k)
            let results = await recommendations(tv: tv, id: tid)
            var keys: [String] = []
            for (rank, r) in results.prefix(20).enumerated() {
                guard let rid = jint(r, "id") else { continue }
                let rk = (tv ? "tv:" : "movie:") + "\(rid)"
                scores[rk, default: 0] += s.weight * (1 - Double(rank) / 30)
                info[rk] = r
                keys.append(rk)
            }
            if s.weight > 0 { perSeed.append((s, keys)) }
        }

        let ranked = scores.filter { $0.value > 0.25 && !seedKeys.contains($0.key) }
            .sorted { $0.value > $1.value }.map { $0.key }

        var top: [Meta] = []
        for k in ranked {
            guard top.count < 20, let m = await meta(k, info[k]) else { continue }
            if seen.contains(m.id) { continue }
            seen.insert(m.id)
            top.append(m)
        }
        var out: [Row] = []
        if top.count >= 4 { out.append(Row(id: "picks", title: "Top Picks for You", items: top)) }

        // "Because you liked …" for your two strongest titles.
        let shown = Set(top.map { $0.id })
        for (s, keys) in perSeed.prefix(2) where s.weight >= 0.7 {
            var items: [Meta] = []
            for k in keys where items.count < 15 {
                guard let m = await meta(k, info[k]), !shown.contains(m.id), !Ratings.shared.all.keys.contains(m.id),
                      store.entry(m.id)?.lastWatched == nil else { continue }
                items.append(m)
            }
            if items.count >= 4 { out.append(Row(id: "because-" + s.imdb, title: "Because you liked " + s.name, items: items)) }
        }
        rows = out
        builtAt = Date()
    }

    // MARK: - TMDB

    private func get(_ path: String) async -> JSON? {
        guard let key = TMDB.key else { return nil }
        let sep = path.contains("?") ? "&" : "?"
        return try? await Net.json("https://api.themoviedb.org/3" + path + sep + "api_key=" + key) as? JSON
    }

    private func tmdbId(_ imdb: String) async -> (Bool, Int)? {
        if let c = tmdbIds[imdb] { return c }
        guard let f = await get("/find/\(imdb)?external_source=imdb_id") else { return nil }
        var r: (Bool, Int)?
        if let m = (f["movie_results"] as? [JSON])?.first, let id = jint(m, "id") { r = (false, id) }
        else if let t = (f["tv_results"] as? [JSON])?.first, let id = jint(t, "id") { r = (true, id) }
        tmdbIds[imdb] = r
        if let r = r { imdbIds[(r.0 ? "tv:" : "movie:") + "\(r.1)"] = imdb }
        return r
    }

    private func recommendations(tv: Bool, id: Int) async -> [JSON] {
        let k = (tv ? "tv:" : "movie:") + "\(id)"
        if let c = recs[k] { return c }
        var list = (await get("/\(tv ? "tv" : "movie")/\(id)/recommendations")?["results"] as? [JSON]) ?? []
        if list.count < 8 { list += (await get("/\(tv ? "tv" : "movie")/\(id)/similar")?["results"] as? [JSON]) ?? [] }
        recs[k] = list
        return list
    }

    /// A TMDB result as a Codio title (Codio's addons use IMDb ids).
    private func meta(_ k: String, _ r: JSON?) async -> Meta? {
        let tv = k.hasPrefix("tv:")
        var imdb = imdbIds[k]
        if imdb == nil {
            let id = k.split(separator: ":").last.map(String.init) ?? ""
            imdb = jstr(await get("/\(tv ? "tv" : "movie")/\(id)/external_ids"), "imdb_id")
            if let i = imdb, !i.isEmpty { imdbIds[k] = i } else { return nil }
        }
        guard let i = imdb, i.hasPrefix("tt") else { return nil }
        let m = Meta(id: i, type: tv ? "series" : "movie", name: jstr(r, tv ? "name" : "title") ?? "")
        if let p = jstr(r, "poster_path") { m.poster = "https://image.tmdb.org/t/p/w342" + p }
        if let b = jstr(r, "backdrop_path") { m.background = "https://image.tmdb.org/t/p/w780" + b }
        return m
    }
}

/// Five tappable stars. Tapping the current rating again clears it.
struct StarRating: View {
    let stars: Int
    var size: CGFloat = 26
    let onSet: (Int) -> Void

    var body: some View {
        HStack(spacing: size * 0.3) {
            ForEach(1...5, id: \.self) { i in
                Button { onSet(i == stars ? 0 : i) } label: {
                    Image(systemName: i <= stars ? "star.fill" : "star")
                        .font(.system(size: size))
                        .foregroundColor(i <= stars ? Theme.red : Color(white: 0.55))
                }
                .buttonStyle(.plain)
            }
        }
    }
}

/// "How was it?" after a movie or a show's last episode.
struct RateCard: View {
    @EnvironmentObject var engine: Engine
    let meta: Meta
    @State private var picked = 0

    var body: some View {
        VStack(spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                RemoteImage(url: meta.poster).frame(width: 60, height: 90).clipShape(RoundedRectangle(cornerRadius: 4))
                VStack(alignment: .leading, spacing: 4) {
                    Text("How was it?").font(.headline).foregroundColor(.white)
                    Text(meta.name).font(.subheadline).foregroundColor(Theme.dim).lineLimit(2)
                    Text("Your stars tune Top Picks for You.").font(.caption).foregroundColor(Theme.dim)
                }
                Spacer()
            }
            StarRating(stars: picked, size: 32) { s in
                picked = s
                guard s > 0 else { return }
                Ratings.shared.set(meta, s)
                Task {
                    try? await Task.sleep(nanoseconds: 450_000_000)
                    engine.askRating = nil
                    engine.show(toast: "Thanks! Your picks will update.")
                }
            }
            Button("Not now") { engine.askRating = nil }
                .font(.subheadline).foregroundColor(Theme.dim)
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(white: 0.11)))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Theme.red.opacity(0.5), lineWidth: 1))
        .padding(.horizontal, 16)
        .onAppear { Ratings.shared.markAsked(meta.id) }
    }
}
