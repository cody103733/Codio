import Foundation

/// Music addons: plain HTTP servers that follow Eclipse Music's addon protocol (https://eclipsemusic.app/docs).
/// The list lives on this phone only. Addon links (which can hold a personal token) go to nobody but the addon itself.
@MainActor
final class MusicAddons: ObservableObject {
    static let shared = MusicAddons()
    private let key = "music_addons_v1"

    @Published var list: [MusicAddon] = []

    init() {
        if let data = UserDefaults.standard.data(forKey: key), let l = try? JSONDecoder().decode([MusicAddon].self, from: data) { list = l }
    }

    func save(_ l: [MusicAddon]) {
        list = l
        if let data = try? JSONEncoder().encode(l) { UserDefaults.standard.set(data, forKey: key) }
    }

    /// Asks the link for its manifest and adds the addon. Throws a message to show.
    func add(_ text: String) async throws {
        let addon = try await MusicAddonClient.fetchManifest(text)
        if list.contains(where: { $0.id == addon.id }) { throw MusicError.message("Already installed") }
        save(list + [addon])
    }

    func remove(_ addon: MusicAddon) { save(list.filter { $0.id != addon.id }) }

    func move(_ index: Int, by: Int) {
        let target = index + by
        guard list.indices.contains(index), list.indices.contains(target) else { return }
        var l = list
        l.swapAt(index, target)
        save(l)
    }
}

enum MusicError: Error {
    case message(String)
    var text: String { if case .message(let m) = self { return m }; return "Something went wrong" }
}

/// An addon's own answers, as the Music screens use them.
struct MusicStream {
    var url: String
    var quality: String?
    var format: String?
}

struct MusicCatalogRow {
    var addon: MusicAddon
    var id: String
    var name: String
    var type: String
    var tracks: [MusicTrack] = []
    var albums: [MusicAlbum] = []
    var artists: [MusicArtist] = []
}

enum MusicAddonClient {
    /// Notices an addon sends along (shown as a short message).
    @MainActor static var onNotice: ((String) -> Void)?

    /// What was typed or pasted, as the addon's base link (no manifest.json).
    static func baseLink(_ text: String) -> String {
        var u = text.trimmingCharacters(in: .whitespacesAndNewlines)
        for scheme in ["stremio://", "eclipse://"] where u.lowercased().hasPrefix(scheme) { u = "https://" + u.dropFirst(scheme.count) }
        if !u.lowercased().hasPrefix("http://") && !u.lowercased().hasPrefix("https://") { u = "https://" + u }
        if u.lowercased().hasSuffix("/manifest.json") { u = String(u.dropLast("/manifest.json".count)) }
        while u.hasSuffix("/") { u.removeLast() }
        return u
    }

    private static func call(_ addon: MusicAddon, _ path: String, _ query: [String: String?] = [:]) async -> MusicReply {
        var items: [String] = []
        for (k, v) in query.sorted(by: { $0.key < $1.key }) {
            if let v = v, !v.isEmpty { items.append("\(menc(k))=\(menc(v))") }
        }
        let target = addon.url + path + (items.isEmpty ? "" : (path.contains("?") ? "&" : "?") + items.joined(separator: "&"))
        return await MusicNet.call(target)
    }

    private static func settings(_ addon: MusicAddon) -> [String: String?] {
        addon.settingDefaults.mapValues { Optional($0) }
    }

    private static func notice(_ addon: MusicAddon, _ json: Any?) {
        guard let n = (json as? JSON)?["notice"] as? JSON, let message = mstr(n["message"]) else { return }
        let title = mstr(n["title"]).map { $0 + ". " } ?? ""
        let text = String("\(addon.name): \(title)\(message)".prefix(200))
        Task { @MainActor in MusicAddonClient.onNotice?(text) }
    }

    static func fetchManifest(_ text: String) async throws -> MusicAddon {
        let url = baseLink(text)
        let probe = MusicAddon(url: url, manifestJSON: "{}")
        let reply = await call(probe, "/manifest.json")
        if reply.status == 0 { throw MusicError.message("Couldn't reach that link") }
        guard reply.status == 200, let m = reply.json as? JSON, mstr(m["id"]) != nil, mstr(m["name"]) != nil, m["resources"] is [Any],
              let data = try? JSONSerialization.data(withJSONObject: m), let json = String(data: data, encoding: .utf8) else {
            throw MusicError.message("That link isn't a music addon (no manifest found)")
        }
        let addon = MusicAddon(url: url, manifestJSON: json)
        if !addon.has("search") && !addon.has("stream") && !addon.has("catalog") {
            throw MusicError.message("That addon doesn't search, stream or list anything")
        }
        return addon
    }

    static func toTrack(_ addon: MusicAddon, _ t: JSON) -> MusicTrack? {
        var id: String? = mstr(t["id"])
        if id == nil, let n = t["id"] as? NSNumber { id = n.stringValue }
        guard let tid = id, let title = mstr(t["title"]) else { return nil }
        var duration = mint(t["durationMs"])
        if duration == nil, let s = (t["duration"] as? NSNumber)?.doubleValue, s > 0 { duration = Int((s * 1000).rounded()) }
        return MusicTrack(id: tid, title: title, artist: mstr(t["artist"]) ?? "", album: mstr(t["album"]), durationMs: duration,
                          artworkURL: mstr(t["artworkURL"]), isrc: mstr(t["isrc"]), mbid: nil, artistMbid: nil, addon: addon.url, streamURL: mstr(t["streamURL"]))
    }

    static func toAlbum(_ addon: MusicAddon, _ a: JSON) -> MusicAlbum? {
        var id: String? = mstr(a["id"])
        if id == nil, let n = a["id"] as? NSNumber { id = n.stringValue }
        guard let aid = id, let title = mstr(a["title"]) else { return nil }
        var year = mstr(a["year"])
        if year == nil, let n = a["year"] as? NSNumber { year = n.stringValue }
        return MusicAlbum(id: aid, title: title, artist: mstr(a["artist"]) ?? "", year: year, kind: nil, artworkURL: mstr(a["artworkURL"]), addon: addon.url, artistMbid: nil)
    }

    static func toArtist(_ addon: MusicAddon, _ a: JSON) -> MusicArtist? {
        var id: String? = mstr(a["id"])
        if id == nil, let n = a["id"] as? NSNumber { id = n.stringValue }
        guard let aid = id, let name = mstr(a["name"]) else { return nil }
        let genres = ((a["genres"] as? [Any]) ?? []).compactMap { $0 as? String }.prefix(3).joined(separator: ", ")
        return MusicArtist(id: aid, name: name, detail: genres.isEmpty ? nil : genres, artworkURL: mstr(a["artworkURL"]), addon: addon.url)
    }

    static func search(_ addon: MusicAddon, _ q: String) async -> MusicResults {
        var query = settings(addon)
        query["q"] = q
        let reply = await call(addon, "/search", query)
        notice(addon, reply.json)
        guard reply.status == 200, let j = reply.json as? JSON else { return MusicResults() }
        return MusicResults(
            artists: marr(j["artists"]).compactMap { toArtist(addon, $0) },
            albums: marr(j["albums"]).compactMap { toAlbum(addon, $0) },
            tracks: marr(j["tracks"]).compactMap { toTrack(addon, $0) })
    }

    /// The audio link for an addon's own track id. A 503 with Retry-After means "still preparing": wait and ask again.
    static func stream(_ addon: MusicAddon, _ id: String) async -> MusicStream? {
        for _ in 0..<4 {
            let reply = await call(addon, "/stream/\(menc(id))", settings(addon))
            notice(addon, reply.json)
            if reply.status == 503 {
                let wait = min(30, max(2, reply.retryAfter ?? 5))
                try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000_000)
                if Task.isCancelled { return nil }
                continue
            }
            guard reply.status == 200, let j = reply.json as? JSON, let url = mstr(j["url"]) else { return nil }
            return MusicStream(url: url, quality: mstr(j["quality"]), format: mstr(j["format"]))
        }
        return nil
    }

    static func resolveIsrc(_ addon: MusicAddon, _ isrc: String) async -> String? {
        var query = settings(addon)
        query["isrc"] = isrc
        let reply = await call(addon, "/resolve-isrc", query)
        notice(addon, reply.json)
        guard reply.status == 200, let j = reply.json as? JSON else { return nil }
        if let s = mstr(j["trackId"]) ?? mstr(j["id"]) { return s }
        if let n = (j["trackId"] as? NSNumber) ?? (j["id"] as? NSNumber) { return n.stringValue }
        return nil
    }

    static func resolve(_ addon: MusicAddon, _ track: MusicTrack) async -> MusicTrack? {
        var query = settings(addon)
        query["isrc"] = track.isrc
        query["title"] = track.title
        query["artist"] = track.artist
        query["durationMs"] = track.durationMs.map { String($0) }
        let reply = await call(addon, "/resolve", query)
        notice(addon, reply.json)
        guard reply.status == 200, let j = reply.json as? JSON, let item = j["item"] as? JSON else { return nil }
        return toTrack(addon, item)
    }

    /// The rows an addon offers for Home (its catalogs).
    static func catalogs(_ addons: [MusicAddon]) async -> [MusicCatalogRow] {
        var rows: [MusicCatalogRow] = []
        for addon in addons where addon.has("catalog") {
            for c in addon.catalogs {
                guard let id = mstr(c["id"]) else { continue }
                var query = settings(addon)
                query["skip"] = "0"
                let reply = await call(addon, "/catalog/\(menc(id))", query)
                notice(addon, reply.json)
                let items = marr((reply.json as? JSON)?["items"])
                let type = mstr(c["type"]) ?? "track"
                var row = MusicCatalogRow(addon: addon, id: id, name: mstr(c["name"]) ?? id, type: type)
                if type == "album" { row.albums = items.compactMap { toAlbum(addon, $0) } }
                else if type == "artist" {
                    row.artists = items.compactMap { i in
                        var x = i
                        if x["name"] == nil { x["name"] = i["title"] }
                        return toArtist(addon, x)
                    }
                } else { row.tracks = items.compactMap { toTrack(addon, $0) } }
                if !(row.tracks.isEmpty && row.albums.isEmpty && row.artists.isEmpty) { rows.append(row) }
            }
        }
        return rows
    }

    struct AlbumDetail {
        var title: String
        var artist: String
        var artworkURL: String?
        var year: String?
        var tracks: [MusicTrack]
    }

    static func albumDetail(_ addon: MusicAddon, _ id: String) async -> AlbumDetail? {
        let reply = await call(addon, "/album/\(menc(id))", settings(addon))
        notice(addon, reply.json)
        guard reply.status == 200, let j = reply.json as? JSON else { return nil }
        let title = mstr(j["title"]) ?? ""
        let artist = mstr(j["artist"]) ?? ""
        let art = mstr(j["artworkURL"])
        var year = mstr(j["year"])
        if year == nil, let n = j["year"] as? NSNumber { year = n.stringValue }
        let tracks: [MusicTrack] = marr(j["tracks"]).compactMap { t in
            var x = t
            if x["artist"] == nil { x["artist"] = artist }
            if x["album"] == nil { x["album"] = title }
            if x["artworkURL"] == nil, let a = art { x["artworkURL"] = a }
            return toTrack(addon, x)
        }
        return AlbumDetail(title: title, artist: artist, artworkURL: art, year: year, tracks: tracks)
    }

    struct ArtistDetail {
        var name: String
        var artworkURL: String?
        var bio: String?
        var topTracks: [MusicTrack]
        var albums: [MusicAlbum]
    }

    static func artistDetail(_ addon: MusicAddon, _ id: String) async -> ArtistDetail? {
        let reply = await call(addon, "/artist/\(menc(id))", settings(addon))
        notice(addon, reply.json)
        guard reply.status == 200, let j = reply.json as? JSON else { return nil }
        let name = mstr(j["name"]) ?? ""
        let top: [MusicTrack] = marr(j["topTracks"]).compactMap { t in
            var x = t
            if x["artist"] == nil { x["artist"] = name }
            return toTrack(addon, x)
        }
        let albums: [MusicAlbum] = marr(j["albums"]).compactMap { a in
            var x = a
            if x["artist"] == nil { x["artist"] = name }
            return toAlbum(addon, x)
        }
        return ArtistDetail(name: name, artworkURL: mstr(j["artworkURL"]), bio: mstr(j["bio"]), topTracks: top, albums: albums)
    }
}

/// Finding the audio for a track: ask each music addon, in the order you put them, and trust only an exact answer.
///   1. The track came from this addon: use its own id.
///   2. The track has an ISRC and the addon lists "isrc": /resolve-isrc.
///   3. The addon lists "resolve": /resolve with title, artist and length.
///   4. Otherwise search "artist title" and take a result only if it passes the strict check below.
/// A wrong song is worse than none, so a doubtful match counts as a miss and the next addon is tried.
enum MusicMatch {
    static let toleranceMs = 3000

    /// Lower case, no accents, no "(Remastered 2011)" / "- Live" style tails, no punctuation.
    static func normalise(_ text: String) -> String {
        var t = text.folding(options: .diacriticInsensitive, locale: nil).lowercased()
        t = t.replacingOccurrences(of: "\\s*[(\\[][^)\\]]*(remaster|version|edit|mix|mono|stereo|deluxe|bonus|feat|ft\\.|with)[^)\\]]*[)\\]]", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "\\s+-\\s+(remaster(ed)?( \\d{4})?|\\d{4} remaster(ed)?|mono|stereo|single version|radio edit)$", with: "", options: .regularExpression)
        t = t.replacingOccurrences(of: "&", with: " and ")
        t = t.replacingOccurrences(of: "[^a-z0-9]+", with: " ", options: .regularExpression)
        return t.trimmingCharacters(in: .whitespaces)
    }

    private static func artistMatches(_ wanted: String, _ got: String) -> Bool {
        func bare(_ s: String) -> String {
            let n = normalise(s)
            return n.hasPrefix("the ") ? String(n.dropFirst(4)) : n
        }
        let a = bare(wanted), b = bare(got)
        if a.isEmpty || b.isEmpty { return false }
        return a == b || a.hasPrefix(b + " ") || b.hasPrefix(a + " ") || a.contains(" " + b + " ") || b.contains(" " + a + " ")
    }

    /// Is `candidate` the recording `wanted`? An equal ISRC says yes outright; otherwise title and artist must agree and
    /// the length must be within a few seconds (when both are known).
    static func isSameRecording(_ wanted: MusicTrack, _ candidate: MusicTrack) -> Bool {
        if let a = wanted.isrc, let b = candidate.isrc { return a.uppercased() == b.uppercased() }
        let title = normalise(wanted.title)
        if title.isEmpty || title != normalise(candidate.title) { return false }
        if !artistMatches(wanted.artist, candidate.artist) { return false }
        if let x = wanted.durationMs, let y = candidate.durationMs, abs(x - y) > toleranceMs { return false }
        return true
    }

    struct Playable {
        var url: String
        var addon: MusicAddon
        var quality: String?
        var format: String?
    }

    private static func via(_ addon: MusicAddon, id: String, direct: String?) async -> Playable? {
        if let d = direct { return Playable(url: d, addon: addon, quality: nil, format: nil) }
        guard let s = await MusicAddonClient.stream(addon, id) else { return nil }
        return Playable(url: s.url, addon: addon, quality: s.quality, format: s.format)
    }

    static func findAudio(_ track: MusicTrack, addons: [MusicAddon], isrcLookup: (() async -> String?)?) async -> Playable? {
        var wanted = track
        var isrcTried = track.isrc != nil
        for addon in addons {
            if Task.isCancelled { return nil }
            if track.addon == addon.url && !track.id.hasPrefix("mbid:") {
                if let own = await via(addon, id: track.id, direct: track.streamURL) { return own }
            }
            if wanted.isrc == nil, !isrcTried, let lookup = isrcLookup, addon.has("isrc") || addon.has("resolve") {
                isrcTried = true
                if let code = await lookup() { wanted.isrc = code }
            }
            if let isrc = wanted.isrc, addon.has("isrc") {
                if let id = await MusicAddonClient.resolveIsrc(addon, isrc), let hit = await via(addon, id: id, direct: nil) { return hit }
                continue
            }
            if addon.has("resolve") {
                if let item = await MusicAddonClient.resolve(addon, wanted), let hit = await via(addon, id: item.id, direct: item.streamURL) { return hit }
                continue
            }
            if addon.has("search") {
                let found = await MusicAddonClient.search(addon, "\(wanted.artist) \(wanted.title)".trimmingCharacters(in: .whitespaces))
                if let best = found.tracks.first(where: { isSameRecording(wanted, $0) }),
                   let hit = await via(addon, id: best.id, direct: best.streamURL) { return hit }
            }
        }
        return nil
    }
}
