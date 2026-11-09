import Foundation

typealias JSON = [String: Any]

// MARK: - JSON helpers

func jstr(_ o: JSON?, _ k: String) -> String? {
    guard let v = o?[k], !(v is NSNull) else { return nil }
    let s: String
    if let x = v as? String { s = x } else if let n = v as? NSNumber { s = n.stringValue } else { return nil }
    let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
    return t.isEmpty ? nil : t
}

func jint(_ o: JSON?, _ k: String) -> Int? {
    guard let v = o?[k] else { return nil }
    if let n = v as? NSNumber { return n.intValue }
    if let s = v as? String { return Int(s) }
    return nil
}

func jdouble(_ o: JSON?, _ k: String) -> Double? {
    guard let v = o?[k] else { return nil }
    if let n = v as? NSNumber { return n.doubleValue }
    if let s = v as? String { return Double(s) }
    return nil
}

func jbool(_ o: JSON?, _ k: String) -> Bool? {
    guard let v = o?[k] else { return nil }
    if let b = v as? Bool { return b }
    if let n = v as? NSNumber { return n.boolValue }
    return nil
}

func parseJSON(_ s: String?) -> Any? {
    guard let s = s, let d = s.data(using: .utf8) else { return nil }
    return try? JSONSerialization.jsonObject(with: d, options: [.fragmentsAllowed])
}

func toJSONString(_ v: Any) -> String {
    guard JSONSerialization.isValidJSONObject(v) || v is String || v is NSNumber,
          let d = try? JSONSerialization.data(withJSONObject: v, options: [.fragmentsAllowed]) else { return "null" }
    return String(data: d, encoding: .utf8) ?? "null"
}

/// A JavaScript string literal for s (quoted and escaped).
func jsQuote(_ s: String) -> String { toJSONString(s) }

func urlEnc(_ s: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
}

func typeLabel(_ type: String) -> String {
    switch type {
    case "movie": return "Movies"
    case "series": return "Series"
    case "channel": return "Channels"
    case "tv": return "Live TV"
    default: return type.prefix(1).uppercased() + type.dropFirst()
    }
}

// MARK: - Addons and catalogs

struct Addon {
    let transportUrl: String
    let base: String
    let name: String
    let manifest: JSON

    init(transportUrl: String, manifest: JSON) {
        self.transportUrl = transportUrl
        self.base = transportUrl.hasSuffix("/manifest.json") ? String(transportUrl.dropLast("/manifest.json".count)) : transportUrl
        self.manifest = manifest
        self.name = jstr(manifest, "name") ?? "Addon"
    }

    /// Does this addon answer resource (catalog/meta/stream) for this type and id?
    func supports(_ resource: String, type: String?, id: String?) -> Bool {
        guard let res = manifest["resources"] as? [Any] else { return false }
        for r in res {
            var types = manifest["types"] as? [String]
            var prefixes = manifest["idPrefixes"] as? [String]
            if let s = r as? String {
                if s != resource { continue }
            } else if let o = r as? JSON {
                if jstr(o, "name") != resource { continue }
                if o["types"] != nil { types = o["types"] as? [String] }
                if o["idPrefixes"] != nil { prefixes = o["idPrefixes"] as? [String] }
            } else { continue }
            if let types = types, let type = type, !types.contains(type) { continue }
            if let prefixes = prefixes, let id = id, !prefixes.isEmpty, !prefixes.contains(where: { id.hasPrefix($0) }) { continue }
            return true
        }
        return false
    }
}

struct Catalog: Identifiable {
    let addon: Addon
    let type: String
    let catalogId: String
    let name: String
    let search: Bool
    let skip: Bool
    let genreRequired: Bool
    let otherRequired: Bool
    let genres: [String]

    var id: String { addon.transportUrl + "|" + type + "|" + catalogId }

    init(addon: Addon, json c: JSON) {
        self.addon = addon
        type = jstr(c, "type") ?? ""
        catalogId = jstr(c, "id") ?? ""
        name = jstr(c, "name") ?? addon.name
        var s = false, k = false, gReq = false, oReq = false
        var g: [String] = []
        for e in (c["extra"] as? [JSON]) ?? [] {
            let n = jstr(e, "name") ?? ""
            let req = jbool(e, "isRequired") ?? false
            if n == "search" { s = true } else if n == "skip" { k = true } else if n == "genre" {
                gReq = gReq || req
                g += ((e["options"] as? [Any]) ?? []).map { "\($0)" }
            }
            if req && n != "genre" { oReq = true }
        }
        if let sup = c["extraSupported"] as? [String] {
            s = s || sup.contains("search")
            k = k || sup.contains("skip")
        }
        for r in (c["extraRequired"] as? [String]) ?? [] {
            if r == "genre" { gReq = true } else { oReq = true }
        }
        search = s
        skip = k
        genreRequired = gReq
        otherRequired = oReq
        genres = g
    }

    var browsable: Bool { !otherRequired && (!genreRequired || !genres.isEmpty) }

    func url(genre: String? = nil, skip skipCount: Int = 0, query: String? = nil) -> String {
        var extra: [String] = []
        if let q = query { extra.append("search=" + urlEnc(q)) }
        var g = genre
        if g == nil, genreRequired, let first = genres.first { g = first }
        if let g = g { extra.append("genre=" + urlEnc(g)) }
        if skipCount > 0 { extra.append("skip=\(skipCount)") }
        return addon.base + "/catalog/" + urlEnc(type) + "/" + urlEnc(catalogId) + (extra.isEmpty ? "" : "/" + extra.joined(separator: "&")) + ".json"
    }
}

// MARK: - Titles

struct Video: Identifiable, Hashable {
    var id: String
    var title: String
    var thumbnail: String?
    var overview: String?
    var released: String?
    var season: Int
    var episode: Int
}

final class Meta: ObservableObject, Identifiable, Hashable {
    var id: String
    var type: String
    @Published var name: String
    @Published var poster: String?
    @Published var background: String?
    @Published var logo: String?
    @Published var description: String?
    @Published var releaseInfo: String?
    @Published var runtime: String?
    @Published var imdbRating: String?
    var posterShape: String = "poster"
    @Published var genres: [String] = []
    @Published var cast: [String] = []
    @Published var videos: [Video] = []
    @Published var full = false
    /// Continue Watching
    var progress: Double = 0
    var lastVideoId: String?
    var raw: JSON?

    init(id: String, type: String, name: String = "") {
        self.id = id
        self.type = type
        self.name = name
    }

    static func == (a: Meta, b: Meta) -> Bool { a === b }
    func hash(into h: inout Hasher) { h.combine(ObjectIdentifier(self)) }

    static func parse(_ o: JSON) -> Meta {
        let m = Meta(id: jstr(o, "id") ?? jstr(o, "_id") ?? "", type: jstr(o, "type") ?? "movie", name: jstr(o, "name") ?? "")
        m.raw = o
        m.poster = jstr(o, "poster")
        m.background = jstr(o, "background")
        m.logo = jstr(o, "logo")
        m.description = jstr(o, "description")
        m.releaseInfo = jstr(o, "releaseInfo") ?? jstr(o, "year")
        m.runtime = jstr(o, "runtime")
        m.imdbRating = jstr(o, "imdbRating")
        m.posterShape = jstr(o, "posterShape") ?? "poster"
        let g = (o["genres"] as? [Any]) ?? (o["genre"] as? [Any]) ?? []
        m.genres = g.prefix(4).map { "\($0)" }
        m.cast = ((o["cast"] as? [Any]) ?? []).prefix(6).map { "\($0)" }
        var vids: [Video] = []
        for e in (o["videos"] as? [JSON]) ?? [] {
            vids.append(Video(id: jstr(e, "id") ?? "",
                              title: jstr(e, "title") ?? jstr(e, "name") ?? "",
                              thumbnail: jstr(e, "thumbnail"),
                              overview: jstr(e, "overview") ?? jstr(e, "description"),
                              released: jstr(e, "released"),
                              season: jint(e, "season") ?? 0,
                              episode: jint(e, "episode") ?? jint(e, "number") ?? 0))
        }
        m.videos = vids.sorted { $0.season != $1.season ? $0.season < $1.season : $0.episode < $1.episode }
        return m
    }

    /// "2003 · 3h 21min · ★ 8.9"
    var metaLine: String {
        var parts: [String] = []
        if let r = releaseInfo { parts.append(r) }
        if let r = runtime { parts.append(r) }
        if let r = imdbRating, !r.isEmpty { parts.append("★ " + r) }
        return parts.joined(separator: "  ·  ")
    }

    func merge(_ f: Meta) {
        if f.raw != nil { raw = f.raw }
        if !f.name.isEmpty { name = f.name }
        if let x = f.poster { poster = x }
        if let x = f.background { background = x }
        if let x = f.logo { logo = x }
        if let x = f.description { description = x }
        if let x = f.releaseInfo { releaseInfo = x }
        if let x = f.runtime { runtime = x }
        if let x = f.imdbRating { imdbRating = x }
        if !f.genres.isEmpty { genres = f.genres }
        if !f.cast.isEmpty { cast = f.cast }
        videos = f.videos
        full = true
    }

    var seasons: [Int] {
        Array(Set(videos.map { $0.season })).sorted { a, b in
            // Specials (season 0) last
            if a == 0 { return false }
            if b == 0 { return true }
            return a < b
        }
    }

    func video(_ vid: String?) -> Video? {
        guard let vid = vid else { return nil }
        return videos.first { $0.id == vid }
    }

    /// The episode after vid, if there is one.
    func next(after vid: String?) -> Video? {
        guard let vid = vid, let i = videos.firstIndex(where: { $0.id == vid }) else { return nil }
        let rest = videos[(i + 1)...].filter { $0.season > 0 }
        return rest.first
    }
}

struct LibraryEntry {
    var id: String
    var type: String
    var videoId: String?
    var offset: Double = 0
    var duration: Double = 0
    var lastWatched: String?
    var mtime: String = ""
    var removed = false
    var temp = false
    var meta: Meta
}

// MARK: - Sources (from the hidden web page)

struct Source: Identifiable {
    var addon: String?
    var name: String?
    var description: String?
    var href: String
    var url: String
    var quality: String?
    var cached = false
    var mbps: Double = -1
    /// Position in Smart auto-play's ranking (0 = what Play would pick), -1 when it isn't a candidate.
    var rank = -1
    var id: String { href }

    var qualityLabel: String? {
        guard let q = quality else { return nil }
        return q == "2160p" ? "4K" : q
    }
}

struct Sources {
    var key: String
    var loading: Bool
    var list: [Source]

    var best: Source? { list.first.flatMap { $0.rank == 0 ? $0 : nil } }

    static func parse(_ o: JSON) -> Sources {
        var byHref: [String: Int] = [:]
        var all: [Source] = []
        for x in (o["streams"] as? [JSON]) ?? [] {
            guard let href = jstr(x, "href"), let url = jstr(x, "url") else { continue }
            // Codio never plays torrents: only http(s) (debrid / direct) sources.
            guard url.hasPrefix("http://") || url.hasPrefix("https://") else { continue }
            byHref[href] = all.count
            all.append(Source(addon: jstr(x, "addon"), name: jstr(x, "name"), description: jstr(x, "description"), href: href, url: url))
        }
        var ranked: [Source] = []
        for c in (o["ranked"] as? [JSON]) ?? [] {
            guard let link = jstr(c, "link"), let i = byHref[link], all[i].rank < 0 else { continue }
            all[i].rank = ranked.count
            all[i].quality = jstr(c, "quality")
            all[i].cached = jbool(c, "cached") ?? false
            all[i].mbps = jdouble(c, "mbps") ?? -1
            ranked.append(all[i])
        }
        return Sources(key: jstr(o, "key") ?? "", loading: jbool(o, "loading") ?? false,
                       list: ranked + all.filter { $0.rank < 0 })
    }
}
