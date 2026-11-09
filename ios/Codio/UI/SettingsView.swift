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

    private let langCodes = ["eng", "spa", "fre", "ger", "ita", "por", "nld", "pol", "rus", "tur", "ara", "hin", "jpn", "kor", "zho"]
    private let langNames = ["English", "Spanish", "French", "German", "Italian", "Portuguese", "Dutch", "Polish", "Russian", "Turkish", "Arabic", "Hindi", "Japanese", "Korean", "Chinese"]

    private var playback: [Choice] {
        let rates = [0, 10, 15, 25, 40, 60, 70, 80, 100, 150]
        return [
            Choice(kind: .autoPlay, label: "Smart auto-play", help: "Tap Play and Codio picks the best source, and moves on to the next one if a source fails.",
                   key: "enabled", dflt: true, options: [("On", true), ("Off", false)]),
            Choice(kind: .autoPlay, label: "Preferred quality", help: "The quality auto-play aims for.",
                   key: "preferredQuality", dflt: "1080p",
                   options: [("4K (2160p)", "2160p"), ("1440p", "1440p"), ("1080p", "1080p"), ("720p", "720p"), ("480p", "480p")]),
            Choice(kind: .autoPlay, label: "If that quality isn't available", help: "What auto-play falls back to.",
                   key: "fallbackMode", dflt: "closest", options: [("Closest quality", "closest"), ("Lower quality only", "lower"), ("Don't play", "exact")]),
            Choice(kind: .autoPlay, label: "Skip sources above", help: "Sources above this bitrate are tried last, so streaming doesn't stall. Lower it on mobile data.",
                   key: "maxBitrateMbps", dflt: 70, options: rates.map { ($0 == 0 ? "No limit" : "\($0) Mbit/s", $0) }),
            Choice(kind: .autoPlay, label: "Prefer instant sources", help: "Among sources of the same quality, start with ones your debrid service already has ready.",
                   key: "preferCached", dflt: true, options: [("On", true), ("Off", false)]),
            Choice(kind: .profile, label: "Next episode", help: "At the end of an episode, play the next one automatically.",
                   key: "bingeWatching", dflt: true, options: [("Plays automatically", true), ("Off", false)]),
            Choice(kind: .app, label: "Skip Intro button", help: "Skip Intro / Recap and Next Episode buttons, with times from IntroDB (not every show has them).",
                   key: "skip_buttons", dflt: true, options: [("On", true), ("Off", false)]),
        ]
    }

    private var languages: [Choice] {
        [
            Choice(kind: .app, label: "Subtitles", help: "Off: subtitles only appear when you turn them on in the player.",
                   key: "subs_auto", dflt: false, options: [("Off until I turn them on", false), ("Automatic", true)]),
            Choice(kind: .profile, label: "Audio language", help: "Auto-play and the player prefer an audio track in this language.",
                   key: "audioLanguage", dflt: "eng", options: Array(zip(langNames, langCodes)).map { ($0.0, $0.1 as Any) }),
            Choice(kind: .profile, label: "Subtitle language", help: "The language picked when you turn subtitles on.",
                   key: "subtitlesLanguage", dflt: NSNull(), options: [("None", NSNull() as Any)] + Array(zip(langNames, langCodes)).map { ($0.0, $0.1 as Any) }),
        ]
    }

    var body: some View {
        Form {
            Section("Playback") { ForEach(playback) { row($0) } }
            Section("Languages & subtitles") { ForEach(languages) { row($0) } }
            Section("Account") {
                if let e = store.signedInEmail {
                    Text(e).foregroundColor(.white)
                    Button("Sign out", role: .destructive) { engine.ctx("Logout") }
                } else {
                    Text("Not signed in").foregroundColor(Theme.dim)
                    Button("Sign in") { showSignIn = true }
                }
            }
            Section {
                ForEach(Array(store.addonDescriptors.enumerated()), id: \.offset) { _, d in
                    addonRow(d)
                }
                Button("Add an addon") { showAddAddon = true }
            } header: {
                Text("Addons")
            } footer: {
                Text("Codio \(Engine.version) for iPhone").frame(maxWidth: .infinity).padding(.top, 20)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.bg.ignoresSafeArea())
        .navigationTitle("Settings")
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
