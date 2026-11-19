import SwiftUI

// Lyrics and downloaded songs: the Music screens' parts for them (DESIGN.md "Lyrics" and "Music downloads").

/// The Downloaded row at the top of Music: songs kept on the iPhone, and what is still downloading.
struct DownloadsStrip: View {
    @ObservedObject private var downloads = MusicDownloads.shared
    @ObservedObject private var player = MusicPlayer.shared

    var body: some View {
        let done = downloads.doneTracks
        if !downloads.entries.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                NavigationLink { MusicDownloadsView() } label: {
                    HStack {
                        Text("Downloaded").font(.title3.bold()).foregroundColor(.white)
                        Spacer()
                        Text("See all").font(.subheadline.weight(.semibold)).foregroundColor(Theme.red)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                }
                .buttonStyle(.plain)
                if let s = downloads.status {
                    Text(s).font(.caption).foregroundColor(Theme.dim).padding(.horizontal, 16)
                }
                if !done.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(alignment: .top, spacing: 12) {
                            ForEach(Array(done.enumerated()), id: \.offset) { i, t in
                                SongTile(track: t) { player.play(done, at: i) }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }
            }
        }
    }
}

/// Every downloaded song: play them offline, see the ones on their way, remove them.
struct MusicDownloadsView: View {
    @ObservedObject private var downloads = MusicDownloads.shared
    @ObservedObject private var player = MusicPlayer.shared
    @State private var confirmClear = false

    var body: some View {
        let done = downloads.doneTracks
        let pending = downloads.entries.reversed().filter { !downloads.isDone($0) }
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 10) {
                if downloads.entries.isEmpty {
                    Text("No songs downloaded yet. Pick Download in a song's ⋯ menu, or on an album.")
                        .foregroundColor(Theme.dim).padding(16)
                } else {
                    Text("\(done.count) song\(done.count == 1 ? "" : "s") · \(ByteCountFormatter.string(fromByteCount: downloads.usedBytes, countStyle: .file))")
                        .font(.subheadline).foregroundColor(Theme.dim).padding(.horizontal, 16)
                    if !done.isEmpty {
                        HStack(spacing: 10) {
                            Button { player.play(done, at: 0, shuffled: false) } label: { Label("Play", systemImage: "play.fill") }.buttonStyle(RedButtonStyle())
                            Button { player.play(done, at: Int.random(in: 0..<done.count), shuffled: true) } label: { Label("Shuffle", systemImage: "shuffle") }
                                .buttonStyle(RedButtonStyle(filled: false))
                        }
                        .padding(.horizontal, 16)
                    }
                    if !pending.isEmpty {
                        MusicSectionHead(title: "On the way")
                        ForEach(pending) { e in pendingRow(e) }
                    }
                    if !done.isEmpty {
                        MusicSectionHead(title: "Songs")
                        ForEach(Array(done.enumerated()), id: \.offset) { i, t in
                            TrackRow(track: t) { player.play(done, at: i) }
                        }
                    }
                    Button(role: .destructive) { confirmClear = true } label: {
                        Text("Remove all downloads").font(.subheadline)
                    }
                    .padding(.horizontal, 16).padding(.top, 14)
                    Text("Downloads follow Download over in Settings" + (Downloads.wifiOnly ? " (now Wi-Fi only)." : "."))
                        .font(.caption).foregroundColor(Theme.faint).padding(.horizontal, 16)
                }
            }
            .padding(.vertical, 10)
            .padding(.bottom, player.current == nil ? 20 : 90)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle("Downloaded")
        .confirmationDialog("Remove all downloads?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Remove", role: .destructive) { downloads.removeAll() }
        } message: {
            Text("The songs leave this iPhone. You can download them again.")
        }
    }

    private func pendingRow(_ e: MusicDownloads.Entry) -> some View {
        HStack(spacing: 12) {
            MusicCover(url: e.track.artworkURL, size: 46)
            VStack(alignment: .leading, spacing: 2) {
                Text(e.track.title).foregroundColor(.white).lineLimit(1)
                Text(e.track.artist + " · " + stateText(e)).font(.caption)
                    .foregroundColor(e.state == .failed ? Theme.redSoft : Theme.dim).lineLimit(2)
            }
            Spacer()
            if e.state == .failed {
                Button { downloads.add([e.track]) } label: {
                    Image(systemName: "arrow.clockwise").foregroundColor(.white).frame(width: 32, height: 32)
                }
            }
            Button { downloads.remove([e.track]) } label: {
                Image(systemName: "xmark").font(.caption).foregroundColor(Theme.dim).frame(width: 32, height: 32)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 4)
    }

    private func stateText(_ e: MusicDownloads.Entry) -> String {
        switch e.state {
        case .failed: return e.error ?? "Failed"
        case .downloading: return Downloads.wifiOnly ? "Downloading (on Wi-Fi)…" : "Downloading…"
        default: return "Queued"
        }
    }
}

/// An album's Download button: downloads every song, shows how far along it is, removes them again.
struct AlbumDownloadButton: View {
    let tracks: [MusicTrack]
    @ObservedObject private var downloads = MusicDownloads.shared
    @ObservedObject private var addons = MusicAddons.shared
    @State private var confirm = false

    var body: some View {
        let n = downloads.count(tracks)
        let all = n.done >= tracks.count
        let going = n.pending > 0
        Button {
            if all || (going && n.done + n.pending >= tracks.count) { confirm = true } else { downloads.add(tracks) }
        } label: {
            Label(all ? "Downloaded" : going ? "Downloading \(n.done) of \(tracks.count)" : n.done > 0 ? "Download the rest" : "Download",
                  systemImage: all ? "checkmark.circle.fill" : "arrow.down.circle")
                .foregroundColor(all ? Theme.red : .white)
        }
        .font(.subheadline.weight(.semibold))
        .disabled(addons.list.isEmpty && n.done == 0 && !going)
        .confirmationDialog(going ? "Stop downloading this album?" : "Remove this album's downloads?", isPresented: $confirm, titleVisibility: .visible) {
            Button("Remove", role: .destructive) { downloads.remove(tracks) }
        }
    }
}

/// The song's state in a list: downloaded (red) or on its way (grey).
struct DownloadMark: View {
    let track: MusicTrack
    @ObservedObject private var downloads = MusicDownloads.shared

    var body: some View {
        if let e = downloads.entry(track), e.state != .failed {
            Image(systemName: "arrow.down.circle.fill").font(.caption)
                .foregroundColor(downloads.isDone(e) ? Theme.red : Theme.faint)
        }
    }
}

/// The Download / Remove download item of a song's ⋯ menu.
struct DownloadMenuItem: View {
    let track: MusicTrack
    @ObservedObject private var downloads = MusicDownloads.shared

    var body: some View {
        if let e = downloads.entry(track) {
            if downloads.isDone(e) {
                Button(role: .destructive) { downloads.remove([track]) } label: { Label("Remove download", systemImage: "trash") }
            } else if e.state == .failed {
                Button { downloads.add([track]) } label: { Label("Try the download again", systemImage: "arrow.clockwise") }
            } else {
                Button { downloads.remove([track]) } label: { Label("Cancel download", systemImage: "xmark.circle") }
            }
        } else {
            Button { downloads.add([track]) } label: { Label("Download", systemImage: "arrow.down.circle") }
        }
    }
}

/// Looks up the lyrics of the song that is playing, for the card and the full page.
@MainActor
final class NowLyrics: ObservableObject {
    static let shared = NowLyrics()
    @Published private(set) var state: LyricsStore.State = .none
    @Published private(set) var forKey: String?
    private var task: Task<Void, Never>?

    func follow(_ t: MusicTrack?, duration: Double, loading: Bool) {
        guard let t = t else {
            forKey = nil
            state = .none
            return
        }
        // wait until the song is found (its length is known by then)
        guard !loading, t.key != forKey else { return }
        forKey = t.key
        state = .loading
        task?.cancel()
        let key = t.key
        task = Task {
            let s = await LyricsStore.shared.get(t, durationMs: Int(duration * 1000))
            if self.forKey == key { self.state = s }
            if case .failed = s, self.forKey == key { self.forKey = nil }
        }
    }
}

/// On Now Playing: the line being sung and the next one. Tap for all the words.
struct LyricsCard: View {
    @ObservedObject private var player = MusicPlayer.shared
    @ObservedObject private var now = NowLyrics.shared
    @State private var open = false

    var body: some View {
        Group {
            if case .found(let l) = now.state {
                Button { open = true } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("LYRICS").font(.caption.weight(.semibold)).foregroundColor(Theme.dim)
                        if l.synced {
                            let at = l.line(at: player.position)
                            Text(at >= 0 && !l.lines[at].text.isEmpty ? l.lines[at].text : "♪")
                                .font(.title3.bold()).foregroundColor(.white).lineLimit(3)
                            if at + 1 < l.lines.count, !l.lines[at + 1].text.isEmpty {
                                Text(l.lines[at + 1].text).font(.headline).foregroundColor(Theme.faint).lineLimit(3)
                            }
                        } else if l.instrumental {
                            Text("♪  Instrumental").font(.title3.bold()).foregroundColor(.white)
                        } else {
                            Text(l.plain ?? "").font(.headline).foregroundColor(.white).lineLimit(3)
                            Text("Tap for all the words").font(.caption).foregroundColor(Theme.faint)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(RoundedRectangle(cornerRadius: 12).fill(Color(hex: 0x1C1C1E)))
                    .multilineTextAlignment(.leading)
                }
                .buttonStyle(.plain)
                .padding(.horizontal, 16)
                .sheet(isPresented: $open) { LyricsView() }
            }
        }
        .onAppear { now.follow(player.current, duration: player.duration, loading: player.loading) }
        .onChange(of: player.current?.key) { _ in now.follow(player.current, duration: player.duration, loading: player.loading) }
        .onChange(of: player.loading) { _ in now.follow(player.current, duration: player.duration, loading: player.loading) }
    }
}

/// All the words. Timed lyrics follow the song: the line being sung is white and kept in view; tap a line to jump there.
struct LyricsView: View {
    @ObservedObject private var player = MusicPlayer.shared
    @ObservedObject private var now = NowLyrics.shared
    @State private var touchedAt = Date.distantPast

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Capsule().fill(Theme.faint).frame(width: 40, height: 5).frame(maxWidth: .infinity).padding(.top, 8)
            if let t = player.current {
                Text(t.title + " · " + t.artist).font(.subheadline).foregroundColor(Theme.dim).lineLimit(1)
                    .padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 6)
            }
            content
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }

    @ViewBuilder private var content: some View {
        switch now.state {
        case .loading:
            ProgressView().tint(Theme.red).frame(maxWidth: .infinity).padding(.top, 60)
            Spacer()
        case .none:
            Text("No lyrics found for this song.").foregroundColor(Theme.dim).padding(20)
            Spacer()
        case .failed:
            Text("Couldn't reach the lyrics. Check your connection.").foregroundColor(Theme.dim).padding(20)
            Spacer()
        case .found(let l):
            if l.synced {
                synced(l)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(l.instrumental ? "♪  Instrumental" : (l.plain ?? "")).font(.title3.bold()).foregroundColor(.white).lineSpacing(6)
                        if !l.instrumental { Text("These lyrics aren't timed to the song.").font(.caption).foregroundColor(Theme.faint) }
                        Text(Lyrics.credit).font(.caption2).foregroundColor(Theme.faint)
                    }
                    .padding(20)
                }
            }
        }
    }

    private func synced(_ l: SongLyrics) -> some View {
        let at = l.line(at: player.position)
        return ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    ForEach(Array(l.lines.enumerated()), id: \.offset) { i, line in
                        Text(line.text.isEmpty ? "♪" : line.text)
                            .font(.title2.bold())
                            .foregroundColor(i == at ? .white : i < at ? Color(hex: 0x5A5A5E) : Theme.faint)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                            .onTapGesture {
                                touchedAt = .distantPast
                                player.seek(Double(line.ms) / 1000)
                            }
                            .id(i)
                    }
                    Text(Lyrics.credit).font(.caption2).foregroundColor(Theme.faint).padding(.top, 20)
                }
                .padding(.horizontal, 20)
                .padding(.top, 10)
                .padding(.bottom, 240)
            }
            .simultaneousGesture(DragGesture().onChanged { _ in touchedAt = Date() })
            .onChange(of: at) { line in
                // after you scroll by hand the page stays put for a few seconds
                guard line >= 0, Date().timeIntervalSince(touchedAt) > 4 else { return }
                withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(line, anchor: UnitPoint(x: 0.5, y: 0.3)) }
            }
            .onAppear { if at >= 0 { proxy.scrollTo(at, anchor: UnitPoint(x: 0.5, y: 0.3)) } }
        }
    }
}
