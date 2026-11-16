import SwiftUI

private struct Choice: Identifiable {
    enum Kind { case autoPlay, profile, app }
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
            Choice(kind: .app, label: "New episode alerts", help: "Marks shows you're watching when a new episode is out, and sends a notification.",
                   key: NewEpisodes.pref, dflt: true, options: [("On", true), ("Off", false)]),
            Choice(kind: .app, label: "Mini player", help: "When you leave Codio during a video, it keeps playing in a small window over your other apps.",
                   key: "pip_auto", dflt: true, options: [("On", true), ("Off", false)]),
        ]
    }

    private var languages: [Choice] {
        [
            Choice(kind: .app, label: "Subtitles", help: "Off: subtitles only appear when you turn them on in the player. Automatic: picked by your subtitle language.",
                   key: "subs_auto", dflt: false, options: [("Off until I turn them on", false), ("Automatic", true)]),
            Choice(kind: .profile, label: "Audio language", help: "Auto-play and the player prefer an audio track in this language.",
                   key: "audioLanguage", dflt: "eng", options: Array(zip(langNames, langCodes)).map { ($0.0, $0.1 as Any) }),
            Choice(kind: .profile, label: "Subtitle language", help: "The language picked when you turn subtitles on.",
                   key: "subtitlesLanguage", dflt: NSNull(), options: [("None", NSNull() as Any)] + Array(zip(langNames, langCodes)).map { ($0.0, $0.1 as Any) }),
            Choice(kind: .profile, label: "Subtitle size", help: "How big subtitles are drawn.",
                   key: "subtitlesSize", dflt: 100, options: [75, 100, 125, 150, 175, 200, 250].map { ("\($0)%", $0 as Any) }),
        ]
    }

    private let askProfile = Choice(kind: .app, label: "When Codio opens", help: "Codio opens in the profile you used last, or asks who's watching every time.",
                                    key: "ask_profile", dflt: false, options: [("Open the last profile", false), ("Ask who's watching", true)])
    private let downloadOver = Choice(kind: .app, label: "Download over",
                                      help: "Big video files can use a lot of mobile data. With Wi-Fi only, downloads wait until you're on Wi-Fi.",
                                      key: "dl_wifi_only", dflt: true, options: [("Wi-Fi only", true), ("Wi-Fi and mobile data", false)])
    @State private var confirmSignOut = false
    @State private var showKey = false
    @State private var keyDraft = ""
    @State private var removing: RemoveAsk?

    private struct RemoveAsk: Identifiable {
        let id: String
        let name: String
        let descriptor: JSON
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageTitle(text: "Settings")
                SectionTitle(text: "Playback")
                ForEach(playback) { choiceRow($0) }
                SectionTitle(text: "Downloads")
                choiceRow(downloadOver)
                SectionTitle(text: "Languages & subtitles")
                ForEach(languages) { choiceRow($0) }
                SectionTitle(text: "Account")
                account
                SectionTitle(text: "Addons")
                ForEach(Array(store.addonDescriptors.enumerated()), id: \.offset) { _, d in addonRow(d) }
                Button { showAddAddon = true } label: {
                    SettingRow(label: "Add an addon", value: "+", help: "Paste the addon's link (it ends in manifest.json).")
                }
                SectionTitle(text: "About")
                Button {
                    Task {
                        Updater.shared.dismissed = false
                        await Updater.shared.forceCheck()
                        if Updater.shared.available != nil { Updater.shared.install() } else { engine.show(toast: "Codio is up to date") }
                    }
                } label: {
                    SettingRow(label: "Updates", value: "Check now", help: "Codio checks for a newer version when it starts and offers to install it.")
                }
                Text("Codio \(Engine.version) for iPhone").font(Theme.font(12)).foregroundColor(Theme.faint)
                    .frame(maxWidth: .infinity).padding(.top, 24)
            }
            .buttonStyle(.plain)
            .padding(.bottom, 24)
        }
        .background(Theme.bg.ignoresSafeArea())
        .navigationBarHidden(true)
        .onAppear { if keyStatus == .empty, !tmdbKey.isEmpty { scheduleKeyCheck(now: true) } }
        .alert("Sign in", isPresented: $showSignIn) {
            TextField("Email", text: $email).textInputAutocapitalization(.never).keyboardType(.emailAddress)
            SecureField("Password", text: $password)
            Button("Sign in") {
                let em = email.trimmingCharacters(in: .whitespaces)
                guard !em.isEmpty, !password.isEmpty else { return }
                engine.ctx("Authenticate", ["type": "Login", "email": em, "password": password] as JSON)
                password = ""
                engine.show(toast: "Signing in\u{2026}")
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Sign out?", isPresented: $confirmSignOut) {
            Button("Sign out", role: .destructive) { engine.ctx("Logout") }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your downloads stay on this phone.")
        }
        .alert("TMDB API key", isPresented: $showKey) {
            TextField("32 letters and numbers", text: $keyDraft).textInputAutocapitalization(.never).autocorrectionDisabled()
            Button("Save") {
                tmdbKey = keyDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                scheduleKeyCheck(now: true)
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert("Addon link", isPresented: $showAddAddon) {
            TextField("https://\u{2026}/manifest.json", text: $addonLink).textInputAutocapitalization(.never).keyboardType(.URL)
            Button("Install") { Task { await installAddon() } }
            Button("Cancel", role: .cancel) {}
        }
        .alert(item: $removing) { r in
            Alert(title: Text("Remove \(r.name)?"),
                  primaryButton: .destructive(Text("Remove")) { engine.ctx("UninstallAddon", r.descriptor) },
                  secondaryButton: .cancel())
        }
    }

    @ViewBuilder private var account: some View {
        let e = store.signedInEmail
        Button {
            if e == nil { showSignIn = true } else { confirmSignOut = true }
        } label: {
            SettingRow(label: e ?? "Not signed in", value: e == nil ? "Sign in" : "Sign out",
                       help: "Signed in, your library, addons and settings are kept in your account and synced to your other devices.")
        }
        Button {
            if e == nil { engine.show(toast: "Sign in first, in Settings.") } else { Profiles.shared.showPicker = true }
        } label: {
            SettingRow(label: "Who's watching", value: Profiles.shared.current?.name ?? "Sign in first",
                       help: "Each profile has its own library, Continue Watching and addons.")
        }
        choiceRow(askProfile)
        Button {
            keyDraft = tmdbKey
            showKey = true
        } label: {
            SettingRow(label: "Cast photos (TMDB key)", value: keyLabel,
                       help: "Actors' photos and characters come from TMDB with your own free API key (themoviedb.org, Settings, API). It's saved with your account, so your other devices get it. Without a key, the cast shows names only.")
        }
    }

    private var keyLabel: String {
        switch keyStatus {
        case .empty: return "Add key"
        case .checking: return "Checking\u{2026}"
        case .ok: return "Key saved"
        case .bad: return "Key not valid"
        case .offline: return tmdbKey.isEmpty ? "Add key" : "Key saved"
        }
    }

    // MARK: - TMDB key check

    enum KeyStatus: Equatable { case empty, checking, ok, bad(String), offline }

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
                if let auth = jstr(store.profile["auth"] as? JSON, "key") { await KeySync.push(k, authKey: auth) }
                Recommender.shared.invalidate()
                await Recommender.shared.refresh(store)
            } else if case .bad(let why) = result {
                UserDefaults.standard.removeObject(forKey: "tmdb_key")
                engine.show(toast: why)
            } else if result == .offline {
                // Can't check right now: keep the key, it's tried again next time Settings opens.
                UserDefaults.standard.set(k, forKey: "tmdb_key")
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
        }
    }

    /// A setting with a fixed list of values: tap it to pick one.
    private func choiceRow(_ c: Choice) -> some View {
        let cur = current(c)
        let index = c.options.firstIndex { same($0.1, cur) } ?? 0
        return Menu {
            Picker(c.label, selection: Binding(get: { index }, set: { set(c, c.options[$0].1) })) {
                ForEach(c.options.indices, id: \.self) { i in Text(c.options[i].0).tag(i) }
            }
        } label: {
            SettingRow(label: c.label, value: c.options[index].0, help: c.help)
        }
    }

    private func addonRow(_ d: JSON) -> some View {
        let manifest = d["manifest"] as? JSON
        let protected = jbool(d["flags"] as? JSON, "protected") ?? false
        let name = jstr(manifest, "name") ?? "Addon"
        var desc = jstr(manifest, "description") ?? ""
        if desc.count > 120 { desc = String(desc.prefix(117)) + "\u{2026}" }
        return Button {
            if !protected { removing = RemoveAsk(id: jstr(d, "transportUrl") ?? name, name: name, descriptor: d) }
        } label: {
            SettingRow(label: name, value: protected ? "Built in" : "Remove", help: desc.isEmpty ? nil : desc)
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

/// One row of Settings: its name, what it's set to on the right, and a line of help underneath.
/// The same card row as on Codio for Android.
struct SettingRow: View {
    let label: String
    let value: String
    var help: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(label).font(Theme.font(15, .bold)).foregroundColor(.white)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(value).font(Theme.font(14, .bold)).foregroundColor(Theme.dim).multilineTextAlignment(.trailing)
            }
            if let h = help {
                Text(h).font(Theme.font(12)).foregroundColor(Theme.dim)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .multilineTextAlignment(.leading)
        .padding(.horizontal, 14).padding(.vertical, 11)
        .background(RoundedRectangle(cornerRadius: 10).fill(Theme.card))
        .padding(.horizontal, 12).padding(.vertical, 3)
        .contentShape(Rectangle())
    }
}
