import SwiftUI

/// What the Music home shows, loaded once a run and kept (each answer is also kept on disk for hours or days).
@MainActor
final class MusicHome: ObservableObject {
    static let shared = MusicHome()

    @Published var popularAlbums: [MusicAlbum]?
    @Published var newReleases: [MusicAlbum]?
    @Published var popularSongs: [MusicTrack]?
    @Published var popularArtists: [MusicArtist]?
    @Published var similar: [String: [MusicArtist]] = [:]
    @Published var more: [String: [MusicAlbum]] = [:]
    @Published var mix: [MusicTrack] = []
    @Published var catalogs: [MusicCatalogRow] = []
    private var mixKey = ""
    private var started = false
    private var catalogsFor = ""

    var loadingGlobal: Bool { popularAlbums == nil && newReleases == nil && popularSongs == nil && popularArtists == nil }

    func loadGlobal() {
        guard !started else { return }
        started = true
        Task { popularAlbums = await LBData.popularAlbums() }
        Task { newReleases = await LBData.newReleases() }
        Task { popularSongs = await LBData.popularSongs() }
        Task { popularArtists = await LBData.popularArtists() }
    }

    func loadCatalogs(_ addons: [MusicAddon]) async {
        let key = addons.map(\.url).joined(separator: ",")
        guard key != catalogsFor else { return }
        catalogsFor = key
        catalogs = await MusicAddonClient.catalogs(addons)
    }

    /// Artists like your favorites, more from the top one, and the Daily mix.
    func loadPersonal(_ seeds: [TasteData.Seed], taste: TasteData) async {
        let missing = seeds.map(\.id).filter { similar[$0] == nil }
        let found = await withTaskGroup(of: (String, [MusicArtist]).self, returning: [(String, [MusicArtist])].self) { group in
            for id in missing {
                group.addTask {
                    let list = await LBData.similarArtists(id)
                    return (id, list)
                }
            }
            var out: [(String, [MusicArtist])] = []
            for await x in group { out.append(x) }
            return out
        }
        for (id, list) in found { similar[id] = list }
        if let first = seeds.first, more[first.id] == nil { more[first.id] = await LBData.moreFrom(first.id) }
        let merged = taste.mergeSimilar(seeds, seeds.map { similar[$0.id] ?? [] })
        // Daily mix: 50 to 100 songs from your ListenBrainz Daily Jams, your artists, artists like them and your hearts
        // (see DailyMix). Built again when the day, your favorites or your ListenBrainz account change; today's order is kept.
        if mix.isEmpty { mix = DailyMix.saved() }
        let mine = taste.topSeeds(8)
        let user = MusicAccount.shared.account?.user
        let bucket = Int((MusicAccount.shared.account?.lastSync?.timeIntervalSince1970 ?? 0) / (3 * 3600))
        let key = DailyMix.today() + (mine.map(\.id) + merged.map(\.id)).joined(separator: ",") + "|\(user ?? "")|\(bucket)|\(taste.likedTracks.count)"
        guard key != mixKey, !mine.isEmpty || user != nil else { return }
        mixKey = key
        let built = await DailyMix.build(seeds: mine, similar: merged, taste: taste, user: user)
        if !built.isEmpty { mix = built }
    }
}

struct MusicView: View {
    @ObservedObject private var addons = MusicAddons.shared
    @ObservedObject private var taste = MusicTaste.shared
    @ObservedObject private var home = MusicHome.shared
    @ObservedObject private var player = MusicPlayer.shared
    @ObservedObject private var account = MusicAccount.shared
    @State private var query = ""
    @State private var results: MusicResults?
    @State private var searching = false
    @State private var searchTask: Task<Void, Never>?
    @State private var searchGen = 0

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 18) {
                if addons.list.isEmpty {
                    NavigationLink { MusicSettingsView() } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "puzzlepiece.extension").font(.title2).foregroundColor(Theme.red)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Add a music addon").font(.headline).foregroundColor(.white)
                                Text("Search works now. To play songs, add a music addon first.").font(.caption).foregroundColor(Theme.dim)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundColor(Theme.faint)
                        }
                        .padding(14)
                        .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x1C1C1E)))
                        .padding(.horizontal, 16)
                    }
                    .buttonStyle(.plain)
                }
                if query.trimmingCharacters(in: .whitespaces).isEmpty {
                    discover
                } else {
                    searchResults
                }
            }
            .padding(.vertical, 10)
            .padding(.bottom, player.current == nil ? 0 : 70)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle("Music")
        .profileToolbar()
        .toolbar {
            ToolbarItem(placement: .navigationBarLeading) {
                NavigationLink { MusicSettingsView() } label: { Image(systemName: "slider.horizontal.3") }
            }
        }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Artists, albums and songs")
        .onChange(of: query) { _ in scheduleSearch() }
        .onChange(of: addons.list) { _ in scheduleSearch(now: true) }
        .onAppear { home.loadGlobal() }
        .task(id: addons.list.map(\.url).joined()) { await home.loadCatalogs(addons.list) }
        .task(id: personalKey) { await home.loadPersonal(seeds, taste: taste.data) }
    }

    private var seeds: [TasteData.Seed] { taste.data.topSeeds(5) }
    /// Changes when your favorites, your hearts or your ListenBrainz account (a sync, every few hours) change.
    private var personalKey: String {
        let a = account.account
        return DailyMix.today() + taste.data.topSeeds(8).map(\.id).joined(separator: ",") + "|\(a?.user ?? "")|\(Int((a?.lastSync?.timeIntervalSince1970 ?? 0) / (3 * 3600)))|\(taste.data.likedTracks.count)"
    }

    // MARK: Search

    private func scheduleSearch(now: Bool = false) {
        searchTask?.cancel()
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else {
            results = nil
            searching = false
            return
        }
        let list = addons.list
        searchGen += 1
        let gen = searchGen
        searchTask = Task {
            if !now { try? await Task.sleep(nanoseconds: 400_000_000) }
            if Task.isCancelled || gen != searchGen { return }
            searching = true
            results = nil
            // Results show as each source answers: addons (fast) and MusicBrainz (artists, albums, songs in turn).
            let acc = SearchParts()
            var jobs: [Task<Void, Never>] = []
            for a in list where a.has("search") {
                jobs.append(Task {
                    let r = await MusicAddonClient.search(a, q)
                    guard gen == searchGen else { return }
                    acc.addons.append(r)
                    results = acc.merged
                })
            }
            await MB.search(q) { part in
                guard gen == searchGen else { return }
                acc.mb = part
                results = acc.merged
            }
            for j in jobs { await j.value }
            if gen == searchGen { searching = false }
        }
    }

    @ViewBuilder private var searchResults: some View {
        if let r = results, !r.isEmpty {
            let artists = r.artists
            let albums = r.albums
            let tracks = r.tracks
            if !artists.isEmpty {
                MusicStrip(title: "Artists") { ForEach(artists, id: \.self) { ArtistTile(artist: $0) } }
            }
            if !albums.isEmpty {
                MusicStrip(title: "Albums") { ForEach(albums, id: \.self) { AlbumTile(album: $0) } }
            }
            if !tracks.isEmpty {
                MusicSectionHead(title: "Songs")
                ForEach(Array(tracks.enumerated()), id: \.offset) { i, t in
                    TrackRow(track: t) { player.play(tracks, at: i) }
                }
            }
        } else if searching {
            HStack { Spacer(); ProgressView().tint(Theme.red); Spacer() }.padding(.top, 40)
        } else if results != nil {
            Text("Nothing found for \u{201C}\(query.trimmingCharacters(in: .whitespaces))\u{201D}")
                .foregroundColor(Theme.dim).frame(maxWidth: .infinity).padding(.top, 40)
        }
    }

    // MARK: The rows

    private func songRow(_ title: String, _ list: [MusicTrack], playAll: Bool = false) -> some View {
        Group {
            if !list.isEmpty {
                MusicStrip(title: title, action: playAll ? (label: "Play all", run: { player.play(list, at: 0, shuffled: false) }) : nil) {
                    ForEach(Array(list.enumerated()), id: \.offset) { i, t in
                        SongTile(track: t) { player.play(list, at: i) }
                    }
                }
            }
        }
    }

    private func artistRow(_ title: String, _ list: [MusicArtist]) -> some View {
        let shown = taste.data.visible(list)
        return Group {
            if !shown.isEmpty {
                MusicStrip(title: title) { ForEach(shown, id: \.id) { ArtistTile(artist: $0) } }
            }
        }
    }

    private func albumRow(_ title: String, _ list: [MusicAlbum]) -> some View {
        Group {
            if !list.isEmpty {
                MusicStrip(title: title) { ForEach(list, id: \.id) { AlbumTile(album: $0) } }
            }
        }
    }

    /// The Music home's rows. First the ones made from you (what you play, heart and hide), then what is popular and new.
    @ViewBuilder private var discover: some View {
        let t = taste.data
        let s = seeds
        let merged = t.mergeSimilar(s, s.map { home.similar[$0.id] ?? [] })
        let liked = Set(s.map(\.id) + t.likedArtists.map(\.id) + merged.map(\.id))
        let fav: ([MusicAlbum]?) -> [MusicAlbum] = { list in
            let v = t.visible(list ?? [])
            return v.filter { a in a.artistMbid.map { liked.contains($0) } ?? false } + v.filter { a in !(a.artistMbid.map { liked.contains($0) } ?? false) }
        }
        let forYou = Array(t.visible(home.newReleases ?? []).filter { a in a.artistMbid.map { liked.contains($0) } ?? false }.prefix(24))
        let mix = t.visible(home.mix)
        let recent = t.visible(player.recentTracks())

        songRow("Daily mix", mix, playAll: true)
        songRow("Recently played", recent)
        if let first = s.first {
            artistRow("Because you played \(first.name)", home.similar[first.id] ?? [])
            albumRow("More from \(first.name)", t.visible(home.more[first.id] ?? []))
        }
        if !merged.isEmpty { artistRow("Similar to your favorites", merged) }
        albumRow("New from artists you like", forYou)
        if s.count > 1 { artistRow("Because you played \(s[1].name)", home.similar[s[1].id] ?? []) }
        songRow("Liked songs", t.likedTracks, playAll: true)
        albumRow("Popular right now", fav(home.popularAlbums))
        albumRow("New releases", Array(fav(home.newReleases).prefix(30)))
        let top = Array(t.visible(home.popularSongs ?? []).prefix(10))
        if !top.isEmpty {
            MusicSectionHead(title: "Songs people are playing")
            ForEach(Array(top.enumerated()), id: \.offset) { i, track in
                TrackRow(track: track, number: i + 1) { player.play(top, at: i) }
            }
        }
        artistRow("Popular artists", home.popularArtists ?? [])
        ForEach(Array(home.catalogs.enumerated()), id: \.offset) { _, row in
            if !row.albums.isEmpty { albumRow(row.name, row.albums) }
            else if !row.artists.isEmpty { artistRow(row.name, row.artists) }
            else { songRow(row.name, row.tracks) }
        }
        if home.loadingGlobal {
            HStack { Spacer(); ProgressView().tint(Theme.red); Spacer() }.padding(.top, 30)
        } else if s.isEmpty {
            Text("Play and heart songs, and this page starts to fit you: daily mixes, artists like the ones you play, new releases from them.")
                .font(.subheadline).foregroundColor(Theme.dim).padding(.horizontal, 16)
        }
        if !home.loadingGlobal, (home.popularAlbums ?? []).isEmpty, (home.newReleases ?? []).isEmpty, (home.popularSongs ?? []).isEmpty, recent.isEmpty {
            Text("Couldn't load ideas right now (no connection?). Search for an artist, an album or a song to start.")
                .font(.subheadline).foregroundColor(Theme.dim).padding(.horizontal, 16)
        }
    }
}

/// Search answers as they come in: MusicBrainz first in each list, then each addon's.
@MainActor
final class SearchParts {
    var mb = MusicResults()
    var addons: [MusicResults] = []
    var merged: MusicResults {
        MusicResults(artists: mb.artists + addons.flatMap(\.artists), albums: mb.albums + addons.flatMap(\.albums), tracks: mb.tracks + addons.flatMap(\.tracks))
    }
}
