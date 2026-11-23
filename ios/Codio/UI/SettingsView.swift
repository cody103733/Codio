import SwiftUI

private struct Choice: Identifiable {
    enum Kind { case autoPlay, profile, app, appProfile }
    let kind: Kind
    let label: String
    let help: String
    let key: String
    let dflt: Any
    let options: [(String, Any)]
    var id: String { key }
}

private func same(_ a: Any, _ b: Any) -> Bool {
    if a is NSNull || b is NSNull { return a is NSNull && b is NSNull }
    if let x = a as? NSNumber, let y = b as? NSNumber { return x == y }
    return "\(a)" == "\(b)"
}

struct SettingsView: View {
    @EnvironmentObject var engine: Engine
    @EnvironmentObject var store: Store
    @State private var showSignIn = false
    @State private var showAddAddon = false
    @State private var email = ""
    @State private var password = ""
    @State private var addonLink = ""
    @State private var tmdbKey = UserDefaults.standard.string(forKey: "tmdb_key") ?? ""
    @State private var keyStatus: KeyStatus = .empty
    @State private var keyTask: Task<Void, Never>?
    @ObservedObject private var vault = Vault.shared
    @State private var passcodeAsk: PasscodeAsk?
    @State private var passcode = ""
    @State private var passcodeWorking = false

    /// Which passcode question is open.
    enum PasscodeAsk: Identifiable { case turnOn, change, startOver; var id: Int { hashValue } }

    private let langCodes = ["eng", "spa", "fre", "ger", "ita", "por", "pob", "nld", "swe", "pol", "rus", "tur", "ara", "hin", "jpn", "kor", "zho"]
    private let langNames = ["English", "Spanish", "French", "German", "Italian", "Portuguese", "Portuguese (Brazil)", "Dutch", "Swedish", "Polish", "Russian", "Turkish", "Arabic", "Hindi", "Japanese", "Korean", "Chinese"]

    private var playback: [Choice] {
        let rates = [0, 10, 15, 25, 40, 60, 70, 80, 100, 150]
        return [
            Choice(kind: .autoPlay, label: "Smart auto-play", help: "Play picks the best source for you, and moves on to the next one if a source fails.",
                   key: "enabled", dflt: true, options: [("On", true), ("Off", false)]),
            Choice(kind: .autoPlay, label: "Preferred quality", help: "The quality auto-play aims for.",
                   key: "preferredQuality", dflt: "1080p",
                   options: [("4K (2160p)", "2160p"), ("1440p", "1440p"), ("1080p", "1080p"), ("720p", "720p"), ("480p", "480p")]),
            Choice(kind: .autoPlay, label: "If that quality isn't available", help: "What auto-play falls back to.",
                   key: "fallbackMode", dflt: "closest", options: [("Closest quality", "closest"), ("Lower quality only", "lower"), ("Don't play", "exact")]),
            Choice(kind: .autoPlay, label: "Skip sources above", help: "Sources above this bitrate are tried last, so playback doesn't stall. Pick what your connection can carry; lower it on mobile data.",
                   key: "maxBitrateMbps", dflt: 70, options: rates.map { ($0 == 0 ? "No limit" : "\($0) Mbit/s", $0) }),
            Choice(kind: .autoPlay, label: "Prefer instant sources", help: "Among sources of the same quality, start with ones your debrid service already has ready.",
                   key: "preferCached", dflt: true, options: [("On", true), ("Off", false)]),
            Choice(kind: .profile, label: "Next episode", help: "At the end of an episode, play the next one automatically.",
                   key: "bingeWatching", dflt: true, options: [("Plays automatically", true), ("Off", false)]),
            Choice(kind: .app, label: "Skip Intro button", help: "Shows Skip Intro, Skip Recap and Skip Credits in the player. Times come from IntroDB, a community database, so not every show has them.",
                   key: "skip_buttons", dflt: true, options: [("On", true), ("Off", false)]),
            Choice(kind: .app, label: "Skip automatically", help: "Jumps past intros and recaps by itself when their times are known. With credits too, the next episode starts as the credits begin.",
                   key: "auto_skip", dflt: 0, options: [("Off", 0), ("Intros and recaps", 1), ("Intros, recaps and credits", 2)]),
            Choice(kind: .profile, label: "Up Next", help: "Shows the next episode with a countdown as the credits start. On episodes whose credits aren't timed, it appears this long before the end.",
                   key: "nextVideoNotificationDuration", dflt: 35000,
                   options: [0, 10000, 15000, 20000, 30000, 35000, 45000, 60000, 90000].map { ($0 == 0 ? "Don't show" : "\($0 / 1000) seconds before the end", $0 as Any) }),
            Choice(kind: .app, label: "Still watching?", help: "When on, after this many episodes in a row with nobody touching the phone, Codio stops and asks before playing the next one, so a show doesn't play all night.",
                   key: "still_watching", dflt: 0, options: [("Off", 0), ("After 2 episodes", 2), ("After 3 episodes", 3), ("After 5 episodes", 5)]),
            Choice(kind: .app, label: "New episode alerts", help: "Marks shows you're watching when a new episode is out, and sends a notification.",
                   key: NewEpisodes.pref, dflt: true, options: [("On", true), ("Off", false)]),
            Choice(kind: .app, label: "Who's watching?", help: "Codio opens in the profile you used last. Turn this on to be asked who's watching every time it opens.",
                   key: "ask_profile", dflt: false, options: [("Ask when Codio opens", true), ("Open the last profile", false)]),
            Choice(kind: .app, label: "Mini player", help: "When you leave Codio during a video, it keeps playing in a small window over your other apps.",
                   key: "pip_auto", dflt: true, options: [("On", true), ("Off", false)]),
        ]
    }

    private var languages: [Choice] {
        [
            Choice(kind: .appProfile, label: "Subtitles", help: "Off: subtitles only appear when you turn them on in the player. Automatic: picked by your subtitle language.",
                   key: "subs_auto", dflt: false, options: [("Off until I turn them on", false), ("Automatic", true)]),
            Choice(kind: .profile, label: "Audio language", help: "Auto-play and the player prefer an audio track in this language.",
                   key: "audioLanguage", dflt: "eng", options: Array(zip(langNames, langCodes)).map { ($0.0, $0.1 as Any) }),
            Choice(kind: .profile, label: "Subtitle language", help: "The language picked when you turn subtitles on.",
                   key: "subtitlesLanguage", dflt: NSNull(), options: [("None", NSNull() as Any)] + Array(zip(langNames, langCodes)).map { ($0.0, $0.1 as Any) }),
            Choice(kind: .profile, label: "Subtitle size", help: "How big subtitles are drawn.",
                   key: "subtitlesSize", dflt: 100, options: [75, 100, 125, 150, 175, 200, 250].map { ("\($0)%", $0 as Any) }),
            Choice(kind: .profile, label: "Subtitle colour", help: "The colour of the words.",
                   key: "subtitlesTextColor", dflt: "#FFFFFFFF",
                   options: [("White", "#FFFFFFFF"), ("Yellow", "#FFFF00FF"), ("Cyan", "#00FFFFFF"), ("Green", "#00FF00FF")]),
            Choice(kind: .profile, label: "Subtitle background", help: "A box behind the words makes them easier to read on bright scenes.",
                   key: "subtitlesBackgroundColor", dflt: "#00000000",
                   options: [("None", "#00000000"), ("Shaded box", "#00000080"), ("Black box", "#000000FF")]),
        ]
    }

    var body: some View {
        Form {
            Section("You") {
                NavigationLink { StatsView() } label: {
                    HStack {
                        Text("Your stats")
                        Spacer()
                        Text("\(statsHours(WatchStats.summary(store.library, profile: store.signedInEmail, year: WatchStats.thisYear).ms)) h in \(String(WatchStats.thisYear))")
                            .foregroundColor(Theme.red)
                    }
                }
            }
            Section("Playback") { ForEach(playback) { row($0) } }
            Section("Downloads") {
                ForEach([Choice(kind: .app, label: "Download over",
                                help: "Big video files can use a lot of mobile data. With Wi-Fi only, downloads wait until you're on Wi-Fi.",
                                key: "dl_wifi_only", dflt: true, options: [("Wi-Fi only", true), ("Wi-Fi and mobile data", false)])]) { row($0) }
            }
            Section("Languages & subtitles") { ForEach(languages) { row($0) } }
            Section {
                NavigationLink { MusicSettingsView() } label: {
                    HStack {
                        Text("Music addons, account and taste")
                        Spacer()
                        Text("\(MusicAddons.shared.list.count)").foregroundColor(Theme.red)
                    }
                }
            } header: {
                Text("Music")
            }
            Section {
                HStack {
                    SecureField("TMDB API key", text: $tmdbKey)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                    Button("Paste") { if let v = UIPasteboard.general.string { tmdbKey = v } }
                        .buttonStyle(.borderless)
                }
                .onChange(of: tmdbKey) { _ in scheduleKeyCheck() }
                keyStatusRow
                if tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Link("Get a key", destination: URL(string: "https://www.themoviedb.org/settings/api")!)
                }
            } header: {
                Text("Cast, actors & Top Picks")
            } footer: {
                Text("Paste the free API Key from themoviedb.org (Settings, API). Codio checks it right away. With a sync passcode (below), your other devices get it too.\n\nThis product uses the TMDB API but is not endorsed or certified by TMDB.")
            }
            Section {
                Button("Check for updates") {
                    Task {
                        Updater.shared.dismissed = false
                        await Updater.shared.forceCheck()
                        if Updater.shared.available != nil { Updater.shared.install() } else { engine.show(toast: "Codio is up to date") }
                    }
                }
            } header: {
                Text("Updates")
            } footer: {
                Text("New versions install through SideStore and keep your data.")
            }
            Section("Account") {
                if let e = store.signedInEmail {
                    Text(e).foregroundColor(.white)
                    Button("Profiles") { Profiles.shared.showPicker = true }
                    Button("Sign out", role: .destructive) { engine.ctx("Logout") }
                } else {
                    Text("Not signed in").foregroundColor(Theme.dim)
                    Button("Sign in") { showSignIn = true }
                }
            }
            syncSection
            Section {
                ForEach(Array(store.addonDescriptors.enumerated()), id: \.offset) { _, d in
                    addonRow(d)
                }
                Button("Add an addon") { showAddAddon = true }
            } header: {
                Text("Addons")
            }
            Section {
                Link("Licenses and notices", destination: URL(string: "https://github.com/cody103733/Codio/blob/main/NOTICE.md")!)
            } header: {
                Text("Legal")
            } footer: {
                VStack(spacing: 0) {
                    Text("Codio is free software under the GNU GPL version 2, based on Stremio Web (© Smart Code OOD). It is not affiliated with or endorsed by Stremio, TMDB, YouTube, Apple, Last.fm, MusicBrainz or any addon.\n\nCodio hosts no videos or music. Everything plays from addons you add, and you are responsible for them and for following the law where you live. Provided as is, with no warranty.\n\nSource, licenses, notices and privacy: github.com/cody103733/Codio")
                    Text("Codio \(Engine.version) for iPhone").frame(maxWidth: .infinity).padding(.top, 20)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle("Settings")
        .profileToolbar()
        .onAppear {
            if keyStatus == .empty, !tmdbKey.isEmpty { scheduleKeyCheck(now: true) }
            vault.maybeSync()
        }
        .onReceive(vault.$lastSync) { _ in
            // A key that came from another device through the sync passcode.
            let k = UserDefaults.standard.string(forKey: "tmdb_key") ?? ""
            if !k.isEmpty, k != tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines) { tmdbKey = k }
        }
        .alert(passcodeTitle, isPresented: Binding(get: { passcodeAsk != nil }, set: { if !$0 { passcodeAsk = nil } })) {
            SecureField("At least \(Vault.minPasscode) characters", text: $passcode)
            Button(passcodeAsk == .change ? "Change" : passcodeAsk == .startOver ? "Start over" : "Unlock") { runPasscode() }
            Button("Cancel", role: .cancel) { passcode = "" }
        } message: {
            Text(passcodeMessage)
        }
        .alert("Sign in", isPresented: $showSignIn) {
            TextField("Email", text: $email).textInputAutocapitalization(.never).keyboardType(.emailAddress)
            SecureField("Password", text: $password)
            Button("Sign in") {
                let em = email.trimmingCharacters(in: .whitespaces)
                guard !em.isEmpty, !password.isEmpty else { return }
                engine.ctx("Authenticate", ["type": "Login", "email": em, "password": password] as JSON)
                password = ""
                engine.show(toast: "Signing in…")
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your Stremio account (addons and library sync).")
        }
        .alert("Add an addon", isPresented: $showAddAddon) {
            TextField("Addon link", text: $addonLink).textInputAutocapitalization(.never).keyboardType(.URL)
            Button("Install") { Task { await installAddon() } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Paste the addon's link (it ends in manifest.json).")
        }
    }

    // MARK: - Sync passcode

    @ViewBuilder private var syncSection: some View {
        Section {
            HStack {
                Text("Sync passcode")
                Spacer()
                if vault.busy || passcodeWorking { ProgressView().tint(Theme.red) }
                Text(vault.label).foregroundColor(Theme.red)
            }
            Text(vault.detail).foregroundColor(vault.problem == .locked ? Theme.red : Theme.dim).font(.subheadline)
            if vault.signedIn {
                if !vault.on {
                    Button("Set sync passcode") { passcode = ""; passcodeAsk = .turnOn }
                } else if vault.problem == .locked {
                    Button("Enter the new passcode") { passcode = ""; passcodeAsk = .turnOn }
                    Button("Start over with a new passcode") { passcode = ""; passcodeAsk = .startOver }
                } else {
                    Button("Sync now") { vault.maybeSync(force: true) }
                    Button("Change passcode") { passcode = ""; passcodeAsk = .change }
                }
                if vault.on {
                    Button("Turn off on this iPhone", role: .destructive) { vault.turnOff() }
                }
            }
        } header: {
            Text("Sync passcode")
        } footer: {
            Text(Vault.help)
        }
    }

    private var passcodeTitle: String {
        switch passcodeAsk {
        case .change: return "New sync passcode"
        case .startOver: return "Start over"
        default: return vault.problem == .locked ? "New sync passcode" : "Sync passcode"
        }
    }

    private var passcodeMessage: String {
        switch passcodeAsk {
        case .change: return "Your other devices will ask for the new one."
        case .startOver: return "Replaces the synced settings with this iPhone's, locked with the new passcode. Your other devices then ask for it."
        default: return vault.problem == .locked ? "The passcode was changed on another device." : "Enter the passcode you set on another device, or pick a new one. It can't be recovered, so remember it."
        }
    }

    private func runPasscode() {
        let p = passcode
        let ask = passcodeAsk
        passcode = ""
        guard !p.trimmingCharacters(in: .whitespaces).isEmpty, let ask = ask else { return }
        passcodeWorking = true
        engine.show(toast: ask == .change ? "Changing passcode…" : "Unlocking…")
        Task {
            do {
                switch ask {
                case .turnOn: try await vault.setPasscode(p)
                case .change: try await vault.changePasscode(p)
                case .startOver: try await vault.startOver(p)
                }
                engine.show(toast: ask == .turnOn ? "Sync is on" : ask == .change ? "Passcode changed" : "Started over with the new passcode")
            } catch let f as Vault.Failure {
                engine.show(toast: f.message)
            } catch {
                engine.show(toast: "Something went wrong. Try again.")
            }
            passcodeWorking = false
        }
    }

    // MARK: - TMDB key check

    enum KeyStatus: Equatable { case empty, checking, ok, bad(String), offline }

    @ViewBuilder private var keyStatusRow: some View {
        switch keyStatus {
        case .empty:
            Label("No key yet", systemImage: "key").foregroundColor(Theme.dim)
        case .checking:
            HStack(spacing: 8) { ProgressView().tint(Theme.red); Text("Checking the key…").foregroundColor(Theme.dim) }
        case .ok:
            Label("Key works. Cast photos and Top Picks are on.", systemImage: "checkmark.circle.fill").foregroundColor(.green)
        case .bad(let why):
            Label(why, systemImage: "xmark.circle.fill").foregroundColor(Theme.red)
        case .offline:
            HStack {
                Label("Couldn't reach TMDB to check it", systemImage: "wifi.exclamationmark").foregroundColor(.orange)
                Spacer()
                Button("Retry") { scheduleKeyCheck(now: true) }.buttonStyle(.borderless)
            }
        }
    }

    private func scheduleKeyCheck(now: Bool = false) {
        keyTask?.cancel()
        let k = tmdbKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !k.isEmpty else {
            UserDefaults.standard.removeObject(forKey: "tmdb_key")
            keyStatus = .empty
            return
        }
        keyStatus = .checking
        keyTask = Task {
            if !now { try? await Task.sleep(nanoseconds: 600_000_000) }
            if Task.isCancelled { return }
            let result = await SettingsView.checkKey(k)
            if Task.isCancelled { return }
            keyStatus = result
            if result == .ok {
                UserDefaults.standard.set(k, forKey: "tmdb_key")
                vault.maybeSync()
                ForYou.shared.invalidate()
            } else if case .bad = result {
                UserDefaults.standard.removeObject(forKey: "tmdb_key")
            }
        }
    }

    /// Asks TMDB with the key: 200 = good, 401 = wrong key.
    static func checkKey(_ k: String) async -> KeyStatus {
        if k.count > 60 { return .bad("That's the long Read Access Token. Paste the shorter API Key instead.") }
        if k.count != 32 || !k.allSatisfy({ $0.isHexDigit }) { return .bad("That doesn't look like a TMDB API Key (32 letters and numbers).") }
        guard let u = URL(string: "https://api.themoviedb.org/3/configuration?api_key=" + k) else { return .bad("Invalid key") }
        do {
            let (_, r) = try await URLSession.shared.data(from: u)
            switch (r as? HTTPURLResponse)?.statusCode ?? 0 {
            case 200: return .ok
            case 401: return .bad("TMDB says this key isn't valid.")
            default: return .offline
            }
        } catch {
            return .offline
        }
    }

    private func current(_ c: Choice) -> Any {
        switch c.kind {
        case .autoPlay: return store.autoPlay[c.key] ?? c.dflt
        case .profile: return store.settings[c.key] ?? c.dflt
        case .app: return UserDefaults.standard.object(forKey: c.key) ?? c.dflt
        case .appProfile:
            return UserDefaults.standard.object(forKey: store.perProfile(c.key)) ?? UserDefaults.standard.object(forKey: c.key) ?? c.dflt
        }
    }

    private func set(_ c: Choice, _ v: Any) {
        switch c.kind {
        case .autoPlay:
            store.autoPlay[c.key] = v
            engine.saveAutoPlay()
        case .profile:
            guard store.profile["settings"] != nil else {
                engine.show(toast: "Still loading your settings, try again in a moment")
                return
            }
            store.setSetting(c.key, v)
            engine.ctx("UpdateSettings", store.settings)
        case .app:
            UserDefaults.standard.set(v is NSNull ? nil : v, forKey: c.key)
            store.objectWillChange.send()
            if c.key == NewEpisodes.pref { NewEpisodes.shared.settingChanged(store) }
        case .appProfile:
            UserDefaults.standard.set(v is NSNull ? nil : v, forKey: store.perProfile(c.key))
            store.objectWillChange.send()
        }
    }

    private func row(_ c: Choice) -> some View {
        let cur = current(c)
        let index = c.options.firstIndex { same($0.1, cur) } ?? 0
        return VStack(alignment: .leading, spacing: 4) {
            Picker(c.label, selection: Binding(get: { index }, set: { set(c, c.options[$0].1) })) {
                ForEach(c.options.indices, id: \.self) { i in Text(c.options[i].0).tag(i) }
            }
            .pickerStyle(.menu)
            Text(c.help).font(.caption).foregroundColor(Theme.dim)
        }
    }

    private func addonRow(_ d: JSON) -> some View {
        let manifest = d["manifest"] as? JSON
        let flags = d["flags"] as? JSON
        let protected = jbool(flags, "protected") ?? false
        return HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(jstr(manifest, "name") ?? "Addon").foregroundColor(.white)
                if let desc = jstr(manifest, "description") {
                    Text(desc).font(.caption).foregroundColor(Theme.dim).lineLimit(2)
                }
            }
            Spacer()
            if protected {
                Text("Built in").font(.caption).foregroundColor(Theme.dim)
            } else {
                Button("Remove", role: .destructive) { engine.ctx("UninstallAddon", d) }
                    .buttonStyle(.borderless)
            }
        }
    }

    private func installAddon() async {
        var link = addonLink.trimmingCharacters(in: .whitespacesAndNewlines)
        addonLink = ""
        if link.hasPrefix("stremio://") { link = "https://" + link.dropFirst("stremio://".count) }
        guard !link.isEmpty else { return }
        engine.show(toast: "Checking the addon…")
        guard let manifest = try? await Net.json(link) as? JSON, jstr(manifest, "id") != nil, jstr(manifest, "name") != nil else {
            engine.show(toast: "That link isn't an addon (no manifest found)")
            return
        }
        if store.isInstalled(link) {
            engine.show(toast: "Already installed")
            return
        }
        if let bh = manifest["behaviorHints"] as? JSON, jbool(bh, "configurationRequired") == true {
            engine.show(toast: "This addon needs setting up on its website first")
            return
        }
        engine.ctx("InstallAddon", ["transportUrl": link, "manifest": manifest, "flags": ["official": false, "protected": false]] as JSON)
    }
}
