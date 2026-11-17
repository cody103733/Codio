import Foundation

/// The Daily mix: 50 to 100 songs made for you, new every day and steady within the day.
///
/// It blends your ListenBrainz Daily Jams (when signed in), a different few songs each day from the artists you play and
/// heart, songs you hearted, two or three songs each from artists like yours, and a handful from your ListenBrainz Weekly
/// Exploration. No song twice (the same title by the same artist counts as the same song), at most two per artist (three
/// when the mix would be short otherwise), nothing you marked Not interested. Songs found later in the day are added at the
/// end, so the mix you're in the middle of doesn't reshuffle.
///
/// The same rules as windows/src/routes/Codio/music/dailymix.ts and android/src/com/codio/tv/music/DailyMix.java.
enum DailyMix {
    private static let maxSongs = 100
    private static let enough = 50
    private static let store = "codioDailyMix"
    /// Daily Jams, artists like yours, your artists, Daily Jams, artists like yours, hearted songs, Weekly Exploration.
    private static let order = [0, 3, 1, 0, 3, 2, 4]
    private static let trackExt = "https://musicbrainz.org/doc/jspf#track"
    private static let playlistExt = "https://musicbrainz.org/doc/jspf#playlist"
    private static let mbidAnywhere = try! NSRegularExpression(pattern: "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    static func today() -> String {
        let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        return "\(c.year ?? 0)-\(c.month ?? 0)-\(c.day ?? 0)"
    }

    private static func mbid(in v: Any?) -> String? {
        guard let s = v as? String, let m = mbidAnywhere.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              let r = Range(m.range, in: s) else { return nil }
        return String(s[r])
    }

    private static func lb(_ path: String, ttl: TimeInterval) async -> Any? {
        let reply = await MusicNet.metadata(LBData.api + path, ttl: ttl, server: LBData.api, gap: 0.25)
        return reply.status == 200 ? reply.json : nil
    }

    private static func playlistTrack(_ t: JSON) -> MusicTrack? {
        let ids: [Any] = (t["identifier"] as? [Any]) ?? [t["identifier"] as Any]
        guard let id = ids.compactMap({ mbid(in: $0) }).first, let title = mstr(t["title"]) else { return nil }
        let meta = ((t["extension"] as? JSON)?[trackExt] as? JSON)?["additional_metadata"] as? JSON ?? [:]
        let artist = marr(meta["artists"]).first
        return MusicTrack(id: "mbid:" + id, title: title, artist: mstr(t["creator"]) ?? mstr(artist?["artist_credit_name"]) ?? "",
                          album: mstr(t["album"]), durationMs: mint(t["duration"]),
                          artworkURL: mstr(meta["caa_release_mbid"]).map { MB.releaseCoverURL($0) }, isrc: nil, mbid: id,
                          artistMbid: mstr(artist?["artist_mbid"]), addon: nil, streamURL: nil)
    }

    /// The newest Daily Jams and Weekly Exploration ListenBrainz made for this user (they're public). Empty when none.
    static func madeForYou(_ user: String) async -> (jams: [MusicTrack], explore: [MusicTrack]) {
        let list = await lb("/1/user/\(menc(user))/playlists/createdfor?count=25", ttl: 2 * 3600) as? JSON
        let playlists = marr(list?["playlists"]).compactMap { $0["playlist"] as? JSON }
        func newest(_ patch: String) -> JSON? {
            playlists.filter { p in
                let ext = (p["extension"] as? JSON)?[playlistExt] as? JSON
                let algo = (ext?["additional_metadata"] as? JSON)?["algorithm_metadata"] as? JSON
                return mstr(algo?["source_patch"]) == patch
            }.max { (mstr($0["date"]) ?? "") < (mstr($1["date"]) ?? "") }
        }
        func tracks(_ p: JSON?) async -> [MusicTrack] {
            guard let p = p, let id = mbid(in: p["identifier"]) else { return [] }
            let j = await lb("/1/playlist/\(id)", ttl: 7 * MusicNet.day) as? JSON
            return marr((j?["playlist"] as? JSON)?["track"]).compactMap { playlistTrack($0) }
        }
        let jams = await tracks(newest("daily-jams"))
        let explore = await tracks(newest("weekly-exploration"))
        return (jams, explore)
    }

    static func norm(_ s: String) -> String {
        var x = s.lowercased()
        x = x.replacingOccurrences(of: "\\s*[(\\[].*?[)\\]]\\s*", with: " ", options: .regularExpression)
        x = x.replacingOccurrences(of: "\\s+-\\s+.*$", with: "", options: .regularExpression)
        x = x.replacingOccurrences(of: "[^\\p{L}\\p{N}]+", with: " ", options: .regularExpression)
        return x.trimmingCharacters(in: .whitespaces)
    }

    private static func songKey(_ t: MusicTrack) -> String { norm(t.artist) + "|" + norm(t.title) }
    private static func artistKey(_ t: MusicTrack) -> String {
        if let a = t.artistMbid, !a.isEmpty { return a }
        return norm(t.artist)
    }

    /// A different few of an artist's best-known songs each day (each song once, live and remastered versions folded in).
    static func pickTop(_ top: [MusicTrack], _ count: Int, salt: String) -> [MusicTrack] {
        var seen = Set<String>()
        let unique = top.filter { seen.insert(norm($0.title)).inserted }.prefix(8)
        return Array(dayShuffle(Array(unique), salt: salt).prefix(count))
    }

    /// Put the sources together: a song from each in turn, no repeats, a cap per artist, nothing hidden, no artist twice
    /// in a row.
    static func blend(_ groups: [[MusicTrack]], taste: TasteData) -> [MusicTrack] {
        var out: [MusicTrack] = []
        var songs = Set<String>()
        var perArtist: [String: Int] = [:]
        for cap in [2, 3] {
            if cap == 3 && out.count >= enough { break }
            var at = Array(repeating: 0, count: groups.count)
            var left = true
            while left && out.count < maxSongs {
                left = false
                for i in order where i < groups.count {
                    let g = groups[i]
                    while at[i] < g.count {
                        let t = g[at[i]]
                        at[i] += 1
                        let a = artistKey(t)
                        if songs.contains(songKey(t)) || (t.mbid.map { songs.contains($0) } ?? false) || taste.isHidden(t) || (perArtist[a] ?? 0) >= cap { continue }
                        songs.insert(songKey(t))
                        if let m = t.mbid { songs.insert(m) }
                        perArtist[a, default: 0] += 1
                        out.append(t)
                        break
                    }
                    if at[i] < g.count { left = true }
                    if out.count >= maxSongs { break }
                }
            }
        }
        // Spread out the same artist.
        if out.count > 2 {
            for i in 1..<out.count where artistKey(out[i]) == artistKey(out[i - 1]) {
                if let j = ((i + 1)..<out.count).first(where: { artistKey(out[$0]) != artistKey(out[i]) }) { out.swapAt(i, j) }
            }
        }
        return out
    }

    private struct Saved: Codable { var day: String; var tracks: [MusicTrack] }

    /// Today's mix as it was last built, shown right away while the new one is put together.
    static func saved() -> [MusicTrack] {
        guard let data = UserDefaults.standard.data(forKey: store), let s = try? JSONDecoder().decode(Saved.self, from: data), s.day == today() else { return [] }
        return s.tracks
    }

    /// Today's mix stays in the order it had; songs that are new since then go at the end.
    static func keepToday(_ fresh: [MusicTrack], taste: TasteData) -> [MusicTrack] {
        let before = saved()
        let have = Set(before.map(songKey))
        let merged = Array((before + fresh.filter { !have.contains(songKey($0)) }).filter { !taste.isHidden($0) }.prefix(maxSongs))
        if let data = try? JSONEncoder().encode(Saved(day: today(), tracks: merged)) { UserDefaults.standard.set(data, forKey: store) }
        return merged
    }

    /// Builds today's mix: seeds = your artists (topSeeds(8)), similar = artists like them (mergeSimilar), user = your
    /// ListenBrainz name or nil.
    static func build(seeds: [TasteData.Seed], similar: [MusicArtist], taste: TasteData, user: String?) async -> [MusicTrack] {
        let day = today()
        let mine = Array(seeds.prefix(8))
        let like = Array((Array(similar.prefix(8)) + dayShuffle(Array(similar.dropFirst(8)), salt: "like" + day)).prefix(16))
        let pairs: [(id: String, name: String)] = mine.map { ($0.id, $0.name) } + like.map { ($0.id, $0.name) }
        let tops = await withTaskGroup(of: (Int, [MusicTrack]).self, returning: [Int: [MusicTrack]].self) { group in
            for (i, p) in pairs.enumerated() {
                let id = p.id, name = p.name
                group.addTask { (i, await LBData.topTracks(id, name: name)) }
            }
            var out: [Int: [MusicTrack]] = [:]
            for await (i, list) in group { out[i] = list }
            return out
        }
        let ownLists = mine.indices.map { pickTop(tops[$0] ?? [], 4, salt: "own" + mine[$0].id) }
        let likeLists = like.indices.map { pickTop(tops[mine.count + $0] ?? [], 3, salt: "like" + like[$0].id) }
        var own: [MusicTrack] = []
        for r in 0..<4 { for l in ownLists where r < l.count { own.append(l[r]) } }
        var likeTracks: [MusicTrack] = []
        for r in 0..<3 { for l in likeLists where r < l.count { likeTracks.append(l[r]) } }
        let hearted = Array(dayShuffle(taste.likedTracks, salt: "liked").prefix(12))
        var made: (jams: [MusicTrack], explore: [MusicTrack]) = ([], [])
        if let user = user { made = await madeForYou(user) }
        let explore = Array(dayShuffle(made.explore, salt: "explore").prefix(15))
        return keepToday(blend([made.jams, own, hearted, likeTracks, explore], taste: taste), taste: taste)
    }
}
