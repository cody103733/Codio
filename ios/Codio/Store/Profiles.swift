import SwiftUI
import Security

/// Netflix-style profiles. Each profile is its own Stremio account, so its Continue Watching,
/// Library and addons sync to the TV, phone and PC just like the main account does.
@MainActor
final class Profiles: ObservableObject {
    static let shared = Profiles()

    struct Profile: Codable, Identifiable, Equatable {
        var email: String
        var name: String
        var color: Int
        var token: String?
        var id: String { email.lowercased() }
    }

    static let colors: [Color] = [
        Color(red: 0.90, green: 0.04, blue: 0.08), Color(red: 0.13, green: 0.47, blue: 0.95),
        Color(red: 0.98, green: 0.73, blue: 0.10), Color(red: 0.16, green: 0.68, blue: 0.38),
        Color(red: 0.55, green: 0.30, blue: 0.85), Color(red: 0.95, green: 0.45, blue: 0.15),
    ]

    @Published private(set) var list: [Profile] = []
    @Published var showPicker = false
    @Published private(set) var busy: String?   // "Creating Kids…", "Switching to Kids…"
    @Published var error: String?
    weak var engine: Engine?
    private var askedAtLaunch = false

    init() {
        if let d = UserDefaults.standard.data(forKey: "profiles_v1"),
           let l = try? JSONDecoder().decode([Profile].self, from: d) { list = l }
    }

    private func save() {
        if let d = try? JSONEncoder().encode(list) { UserDefaults.standard.set(d, forKey: "profiles_v1") }
    }

    private var store: Store? { engine?.store }
    var currentEmail: String? { store?.signedInEmail?.lowercased() }
    var current: Profile? { list.first { $0.id == currentEmail } }

    private var currentToken: String? { jstr(store?.profile["auth"] as? JSON, "key") }

    /// Called whenever the engine's profile changes: remembers the signed-in account and its sign-in token.
    func profileChanged() {
        guard let email = store?.signedInEmail else { return }
        let token = currentToken
        if let i = list.firstIndex(where: { $0.id == email.lowercased() }) {
            if let t = token, list[i].token != t { list[i].token = t; save() }
        } else if busy == nil {
            list.insert(Profile(email: email, name: Profiles.firstName(email), color: 0, token: token), at: 0)
            save()
        }
        Ratings.shared.load(for: email)
        KeySync.sync(authKey: token)
        if !askedAtLaunch {
            askedAtLaunch = true
            if list.count > 1, UserDefaults.standard.object(forKey: "ask_profile") as? Bool ?? true { showPicker = true }
        }
    }

    static func firstName(_ email: String) -> String {
        let local = email.split(separator: "@").first.map(String.init) ?? email
        let first = local.split(whereSeparator: { ".+_-0123456789".contains($0) }).first.map(String.init) ?? local
        return first.prefix(1).uppercased() + first.dropFirst()
    }

    // MARK: - Switching

    func select(_ p: Profile) async {
        guard let engine = engine, busy == nil else { return }
        if p.id == currentEmail { showPicker = false; return }
        busy = "Switching to \(p.name)…"
        defer { busy = nil }
        if let t = p.token {
            engine.ctx("Authenticate", ["type": "LoginWithToken", "token": t] as JSON)
            if await waitFor(p.email) { showPicker = false; return }
        }
        if let pw = Keychain.get(p.email) {
            engine.ctx("Authenticate", ["type": "Login", "email": p.email, "password": pw] as JSON)
            if await waitFor(p.email) { showPicker = false; return }
        }
        error = "Couldn't switch to \(p.name). Sign in to that account once in Settings, then try again."
    }

    /// Waits until the engine is signed in as this email (up to 20 seconds).
    private func waitFor(_ email: String) async -> Bool {
        for _ in 0..<40 {
            try? await Task.sleep(nanoseconds: 500_000_000)
            engine?.readStorage()
            if currentEmail == email.lowercased() { return true }
        }
        return false
    }

    // MARK: - Adding

    /// Makes a Stremio account for the new profile and copies the current profile's addons into it.
    func add(name: String) async {
        guard let engine = engine, let store = store, busy == nil, let base = store.signedInEmail else {
            error = "Sign in to your Stremio account first (Settings, Account)."
            return
        }
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        busy = "Creating \(clean)…"
        defer { busy = nil }
        let addons = store.addonDescriptors.filter { !(jbool($0["flags"] as? JSON, "protected") ?? false) }
        let email = Profiles.aliasEmail(base, clean)
        let password = Profiles.randomPassword()
        Keychain.set(password, for: email)
        list.append(Profile(email: email, name: clean, color: list.count % Profiles.colors.count, token: nil))
        save()
        engine.ctx("Authenticate", ["type": "Register", "email": email, "password": password,
                                    "gdpr_consent": ["tos": true, "privacy": true, "marketing": false, "from": "web"]] as JSON)
        guard await waitFor(email) else {
            list.removeAll { $0.id == email.lowercased() }
            save()
            error = "Stremio didn't create the account. Check your internet and try again."
            return
        }
        // Copy addons over (the new account starts with Stremio's defaults).
        busy = "Copying your addons…"
        for d in addons where !store.isInstalled(jstr(d, "transportUrl") ?? "") {
            engine.ctx("InstallAddon", d)
            try? await Task.sleep(nanoseconds: 400_000_000)
        }
        engine.readStorage(after: 1.5)
        profileChanged()
        showPicker = false
        engine.show(toast: "\(clean) is ready")
    }

    func remove(_ p: Profile) {
        guard p.id != currentEmail else { error = "Switch to another profile before removing this one."; return }
        list.removeAll { $0.id == p.id }
        Keychain.remove(p.email)
        save()
    }

    func recolor(_ p: Profile) {
        guard let i = list.firstIndex(of: p) else { return }
        list[i].color = (list[i].color + 1) % Profiles.colors.count
        save()
    }

    /// name+kids1234@gmail.com still lands in the main inbox.
    static func aliasEmail(_ base: String, _ name: String) -> String {
        let parts = base.split(separator: "@", maxSplits: 1).map(String.init)
        let slug = name.lowercased().filter { $0.isLetter || $0.isNumber }.prefix(12)
        let tag = (slug.isEmpty ? "profile" : String(slug)) + String(Int.random(in: 1000...9999))
        let local = parts[0].split(separator: "+").first.map(String.init) ?? parts[0]
        return local + "+" + tag + "@" + (parts.count > 1 ? parts[1] : "gmail.com")
    }

    static func randomPassword() -> String {
        let chars = Array("abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        return String((0..<20).map { _ in chars[Int.random(in: 0..<chars.count)] })
    }
}

/// Profile passwords live in the iPhone's keychain, never in plain settings.
enum Keychain {
    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.codio.profiles",
         kSecAttrAccount as String: account.lowercased()]
    }
    static func set(_ value: String, for account: String) {
        SecItemDelete(query(account) as CFDictionary)
        var q = query(account)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(q as CFDictionary, nil)
    }
    static func get(_ account: String) -> String? {
        var q = query(account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }
    static func remove(_ account: String) { SecItemDelete(query(account) as CFDictionary) }
}

/// The coloured square with the profile's first letter.
struct ProfileAvatar: View {
    let profile: Profiles.Profile
    var size: CGFloat = 96
    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.08)
            .fill(Profiles.colors[profile.color % Profiles.colors.count])
            .frame(width: size, height: size)
            .overlay(Text(String(profile.name.prefix(1)).uppercased())
                .font(.system(size: size * 0.48, weight: .heavy)).foregroundColor(.white))
    }
}

/// "Who's watching?"
struct ProfilePicker: View {
    @ObservedObject var profiles = Profiles.shared
    @State private var editing = false
    @State private var adding = false
    @State private var newName = ""

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VStack(spacing: 28) {
                HStack {
                    Spacer()
                    BrandWordmark(height: 28)
                    Spacer()
                }
                .overlay(alignment: .trailing) {
                    Button(editing ? "Done" : "Edit") { editing.toggle() }
                        .font(.subheadline.weight(.semibold)).foregroundColor(.white).padding(.trailing, 20)
                }
                Text(editing ? "Manage Profiles" : "Who's watching?").font(.title2.weight(.semibold)).foregroundColor(.white)
                LazyVGrid(columns: [GridItem(.fixed(120), spacing: 24), GridItem(.fixed(120), spacing: 24)], spacing: 22) {
                    ForEach(profiles.list) { p in
                        Button {
                            if editing { profiles.recolor(p) } else { Task { await profiles.select(p) } }
                        } label: {
                            VStack(spacing: 8) {
                                ProfileAvatar(profile: p, size: 104)
                                    .overlay(RoundedRectangle(cornerRadius: 8)
                                        .stroke(Color.white, lineWidth: p.id == profiles.currentEmail ? 3 : 0))
                                    .overlay(alignment: .topTrailing) {
                                        if editing, p.id != profiles.currentEmail {
                                            Button { profiles.remove(p) } label: {
                                                Image(systemName: "minus.circle.fill").font(.title2)
                                                    .foregroundStyle(Color.white, Theme.red)
                                            }
                                            .offset(x: 8, y: -8)
                                        }
                                    }
                                Text(p.name).font(.subheadline).foregroundColor(Theme.dim).lineLimit(1)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    if profiles.list.count < 5 {
                        Button { newName = ""; adding = true } label: {
                            VStack(spacing: 8) {
                                RoundedRectangle(cornerRadius: 8).stroke(Color(white: 0.35), lineWidth: 2)
                                    .frame(width: 104, height: 104)
                                    .overlay(Image(systemName: "plus").font(.system(size: 38, weight: .light)).foregroundColor(.white))
                                Text("Add Profile").font(.subheadline).foregroundColor(Theme.dim)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                if editing {
                    Text("Tap a profile to change its colour.").font(.caption).foregroundColor(Theme.dim)
                }
                if let b = profiles.busy {
                    HStack(spacing: 10) { ProgressView().tint(Theme.red); Text(b).foregroundColor(.white) }
                }
                Spacer()
                if profiles.current != nil {
                    Button("Close") { profiles.showPicker = false }.foregroundColor(Theme.dim).padding(.bottom, 20)
                }
            }
            .padding(.top, 30)
        }
        .preferredColorScheme(.dark)
        .disabled(profiles.busy != nil)
        .alert("Add Profile", isPresented: $adding) {
            TextField("Name", text: $newName)
            Button("Create") { let n = newName; Task { await profiles.add(name: n) } }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Codio makes a free Stremio account for this profile and copies your addons into it, so it syncs to your other devices.")
        }
        .alert("Profiles", isPresented: Binding(get: { profiles.error != nil }, set: { if !$0 { profiles.error = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(profiles.error ?? "")
        }
    }
}
