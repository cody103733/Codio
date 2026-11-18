import Foundation

/// MusicBrainz and the Cover Art Archive: artists, albums, tracklists with ISRC codes, artwork.
/// The server address is a setting (music_mb_base), so a self-hosted mirror works without code changes.
enum MB {
    static let baseKey = "music_mb_base"

    static var base: String {
        var b = (UserDefaults.standard.string(forKey: baseKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        while b.hasSuffix("/") { b.removeLast() }
        return b.hasPrefix("http") ? b : "https://musicbrainz.org"
    }

    private static func get(_ path: String) async -> Any? {
        let b = base
        let url = b + path + (path.contains("fmt=") ? "" : (path.contains("?") ? "&" : "?") + "fmt=json")
        let isSearch = path.contains("query=")
        let reply = await MusicNet.metadata(url, ttl: isSearch ? MusicNet.day : 7 * MusicNet.day, server: b)
        return reply.status == 200 ? reply.json : nil
    }

    private static func lucene(_ text: String) -> String {
        let special = Set("+-&|!(){}[]^\"~*?:\\/")
        var out = ""
        for c in text {
            if special.contains(c) { out.append("\\") }
            out.append(c)
        }
        return out.trimmingCharacters(in: .whitespaces)
    }

    static func credit(_ list: Any?) -> (name: String, id: String?) {
        let l = marr(list)
        if l.isEmpty { return ("", nil) }
        var name = ""
        for c in l {
            var part = mstr(c["name"]) ?? ""
            if part.isEmpty, let a = c["artist"] as? JSON { part = mstr(a["name"]) ?? "" }
            name += part + (mstr(c["joinphrase"]) ?? "")
        }
        let artist = l[0]["artist"] as? JSON
        return (name.trimmingCharacters(in: .whitespaces), mstr(artist?["id"]))
    }

    private static func year(_ date: Any?) -> String? {
        if let s = date as? String, s.count >= 4, s.prefix(4).allSatisfy({ $0.isNumber }) { return String(s.prefix(4)) }
        return nil
    }

    // MARK: Pictures

    static func coverURL(_ releaseGroupId: String, size: Int = 250) -> String {
        "https://coverartarchive.org/release-group/\(releaseGroupId)/front-\(size)"
    }
    static func releaseCoverURL(_ releaseId: String, size: Int = 250) -> String {
        "https://coverartarchive.org/release/\(releaseId)/front-\(size)"
    }

    // MARK: Mapping

    static func toAlbum(_ g: JSON) -> MusicAlbum {
        let secondary = (g["secondary-types"] as? [Any])?.compactMap { $0 as? String } ?? []
        var kinds: [String] = []
        if let p = mstr(g["primary-type"]) { kinds.append(p) }
        kinds.append(contentsOf: secondary)
        let c = credit(g["artist-credit"])
        let id = jstr(g, "id") ?? ""
        return MusicAlbum(id: id, title: mstr(g["title"]) ?? "", artist: c.name, year: year(g["first-release-date"]),
                          kind: kinds.isEmpty ? nil : kinds.joined(separator: " · "), artworkURL: coverURL(id), addon: nil, artistMbid: c.id)
    }

    static func toTrack(_ recording: JSON, albumTitle: String? = nil, artwork: String? = nil, artistFallback: String = "") -> MusicTrack? {
        guard let id = mstr(recording["id"]) else { return nil }
        let isrcs = (recording["isrcs"] as? [Any])?.compactMap { $0 as? String } ?? []
        let c = credit(recording["artist-credit"])
        return MusicTrack(id: "mbid:" + id, title: mstr(recording["title"]) ?? "", artist: c.name.isEmpty ? artistFallback : c.name,
                          album: albumTitle, durationMs: mint(recording["length"]), artworkURL: artwork, isrc: isrcs.first,
                          mbid: id, artistMbid: c.id, addon: nil, streamURL: nil)
    }

    // MARK: Search

    /// Artists, then albums, then songs: each is handed over as soon as it arrives.
    static func search(_ query: String, onPart: @MainActor (MusicResults) -> Void) async {
        let q = menc(lucene(query))
        var out = MusicResults()
        if let j = await get("/ws/2/artist?query=\(q)&limit=8") as? JSON {
            out.artists = marr(j["artists"]).map { a in
                let detail = [mstr(a["disambiguation"]), mstr(a["type"])].compactMap { $0 }.joined(separator: " · ")
                return MusicArtist(id: jstr(a, "id") ?? "", name: mstr(a["name"]) ?? "", detail: detail.isEmpty ? nil : detail, artworkURL: nil, addon: nil)
            }
            await onPart(out)
        }
        if Task.isCancelled { return }
        if let j = await get("/ws/2/release-group?query=\(q)&limit=12") as? JSON {
            out.albums = marr(j["release-groups"]).map { toAlbum($0) }
            await onPart(out)
        }
        if Task.isCancelled { return }
        if let j = await get("/ws/2/recording?query=\(q)&limit=20") as? JSON {
            out.tracks = marr(j["recordings"]).compactMap { r in
                let release = marr(r["releases"]).first
                var art: String? = nil
                if let gid = jstr(release?["release-group"] as? JSON, "id") { art = coverURL(gid) }
                return toTrack(r, albumTitle: mstr(release?["title"]), artwork: art)
            }
            await onPart(out)
        }
    }

    // MARK: Artist and album pages

    struct ArtistPage {
        var id: String
        var name: String
        var detail: String?
        var albums: [MusicAlbum]
        var singles: [MusicAlbum]
        var other: [MusicAlbum]
    }

    private static func newestFirst(_ a: MusicAlbum, _ b: MusicAlbum) -> Bool { (a.year ?? "0") > (b.year ?? "0") }

    static func artist(_ id: String, knownName: String? = nil) async -> ArtistPage? {
        var i: JSON? = nil
        if knownName == nil { i = await get("/ws/2/artist/\(id)") as? JSON }
        guard let g = await get("/ws/2/release-group?artist=\(id)&type=album%7Cep%7Csingle&limit=100") as? JSON else { return nil }
        var albums: [MusicAlbum] = [], singles: [MusicAlbum] = [], other: [MusicAlbum] = []
        for raw in marr(g["release-groups"]) {
            let album = toAlbum(raw)
            let secondary = (raw["secondary-types"] as? [Any]) ?? []
            if !secondary.isEmpty { other.append(album) } else if mstr(raw["primary-type"]) == "Album" { albums.append(album) } else { singles.append(album) }
        }
        let detail = [mstr(i?["disambiguation"]), mstr(i?["type"]), mstr(i?["country"])].compactMap { $0 }.joined(separator: " · ")
        return ArtistPage(id: id, name: knownName ?? mstr(i?["name"]) ?? "", detail: detail.isEmpty ? nil : detail,
                          albums: albums.sorted(by: newestFirst), singles: singles.sorted(by: newestFirst), other: other.sorted(by: newestFirst))
    }

    struct AlbumPage {
        var id: String
        var title: String
        var artist: String
        var artistId: String?
        var year: String?
        var artworkURL: String
        var tracks: [MusicTrack]
    }

    /// The earliest official release of the release group, with its tracks and their ISRC codes.
    static func album(_ groupId: String) async -> AlbumPage? {
        guard let list = await get("/ws/2/release?release-group=\(groupId)&status=official&limit=100") as? JSON else { return nil }
        var releases = marr(list["releases"])
        releases.sort { (mstr($0["date"]) ?? "9999") < (mstr($1["date"]) ?? "9999") }
        guard let first = releases.first, let rid = mstr(first["id"]),
              let release = await get("/ws/2/release/\(rid)?inc=recordings+isrcs+artist-credits+release-groups") as? JSON else { return nil }
        let group = release["release-group"] as? JSON
        let art = coverURL(groupId)
        let c = credit(release["artist-credit"])
        let title = mstr(group?["title"]) ?? mstr(release["title"]) ?? ""
        var tracks: [MusicTrack] = []
        for medium in marr(release["media"]) {
            for t in marr(medium["tracks"]) {
                var recording = (t["recording"] as? JSON) ?? [:]
                if recording["title"] == nil { recording["title"] = t["title"] }
                if recording["length"] == nil || recording["length"] is NSNull { recording["length"] = t["length"] }
                if let track = toTrack(recording, albumTitle: title, artwork: art, artistFallback: c.name) { tracks.append(track) }
            }
        }
        return AlbumPage(id: groupId, title: title, artist: c.name, artistId: c.id, year: year(group?["first-release-date"] ?? release["date"]), artworkURL: art, tracks: tracks)
    }

    /// A recording's ISRC code, asked when a track from a list without them is played.
    static func recordingIsrc(_ mbid: String) async -> String? {
        guard let r = await get("/ws/2/recording/\(mbid)?inc=isrcs") as? JSON else { return nil }
        return (r["isrcs"] as? [Any])?.compactMap { $0 as? String }.first
    }
}

/// ListenBrainz (open listening data): charts, new releases, an artist's top tracks, artists like an artist.
enum LBData {
    static let api = "https://api.listenbrainz.org"
    static let labs = "https://labs.api.listenbrainz.org"

    private static func get(_ path: String, ttl: TimeInterval, host: String = LBData.api) async -> Any? {
        let reply = await MusicNet.metadata(host + path, ttl: ttl, server: host, gap: 0.25)
        return reply.status == 200 ? reply.json : nil
    }

    /// The artist's most listened-to tracks. Empty if unavailable.
    static func topTracks(_ artistId: String, name: String) async -> [MusicTrack] {
        guard let list = await get("/1/popularity/top-recordings-for-artist/\(artistId)", ttl: MusicNet.day) as? [Any] else { return [] }
        return list.prefix(10).compactMap { $0 as? JSON }.compactMap { r in
            guard let mbid = mstr(r["recording_mbid"]) else { return nil }
            return MusicTrack(id: "mbid:" + mbid, title: mstr(r["recording_name"]) ?? "", artist: mstr(r["artist_name"]) ?? name,
                              album: mstr(r["release_name"]), durationMs: mint(r["length"]),
                              artworkURL: mstr(r["release_mbid"]).map { MB.releaseCoverURL($0) }, isrc: nil, mbid: mbid,
                              artistMbid: mfirst(r["artist_mbids"]) ?? artistId, addon: nil, streamURL: nil)
        }
    }

    /// Albums people are playing this week.
    static func popularAlbums() async -> [MusicAlbum] {
        let j = await get("/1/stats/sitewide/release-groups?range=week&count=25", ttl: 6 * 3600) as? JSON
        let payload = j?["payload"] as? JSON
        return marr(payload?["release_groups"]).compactMap { g in
            guard let id = mstr(g["release_group_mbid"]) else { return nil }
            return MusicAlbum(id: id, title: mstr(g["release_group_name"]) ?? "", artist: mstr(g["artist_name"]) ?? "", year: nil, kind: nil,
                              artworkURL: MB.coverURL(id), addon: nil, artistMbid: mfirst(g["artist_mbids"]))
        }
    }

    /// Albums out in the last two weeks.
    static func newReleases() async -> [MusicAlbum] {
        let j = await get("/1/explore/fresh-releases/?days=14&past=true&future=false&sort=release_date", ttl: 6 * 3600)
        var items: [JSON] = []
        if let arr = j as? [Any] { items = arr.compactMap { $0 as? JSON } } else if let d = j as? JSON {
            let payload = d["payload"] as? JSON
            items = marr(payload?["releases"] ?? d["releases"])
        }
        items = items.filter { r in
            guard mstr(r["release_group_mbid"]) != nil else { return false }
            let kind = mstr(r["release_group_primary_type"])
            return kind == nil || kind == "Album" || kind == "EP"
        }
        items.sort { (mstr($0["release_date"]) ?? "") > (mstr($1["release_date"]) ?? "") }
        var seen = Set<String>()
        var out: [MusicAlbum] = []
        for r in items {
            guard let id = mstr(r["release_group_mbid"]), !seen.contains(id) else { continue }
            seen.insert(id)
            out.append(MusicAlbum(id: id, title: mstr(r["release_name"]) ?? "", artist: mstr(r["artist_credit_name"]) ?? "",
                                  year: mstr(r["release_date"]).map { String($0.prefix(4)) }, kind: mstr(r["release_group_primary_type"]),
                                  artworkURL: MB.coverURL(id), addon: nil, artistMbid: mfirst(r["artist_mbids"])))
            if out.count >= 300 { break }
        }
        return out
    }

    /// Songs people are playing this week: the country's chart, else ListenBrainz's.
    static func popularSongs() async -> [MusicTrack] {
        let chart = await Chart.shared.get()
        if chart.songs.count >= 5 { return Array(chart.songs.prefix(25)) }
        let j = await get("/1/stats/sitewide/recordings?range=week&count=25", ttl: 6 * 3600) as? JSON
        let payload = j?["payload"] as? JSON
        return marr(payload?["recordings"]).compactMap { r in
            guard let mbid = mstr(r["recording_mbid"]), let title = mstr(r["track_name"]) else { return nil }
            return MusicTrack(id: "mbid:" + mbid, title: title, artist: mstr(r["artist_name"]) ?? "", album: mstr(r["release_name"]), durationMs: nil,
                              artworkURL: mstr(r["release_mbid"]).map { MB.releaseCoverURL($0) }, isrc: nil, mbid: mbid,
                              artistMbid: mfirst(r["artist_mbids"]), addon: nil, streamURL: nil)
        }
    }

    /// Artists people are playing now: the ones in the country's chart, else ListenBrainz's.
    static func popularArtists() async -> [MusicArtist] {
        let chart = await Chart.shared.get()
        if chart.artists.count >= 5 { return Array(chart.artists.prefix(20)) }
        let j = await get("/1/stats/sitewide/artists?range=month&count=30", ttl: 6 * 3600) as? JSON
        let payload = j?["payload"] as? JSON
        return marr(payload?["artists"]).compactMap { a in
            guard let id = mstr(a["artist_mbid"]), let name = mstr(a["artist_name"]) else { return nil }
            return MusicArtist(id: id, name: name, detail: nil, artworkURL: nil, addon: nil)
        }.prefix(20).map { $0 }
    }

    private static let similarAlgorithm = "session_based_days_9000_session_300_contribution_5_threshold_15_limit_50_skip_30"

    private static func walk(_ x: Any?, _ out: inout [JSON]) {
        if let a = x as? [Any] {
            for v in a { walk(v, &out) }
        } else if let d = x as? JSON {
            if d["artist_mbid"] != nil && d["name"] != nil { out.append(d) } else {
                for v in d.values where v is [Any] { walk(v, &out) }
            }
        }
    }

    /// Artists like this one (ListenBrainz Labs). Empty if the service doesn't answer.
    static func similarArtists(_ artistId: String) async -> [MusicArtist] {
        let j = await get("/similar-artists/json?artist_mbids=\(artistId)&algorithm=\(similarAlgorithm)", ttl: 7 * MusicNet.day, host: labs)
        var flat: [JSON] = []
        walk(j, &flat)
        return flat.filter { mstr($0["artist_mbid"]) != artistId }.prefix(20).compactMap { a in
            guard let id = mstr(a["artist_mbid"]), let name = mstr(a["name"]) else { return nil }
            let detail = [mstr(a["type"]), mstr(a["comment"])].compactMap { $0 }.joined(separator: " · ")
            return MusicArtist(id: id, name: name, detail: detail.isEmpty ? nil : detail, artworkURL: nil, addon: nil)
        }
    }

    /// More albums by an artist, most played first.
    static func moreFrom(_ artistId: String) async -> [MusicAlbum] {
        guard let list = await get("/1/popularity/top-release-groups-for-artist/\(artistId)", ttl: MusicNet.day) as? [Any] else { return [] }
        return list.prefix(20).compactMap { $0 as? JSON }.compactMap { g in
            guard let id = mstr(g["release_group_mbid"]) else { return nil }
            let rg = g["release_group"] as? JSON
            let artist = g["artist"] as? JSON
            var art = MB.coverURL(id)
            if rg?["caa_id"] != nil, let rel = mstr(rg?["caa_release_mbid"]) { art = MB.releaseCoverURL(rel) }
            return MusicAlbum(id: id, title: mstr(rg?["name"]) ?? "", artist: mstr(artist?["name"]) ?? "",
                              year: mstr(rg?["date"]).map { String($0.prefix(4)) }, kind: nil, artworkURL: art, addon: nil, artistMbid: artistId)
        }
    }
}

/// A picture of an artist. MusicBrainz has none, so: Deezer's photo of the artist with exactly that name, else the cover
/// of their most played album (ListenBrainz). What was found is kept for a month (a miss for a day).
actor ArtistPhotos {
    static let shared = ArtistPhotos()
    private var memory: [String: String?] = [:]
    private var running: [String: Task<String?, Never>] = [:]

    private func plain(_ s: String) -> String {
        let folded = s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: nil).lowercased().replacingOccurrences(of: "&", with: "and")
        return String(folded.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) && $0.isASCII }.map { Character($0) })
    }

    private func deezer(_ name: String) async -> String? {
        let url = "https://api.deezer.com/search/artist?q=\(menc(name))&limit=10"
        let reply = await MusicNet.metadata(url, ttl: 30 * MusicNet.day, server: "deezer", gap: 0.12)
        guard reply.status == 200, let j = reply.json as? JSON else { return nil }
        let wanted = plain(name)
        let best = marr(j["data"]).filter { plain(mstr($0["name"]) ?? "") == wanted }.max { (mint($0["nb_fan"]) ?? 0) < (mint($1["nb_fan"]) ?? 0) }
        guard let b = best, let url = mstr(b["picture_big"]) ?? mstr(b["picture_medium"]) else { return nil }
        // Deezer's stand-in for "no photo" has an empty picture id.
        return url.hasPrefix("https://") && !url.contains("/artist//") ? url : nil
    }

    private func album(_ id: String) async -> String? {
        let reply = await MusicNet.metadata("\(LBData.api)/1/popularity/top-release-groups-for-artist/\(id)", ttl: MusicNet.day, server: LBData.api, gap: 0.25)
        guard reply.status == 200, let list = reply.json as? [Any] else { return nil }
        for case let g as JSON in list {
            if let rg = g["release_group"] as? JSON, let rel = mstr(rg["caa_release_mbid"]) { return MB.releaseCoverURL(rel, size: 500) }
        }
        return nil
    }

    func photo(id: String, name: String) async -> String? {
        if let known = memory[id] { return known }
        if let job = running[id] { return await job.value }
        let job = Task<String?, Never> {
            var url: String? = nil
            if !name.isEmpty { url = await self.deezer(name) }
            if url == nil { url = await self.album(id) }
            return url
        }
        running[id] = job
        let found = await job.value
        running[id] = nil
        memory[id] = found
        return found
    }
}

/// What the country is playing (windows/desktop/codio-music.js buildChart): Apple Music's public "most played" chart for
/// the phone's region (no account or key), each song matched to MusicBrainz ids with ListenBrainz's name lookup.
/// ListenBrainz's own sitewide charts only reflect its few thousand listeners, which leans heavily towards a handful of
/// very active fans (mostly J-pop and K-pop). The chart is kept 6 hours, each lookup a month.
actor Chart {
    static let shared = Chart()
    static let host = "https://rss.applemarketingtools.com"

    struct Result {
        var songs: [MusicTrack] = []
        var artists: [MusicArtist] = []
    }

    private var job: Task<Result, Never>?
    private var at = Date.distantPast

    func get() async -> Result {
        if let job, Date().timeIntervalSince(at) < 1800 { return await job.value }
        let task = Task { await Chart.build() }
        job = task
        at = Date()
        let r = await task.value
        if r.songs.isEmpty { job = nil }
        return r
    }

    static var country: String {
        let c = (Locale.current.region?.identifier ?? "US").lowercased()
        return c.range(of: "^[a-z]{2}$", options: .regularExpression) != nil ? c : "us"
    }

    private static func feed(_ cc: String) async -> [JSON] {
        let reply = await MusicNet.metadata("\(host)/api/v2/\(cc)/music/most-played/50/songs.json", ttl: 6 * 3600, server: host, gap: 0)
        if reply.status != 200 && cc != "us" { return await feed("us") }
        return marr(((reply.json as? JSON)?["feed"] as? JSON)?["results"])
    }

    private static func lookup(_ artist: String, _ title: String) async -> JSON? {
        var c = URLComponents(string: LBData.api + "/1/metadata/lookup/")!
        c.queryItems = [URLQueryItem(name: "artist_name", value: artist), URLQueryItem(name: "recording_name", value: title)]
        guard let url = c.url?.absoluteString else { return nil }
        let reply = await MusicNet.metadata(url, ttl: 30 * MusicNet.day, server: LBData.api + "#lookup", gap: 0.15)
        guard reply.status == 200, let j = reply.json as? JSON, mstr(j["recording_mbid"]) != nil else { return nil }
        return j
    }

    /// "Artist A & Artist B", "A, B & C", "A feat. B": the first name is who the song is by.
    static func lead(_ name: String) -> String {
        let pattern = "\\s+(?:&|feat\\.?|ft\\.?|featuring|with|x)\\s+|,\\s+(?!the\\s)"
        guard let r = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let m = r.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)),
              let range = Range(m.range, in: name) else { return name }
        return String(name[..<range.lowerBound]).trimmingCharacters(in: .whitespaces)
    }

    static func build() async -> Result {
        let items = Array(await feed(country).prefix(40))
        let found: [JSON?] = await withTaskGroup(of: (Int, JSON?).self) { group in
            for (i, r) in items.enumerated() {
                let artist = mstr(r["artistName"]) ?? "", title = mstr(r["name"]) ?? ""
                group.addTask { (i, await lookup(artist, title)) }
            }
            var out = [JSON?](repeating: nil, count: items.count)
            for await (i, j) in group { out[i] = j }
            return out
        }
        var result = Result()
        var seen = Set<String>()
        for (i, r) in items.enumerated() {
            guard let m = found[i], let mbid = mstr(m["recording_mbid"]) else { continue }
            let ids = ((m["artist_mbids"] as? [Any]) ?? []).compactMap { $0 as? String }
            let credit = mstr(m["artist_credit_name"]) ?? mstr(r["artistName"]) ?? ""
            result.songs.append(MusicTrack(id: "mbid:" + mbid, title: mstr(m["recording_name"]) ?? mstr(r["name"]) ?? "", artist: credit,
                                           album: mstr(m["release_name"]), durationMs: nil,
                                           artworkURL: mstr(m["release_mbid"]).map { MB.releaseCoverURL($0) }, isrc: nil, mbid: mbid,
                                           artistMbid: ids.first, addon: nil, streamURL: nil))
            if let id = ids.first, !seen.contains(id) {
                seen.insert(id)
                result.artists.append(MusicArtist(id: id, name: ids.count == 1 ? credit : lead(credit), detail: nil, artworkURL: nil, addon: nil))
            }
        }
        return result
    }
}
