import SwiftUI

/// The bar above the tab bar while music plays: cover, song, play/pause and next. Tap it for Now Playing.
struct MiniPlayerBar: View {
    @ObservedObject private var player = MusicPlayer.shared
    @ObservedObject private var clock = MusicClock.shared

    var body: some View {
        if let t = player.current {
            VStack(spacing: 0) {
                if let m = player.message {
                    Text(m).font(.caption).foregroundColor(.white).lineLimit(2)
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .background(Capsule().fill(Theme.raised))
                        .padding(.bottom, 6)
                }
                HStack(spacing: 12) {
                    MusicCover(url: t.artworkURL, size: 42)
                    VStack(alignment: .leading, spacing: 1) {
                        Text(t.title).font(.subheadline.weight(.semibold)).foregroundColor(.white).lineLimit(1)
                        Text(player.loading ? (player.preparing ?? "Finding a source…") : t.artist).font(.caption).foregroundColor(Theme.dim).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    if player.loading {
                        ProgressView().tint(.white).frame(width: 36)
                    } else {
                        Button { player.toggle() } label: {
                            Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.title3).foregroundColor(.white).frame(width: 36, height: 36)
                        }
                    }
                    Button { player.next() } label: {
                        Image(systemName: "forward.fill").font(.title3).foregroundColor(.white).frame(width: 36, height: 36)
                    }
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .background(
                    ZStack(alignment: .bottomLeading) {
                        RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x1C1C1E))
                        GeometryReader { g in
                            Rectangle().fill(Theme.red)
                                .frame(width: player.duration > 0 ? g.size.width * min(1, player.position / player.duration) : 0, height: 2)
                                .frame(maxHeight: .infinity, alignment: .bottom)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                )
                .contentShape(Rectangle())
                .onTapGesture { player.showFull = true }
                .gesture(DragGesture(minimumDistance: 20).onEnded { v in if v.translation.height < -20 { player.showFull = true } })
            }
            .padding(.horizontal, 8)
            .sheet(isPresented: $player.showFull) { NowPlayingView() }
        }
    }
}

/// Now Playing: big cover, the heart, the time bar, controls, volume, and Up next (the queue).
struct NowPlayingView: View {
    @ObservedObject private var player = MusicPlayer.shared
    @ObservedObject private var clock = MusicClock.shared
    @ObservedObject private var taste = MusicTaste.shared
    @Environment(\.dismiss) private var dismiss
    @State private var scrub: Double?

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                Capsule().fill(Theme.faint).frame(width: 40, height: 5).padding(.top, 8)
                if let t = player.current {
                    MusicCover(url: t.artworkURL.map { $0.replacingOccurrences(of: "front-250", with: "front-500") }, size: 300)
                        .shadow(color: .black.opacity(0.6), radius: 20, y: 10)
                        .padding(.top, 10)
                    HStack(alignment: .center) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(t.title).font(.title3.bold()).foregroundColor(.white).lineLimit(2)
                            if let id = t.artistMbid, isMbid(id) {
                                Button { MusicNav.shared.open(MusicArtist(id: id, name: t.artist, detail: nil, artworkURL: nil, addon: nil)) } label: {
                                    Text(t.artist).font(.headline).foregroundColor(Theme.red).lineLimit(1)
                                }
                            } else {
                                Text(t.artist).font(.headline).foregroundColor(Theme.dim).lineLimit(1)
                            }
                        }
                        Spacer()
                        Button { taste.toggleLike(t) } label: {
                            Image(systemName: taste.data.isLiked(t) ? "heart.fill" : "heart").font(.title2)
                                .foregroundColor(taste.data.isLiked(t) ? Theme.red : .white)
                        }
                        Menu { TrackMenuItems(track: t) } label: {
                            Image(systemName: "ellipsis.circle").font(.title2).foregroundColor(.white).padding(.leading, 10)
                        }
                    }
                    .padding(.horizontal, 24)
                    timeBar
                    controls
                    HStack(spacing: 10) {
                        Image(systemName: "speaker.fill").foregroundColor(Theme.dim)
                        Slider(value: Binding(get: { Double(player.volume) }, set: { player.setVolume(Float($0)) }), in: 0...1).tint(.white)
                        Image(systemName: "speaker.wave.3.fill").foregroundColor(Theme.dim)
                    }
                    .padding(.horizontal, 24)
                    LyricsCard()
                    if player.loading, let p = player.preparing {
                        Text(p).font(.caption).foregroundColor(Theme.dim).multilineTextAlignment(.center).padding(.horizontal, 24)
                    } else if let m = player.message {
                        Text(m).font(.caption).foregroundColor(Theme.redSoft).multilineTextAlignment(.center).padding(.horizontal, 24)
                    } else if let s = player.source {
                        Text("From \(s)\(player.quality.map { " · \($0)" } ?? "")").font(.caption).foregroundColor(Theme.faint)
                    }
                    upNext
                } else {
                    Text("Nothing playing").foregroundColor(Theme.dim).padding(.top, 60)
                }
            }
            .padding(.bottom, 30)
        }
        .background(Color.black.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }

    private var timeBar: some View {
        let length = max(player.duration, 1)
        return VStack(spacing: 2) {
            Slider(value: Binding(get: { scrub ?? min(player.position, length) }, set: { scrub = $0 }), in: 0...length) { editing in
                if !editing, let s = scrub {
                    player.seek(s)
                    scrub = nil
                }
            }
            .tint(.white)
            HStack {
                Text(formatTime(scrub ?? player.position))
                Spacer()
                Text(player.duration > 0 ? "-" + formatTime(max(0, player.duration - (scrub ?? player.position))) : "")
            }
            .font(.caption.monospacedDigit()).foregroundColor(Theme.dim)
        }
        .padding(.horizontal, 24)
    }

    private var controls: some View {
        HStack {
            Button { player.toggleShuffle() } label: {
                Image(systemName: "shuffle").font(.title3).foregroundColor(player.shuffle ? Theme.red : .white)
            }
            Spacer()
            Button { player.previous() } label: { Image(systemName: "backward.fill").font(.title).foregroundColor(.white) }
            Spacer()
            Button { player.toggle() } label: {
                ZStack {
                    Circle().fill(Color.white).frame(width: 70, height: 70)
                    if player.loading {
                        ProgressView().tint(.black)
                    } else {
                        Image(systemName: player.playing ? "pause.fill" : "play.fill").font(.title).foregroundColor(.black)
                    }
                }
            }
            Spacer()
            Button { player.next() } label: { Image(systemName: "forward.fill").font(.title).foregroundColor(.white) }
            Spacer()
            Button { player.cycleRepeat() } label: {
                Image(systemName: player.repeatMode == .one ? "repeat.1" : "repeat").font(.title3)
                    .foregroundColor(player.repeatMode == .off ? .white : Theme.red)
            }
        }
        .padding(.horizontal, 28)
    }

    @ViewBuilder private var upNext: some View {
        let later = player.queue.indices.filter { $0 > player.index }
        if !later.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("Up next").font(.title3.bold()).foregroundColor(.white)
                    Spacer()
                    Text("\(later.count) songs").font(.caption).foregroundColor(Theme.dim)
                }
                .padding(.horizontal, 16).padding(.bottom, 6)
                ForEach(later, id: \.self) { i in
                    let t = player.queue[i]
                    HStack(spacing: 12) {
                        Button { player.playIndex(i) } label: {
                            HStack(spacing: 12) {
                                MusicCover(url: t.artworkURL, size: 42)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(t.title).foregroundColor(.white).lineLimit(1)
                                    Text(t.artist).font(.caption).foregroundColor(Theme.dim).lineLimit(1)
                                }
                                Spacer()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        Button { player.remove(at: i) } label: {
                            Image(systemName: "xmark").font(.caption).foregroundColor(Theme.dim).frame(width: 32, height: 32)
                        }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 4)
                }
                Button(role: .destructive) { player.stop() } label: {
                    Text("Stop and clear the queue").font(.subheadline).frame(maxWidth: .infinity)
                }
                .padding(.top, 12)
            }
        }
    }
}
