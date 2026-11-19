import SwiftUI

/// Your stats: what this profile watched and listened to, this year or all time (DESIGN.md "Your stats").
struct StatsView: View {
    @EnvironmentObject var store: Store
    @ObservedObject private var listen = ListenStats.shared
    @ObservedObject private var player = MusicPlayer.shared
    @State private var allTime = false

    private var year: Int? { allTime ? nil : WatchStats.thisYear }

    var body: some View {
        let watch = WatchStats.summary(store.library, profile: store.signedInEmail, year: year)
        let music = listen.summary(year: year)
        let songs = music.songs.values.sorted { $0.count > $1.count }.prefix(5)
        let artists = music.artists.values.sorted { $0.count > $1.count }.prefix(5)
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Picker("", selection: $allTime) {
                    Text(String(WatchStats.thisYear)).tag(false)
                    Text("All time").tag(true)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)

                LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                    StatTile(value: statsHours(watch.ms), label: "Hours watched")
                    StatTile(value: "\(watch.shows.count + watch.movies.count)", label: "Titles watched",
                             detail: count(watch.movies.count, "movie") + " · " + count(watch.shows.count, "show"))
                    StatTile(value: "\(Int((music.seconds / 60).rounded()))", label: "Minutes listened")
                    StatTile(value: "\(music.plays)", label: "Songs played")
                }
                .padding(.horizontal, 16)

                titles("Top shows", watch.shows)
                titles("Top movies", watch.movies)

                MusicSectionHead(title: "Top songs")
                if songs.isEmpty {
                    empty("Nothing played yet")
                } else {
                    ForEach(Array(songs.enumerated()), id: \.offset) { _, s in
                        Button { player.play([s.track]) } label: {
                            HStack(spacing: 12) {
                                MusicCover(url: s.track.artworkURL, size: 46)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(s.track.title).foregroundColor(.white).lineLimit(1)
                                    Text(s.track.artist).font(.caption).foregroundColor(Theme.dim).lineLimit(1)
                                }
                                Spacer(minLength: 4)
                                Text(plays(s.count)).font(.caption).foregroundColor(Theme.red)
                            }
                            .padding(.horizontal, 16).padding(.vertical, 3)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }

                MusicSectionHead(title: "Top artists")
                if artists.isEmpty {
                    empty("Nothing played yet")
                } else {
                    VStack(spacing: 0) {
                        ForEach(Array(artists.enumerated()), id: \.offset) { i, a in
                            HStack {
                                Text("\(i + 1)").font(.subheadline.monospacedDigit()).foregroundColor(Theme.dim).frame(width: 22, alignment: .leading)
                                Text(a.name).foregroundColor(.white).lineLimit(1)
                                Spacer()
                                Text(plays(a.count)).font(.caption).foregroundColor(Theme.red)
                            }
                            .padding(.horizontal, 16).padding(.vertical, 10)
                            if i < artists.count - 1 { Divider().background(Color(hex: 0x38383A)).padding(.leading, 54) }
                        }
                    }
                    .background(RoundedRectangle(cornerRadius: 10).fill(Color(hex: 0x1C1C1E)))
                    .padding(.horizontal, 16)
                }
                Text("Worked out on this phone from your library and the music you play here.")
                    .font(.footnote).foregroundColor(Color(hex: 0x8D8D93))
                    .padding(.horizontal, 16).padding(.top, 6)
            }
            .padding(.vertical, 12)
            .padding(.bottom, player.current != nil ? 70 : 0)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle("Your stats")
    }

    private func plays(_ n: Int) -> String { count(n, "play") }
    private func count(_ n: Int, _ word: String) -> String { n == 1 ? "1 \(word)" : "\(n) \(word)s" }

    private func empty(_ what: String) -> some View {
        Text(what + (allTime ? "." : " this year."))
            .font(.subheadline).foregroundColor(Theme.faint)
            .padding(.horizontal, 16)
    }

    @ViewBuilder private func titles(_ head: String, _ list: [WatchStats.Title]) -> some View {
        MusicSectionHead(title: head)
        if list.isEmpty {
            empty("Nothing watched yet")
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 10) {
                    ForEach(list.prefix(5)) { t in
                        NavigationLink(value: t.entry.meta) {
                            VStack(alignment: .leading, spacing: 4) {
                                PosterCard(meta: t.entry.meta, width: 108)
                                Text(t.entry.meta.name).font(.caption).foregroundColor(.white).lineLimit(1)
                                Text("\(statsHours(t.ms)) h").font(.caption2).foregroundColor(Theme.red)
                            }
                            .frame(width: 108, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
    }
}

/// One number on Your stats: big value, label, and a small line under it.
private struct StatTile: View {
    let value: String
    let label: String
    var detail: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(value).font(.system(size: 30, weight: .bold)).foregroundColor(.white).lineLimit(1).minimumScaleFactor(0.6)
            Text(label).font(.subheadline.weight(.semibold)).foregroundColor(Theme.body)
            Text(detail ?? " ").font(.caption).foregroundColor(Theme.faint).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: 12).fill(Color(hex: 0x1C1C1E)))
    }
}
