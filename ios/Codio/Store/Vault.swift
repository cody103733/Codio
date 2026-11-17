import Foundation

/// Sync passcode: the private settings (TMDB key, music addon links, ListenBrainz sign-in, MusicBrainz server) follow
/// you to every device signed in to the same Stremio account, locked with a passcode only you know. They're encrypted
/// here (PBKDF2-SHA256 600,000 rounds, then AES-256-GCM) and kept in a hidden library entry, "codio:vault", which Stremio
/// stores but can't open. Same format and rules as Windows (vault.ts) and Android (ui/Vault.java): see SYNC.md.
@MainActor
final class Vault: ObservableObject {
    static let shared = Vault()

    static let vaultId = "codio:vault"
    static let iterations = 600_000
    static let minPasscode = 6
    static let help = "One passcode unlocks your private settings on every device: the TMDB key, music addon links, your ListenBrainz sign-in and the MusicBrainz server. They're locked with the passcode before they go to your Stremio account, so nobody without it (Stremio included) can read them. Enter the same passcode once on each device."
    private static let api = "https://api.strem.io/api/"
    private static let pullEvery: TimeInterval = 10 * 60

    enum Problem: String { case locked, offline }
    struct Failure: Error { let message: String }

    @Published private(set) var on = false
    @Published private(set) var busy = false
    @Published private(set) var lastSync: Date?
    @Published private(set) var problem: Problem?
    @Published private(set) var signedIn = false

    private var authKey: String?
    private var accountId: String?
    private let keychainAccount = "sync-passcode-key"
    private let stateKey = "sync_state_v1"   // salt, iter, synced, lastSync, problem (the key itself is in the keychain)
    private var salt: String?
    private var iter = Vault.iterations
    private var synced: [String: [String: String]] = [:]
    private var timer: Timer?

    init() {
        if let d = UserDefaults.standard.dictionary(forKey: stateKey) {
            salt = d["salt"] as? String
            iter = d["iter"] as? Int ?? Vault.iterations
            synced = d["synced"] as? [String: [String: String]] ?? [:]
            if let t = d["lastSync"] as? Double { lastSync = Date(timeIntervalSince1970: t) }
            problem = (d["problem"] as? String).flatMap(Problem.init(rawValue:))
        }
        on = salt != nil && Keychain.get(keychainAccount) != nil
    }

    private func saveState() {
        var d: [String: Any] = ["iter": iter, "synced": synced]
        if let s = salt { d["salt"] = s }
        if let t = lastSync { d["lastSync"] = t.timeIntervalSince1970 }
        if let p = problem { d["problem"] = p.rawValue }
        UserDefaults.standard.set(d, forKey: stateKey)
        on = salt != nil && Keychain.get(keychainAccount) != nil
    }

    var label: String {
        if !signedIn { return "Sign in first" }
        if !on { return "Off" }
        if problem == .locked { return "Enter the new passcode" }
        if busy { return "Syncing…" }
        if problem == .offline { return "On · Offline" }
        return "On"
    }

    var detail: String {
        if !signedIn { return "Sign in to your Stremio account first. The locked settings are kept with it." }
        if !on { return "Off. Enter the passcode you set on another device, or pick a new one." }
        if problem == .locked { return "The sync passcode was changed on another device. Enter the new one." }
        if problem == .offline { return "Couldn't reach your Stremio account. Codio tries again on its own." }
        guard let t = lastSync else { return "On." }
        let m = Int((Date().timeIntervalSince(t) / 60).rounded())
        return "On. Last synced " + (m < 1 ? "just now" : m < 60 ? "\(m) min ago" : "\(Int((Double(m) / 60).rounded())) h ago") + "."
    }

    // MARK: Crypto (VaultCrypto.swift, checked against sync/vector.json in CI)

    nonisolated static func normalise(_ p: String) -> String { VaultCrypto.normalise(p) }
    nonisolated static func deriveKey(_ passcode: String, salt: Data, iterations: Int) -> Data? { VaultCrypto.deriveKey(passcode, salt: salt, iterations: iterations) }
    nonisolated static func seal(_ key: Data, salt: String, iter: Int, items: JSON) throws -> JSON { try VaultCrypto.seal(key, salt: salt, iter: iter, items: items) }
    nonisolated static func open(_ key: Data, _ box: JSON) -> JSON? { VaultCrypto.open(key, box) }

    // MARK: The hidden entry

    private nonisolated static func call(_ method: String, _ body: JSON) async throws -> JSON {
        guard let url = URL(string: api + method) else { throw Failure(message: "offline") }
        var req = URLRequest(url: url, timeoutInterval: 15)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (d, resp) = try await URLSession.shared.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200, let o = (try? JSONSerialization.jsonObject(with: d)) as? JSON else {
            throw Failure(message: "offline")
        }
        if let e = o["error"], !(e is NSNull) { throw Failure(message: "offline") }
        return o
    }

    /// The box saved with the account, or nil when there is none yet. Throws when the account can't be reached.
    private nonisolated static func readBox(_ auth: String) async throws -> JSON? {
        let r = try await call("datastoreGet", ["authKey": auth, "collection": "libraryItem", "ids": [vaultId], "all": false])
        guard let items = r["result"] as? [JSON], let it = items.first(where: { ($0["_id"] as? String) == vaultId }),
              let name = it["name"] as? String, !name.isEmpty,
              let box = parseJSON(name) as? JSON, box["salt"] is String, box["iv"] is String, box["data"] is String else { return nil }
        return box
    }

    private nonisolated static func hidden(_ id: String, _ name: String) -> JSON {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let now = f.string(from: Date())
        return [
            "_id": id, "name": name, "type": "other", "poster": NSNull(), "posterShape": "poster",
            "removed": true, "temp": true, "_ctime": now, "_mtime": now,
            "state": ["lastWatched": NSNull(), "timeWatched": 0, "timeOffset": 0, "overallTimeWatched": 0,
                      "timesWatched": 0, "flaggedWatched": 0, "duration": 0, "video_id": NSNull(),
                      "watched": NSNull(), "noNotif": true] as JSON,
            "behaviorHints": ["defaultVideoId": NSNull(), "featuredVideoId": NSNull(), "hasScheduledVideos": false] as JSON,
        ]
    }

    /// Saves the box, and empties the old unlocked TMDB entry so the key can't be read there any more.
    private nonisolated static func writeBox(_ auth: String, _ box: JSON) async throws {
        let text = String(data: try JSONSerialization.data(withJSONObject: box), encoding: .utf8) ?? ""
        _ = try await call("datastorePut", ["authKey": auth, "collection": "libraryItem",
                                            "changes": [hidden(vaultId, text), hidden(KeySync.itemId, "")]])
    }

    // MARK: What is synced, as it is on this phone

    static let names = ["tmdbKey", "musicAddons", "listenBrainz", "musicBrainzServer"]

    private func read(_ name: String) -> Any {
        switch name {
        case "tmdbKey":
            return (UserDefaults.standard.string(forKey: "tmdb_key") ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        case "musicAddons":
            return MusicAddons.shared.list.map { ["url": $0.url, "manifest": $0.manifest] as JSON }
        case "listenBrainz":
            if let a = MusicAccount.shared.account, let t = MusicAccount.shared.token { return ["token": t, "user": a.user] as JSON }
            return NSNull()
        case "musicBrainzServer":
            return (UserDefaults.standard.string(forKey: MB.baseKey) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        default:
            return NSNull()
        }
    }

    private static func addonList(_ v: Any?) -> [JSON] {
        ((v as? [Any]) ?? []).compactMap { $0 as? JSON }.filter { (($0["url"] as? String) ?? "").isEmpty == false && $0["manifest"] is JSON }
    }

    static func print(_ name: String, _ v: Any?) -> String {
        switch name {
        case "musicAddons": return addonList(v).compactMap { $0["url"] as? String }.joined(separator: "\n")
        case "listenBrainz": return ((v as? JSON)?["token"] as? String) ?? ""
        default: return ((v as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        }
    }

    private func write(_ name: String, _ v: Any?) {
        switch name {
        case "tmdbKey":
            let k = (v as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if k.isEmpty { UserDefaults.standard.removeObject(forKey: "tmdb_key") } else { UserDefaults.standard.set(k, forKey: "tmdb_key") }
            Recommender.shared.invalidate()
        case "musicAddons":
            let list = Vault.addonList(v).compactMap { o -> MusicAddon? in
                guard let url = o["url"] as? String, let m = o["manifest"],
                      let d = try? JSONSerialization.data(withJSONObject: m), let text = String(data: d, encoding: .utf8) else { return nil }
                return MusicAddon(url: url, manifestJSON: text)
            }
            MusicAddons.shared.save(list)
        case "listenBrainz":
            if let o = v as? JSON, let t = o["token"] as? String, !t.isEmpty {
                MusicAccount.shared.adopt(token: t, user: o["user"] as? String ?? "")
            } else {
                MusicAccount.shared.signOut()
            }
        case "musicBrainzServer":
            UserDefaults.standard.set((v as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines), forKey: MB.baseKey)
        default:
            break
        }
    }

    /// Joining an account for the first time: keep both where we can.
    private static func join(_ name: String, _ remote: Any?, _ local: Any) -> Any {
        if name == "musicAddons" {
            var out = addonList(remote)
            for a in addonList(local) where !out.contains(where: { ($0["url"] as? String) == (a["url"] as? String) }) { out.append(a) }
            return out
        }
        return print(name, remote).isEmpty ? local : (remote ?? NSNull())
    }

    // MARK: Sync

    /// Called whenever the engine's profile changes: notes the account and syncs when needed.
    func account(authKey: String?, id: String?) {
        let before = self.authKey
        if let k = authKey, !k.isEmpty, let i = id, !i.isEmpty {
            self.authKey = k
            accountId = i.lowercased()
        } else {
            self.authKey = nil
            accountId = nil
        }
        signedIn = self.authKey != nil
        if timer == nil {
            timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { _ in Task { @MainActor in Vault.shared.maybeSync() } }
        }
        if self.authKey != nil, self.authKey != before { maybeSync(force: true) }
    }

    /// Cheap check, safe to call often: syncs when something changed here, or now and then for other devices' changes.
    func maybeSync(force: Bool = false) {
        guard on, authKey != nil, let id = accountId, !busy else { return }
        let before = synced[id]
        let changedHere = before == nil || Vault.names.contains { Vault.print($0, read($0)) != before?[$0] }
        let stale = lastSync.map { Date().timeIntervalSince($0) > Vault.pullEvery } ?? true
        guard force || changedHere || stale || problem == .offline else { return }
        Task { await syncNow() }
    }

    private func syncNow() async {
        guard let s = salt, let k = Keychain.get(keychainAccount), let key = Data(base64Encoded: k), !busy else { return }
        busy = true
        defer { busy = false }
        do {
            try await syncWith(key, salt: s, iter: iter)
        } catch {
            if problem != .locked { problem = .offline; saveState() }
        }
    }

    /// Merges this phone's values with the account's box and saves both. fresh = start the box over from this phone.
    private func syncWith(_ key: Data, salt s: String, iter n: Int, fresh: Bool = false) async throws {
        guard let auth = authKey, let id = accountId else { throw Failure(message: "Sign in first") }
        let box = fresh ? nil : try await Vault.readBox(auth)
        var remote: JSON = [:]
        if let box = box {
            guard (box["salt"] as? String) == s, let opened = Vault.open(key, box) else {
                problem = .locked
                saveState()
                return
            }
            remote = opened
        }
        let before = fresh ? [:] : (synced[id] ?? [:])
        let now = Date().timeIntervalSince1970 * 1000
        var result = remote
        var prints: [String: String] = [:]
        var changedRemote = box == nil
        for name in Vault.names {
            let local = read(name)
            let r = remote[name] as? JSON
            let rv = r?["value"]
            var value: Any
            var at: Double
            if r == nil {
                value = local; at = now
            } else if before[name] == nil {
                value = Vault.join(name, rv, local)
                at = Vault.print(name, value) == Vault.print(name, rv) ? (r?["at"] as? Double ?? now) : now
            } else if Vault.print(name, local) != before[name] {
                value = local; at = now
            } else {
                value = rv ?? NSNull(); at = r?["at"] as? Double ?? now
            }
            if Vault.print(name, value) != Vault.print(name, local) { write(name, value) }
            if r == nil || Vault.print(name, value) != Vault.print(name, rv) { changedRemote = true }
            result[name] = ["value": value, "at": at] as JSON
            prints[name] = Vault.print(name, read(name))
        }
        if changedRemote { try await Vault.writeBox(auth, try Vault.seal(key, salt: s, iter: n, items: result)) }
        synced[id] = prints
        lastSync = Date()
        problem = nil
        saveState()
    }

    private func randomSalt() -> Data {
        var b = [UInt8](repeating: 0, count: 16)
        _ = SecRandomCopyBytes(kSecRandomDefault, 16, &b)
        return Data(b)
    }

    private func derive(_ p: String, _ salt: Data, _ n: Int) async throws -> Data {
        let key = await Task.detached { Vault.deriveKey(p, salt: salt, iterations: n) }.value
        guard let k = key else { throw Failure(message: "Something went wrong. Try again.") }
        return k
    }

    private func keep(_ key: Data, salt s: Data, iter n: Int, clearSynced: Bool) {
        Keychain.set(key.base64EncodedString(), for: keychainAccount)
        salt = s.base64EncodedString()
        iter = n
        problem = nil
        if clearSynced { synced = [:] }
        saveState()
    }

    private func job(_ passcode: String, _ work: (String) async throws -> Void) async throws {
        let p = Vault.normalise(passcode)
        guard p.count >= Vault.minPasscode else { throw Failure(message: "Use at least \(Vault.minPasscode) characters") }
        guard authKey != nil else { throw Failure(message: "Sign in first") }
        busy = true
        defer { busy = false }
        do {
            try await work(p)
        } catch let f as Failure {
            throw f
        } catch {
            throw Failure(message: "Couldn't reach your Stremio account. Check the connection and try again.")
        }
    }

    /// Turns sync on with this passcode. With a box already saved, the passcode must open it.
    func setPasscode(_ passcode: String) async throws {
        try await job(passcode) { p in
            guard let auth = authKey else { return }
            let box = try await Vault.readBox(auth)
            let s = (box?["salt"] as? String).flatMap { Data(base64Encoded: $0) } ?? randomSalt()
            let n = box?["iter"] as? Int ?? Vault.iterations
            let key = try await derive(p, s, n)
            if let box = box, Vault.open(key, box) == nil {
                throw Failure(message: "That's not the sync passcode. Try again, or start over with a new one.")
            }
            keep(key, salt: s, iter: n, clearSynced: false)
            try await syncWith(key, salt: s.base64EncodedString(), iter: n)
        }
    }

    /// Locks the box with a new passcode; the other devices then ask for it.
    func changePasscode(_ passcode: String) async throws {
        try await job(passcode) { p in
            guard let old = salt, let k = Keychain.get(keychainAccount), let oldKey = Data(base64Encoded: k) else {
                throw Failure(message: "Turn sync on first")
            }
            try await syncWith(oldKey, salt: old, iter: iter)
            if problem != nil { throw Failure(message: "Enter the current sync passcode first") }
            let s = randomSalt()
            let key = try await derive(p, s, Vault.iterations)
            keep(key, salt: s, iter: Vault.iterations, clearSynced: false)
            try await syncWith(key, salt: s.base64EncodedString(), iter: Vault.iterations, fresh: true)
        }
    }

    /// Forgot the passcode: a new box with a new passcode, from what this phone has.
    func startOver(_ passcode: String) async throws {
        try await job(passcode) { p in
            let s = randomSalt()
            let key = try await derive(p, s, Vault.iterations)
            keep(key, salt: s, iter: Vault.iterations, clearSynced: true)
            try await syncWith(key, salt: s.base64EncodedString(), iter: Vault.iterations, fresh: true)
        }
    }

    /// Stops syncing on this phone. What's here stays here.
    func turnOff() {
        Keychain.remove(keychainAccount)
        salt = nil
        synced = [:]
        lastSync = nil
        problem = nil
        saveState()
    }
}
