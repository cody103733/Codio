import SwiftUI

/// Cover art (or an artist photo), with a music note while there's none.
struct MusicCover: View {
    let url: String?
    var size: CGFloat = 140
    var round = false

    var body: some View {
        ZStack {
            Theme.card
            Image(systemName: round ? "music.mic" : "music.note").font(.system(size: size * 0.3)).foregroundColor(Theme.faint)
            if url != nil { RemoteImage(url: url) }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: round ? size / 2 : 6))
    }
}

/// An artist's picture: the addon's, else Deezer's photo, else the cover of their most played album. Found when shown.
struct ArtistPhoto: View {
    let artist: MusicArtist
    var size: CGFloat = 140
    @State private var found: String?

    var body: some View {
        MusicCover(url: artist.artworkURL ?? found, size: size, round: true)
            .task(id: artist.id) {
                guard artist.artworkURL == nil, artist.addon == nil, isMbid(artist.id) else { return }
                found = await ArtistPhotos.shared.photo(id: artist.id, name: artist.name)
            }
    }
}

struct AlbumTile: View {
    let album: MusicAlbum
    @ObservedObject private var taste = MusicTaste.shared
    var sub: String? = nil

    var body: some View {
        NavigationLink(value: album) {
            VStack(alignment: .leading, spacing: 4) {
                MusicCover(url: album.artworkURL)
                    .overlay(alignment: .topTrailing) { heart(taste.data.isLiked(album: album.id)) }
                Text(album.title).font(.subheadline.weight(.semibold)).foregroundColor(.white).lineLimit(1)
                Text(sub ?? [album.artist, album.year].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundColor(Theme.dim).lineLimit(1)
            }
            .frame(width: 140, alignment: .leading)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { taste.toggleLike(album) } label: {
                Label(taste.data.isLiked(album: album.id) ? "Remove heart" : "Heart", systemImage: taste.data.isLiked(album: album.id) ? "heart.slash" : "heart")
            }
            Button(role: .destructive) { taste.hide(album) } label: { Label("Not interested", systemImage: "hand.thumbsdown") }
        }
    }
}

struct ArtistTile: View {
    let artist: MusicArtist
    @ObservedObject private var taste = MusicTaste.shared

    var body: some View {
        NavigationLink(value: artist) {
            VStack(spacing: 6) {
                ArtistPhoto(artist: artist, size: 120)
                    .overlay(alignment: .topTrailing) { heart(taste.data.isLiked(artist: artist.id)) }
                Text(artist.name).font(.subheadline.weight(.semibold)).foregroundColor(.white).lineLimit(1)
                if let d = artist.detail { Text(d).font(.caption).foregroundColor(Theme.dim).lineLimit(1) }
            }
            .frame(width: 120)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { taste.toggleLike(artist) } label: {
                Label(taste.data.isLiked(artist: artist.id) ? "Remove heart" : "Heart", systemImage: taste.data.isLiked(artist: artist.id) ? "heart.slash" : "heart")
            }
            Button(role: .destructive) { taste.hide(artist: artist.id, name: artist.name) } label: { Label("Not interested", systemImage: "hand.thumbsdown") }
        }
    }
}

/// A song as a square tile (Daily mix, Recently played, Liked songs). Tap plays the row from there.
struct SongTile: View {
    let track: MusicTrack
    /// Chart rows show the place before the name ("1. Song").
    var number: Int? = nil
    let onPlay: () -> Void
    @ObservedObject private var taste = MusicTaste.shared

    var body: some View {
        Button(action: onPlay) {
            VStack(alignment: .leading, spacing: 4) {
                MusicCover(url: track.artworkURL)
                    .overlay(alignment: .topTrailing) { heart(taste.data.isLiked(track)) }
                Text(number.map { "\($0). \(track.title)" } ?? track.title).font(.subheadline.weight(.semibold)).foregroundColor(.white).lineLimit(1)
                Text(track.artist).font(.caption).foregroundColor(Theme.dim).lineLimit(1)
            }
            .frame(width: 140, alignment: .leading)
        }
        .buttonStyle(.plain)
        .contextMenu { TrackMenuItems(track: track) }
    }
}

@ViewBuilder private func heart(_ on: Bool) -> some View {
    if on {
        Image(systemName: "heart.fill").font(.caption).foregroundColor(Theme.red)
            .padding(5).background(Circle().fill(Color.black.opacity(0.6))).padding(5)
    }
}

/// Play next, Add to queue, Heart, Not interested, and the artist's page.
struct TrackMenuItems: View {
    let track: MusicTrack
    @ObservedObject private var taste = MusicTaste.shared

    var body: some View {
        Button { MusicPlayer.shared.playNext(track) } label: { Label("Play next", systemImage: "text.insert") }
        Button { MusicPlayer.shared.addToQueue(track) } label: { Label("Add to queue", systemImage: "text.append") }
        Button { taste.toggleLike(track) } label: {
            Label(taste.data.isLiked(track) ? "Remove heart" : "Heart", systemImage: taste.data.isLiked(track) ? "heart.slash" : "heart")
        }
        if let id = track.artistMbid, isMbid(id) {
            Button { MusicNav.shared.open(MusicArtist(id: id, name: track.artist, detail: nil, artworkURL: nil, addon: nil)) } label: {
                Label("Go to artist", systemImage: "music.mic")
            }
        }
        if let apple = AppleLinks.url(track) {
            Link(destination: apple) { Label("Listen on Apple Music", systemImage: "applelogo") }
        }
        Button(role: .destructive) { taste.hide(track) } label: { Label("Not interested", systemImage: "hand.thumbsdown") }
    }
}

/// A song in a list: cover (or its number), title, artist, length, and a ⋯ menu.
struct TrackRow: View {
    let track: MusicTrack
    var number: Int? = nil
    var showArt = true
    let onPlay: () -> Void
    @ObservedObject private var player = MusicPlayer.shared
    @ObservedObject private var taste = MusicTaste.shared

    var body: some View {
        let isCurrent = player.current?.key == track.key
        HStack(spacing: 12) {
            Button(action: onPlay) {
                HStack(spacing: 12) {
                    if showArt {
                        MusicCover(url: track.artworkURL, size: 46)
                    } else if let n = number {
                        Text("\(n)").font(.subheadline.monospacedDigit()).foregroundColor(Theme.dim).frame(width: 24)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(track.title).font(.body).foregroundColor(isCurrent ? Theme.red : .white).lineLimit(1)
                        Text([track.artist, track.album].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                            .font(.caption).foregroundColor(Theme.dim).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    if taste.data.isLiked(track) { Image(systemName: "heart.fill").font(.caption).foregroundColor(Theme.red) }
                    if let ms = track.durationMs { Text(formatTime(Double(ms) / 1000)).font(.caption.monospacedDigit()).foregroundColor(Theme.faint) }
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            Menu { TrackMenuItems(track: track) } label: {
                Image(systemName: "ellipsis").foregroundColor(Theme.dim).frame(width: 30, height: 36).contentShape(Rectangle())
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 5)
    }
}

/// A row title, with an optional button on the right (Play all).
struct MusicSectionHead: View {
    let title: String
    var action: (label: String, run: () -> Void)? = nil

    var body: some View {
        HStack {
            Text(title).font(.title3.bold()).foregroundColor(.white).lineLimit(1)
            Spacer()
            if let a = action {
                Button(a.label, action: a.run).font(.subheadline.weight(.semibold)).foregroundColor(Theme.red)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }
}

/// A titled row that scrolls sideways.
struct MusicStrip<Content: View>: View {
    let title: String
    var action: (label: String, run: () -> Void)? = nil
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            MusicSectionHead(title: title, action: action)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: 12) { content() }.padding(.horizontal, 16)
            }
        }
    }
}

extension View {
    /// Album and artist pages, for any Music navigation stack.
    func musicDestinations() -> some View {
        navigationDestination(for: MusicAlbum.self) { AlbumPageView(album: $0) }
            .navigationDestination(for: MusicArtist.self) { ArtistPageView(artist: $0) }
            .navigationDestination(for: MusicChartRef.self) { ChartPageView(chart: $0) }
    }
}

/// The Music tab's page stack, so a menu (or the Now Playing screen) can open an artist.
@MainActor
final class MusicNav: ObservableObject {
    static let shared = MusicNav()
    @Published var path = NavigationPath()
    /// Bumped to ask for the Music tab to come to the front.
    @Published var showTab = 0

    func open(_ artist: MusicArtist) {
        MusicPlayer.shared.showFull = false
        path.append(artist)
        showTab += 1
    }
}
