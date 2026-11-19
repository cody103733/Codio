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

    private static func get(_ path: String, background: Bool = false) async -> Any? {
        let b = base
        let url = b + path + (path.contains("fmt=") ? "" : (path.contains("?") ? "&" : "?") + "fmt=json")
        let isSearch = path.contains("query=")
        let reply = await MusicNet.metadata(url, ttl: isSearch ? MusicNet.day : 7 * MusicNet.day, server: b, background: background)
        return reply.status == 200 ? reply.json : nil
    }

    private static func plain(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US"))
            .replacingOccurrences(of: "&", with: "and").filter { $0.isLetter || $0.isNumber }.lowercased()
    }

    private static func quoted(_ s: String) -> String {
        "\"" + s.replacingOccurrences(of: "\"", with: "").replacingOccurrences(of: "\\", with: "") + "\""
    }

    private static func q(_ s: String) -> String? {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed)
    }

    /// Artists by exact name (or alias), all in one request (a OR b OR ...; MusicBrainz asks for one request a second).
    /// Same order as names, nil where none.
    static func artistIds(named names: [String]) async -> [String?] {
        guard !names.isEmpty, let enc = q(names.map { "artist:" + quoted($0) }.joined(separator: " OR ")),
              let j = await get("/ws/2/artist?query=\(enc)&limit=100", background: true) as? JSON else { return names.map { _ in nil } }
        let list = marr(j["artists"])
        return names.map { name in
            let wanted = plain(name)
            return list.filter { a in
                guard isMbid(mstr(a["id"])) else { return false }
                if plain(mstr(a["name"]) ?? "") == wanted { return true }
                return marr(a["aliases"]).contains { plain(mstr($0["name"]) ?? "") == wanted }
            }.max { (mint($0["score"]) ?? 0) < (mint($1["score"]) ?? 0) }.flatMap { mstr($0["id"]) }
        }
    }

    /// Albums by title and artist, the whole chart in one request (Apple adds " - Single" / " - EP"). Same order, or nil.
    static func albums(named wanted: [(title: String, artist: String)]) async -> [MusicAlbum?] {
        func clean(_ t: String) -> String { t.replacingOccurrences(of: "\\s+-\\s+(Single|EP|single|ep)$", with: "", options: .regularExpression) }
        let query = wanted.map { "(releasegroup:\(quoted(clean($0.title))) AND artist:\(quoted(Chart.lead($0.artist))))" }.joined(separator: " OR ")
        guard !wanted.isEmpty, let enc = q(query),
              let j = await get("/ws/2/release-group?query=\(enc)&limit=100", background: true) as? JSON else { return wanted.map { _ in nil } }
        let list = marr(j["release-groups"])
        return wanted.map { w in
            let title = plain(clean(w.title)), who = plain(Chart.lead(w.artist))
            guard let g = list.filter({ g in
                guard isMbid(mstr(g["id"])), plain(mstr(g["title"]) ?? "") == title else { return false }
                let credit = plain(marr(g["artist-credit"]).compactMap { mstr($0["name"]) }.joined(separator: " "))
                return credit.contains(who) || who.contains(credit)
            }).max(by: { (mint($0["score"]) ?? 0) < (mint($1["score"]) ?? 0) }) else { return nil }
            var a = toAlbum(g)
            if a.artist.isEmpty { a.artist = w.artist }
            return a
        }
    }

    /// An artist's id by exact name (or alias), best score first; nil if none.
    static func artistId(named name: String) async -> String? {
        let q = "artist:\"" + name.replacingOccurrences(of: "\"", with: "") + "\""
        guard let enc = q.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let j = await get("/ws/2/artist?query=\(enc)&limit=5", background: true) as? JSON else { return nil }
        let wanted = plain(name)
        return marr(j["artists"]).filter { a in
            guard let id = mstr(a["id"]), isMbid(id) else { return false }
            if plain(mstr(a["name"]) ?? "") == wanted { return true }
            return marr(a["aliases"]).contains { plain(mstr($0["name"]) ?? "") == wanted }
        }.max { (mint($0["score"]) ?? 0) < (mint($1["score"]) ?? 0) }.flatMap { mstr($0["id"]) }
    }

    /// An album (release group) by title and artist, for chart albums (Apple adds " - Single" / " - EP"); nil if none.
    static func album(named title: String, artist: String) async -> MusicAlbum? {
        let clean = title.replacingOccurrences(of: "\\s+-\\s+(Single|EP|single|ep)$", with: "", options: .regularExpression).replacingOccurrences(of: "\"", with: "")
        let q = "releasegroup:\"\(clean)\" AND artist:\"\(artist.replacingOccurrences(of: "\"", with: ""))\""
        guard let enc = q.addingPercentEncoding(withAllowedCharacters: .alphanumerics),
              let j = await get("/ws/2/release-group?query=\(enc)&limit=5", background: true) as? JSON else { return nil }
        let wanted = plain(clean)
        guard let g = marr(j["release-groups"]).filter({ isMbid(mstr($0["id"])) && plain(mstr($0["title"]) ?? "") == wanted })
            .max(by: { (mint($0["score"]) ?? 0) < (mint($1["score"]) ?? 0) }) else { return nil }
        var a = toAlbum(g)
        if a.artist.isEmpty { a.artist = artist }
        return a
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
        // One request: the album's releases with their tracks; the earliest official one is shown.
        func key(_ r: JSON) -> String { (mstr(r["status"]) == "Official" ? "0" : "1") + (mstr(r["date"]).flatMap { $0.isEmpty ? nil : $0 } ?? "9999") }
        var picked: JSON?
        if let all = await get("/ws/2/release?release-group=\(groupId)&inc=recordings+isrcs+artist-credits+release-groups&limit=25") as? JSON {
            picked = marr(all["releases"]).filter { r in marr(r["media"]).contains { !marr($0["tracks"]).isEmpty } }.min { key($0) < key($1) }
        }
        if picked == nil {
            guard let list = await get("/ws/2/release?release-group=\(groupId)&limit=100") as? JSON,
                  let first = marr(list["releases"]).min(by: { key($0) < key($1) }), let rid = mstr(first["id"]) else { return nil }
            picked = await get("/ws/2/release/\(rid)?inc=recordings+isrcs+artist-credits+release-groups") as? JSON
        }
        guard let release = picked else { return nil }
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
        await AlbumChart.shared.get().popular
    }

    /// Popular albums out in the last 8 weeks, newest first.
    static func chartNewReleases() async -> [MusicAlbum] {
        await AlbumChart.shared.get().recent
    }

    /// Albums ListenBrainz's listeners played this week (no longer shown).
    static func sitewideAlbums() async -> [MusicAlbum] {
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

    /// Songs people are playing now: the country's chart (Apple's, or Last.fm's with your key).
    static func popularSongs() async -> [MusicTrack] {
        return Array(await Chart.shared.get().songs.prefix(100))
    }

    /// A chart by id: "top" (the Top 100) or a genre id.
    static func chartSongs(_ id: String) async -> [MusicTrack] {
        id == "top" ? await popularSongs() : await GenreCharts.shared.songs(id)
    }

    /// Songs ListenBrainz's listeners played this week (no longer shown: it leaned on a few very active fans).
    static func sitewideSongs() async -> [MusicTrack] {
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
        return Array(await Chart.shared.artists().prefix(20))
    }

    /// Artists ListenBrainz's listeners played this month (no longer shown).
    static func sitewideArtists() async -> [MusicArtist] {
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
    private var keyUsed = ""

    static let lastfmHost = "https://ws.audioscrobbler.com"
    static let lastfmDefaultsKey = "music_lastfm_key"

    /// Your own Last.fm API key; with one, the Popular rows use Last.fm's chart instead of Apple's.
    static var lastfmKey: String {
        get { (UserDefaults.standard.string(forKey: lastfmDefaultsKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        set {
            let k = newValue.trimmingCharacters(in: .whitespacesAndNewlines)
            if k.isEmpty { UserDefaults.standard.removeObject(forKey: lastfmDefaultsKey) } else { UserDefaults.standard.set(k, forKey: lastfmDefaultsKey) }
        }
    }

    /// "lastfm" when your Last.fm key is set (its chart replaces Apple's), else "apple".
    static var source: String { activeKey.isEmpty ? "apple" : "lastfm" }

    /// True when you picked Last.fm's chart (Apple's, the closest to Billboard, is the default).
    static var useLastfm: Bool {
        get { UserDefaults.standard.bool(forKey: "music_chart_lastfm") }
        set { UserDefaults.standard.set(newValue, forKey: "music_chart_lastfm") }
    }

    /// The Last.fm key in use for the charts: only when you picked Last.fm.
    static var activeKey: String { useLastfm ? lastfmKey : "" }

    func get() async -> Result {
        let key = Chart.activeKey
        if key != keyUsed {
            keyUsed = key
            job = nil
            AppleLinks.clear()
        }
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
        let reply = await MusicNet.metadata("\(host)/api/v2/\(cc)/music/most-played/100/songs.json", ttl: 6 * 3600, server: host, gap: 0)
        if reply.status != 200 && cc != "us" { return await feed("us") }
        return marr(((reply.json as? JSON)?["feed"] as? JSON)?["results"])
    }

    /// Last.fm's chart for the country (your own key), as Apple-shaped items. Cached under an address without the key.
    private static func lastfmFeed(_ cc: String, key: String) async -> [JSON] {
        let name = Locale(identifier: "en_US").localizedString(forRegionCode: cc.uppercased()) ?? "United States"
        var c = URLComponents(string: lastfmHost + "/2.0/")!
        c.queryItems = [URLQueryItem(name: "method", value: "geo.gettoptracks"), URLQueryItem(name: "country", value: name),
                        URLQueryItem(name: "limit", value: "100"), URLQueryItem(name: "format", value: "json")]
        guard let plain = c.url?.absoluteString else { return [] }
        var data = MusicNet.readCache(plain, ttl: 6 * 3600)
        if data == nil {
            c.queryItems?.append(URLQueryItem(name: "api_key", value: key))
            guard let u = c.url else { return [] }
            var request = URLRequest(url: u)
            request.setValue(MusicNet.agent, forHTTPHeaderField: "User-Agent")
            let answer = try? await MusicNet.session.data(for: request)
            if let d = answer?.0, (answer?.1 as? HTTPURLResponse)?.statusCode == 200, (MusicNet.parse(d) as? JSON)?["tracks"] is JSON {
                MusicNet.writeCache(plain, d)
                data = d
            }
        }
        guard let d = data, let j = MusicNet.parse(d) as? JSON else { return [] }
        return marr((j["tracks"] as? JSON)?["track"]).map { t in
            ["name": mstr(t["name"]) ?? "", "artistName": mstr((t["artist"] as? JSON)?["name"]) ?? ""] as JSON
        }
    }

    private static func lookup(_ artist: String, _ title: String) async -> JSON? {
        var c = URLComponents(string: LBData.api + "/1/metadata/lookup/")!
        c.queryItems = [URLQueryItem(name: "artist_name", value: artist), URLQueryItem(name: "recording_name", value: title)]
        guard let url = c.url?.absoluteString else { return nil }
        let reply = await MusicNet.metadata(url, ttl: 30 * MusicNet.day, server: LBData.api + "#lookup", gap: 0.15)
        guard reply.status == 200, let j = reply.json as? JSON, mstr(j["recording_mbid"]) != nil else { return nil }
        return j
    }

    /// Is this Last.fm key accepted? true / false (Last.fm said no) / nil (couldn't ask).
    static func check(_ key: String) async -> Bool? {
        let k = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard k.range(of: "^[A-Za-z0-9]{16,64}$", options: .regularExpression) != nil else { return false }
        guard let u = URL(string: "\(lastfmHost)/2.0/?method=geo.gettoptracks&country=United%20States&limit=1&format=json&api_key=\(k)") else { return nil }
        var request = URLRequest(url: u)
        request.setValue(MusicNet.agent, forHTTPHeaderField: "User-Agent")
        let answer = try? await URLSession.shared.data(for: request)
        guard let d = answer?.0 else { return nil }
        let j = MusicNet.parse(d) as? JSON
        if (answer?.1 as? HTTPURLResponse)?.statusCode == 200, j?["tracks"] is JSON { return true }
        if let e = mint(j?["error"]), [4, 10, 26].contains(e) { return false }
        return nil
    }

    private static func plainName(_ s: String) -> String {
        s.folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "en_US"))
            .replacingOccurrences(of: "&", with: "and").filter { $0.isLetter || $0.isNumber }.lowercased()
    }

    /// A song's cover from Deezer, or nil. Kept a month.
    static func deezerCover(_ artist: String, _ title: String) async -> String? {
        let q = "artist:\"\(artist.replacingOccurrences(of: "\"", with: ""))\" track:\"\(title.replacingOccurrences(of: "\"", with: ""))\""
        guard let enc = q.addingPercentEncoding(withAllowedCharacters: .alphanumerics) else { return nil }
        let reply = await MusicNet.metadata("https://api.deezer.com/search/track?q=\(enc)&limit=5", ttl: 30 * MusicNet.day, server: "https://api.deezer.com", gap: 0.12)
        let list = marr((reply.json as? JSON)?["data"])
        let wanted = plainName(Chart.lead(artist))
        let hit = list.first { plainName(mstr(($0["artist"] as? JSON)?["name"]) ?? "") == wanted } ?? list.first
        let album = hit?["album"] as? JSON
        guard let cover = mstr(album?["cover_big"]) ?? mstr(album?["cover_medium"]), cover.hasPrefix("https://"), !cover.contains("/cover//") else { return nil }
        return cover
    }

    /// "Artist A & Artist B", "A, B & C", "A feat. B": the first name is who the song is by.
    static func lead(_ name: String) -> String {
        let pattern = "\\s+(?:&|feat\\.?|ft\\.?|featuring|with|x)\\s+|,\\s+(?!the\\s)"
        guard let r = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
              let m = r.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)),
              let range = Range(m.range, in: name) else { return name }
        return String(name[..<range.lowerBound]).trimmingCharacters(in: .whitespaces)
    }

    /// The chart's songs, straight from the chart: no matching needed (an addon finds a song by title and artist), so
    /// the row shows at once. Apple gives covers; for Last.fm's chart (no real pictures) Deezer's cover is looked up.
    static func build() async -> Result {
        let key = activeKey
        let items = Array((key.isEmpty ? await feed(country) : await lastfmFeed(country, key: key)).prefix(100))
        var result = Result()
        for r in items {
            let artist = mstr(r["artistName"]) ?? "", title = mstr(r["name"]) ?? ""
            guard !artist.isEmpty, !title.isEmpty else { continue }
            let id = "chart:\(artist.lowercased())|\(title.lowercased())"
            if let link = mstr(r["url"]), link.hasPrefix("https://music.apple.com/") { AppleLinks.set(id, link) }
            var art: String?
            if let a = mstr(r["artworkUrl100"]), a.hasPrefix("https://"), a.contains(".mzstatic.com/") {
                art = a.replacingOccurrences(of: "100x100bb", with: "400x400bb")
            }
            result.songs.append(MusicTrack(id: id, title: title, artist: artist, album: nil, durationMs: nil,
                                           artworkURL: art, isrc: nil, mbid: nil, artistMbid: nil, addon: nil, streamURL: nil))
        }
        let covers: [Int: String] = await withTaskGroup(of: (Int, String?).self) { group in
            for (i, s) in result.songs.enumerated() where s.artworkURL == nil {
                let artist = s.artist, title = s.title
                group.addTask { (i, await deezerCover(artist, title)) }
            }
            var out: [Int: String] = [:]
            for await (i, c) in group { if let c { out[i] = c } }
            return out
        }
        for (i, c) in covers { result.songs[i].artworkURL = c }
        return result
    }

    private var artistJob: Task<[MusicArtist], Never>?
    private var artistAt = Date.distantPast
    private var artistKey = ""

    /// The chart's artists, in chart order (a separate, slower job: their ids are looked up).
    func artists() async -> [MusicArtist] {
        let key = Chart.activeKey
        if key != artistKey { artistKey = key; artistJob = nil }
        if let artistJob, Date().timeIntervalSince(artistAt) < 1800 { return await artistJob.value }
        let task = Task { await Chart.buildArtists() }
        artistJob = task
        artistAt = Date()
        let r = await task.value
        if r.isEmpty { artistJob = nil }
        return r
    }

    /// Ids from ListenBrainz's name lookup, else an exact-name MusicBrainz search in the background lane.
    static func buildArtists() async -> [MusicArtist] {
        let key = activeKey
        let items = Array((key.isEmpty ? await feed(country) : await lastfmFeed(country, key: key)).prefix(60))
        var people: [(name: String, full: String, song: String)] = []
        for r in items where people.count < 20 {
            let full = mstr(r["artistName"]) ?? ""
            let name = lead(full)
            if !name.isEmpty, !people.contains(where: { plainName($0.name) == plainName(name) }) { people.append((name, full, mstr(r["name"]) ?? "")) }
        }
        let ids = await MB.artistIds(named: people.map(\.name))
        var out: [MusicArtist] = []
        var seen = Set<String>()
        for (i, p) in people.enumerated() {
            guard let found = ids[i], !seen.contains(found) else { continue }
            seen.insert(found)
            out.append(MusicArtist(id: found, name: p.name, detail: nil, artworkURL: nil, addon: nil))
        }
        return out
    }
}

/// The song's page on Apple Music, for songs from Apple's chart (the chart is meant to send people there).
enum AppleLinks {
    private static let lock = NSLock()
    private static var links: [String: String] = [:]

    static func clear() {
        lock.lock(); links.removeAll(); lock.unlock()
    }

    static func set(_ mbid: String, _ url: String) {
        lock.lock(); links[mbid] = url; lock.unlock()
    }

    static func url(_ track: MusicTrack) -> URL? {
        lock.lock(); defer { lock.unlock() }
        return (links[track.id] ?? genreLinks[track.id]).flatMap { URL(string: $0) }
    }

    /// Links from the genre charts (always Apple's), kept apart so switching the main chart doesn't drop them.
    private static var genreLinks: [String: String] = [:]
    static func setGenre(_ id: String, _ url: String) {
        lock.lock(); genreLinks[id] = url; lock.unlock()
    }
}

/// Genre charts: the iTunes Store's top 100 per genre (Apple's newer feed has no genres; Last.fm has no current genre
/// charts, so these are Apple's whichever chart you picked). Same as windows/desktop/codio-music.js genreFeed.
actor GenreCharts {
    static let shared = GenreCharts()
    static let host = "https://itunes.apple.com"
    static let all: [(id: String, name: String)] = [
        ("14", "Pop"), ("18", "Hip-Hop/Rap"), ("6", "Country"), ("21", "Rock"), ("15", "R&B/Soul"), ("20", "Alternative"),
        ("17", "Dance"), ("7", "Electronic"), ("12", "Latin"), ("1153", "Metal"), ("22", "Christian"), ("10", "Singer/Songwriter"),
        ("11", "Jazz"), ("2", "Blues"), ("5", "Classical"), ("24", "Reggae"), ("16", "Soundtrack"), ("51", "K-Pop"), ("4", "Children's Music"),
    ]
    static let defaultsKey = "music_chart_genres"

    /// The genre charts shown on the Music page, in the order picked.
    static var picked: [String] {
        get { (UserDefaults.standard.string(forKey: defaultsKey) ?? "14,18,6,21,15").split(separator: ",").map(String.init).filter { !$0.isEmpty } }
        set { UserDefaults.standard.set(newValue.joined(separator: ","), forKey: defaultsKey) }
    }

    static func toggle(_ id: String) {
        var now = picked
        if let i = now.firstIndex(of: id) { now.remove(at: i) } else { now.append(id) }
        picked = now
    }

    static func name(_ id: String) -> String { all.first { $0.id == id }?.name ?? "Genre" }
    /// "Top 100" for the main chart ("top"), else "Top Pop" and so on.
    static func title(_ id: String) -> String { id == "top" ? "Top 100" : "Top \(name(id))" }

    private var kept: [String: (at: Date, songs: [MusicTrack])] = [:]

    func songs(_ genre: String) async -> [MusicTrack] {
        if let k = kept[genre], Date().timeIntervalSince(k.at) < 1800 { return k.songs }
        let list = await GenreCharts.load(Chart.country, genre)
        if !list.isEmpty { kept[genre] = (Date(), list) }
        return list
    }

    private static func label(_ x: Any?) -> String { ((x as? JSON)?["label"] as? String) ?? "" }

    private static func load(_ cc: String, _ genre: String) async -> [MusicTrack] {
        let reply = await MusicNet.metadata("\(host)/\(cc)/rss/topsongs/limit=100/genre=\(genre)/json", ttl: 6 * 3600, server: host, gap: 0)
        if reply.status != 200 && cc != "us" { return await load("us", genre) }
        let feed = (reply.json as? JSON)?["feed"] as? JSON
        let entries: [JSON] = (feed?["entry"] as? [JSON]) ?? ((feed?["entry"] as? JSON).map { [$0] } ?? [])
        return entries.compactMap { e in
            let title = label(e["im:name"]), artist = label(e["im:artist"])
            guard !title.isEmpty, !artist.isEmpty else { return nil }
            let id = "chart:\(artist.lowercased())|\(title.lowercased())"
            let images = (e["im:image"] as? [Any]) ?? []
            var art = label(images.last)
            if art.range(of: "^https://is\\d+-ssl\\.mzstatic\\.com/", options: .regularExpression) != nil {
                art = art.replacingOccurrences(of: "/\\d+x\\d+bb\\.", with: "/400x400bb.", options: .regularExpression)
            } else {
                art = ""
            }
            let links: [JSON] = (e["link"] as? [JSON]) ?? ((e["link"] as? JSON).map { [$0] } ?? [])
            var page = links.compactMap { ($0["attributes"] as? JSON)?["href"] as? String }.first { $0.hasPrefix("https://music.apple.com/") } ?? ""
            if page.isEmpty, label(e["id"]).hasPrefix("https://music.apple.com/") { page = label(e["id"]) }
            if !page.isEmpty { AppleLinks.setGenre(id, page) }
            let album = label((e["im:collection"] as? JSON)?["im:name"])
            return MusicTrack(id: id, title: title, artist: artist, album: album.isEmpty ? nil : album, durationMs: nil,
                              artworkURL: art.isEmpty ? nil : art, isrc: nil, mbid: nil, artistMbid: nil, addon: nil, streamURL: nil)
        }
    }
}

/// A chart page to open from a chart row ("top" or a genre id).
struct MusicChartRef: Hashable {
    let id: String
}

/// The country's most played albums (Apple's chart; Last.fm has no albums chart per country), matched to MusicBrainz
/// release groups by title and artist (windows/desktop/codio-music.js buildAlbums). Kept 6 hours, each match a day.
actor AlbumChart {
    static let shared = AlbumChart()

    struct Result {
        var popular: [MusicAlbum] = []
        var recent: [MusicAlbum] = []
    }

    private var job: Task<Result, Never>?
    private var at = Date.distantPast

    func get() async -> Result {
        if let job, Date().timeIntervalSince(at) < 1800 { return await job.value }
        let task = Task { await AlbumChart.build() }
        job = task
        at = Date()
        let r = await task.value
        if r.popular.isEmpty { job = nil }
        return r
    }

    private static func feed(_ cc: String) async -> [JSON] {
        let reply = await MusicNet.metadata("\(Chart.host)/api/v2/\(cc)/music/most-played/100/albums.json", ttl: 6 * 3600, server: Chart.host, gap: 0)
        if reply.status != 200 && cc != "us" { return await feed("us") }
        return marr(((reply.json as? JSON)?["feed"] as? JSON)?["results"])
    }

    static func build() async -> Result {
        let items = await feed(Chart.country)
        let day = DateFormatter()
        day.dateFormat = "yyyy-MM-dd"
        day.locale = Locale(identifier: "en_US_POSIX")
        let cutoff = Date().addingTimeInterval(-56 * 24 * 3600)
        var result = Result()
        var recent: [(String, MusicAlbum)] = []
        var picked: [(item: JSON, top: Bool, recent: Bool, date: String)] = []
        for (i, r) in items.enumerated() {
            let date = mstr(r["releaseDate"]) ?? ""
            let isRecent = day.date(from: String(date.prefix(10))).map { $0 >= cutoff } ?? false
            if i >= 30 && !isRecent { continue }
            picked.append((r, i < 30, isRecent, date))
        }
        let matches = await MB.albums(named: picked.map { (title: mstr($0.item["name"]) ?? "", artist: mstr($0.item["artistName"]) ?? "") })
        for (i, p) in picked.enumerated() {
            guard var a = matches[i] else { continue }
            if p.date.count >= 4 { a.year = String(p.date.prefix(4)) }
            if let art = mstr(p.item["artworkUrl100"]), art.hasPrefix("https://"), art.contains(".mzstatic.com/") {
                a.artworkURL = art.replacingOccurrences(of: "100x100bb", with: "400x400bb")
            }
            if p.top { result.popular.append(a) }
            if p.recent { recent.append((p.date, a)) }
        }
        result.recent = recent.sorted { $0.0 > $1.0 }.map { $0.1 }
        return result
    }
}
