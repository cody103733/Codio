import SwiftUI
import SafariServices

/// A title's trailer (a YouTube video). Rules in DESIGN.md "Trailers".
enum Trailer {
    @MainActor private static var cache: [String: String] = [:]

    /// The YouTube id of the trailer, or nil when there is none.
    /// TMDB first (needs the key from Settings), else the trailer the addon gave (Cinemeta has one for most titles).
    @MainActor
    static func find(for meta: Meta) async -> String? {
        let cacheKey = SkipTimes.imdb(of: meta, nil) ?? meta.id
        if let c = cache[cacheKey] { return c.isEmpty ? nil : c }
        var found = await fromTMDB(meta)
        if found == nil { found = fromAddon(meta.raw) }
        // An empty answer is remembered only once the full details are in.
        if found != nil || meta.full { cache[cacheKey] = found ?? "" }
        return found
    }

    static func fromAddon(_ raw: JSON?) -> String? {
        guard let raw = raw else { return nil }
        for s in (raw["trailerStreams"] as? [JSON]) ?? [] {
            if let y = jstr(s, "ytId"), !y.isEmpty { return y }
        }
        for s in (raw["trailers"] as? [JSON]) ?? [] {
            if let y = jstr(s, "source"), !y.isEmpty, (jstr(s, "type") ?? "Trailer") == "Trailer" { return y }
        }
        return nil
    }

    @MainActor
    private static func fromTMDB(_ meta: Meta) async -> String? {
        guard let key = TMDB.key else { return nil }
        let base = "https://api.themoviedb.org/3"
        let series = meta.type != "movie"
        var path: String?
        if let imdb = SkipTimes.imdb(of: meta, nil),
           let found = try? await Net.json("\(base)/find/\(imdb)?api_key=\(key)&external_source=imdb_id") as? JSON {
            let show = (found["tv_results"] as? [JSON])?.first.flatMap { jint($0, "id") }
            let movie = (found["movie_results"] as? [JSON])?.first.flatMap { jint($0, "id") }
            if series, let s = show { path = "tv/\(s)" } else if !series, let m = movie { path = "movie/\(m)" }
            else if let m = movie { path = "movie/\(m)" } else if let s = show { path = "tv/\(s)" }
        }
        if path == nil, let t = jstr(meta.raw, "moviedb_id") ?? jint(meta.raw, "moviedb_id").map(String.init), Int(t) != nil {
            path = (series ? "tv/" : "movie/") + t
        }
        guard let p = path,
              let v = try? await Net.json("\(base)/\(p)/videos?api_key=\(key)&include_video_language=en,null") as? JSON else { return nil }
        return pick((v["results"] as? [JSON]) ?? [])
    }

    /// YouTube only; a Trailer before a Teaser, official before fan-made, otherwise TMDB's order.
    static func pick(_ videos: [JSON]) -> String? {
        var best: (score: Int, id: String)?
        for v in videos {
            guard jstr(v, "site") == "YouTube", let id = jstr(v, "key"), !id.isEmpty else { continue }
            let type = jstr(v, "type") ?? ""
            guard type == "Trailer" || type == "Teaser" else { continue }
            let score = (type == "Trailer" ? 2 : 0) + (jbool(v, "official") == true ? 1 : 0)
            if best == nil || score > best!.score { best = (score, id) }
        }
        return best?.id
    }

    /// Opens the YouTube app when it's installed, else plays it in Codio.
    @MainActor
    static func open(_ id: String, fallback: @escaping (URL) -> Void) {
        guard let url = URL(string: "https://www.youtube.com/watch?v=" + id) else { return }
        UIApplication.shared.open(url, options: [.universalLinksOnly: true]) { opened in
            if !opened { fallback(url) }
        }
    }
}

/// A YouTube page inside Codio, for when the YouTube app isn't installed.
struct WebSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController {
        let v = SFSafariViewController(url: url)
        v.preferredControlTintColor = UIColor(Theme.red)
        return v
    }
    func updateUIViewController(_ v: SFSafariViewController, context: Context) {}
}
