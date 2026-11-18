import SwiftUI

/// Music settings: music addons, the MusicBrainz server, your ListenBrainz account and your taste.
struct MusicSettingsView: View {
    @ObservedObject private var addons = MusicAddons.shared
    @ObservedObject private var account = MusicAccount.shared
    @ObservedObject private var taste = MusicTaste.shared
    @State private var link = ""
    @State private var adding = false
    @State private var addError: String?
    @State private var token = ""
    @State private var signingIn = false
    @State private var signInError: String?
    @State private var server = UserDefaults.standard.string(forKey: MB.baseKey) ?? ""
    @State private var lastfm = Chart.lastfmKey
    @State private var lastfmOK: Bool??
    @State private var lastfmChecking = false
    @State private var sure = false
    @State private var syncing = false

    var body: some View {
        Form {
            addonsSection
            accountSection
            tasteSection
            Section {
                TextField("https://musicbrainz.org", text: $server)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    .onSubmit { saveServer() }
                    .onDisappear { saveServer() }
            } header: {
                Text("Music information")
            } footer: {
                Text("Artists, albums and covers come from MusicBrainz. If you run your own MusicBrainz server, put its address here. Leave it empty to use musicbrainz.org.")
            }
            Section {
                TextField("Last.fm API key", text: $lastfm)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .onSubmit { saveLastfm() }
                    .onDisappear { saveLastfm() }
                    .task { await checkLastfm() }
                if lastfmChecking {
                    Text("Checking the key…").foregroundColor(Theme.dim)
                } else if let ok = lastfmOK, !lastfm.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    if ok == true {
                        Label("Connected. Popular songs and artists use Last.fm.", systemImage: "checkmark.circle.fill").foregroundColor(.green)
                    } else if ok == false {
                        Label("Last.fm didn't accept this key. Use the API key, not the shared secret.", systemImage: "xmark.circle.fill").foregroundColor(Theme.redSoft)
                    } else {
                        Text("Couldn't reach Last.fm to check the key.").foregroundColor(Theme.dim)
                    }
                }
                if lastfm.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Link("Get a key", destination: URL(string: "https://www.last.fm/api/account/create")!)
                }
            } header: {
                Text("Charts")
            } footer: {
                Text("Popular songs and artists come from Apple Music's chart for your country, with a way to listen there. Paste your own Last.fm API key (free at last.fm/api) to use Last.fm's chart instead. Leave it empty for Apple's. It syncs with your Sync passcode.")
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle("Music")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func saveLastfm() {
        let changed = Chart.lastfmKey != lastfm.trimmingCharacters(in: .whitespacesAndNewlines)
        Chart.lastfmKey = lastfm
        if changed {
            MusicHome.shared.reloadCharts()
            Task { await checkLastfm() }
        }
    }

    private func checkLastfm() async {
        let key = Chart.lastfmKey
        guard !key.isEmpty else { lastfmOK = nil; return }
        lastfmChecking = true
        let r = await Chart.check(key)
        lastfmChecking = false
        lastfmOK = .some(r)
    }

    private func saveServer() {
        let s = server.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { UserDefaults.standard.removeObject(forKey: MB.baseKey) } else { UserDefaults.standard.set(s, forKey: MB.baseKey) }
    }

    // MARK: Addons

    private func describe(_ a: MusicAddon) -> String {
        let can = ["search", "stream", "catalog", "isrc", "resolve"].filter { a.has($0) }.joined(separator: ", ")
        return [a.version.map { "v" + $0 }, can.isEmpty ? nil : can].compactMap { $0 }.joined(separator: " · ")
    }

    private var addonsSection: some View {
        Section {
            ForEach(Array(addons.list.enumerated()), id: \.element.id) { i, a in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(i + 1). \(a.name)").foregroundColor(.white)
                        Text(describe(a)).font(.caption).foregroundColor(Theme.dim)
                    }
                    Spacer()
                    Button { addons.move(i, by: -1) } label: { Image(systemName: "chevron.up") }
                        .buttonStyle(.borderless).disabled(i == 0)
                    Button { addons.move(i, by: 1) } label: { Image(systemName: "chevron.down") }
                        .buttonStyle(.borderless).disabled(i == addons.list.count - 1).padding(.leading, 8)
                }
                .swipeActions {
                    Button("Remove", role: .destructive) { addons.remove(a) }
                }
            }
            HStack {
                TextField("Paste a music addon link", text: $link)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
                    .onSubmit { add() }
                if link.isEmpty {
                    Button("Paste") { if let v = UIPasteboard.general.string { link = v } }.buttonStyle(.borderless)
                } else if adding {
                    ProgressView().tint(Theme.red)
                } else {
                    Button("Add") { add() }.buttonStyle(.borderless)
                }
            }
            if let e = addError {
                Label(e, systemImage: "xmark.circle.fill").foregroundColor(Theme.redSoft)
            }
        } header: {
            Text("Music addons")
        } footer: {
            Text("A music addon finds the audio for a song. Paste its link to add it. Codio asks them in this order, and plays from the first one that has the song. Swipe left to remove one. Your addon links stay on this phone and are only sent to the addon itself.")
        }
    }

    private func add() {
        let text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !adding else { return }
        adding = true
        addError = nil
        Task {
            do {
                try await addons.add(text)
                link = ""
            } catch {
                addError = (error as? MusicError)?.text ?? "Couldn't add that addon"
            }
            adding = false
        }
    }

    // MARK: ListenBrainz

    private var accountSection: some View {
        Section {
            if let a = account.account {
                HStack {
                    Text("Signed in as").foregroundColor(.white)
                    Spacer()
                    Text(a.user).foregroundColor(Theme.red)
                }
                HStack {
                    Text("Last synced").foregroundColor(.white)
                    Spacer()
                    Text(a.lastSync.map { $0.formatted(.relative(presentation: .named)) } ?? "not yet").foregroundColor(Theme.dim)
                }
                HStack {
                    Text("Last song sent").foregroundColor(.white)
                    Spacer()
                    Text(a.lastSentAt.map { "\(a.lastSentTitle ?? ""), \($0.formatted(.relative(presentation: .named)))" } ?? "none yet").foregroundColor(Theme.dim).lineLimit(2).multilineTextAlignment(.trailing)
                }
                if account.waitingListens > 0 {
                    Text("\(account.waitingListens) waiting to be sent").foregroundColor(Theme.dim)
                }
                if let e = a.sendError ?? a.syncError { Label(e, systemImage: "exclamationmark.triangle.fill").foregroundColor(.orange) }
                Button {
                    syncing = true
                    Task { await account.syncNow(); syncing = false }
                } label: {
                    HStack { Text("Sync now"); if syncing { Spacer(); ProgressView().tint(Theme.red) } }
                }
                Link("Open my ListenBrainz page", destination: URL(string: "https://listenbrainz.org/user/\(menc(a.user))/")!)
                Button("Sign out", role: .destructive) { account.signOut() }
            } else {
                HStack {
                    SecureField("Paste your ListenBrainz user token", text: $token)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .onSubmit { signIn() }
                    if token.isEmpty {
                        Button("Paste") { if let v = UIPasteboard.general.string { token = v } }.buttonStyle(.borderless)
                    } else if signingIn {
                        ProgressView().tint(Theme.red)
                    } else {
                        Button("Sign in") { signIn() }.buttonStyle(.borderless)
                    }
                }
                if let e = signInError { Label(e, systemImage: "xmark.circle.fill").foregroundColor(Theme.redSoft) }
                Link("Get my token", destination: URL(string: "https://listenbrainz.org/settings/")!)
            }
        } header: {
            Text("Your account (ListenBrainz)")
        } footer: {
            if account.account != nil {
                Text("The songs you listen to and the ones you heart go to your ListenBrainz account, and hearts and plays from your other devices come back here.")
            } else {
                Text("Sign in to keep what you listen to and the songs you heart in step across your devices. ListenBrainz is free and open (from the people behind MusicBrainz). Make an account at listenbrainz.org, copy your user token from its settings page, and paste it here. The token is only sent to ListenBrainz, and to your other devices locked with your sync passcode if you set one (Settings).")
            }
        }
    }

    private func signIn() {
        guard !token.isEmpty, !signingIn else { return }
        signingIn = true
        signInError = nil
        Task {
            do {
                try await account.signIn(token)
                token = ""
            } catch {
                signInError = (error as? MusicError)?.text ?? "Couldn't sign in"
            }
            signingIn = false
        }
    }

    // MARK: Taste

    private struct HiddenItem: Hashable {
        var kind: MusicTaste.Kind
        var key: String
        var name: String
    }

    private var hidden: [HiddenItem] {
        let d = taste.data
        let artists = d.hiddenArtists.map { HiddenItem(kind: .artist, key: $0.key, name: $0.value) }.sorted { $0.name < $1.name }
        let albums = d.hiddenAlbums.map { HiddenItem(kind: .album, key: $0.key, name: $0.value) }.sorted { $0.name < $1.name }
        let tracks = d.hiddenTracks.map { HiddenItem(kind: .track, key: $0.key, name: $0.value) }.sorted { $0.name < $1.name }
        return artists + albums + tracks
    }

    private var tasteSection: some View {
        let d = taste.data
        return Section {
            HStack { Text("Artists played").foregroundColor(.white); Spacer(); Text("\(d.plays.count)").foregroundColor(Theme.red) }
            HStack {
                Text("Hearted").foregroundColor(.white)
                Spacer()
                Text("\(d.likedTracks.count) songs, \(d.likedAlbums.count) albums, \(d.likedArtists.count) artists").foregroundColor(Theme.red)
            }
            Button("Forget what I've played") { taste.clearHistory() }
            Button(sure ? "Tap again to forget everything" : "Start over", role: .destructive) {
                if sure { taste.clearAll(); sure = false } else { sure = true }
            }
            if !hidden.isEmpty {
                Button("Show all hidden again") { taste.unhideAll() }
                ForEach(hidden, id: \.self) { h in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(h.name).foregroundColor(.white).lineLimit(1)
                            Text("Not interested (\(h.kind.rawValue))").font(.caption).foregroundColor(Theme.dim)
                        }
                        Spacer()
                        Button("Show again") { taste.unhide(h.kind, h.key) }.buttonStyle(.borderless)
                    }
                }
            }
        } header: {
            Text("Your taste")
        } footer: {
            Text(account.account != nil
                 ? "Music's rows follow what you play, heart and hide. Plays and hearted songs also sync with your ListenBrainz account; hidden items stay on this phone."
                 : "Music's rows follow what you play, heart and hide. It all stays on this phone. Long-press a song, album or artist to heart it or say Not interested.")
        }
    }
}
