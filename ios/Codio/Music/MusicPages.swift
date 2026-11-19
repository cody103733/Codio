import SwiftUI

/// The big round buttons under an album or artist: Play, Shuffle, the heart and Not interested.
private struct HeroButtons: View {
    let canPlay: Bool
    let liked: Bool
    let hidden: Bool
    let play: () -> Void
    let shuffle: () -> Void
    let like: () -> Void
    let hide: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            if canPlay {
                HStack(spacing: 10) {
                    Button(action: play) { Label("Play", systemImage: "play.fill") }.buttonStyle(RedButtonStyle())
                    Button(action: shuffle) { Label("Shuffle", systemImage: "shuffle") }.buttonStyle(RedButtonStyle(filled: false))
                }
            }
            HStack(spacing: 24) {
                Button(action: like) {
                    Label(liked ? "Liked" : "Like", systemImage: liked ? "heart.fill" : "heart")
                        .foregroundColor(liked ? Theme.red : .white)
                }
                Button(action: hide) {
                    Label(hidden ? "Hidden: undo" : "Not interested", systemImage: "hand.thumbsdown")
                        .foregroundColor(Theme.dim)
                }
            }
            .font(.subheadline.weight(.semibold))
        }
        .padding(.horizontal, 16)
    }
}

/// An album: cover, tracklist with lengths, Play and Shuffle. From MusicBrainz, or from the addon that offered it.
struct AlbumPageView: View {
    let album: MusicAlbum
    @ObservedObject private var taste = MusicTaste.shared
    @ObservedObject private var player = MusicPlayer.shared
    @ObservedObject private var nav = MusicNav.shared
    @State private var page: Page?
    @State private var failed = false

    struct Page {
        var title: String
        var artist: String
        var artistId: String?
        var year: String?
        var artworkURL: String?
        var tracks: [MusicTrack]
    }

    private var shown: MusicAlbum {
        var a = album
        if let p = page {
            a.title = p.title.isEmpty ? a.title : p.title
            a.artist = p.artist.isEmpty ? a.artist : p.artist
            a.year = p.year ?? a.year
            a.artworkURL = p.artworkURL ?? a.artworkURL
            a.artistMbid = p.artistId ?? a.artistMbid
        }
        return a
    }

    var body: some View {
        let a = shown
        let tracks = page?.tracks ?? []
        ScrollView {
            VStack(spacing: 14) {
                MusicCover(url: a.artworkURL.map { $0.replacingOccurrences(of: "front-250", with: "front-500") }, size: 230)
                    .shadow(color: .black.opacity(0.6), radius: 16, y: 8)
                    .padding(.top, 8)
                VStack(spacing: 4) {
                    Text(a.title).font(.title2.bold()).foregroundColor(.white).multilineTextAlignment(.center)
                    if let id = a.artistMbid, isMbid(id) {
                        Button { nav.path.append(MusicArtist(id: id, name: a.artist, detail: nil, artworkURL: nil, addon: nil)) } label: {
                            Text(a.artist).font(.headline).foregroundColor(Theme.red)
                        }
                    } else {
                        Text(a.artist).font(.headline).foregroundColor(Theme.dim)
                    }
                    Text([a.kind ?? "Album", a.year].compactMap { $0 }.joined(separator: " · ")).font(.caption).foregroundColor(Theme.faint)
                }
                .padding(.horizontal, 16)
                HeroButtons(canPlay: !tracks.isEmpty, liked: taste.data.isLiked(album: album.id), hidden: taste.data.hiddenAlbums[album.id] != nil,
                            play: { player.play(tracks, at: 0, shuffled: false) },
                            shuffle: { player.play(tracks, at: Int.random(in: 0..<max(1, tracks.count)), shuffled: true) },
                            like: { taste.toggleLike(a) },
                            hide: { if taste.data.hiddenAlbums[album.id] != nil { taste.unhide(.album, album.id) } else { taste.hide(a) } })
                if page == nil && !failed {
                    ProgressView().tint(Theme.red).padding(.top, 30)
                } else if failed {
                    Text("Couldn't load this album").foregroundColor(Theme.dim).padding(.top, 30)
                } else if tracks.isEmpty {
                    Text("No songs listed for this album").foregroundColor(Theme.dim).padding(.top, 30)
                }
                LazyVStack(spacing: 0) {
                    ForEach(Array(tracks.enumerated()), id: \.offset) { i, t in
                        TrackRow(track: t, number: i + 1, showArt: false) { player.play(tracks, at: i, shuffled: false) }
                    }
                }
            }
            .padding(.bottom, player.current == nil ? 20 : 90)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task(id: album.id) { await load() }
    }

    private func load() async {
        failed = false
        if let url = album.addon, let addon = MusicAddons.shared.list.first(where: { $0.url == url }) {
            guard let d = await MusicAddonClient.albumDetail(addon, album.id) else { failed = true; return }
            page = Page(title: d.title, artist: d.artist, artistId: nil, year: d.year, artworkURL: d.artworkURL, tracks: d.tracks)
        } else {
            guard let p = await MB.album(album.id) else { failed = true; return }
            page = Page(title: p.title, artist: p.artist, artistId: p.artistId, year: p.year, artworkURL: p.artworkURL, tracks: p.tracks)
        }
    }
}

/// An artist: their most played songs, then albums, singles and the rest. From MusicBrainz, or from the addon.
struct ArtistPageView: View {
    let artist: MusicArtist
    @ObservedObject private var taste = MusicTaste.shared
    @ObservedObject private var player = MusicPlayer.shared
    @State private var detail: String?
    @State private var top: [MusicTrack] = []
    @State private var groups: [(title: String, albums: [MusicAlbum])] = []
    @State private var loaded = false
    @State private var failed = false
    @State private var photo: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(spacing: 8) {
                    if let p = photo ?? artist.artworkURL {
                        MusicCover(url: p, size: 190, round: true)
                    } else {
                        ArtistPhoto(artist: artist, size: 190)
                    }
                    Text(artist.name).font(.title.bold()).foregroundColor(.white).multilineTextAlignment(.center)
                    if let d = detail ?? artist.detail {
                        Text(d).font(.caption).foregroundColor(Theme.dim).multilineTextAlignment(.center).lineLimit(4)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 16)
                .padding(.top, 8)
                HeroButtons(canPlay: !top.isEmpty, liked: taste.data.isLiked(artist: artist.id), hidden: taste.data.isHidden(artist: artist.id),
                            play: { player.play(top, at: 0, shuffled: false) },
                            shuffle: { player.play(top, at: Int.random(in: 0..<max(1, top.count)), shuffled: true) },
                            like: { taste.toggleLike(artist) },
                            hide: { if taste.data.isHidden(artist: artist.id) { taste.unhide(.artist, artist.id) } else { taste.hide(artist: artist.id, name: artist.name) } })
                if !top.isEmpty {
                    MusicSectionHead(title: "Popular songs")
                    LazyVStack(spacing: 0) {
                        ForEach(Array(top.enumerated()), id: \.offset) { i, t in
                            TrackRow(track: t) { player.play(top, at: i, shuffled: false) }
                        }
                    }
                }
                ForEach(groups, id: \.title) { g in
                    MusicStrip(title: g.title) {
                        ForEach(g.albums, id: \.id) { a in
                            AlbumTile(album: a, sub: [a.year, a.kind?.replacingOccurrences(of: "Album · ", with: "")].compactMap { $0 }.joined(separator: " · "))
                        }
                    }
                }
                if !loaded && !failed {
                    HStack { Spacer(); ProgressView().tint(Theme.red); Spacer() }.padding(.top, 20)
                } else if failed {
                    Text("Couldn't load this artist").foregroundColor(Theme.dim).frame(maxWidth: .infinity)
                } else if top.isEmpty && groups.isEmpty {
                    Text("No albums found for this artist.").foregroundColor(Theme.dim).frame(maxWidth: .infinity)
                }
            }
            .padding(.bottom, player.current == nil ? 20 : 90)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task(id: artist.id) { await load() }
    }

    private func load() async {
        failed = false
        if let url = artist.addon, let addon = MusicAddons.shared.list.first(where: { $0.url == url }) {
            guard let d = await MusicAddonClient.artistDetail(addon, artist.id) else { failed = true; return }
            detail = d.bio
            photo = d.artworkURL
            top = d.topTracks
            groups = d.albums.isEmpty ? [] : [(title: "Albums", albums: d.albums)]
            loaded = true
        } else {
            guard let a = await MB.artist(artist.id, knownName: artist.name.isEmpty ? nil : artist.name) else { failed = true; return }
            if artist.detail == nil { detail = a.detail }
            groups = [(title: "Albums", albums: a.albums), (title: "Singles and EPs", albums: a.singles), (title: "Live, compilations and more", albums: a.other)]
                .filter { !$0.albums.isEmpty }
            loaded = true
            top = await LBData.topTracks(artist.id, name: a.name)
        }
    }
}

/// A whole chart as a numbered list (the Top 100 or a genre's top 100), opened from its row on the Music page.
struct ChartPageView: View {
    let chart: MusicChartRef
    @ObservedObject private var taste = MusicTaste.shared
    @ObservedObject private var player = MusicPlayer.shared
    @State private var songs: [MusicTrack]?

    var body: some View {
        let tracks = taste.data.visible(songs ?? [])
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(GenreCharts.title(chart.id)).font(.largeTitle.bold()).foregroundColor(.white).padding(.horizontal, 16).padding(.top, 8)
                if tracks.first.flatMap({ AppleLinks.url($0) }) != nil {
                    Text("Chart from Apple Music. Tap ⋯ on a song to listen there.").font(.footnote).foregroundColor(.white.opacity(0.6)).padding(.horizontal, 16)
                } else if chart.id == "top" && Chart.source == "lastfm" {
                    Text("Chart data from Last.fm").font(.footnote).foregroundColor(.white.opacity(0.6)).padding(.horizontal, 16)
                }
                if !tracks.isEmpty {
                    HStack(spacing: 10) {
                        Button { player.play(tracks, at: 0, shuffled: false) } label: { Label("Play", systemImage: "play.fill") }.buttonStyle(RedButtonStyle())
                        Button { player.play(tracks, at: Int.random(in: 0..<tracks.count), shuffled: true) } label: { Label("Shuffle", systemImage: "shuffle") }.buttonStyle(RedButtonStyle(filled: false))
                    }
                    .padding(.horizontal, 16)
                }
                if songs == nil {
                    HStack { Spacer(); ProgressView().tint(Theme.red); Spacer() }.padding(.top, 30)
                } else if tracks.isEmpty {
                    Text("Couldn't load this chart right now (no connection?).").foregroundColor(Theme.dim).padding(.horizontal, 16).padding(.top, 30)
                }
                LazyVStack(spacing: 0) {
                    ForEach(Array(tracks.enumerated()), id: \.offset) { i, t in
                        TrackRow(track: t, number: i + 1) { player.play(tracks, at: i, shuffled: false) }
                    }
                }
            }
            .padding(.bottom, player.current == nil ? 20 : 90)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .task(id: chart.id) { songs = await LBData.chartSongs(chart.id) }
    }
}
