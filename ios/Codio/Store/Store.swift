import Foundation

/// Addons, library and settings as the hidden web UI (Codio's engine) saved them, plus catalog/meta loading.
@MainActor
final class Store: ObservableObject {
    static let cinemeta = "https://v3-cinemeta.strem.io/manifest.json"

    @Published private(set) var addons: [Addon] = []
    @Published private(set) var library: [String: LibraryEntry] = [:]
    @Published private(set) var continueWatching: [Meta] = []
    @Published private(set) var profile: JSON = [:]
    @Published private(set) var profileLoaded = false
    /// Smart auto-play settings (the web UI's autoPlaySettings in localStorage).
    @Published var autoPlay: JSON = [:]
    /// The latest source list from the hidden movie page.
    @Published var sources: Sources?
    /// Bumped when the addon list changes (screens reload their rows).
    @Published private(set) var generation = 0

    private var metaCache: [String: Meta] = [:]
    private let cacheFile: URL = {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("native-profile.json")
    }()

    init() {
        // Last run's addons, so Home can load before the web engine is up.
        if let d = try? Data(contentsOf: cacheFile),
           let list = (try? JSONSerialization.jsonObject(with: d)) as? [JSON] {
            addons = Store.parseAddons(list)
        }
        URLCache.shared = URLCache(memoryCapacity: 64 << 20, diskCapacity: 512 << 20)
    }

    // MARK: - From the web engine

    func update(storage: JSON) {
        let profileJSON = parseJSON(storage["profile"] as? String) as? JSON
        let recent = parseJSON(storage["recent"] as? String) as? JSON
        let all = parseJSON(storage["library"] as? String) as? JSON
        if let ap = parseJSON(storage["autoplay"] as? String) as? JSON { autoPlay = ap }

        if let p = profileJSON {
            profile = p
            if let list = p["addons"] as? [JSON] {
                let fresh = Store.parseAddons(list)
                if Store.signature(fresh) != Store.signature(addons), !fresh.isEmpty {
                    addons = fresh
                    generation += 1
                    if let d = try? JSONSerialization.data(withJSONObject: list) { try? d.write(to: cacheFile) }
                }
            }
        }
        var lib: [String: LibraryEntry] = [:]
        var cw: [String: (Meta, String)] = [:]
        Store.readLibrary(all, into: &lib, cw: &cw)
        Store.readLibrary(recent, into: &lib, cw: &cw) // newer, wins
        library = lib
        continueWatching = cw.values.sorted { $0.1 > $1.1 }.prefix(30).map { $0.0 }
        profileLoaded = true
        if addons.isEmpty { Task { await ensureDefaults() } }
    }

    /// A fresh install has no profile yet: show Cinemeta's catalogs until there is one.
    private func ensureDefaults() async {
        guard addons.isEmpty, let o = try? await Net.json(Store.cinemeta) as? JSON else { return }
        if addons.isEmpty {
            addons = [Addon(transportUrl: Store.cinemeta, manifest: o)]
            generation += 1
        }
    }

    private static func parseAddons(_ list: [JSON]) -> [Addon] {
        list.compactMap { d in
            guard let url = jstr(d, "transportUrl"), let m = d["manifest"] as? JSON else { return nil }
            return Addon(transportUrl: url, manifest: m)
        }
    }

    private static func signature(_ list: [Addon]) -> String {
        list.map { $0.transportUrl + "|" + toJSONString(($0.manifest["catalogs"] as? [Any]) ?? []) }.joined(separator: "\n")
    }

    private static func readLibrary(_ bucket: JSON?, into lib: inout [String: LibraryEntry], cw: inout [String: (Meta, String)]) {
        guard let items = bucket?["items"] as? [String: Any] else { return }
        for (_, value) in items {
            guard let it = value as? JSON, let id = jstr(it, "_id") else { continue }
            let st = it["state"] as? JSON
            let meta = Meta.parse(it)
            meta.id = id
            var e = LibraryEntry(id: id, type: jstr(it, "type") ?? "", meta: meta)
            e.removed = jbool(it, "removed") ?? false
            e.temp = jbool(it, "temp") ?? false
            e.mtime = jstr(it, "_mtime") ?? ""
            e.offset = jdouble(st, "timeOffset") ?? 0
            e.duration = jdouble(st, "duration") ?? 0
            e.videoId = jstr(st, "video_id")
            e.lastWatched = jstr(st, "lastWatched")
            e.watched = (jint(st, "timesWatched") ?? 0) > 0 || (jint(st, "flaggedWatched") ?? 0) > 0
            lib[id] = e
            if e.offset > 0, !e.removed || e.temp, e.type != "other" {
                meta.progress = e.duration > 0 ? min(1, e.offset / e.duration) : 0
                meta.lastVideoId = e.videoId
                cw[id] = (meta, e.lastWatched ?? e.mtime)
            } else {
                cw[id] = nil
            }
        }
    }

    // MARK: - Library

    func inLibrary(_ id: String) -> Bool {
        guard let e = library[id] else { return false }
        return !e.removed && !e.temp
    }

    func entry(_ id: String) -> LibraryEntry? { library[id] }

    /// Library items of a type (nil = all), most recently watched or added first.
    func libraryItems(_ type: String?) -> [Meta] {
        let list = library.values.filter { !$0.removed && !$0.temp && $0.type != "other" && (type == nil || $0.type == type) }
        func key(_ e: LibraryEntry) -> String {
            if let w = e.lastWatched, w > e.mtime { return w }
            return e.mtime
        }
        return list.sorted { key($0) > key($1) }.map { e in
            e.meta.progress = e.duration > 0 && e.offset > 0 ? min(1, e.offset / e.duration) : 0
            return e.meta
        }
    }

    /// Optimistic change after Add/Remove, until the engine saves the library again.
    func markInLibrary(_ m: Meta, _ inLib: Bool) {
        var e = library[m.id] ?? LibraryEntry(id: m.id, type: m.type, mtime: "9999", meta: m)
        e.removed = !inLib
        e.temp = false
        library[m.id] = e
    }

    /// The library preview the core expects for AddToLibrary.
    static func preview(_ m: Meta) -> JSON {
        var p: JSON = [:]
        if let raw = m.raw {
            for k in ["poster", "background", "logo", "description", "releaseInfo", "runtime", "released", "links", "trailerStreams", "behaviorHints"] {
                if let v = raw[k], !(v is NSNull) { p[k] = v }
            }
        }
        p["id"] = m.id
        p["type"] = m.type
        p["name"] = m.name
        p["posterShape"] = m.posterShape
        if p["poster"] == nil, let x = m.poster { p["poster"] = x }
        if p["background"] == nil, let x = m.background { p["background"] = x }
        if p["logo"] == nil, let x = m.logo { p["logo"] = x }
        if p["description"] == nil, let x = m.description { p["description"] = x }
        if p["releaseInfo"] == nil, let x = m.releaseInfo { p["releaseInfo"] = x }
        if p["links"] == nil { p["links"] = [Any]() }
        if p["behaviorHints"] == nil { p["behaviorHints"] = JSON() }
        return p
    }

    // MARK: - Profile

    var signedInEmail: String? {
        guard let auth = profile["auth"] as? JSON, let user = auth["user"] as? JSON else { return nil }
        return jstr(user, "email")
    }

    var settings: JSON { (profile["settings"] as? JSON) ?? [:] }

    var addonDescriptors: [JSON] { (profile["addons"] as? [JSON]) ?? [] }

    func isInstalled(_ url: String) -> Bool { addonDescriptors.contains { jstr($0, "transportUrl") == url } }

    /// Local copy of a settings change (UpdateSettings goes to the core separately).
    func setSetting(_ key: String, _ value: Any) {
        var p = profile
        var s = settings
        s[key] = value
        p["settings"] = s
        profile = p
    }

    // MARK: - Catalogs

    func homeCatalogs() -> [Catalog] {
        catalogs { !$0.otherRequired && !$0.genreRequired }
    }

    func discoverCatalogs(_ type: String) -> [Catalog] {
        catalogs { $0.type == type && $0.browsable }
    }

    func searchCatalogs() -> [Catalog] {
        func rank(_ t: String) -> Int { t == "movie" ? 0 : t == "series" ? 1 : 2 }
        return catalogs { $0.search && !$0.genreRequired }.enumerated()
            .sorted { rank($0.element.type) != rank($1.element.type) ? rank($0.element.type) < rank($1.element.type) : $0.offset < $1.offset }
            .map { $0.element }
    }

    private func catalogs(_ keep: (Catalog) -> Bool) -> [Catalog] {
        var out: [Catalog] = []
        for a in addons {
            for c in (a.manifest["catalogs"] as? [JSON]) ?? [] {
                let cat = Catalog(addon: a, json: c)
                if keep(cat) { out.append(cat) }
            }
        }
        return out
    }

    func load(_ cat: Catalog, genre: String? = nil, skip: Int = 0, query: String? = nil) async -> [Meta] {
        guard let r = try? await Net.json(cat.url(genre: genre, skip: skip, query: query)) as? JSON else { return [] }
        return ((r["metas"] as? [JSON]) ?? []).compactMap { o in
            let m = Meta.parse(o)
            if m.id.isEmpty { return nil }
            if jstr(o, "type") == nil { m.type = cat.type }
            return m
        }
    }

    /// Full details (episodes, cast, runtime) from the first addon that has meta for this title.
    func loadMeta(_ m: Meta) async {
        let key = m.type + "/" + m.id
        if let c = metaCache[key] {
            m.merge(c)
            return
        }
        for a in addons where a.supports("meta", type: m.type, id: m.id) {
            if let r = try? await Net.json(a.base + "/meta/" + urlEnc(m.type) + "/" + urlEnc(m.id) + ".json") as? JSON,
               let o = r["meta"] as? JSON {
                let f = Meta.parse(o)
                metaCache[key] = f
                m.merge(f)
                return
            }
        }
    }

    func sourcesFor(_ m: Meta, _ vid: String) -> Sources? {
        guard let s = sources, s.key == m.type + "/" + m.id + "/" + vid else { return nil }
        return s
    }

    // MARK: - Settings stored by the app itself

    static var skipButtons: Bool {
        get { UserDefaults.standard.object(forKey: "skip_buttons") as? Bool ?? true }
        set { UserDefaults.standard.set(newValue, forKey: "skip_buttons") }
    }

    static var subsAuto: Bool {
        get { UserDefaults.standard.bool(forKey: "subs_auto") }
        set { UserDefaults.standard.set(newValue, forKey: "subs_auto") }
    }
}

enum Net {
    static let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        c.requestCachePolicy = .useProtocolCachePolicy
        return URLSession(configuration: c)
    }()

    static func json(_ url: String) async throws -> Any {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        let (data, response) = try await session.data(from: u)
        if let h = response as? HTTPURLResponse, h.statusCode / 100 != 2 { throw URLError(.badServerResponse) }
        return try JSONSerialization.jsonObject(with: data)
    }
}
