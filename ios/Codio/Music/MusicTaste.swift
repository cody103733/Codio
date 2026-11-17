import Foundation

/// What you like, kept on this phone only: the artists you play, the songs, albums and artists you heart, and the ones
/// you said "Not interested" to. The Music rows are built from this (only artist ids go to ListenBrainz when asking for
/// similar artists). Same rules as the Windows app (music/taste.ts).
struct TasteData: Codable {
    struct Play: Codable { var name: String; var count: Double; var last: Double }
    /// Artist (MusicBrainz id) -> how often you played them.
    var plays: [String: Play] = [:]
    var likedTracks: [MusicTrack] = []
    var likedAlbums: [MusicAlbum] = []
    var likedArtists: [MusicArtist] = []
    /// id -> name, for the lists in Music settings where you can bring them back.
    var hiddenArtists: [String: String] = [:]
    var hiddenAlbums: [String: String] = [:]
    var hiddenTracks: [String: String] = [:]

    func isLiked(_ t: MusicTrack) -> Bool { likedTracks.contains { $0.key == t.key } }
    func isLiked(album id: String) -> Bool { likedAlbums.contains { $0.id == id } }
    func isLiked(artist id: String) -> Bool { likedArtists.contains { $0.id == id } }

    func isHidden(_ t: MusicTrack) -> Bool { hiddenTracks[t.key] != nil || (t.artistMbid.map { hiddenArtists[$0] != nil } ?? false) }
    func isHidden(_ a: MusicAlbum) -> Bool { hiddenAlbums[a.id] != nil || (a.artistMbid.map { hiddenArtists[$0] != nil } ?? false) }
    func isHidden(artist id: String) -> Bool { hiddenArtists[id] != nil }

    func visible(_ l: [MusicTrack]) -> [MusicTrack] { l.filter { !isHidden($0) } }
    func visible(_ l: [MusicAlbum]) -> [MusicAlbum] { l.filter { !isHidden($0) } }
    func visible(_ l: [MusicArtist]) -> [MusicArtist] { l.filter { !isHidden(artist: $0.id) } }

    struct Seed: Hashable { var id: String; var name: String; var score: Double }

    /// The artists you like most, best first. A play counts 1 (less as it gets old), a heart on the artist 6, a hearted
    /// song or album by them 3.
    func topSeeds(_ limit: Int = 8) -> [Seed] {
        var scores: [String: Seed] = [:]
        func add(_ id: String?, _ name: String, _ points: Double) {
            guard let id = id, hiddenArtists[id] == nil else { return }
            var s = scores[id] ?? Seed(id: id, name: name, score: 0)
            s.score += points
            scores[id] = s
        }
        let now = Date().timeIntervalSince1970 * 1000
        for (id, p) in plays { add(id, p.name, p.count / (1 + (now - p.last) / (30 * 24 * 3600 * 1000))) }
        for a in likedArtists { add(a.id, a.name, 6) }
        for t in likedTracks { add(t.artistMbid, t.artist, 3) }
        for a in likedAlbums { add(a.artistMbid, a.artist, 3) }
        return Array(scores.values.sorted { $0.score > $1.score }.prefix(limit))
    }

    /// Artists similar to your favorites, put together: one that shows up for several favorites ranks higher.
    func mergeSimilar(_ seeds: [Seed], _ lists: [[MusicArtist]]) -> [MusicArtist] {
        let known = Set(seeds.map(\.id))
        var scored: [String: (artist: MusicArtist, score: Double)] = [:]
        for (i, seed) in seeds.enumerated() where i < lists.count {
            for (rank, a) in lists[i].enumerated() where !known.contains(a.id) && hiddenArtists[a.id] == nil {
                var e = scored[a.id] ?? (artist: a, score: 0)
                e.score += seed.score / (1 + Double(rank) * 0.2)
                scored[a.id] = e
            }
        }
        return Array(scored.values.sorted { $0.score > $1.score }.prefix(24).map(\.artist))
    }
}

/// The same shuffle all day, a different one tomorrow.
func dayShuffle<T>(_ list: [T], salt: String = "") -> [T] {
    let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
    var seed: UInt32 = 0
    for u in "\(c.year ?? 0)-\(c.month ?? 0)-\(c.day ?? 0)\(salt)".unicodeScalars { seed = seed &* 31 &+ u.value }
    var copy = list
    if copy.count < 2 { return copy }
    for i in stride(from: copy.count - 1, to: 0, by: -1) {
        seed = seed &* 1664525 &+ 1013904223
        let j = Int(Double(seed) / 4294967296.0 * Double(i + 1))
        copy.swapAt(i, min(j, i))
    }
    return copy
}

@MainActor
final class MusicTaste: ObservableObject {
    static let shared = MusicTaste()
    private let key = "music_taste_v1"

    @Published private(set) var data = TasteData()
    /// Told when you heart or un-heart a song (the ListenBrainz account, when signed in, keeps them in step).
    var likeHooks: [(MusicTrack, Bool) -> Void] = []

    init() {
        if let d = UserDefaults.standard.data(forKey: key), let t = try? JSONDecoder().decode(TasteData.self, from: d) { data = t }
    }

    private func commit(_ next: TasteData) {
        data = next
        if let d = try? JSONEncoder().encode(next) { UserDefaults.standard.set(d, forKey: key) }
    }

    /// A track started playing.
    func recordPlay(_ t: MusicTrack) {
        guard let id = t.artistMbid else { return }
        var next = data
        let prev = next.plays[id]
        next.plays[id] = TasteData.Play(name: t.artist, count: (prev?.count ?? 0) + 1, last: Date().timeIntervalSince1970 * 1000)
        commit(next)
    }

    func toggleLike(_ track: MusicTrack) {
        let liked = data.isLiked(track)
        var next = data
        next.hiddenTracks[track.key] = nil
        if liked { next.likedTracks.removeAll { $0.key == track.key } } else { next.likedTracks = Array(([track] + next.likedTracks).prefix(500)) }
        commit(next)
        likeHooks.forEach { $0(track, !liked) }
    }

    func toggleLike(_ album: MusicAlbum) {
        var next = data
        next.hiddenAlbums[album.id] = nil
        if data.isLiked(album: album.id) { next.likedAlbums.removeAll { $0.id == album.id } } else { next.likedAlbums = Array(([album] + next.likedAlbums).prefix(300)) }
        commit(next)
    }

    func toggleLike(_ artist: MusicArtist) {
        var next = data
        next.hiddenArtists[artist.id] = nil
        if data.isLiked(artist: artist.id) { next.likedArtists.removeAll { $0.id == artist.id } } else { next.likedArtists = Array(([artist] + next.likedArtists).prefix(300)) }
        commit(next)
    }

    /// "Not interested": gone from the rows, and no longer counts towards your taste.
    func hide(_ track: MusicTrack) {
        let wasLiked = data.isLiked(track)
        var next = data
        next.likedTracks.removeAll { $0.key == track.key }
        next.hiddenTracks[track.key] = "\(track.title) · \(track.artist)"
        commit(next)
        if wasLiked { likeHooks.forEach { $0(track, false) } }
    }

    func hide(_ album: MusicAlbum) {
        var next = data
        next.likedAlbums.removeAll { $0.id == album.id }
        next.hiddenAlbums[album.id] = "\(album.title) · \(album.artist)"
        commit(next)
    }

    func hide(artist id: String, name: String) {
        var next = data
        next.plays[id] = nil
        next.likedArtists.removeAll { $0.id == id }
        next.hiddenArtists[id] = name
        commit(next)
    }

    enum Kind: String { case artist, album, track }

    func unhide(_ kind: Kind, _ key: String) {
        var next = data
        switch kind {
        case .artist: next.hiddenArtists[key] = nil
        case .album: next.hiddenAlbums[key] = nil
        case .track: next.hiddenTracks[key] = nil
        }
        commit(next)
    }

    func unhideAll() {
        var next = data
        next.hiddenArtists = [:]
        next.hiddenAlbums = [:]
        next.hiddenTracks = [:]
        commit(next)
    }

    func clearHistory() {
        var next = data
        next.plays = [:]
        commit(next)
    }

    func clearAll() { commit(TasteData()) }

    /// The songs loved on your ListenBrainz account (from any device). For songs with a MusicBrainz id the account is the
    /// truth: loved ones are added here, ones no longer loved are dropped, except those `keep` says changed here just now.
    func applyLovedFromAccount(_ lovedIds: Set<String>, _ loved: [MusicTrack], keep: (MusicTrack) -> Bool) {
        let kept = data.likedTracks.filter { t in t.mbid == nil || lovedIds.contains(t.mbid!) || keep(t) }
        let have = Set(kept.compactMap(\.mbid))
        let added = loved.filter { t in t.mbid != nil && !have.contains(t.mbid!) && data.hiddenTracks[t.key] == nil && !keep(t) }
        if added.isEmpty && kept.count == data.likedTracks.count { return }
        var next = data
        next.likedTracks = Array((kept + added).prefix(500))
        commit(next)
    }

    /// Plays from your other devices (ListenBrainz listens): they count towards your taste like the ones here.
    func addPlaysFromAccount(_ plays: [(artistMbid: String, name: String, at: Double)]) {
        if plays.isEmpty { return }
        var next = data
        for p in plays where data.hiddenArtists[p.artistMbid] == nil {
            let prev = next.plays[p.artistMbid]
            next.plays[p.artistMbid] = TasteData.Play(name: prev?.name ?? p.name, count: (prev?.count ?? 0) + 1, last: max(prev?.last ?? 0, p.at))
        }
        commit(next)
    }
}
