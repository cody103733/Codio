import Foundation
import UIKit

/// Your ListenBrainz account (listenbrainz.org, free and open): sign in once with your user token on each device, and
/// what you play and the songs you heart follow you. Codio sends each song you listen to (a "listen") and each heart
/// (a "love"), and reads back your loved songs and the listens from your other devices. Same rules as Windows
/// (music/listenbrainz.ts). The token is kept in the iPhone's keychain and only ever sent to ListenBrainz.
@MainActor
final class MusicAccount: ObservableObject {
    static let shared = MusicAccount()

    struct Account: Codable {
        var user: String
        /// This phone, so its own listens aren't counted twice when they come back.
        var device: String
        /// Newest listen already read back (seconds).
        var listensUntil: Int
        var lastSync: Date?
        var syncError: String?
    }

    struct Queue: Codable {
        struct Listen: Codable { var at: Int; var meta: Data }
        struct Feedback: Codable { var score: Int; var at: Double }
        var listens: [Listen] = []
        /// recording mbid -> 1 (love) or 0 (no longer loved), with when it was changed here.
        var feedback: [String: Feedback] = [:]
    }

    struct Failure: Error { var status: Int }

    private let accountKey = "music_lb_account"
    private let queueKey = "music_lb_queue"
    private let tokenAccount = "listenbrainz-token"
    private let api = "https://api.listenbrainz.org"

    @Published private(set) var account: Account?
    private var queue = Queue()
    private var syncing = false
    private var started = false
    private var timer: Timer?

    init() {
        if let d = UserDefaults.standard.data(forKey: accountKey), let a = try? JSONDecoder().decode(Account.self, from: d), Keychain.get(tokenAccount) != nil { account = a }
        if let d = UserDefaults.standard.data(forKey: queueKey), let q = try? JSONDecoder().decode(Queue.self, from: d) { queue = q }
        MusicTaste.shared.likeHooks.append { [weak self] track, liked in self?.hearted(track, liked) }
    }

    private func setAccount(_ a: Account?) {
        account = a
        if let a = a, let d = try? JSONEncoder().encode(a) { UserDefaults.standard.set(d, forKey: accountKey) } else { UserDefaults.standard.removeObject(forKey: accountKey) }
    }

    private func saveQueue() {
        if let d = try? JSONEncoder().encode(queue) { UserDefaults.standard.set(d, forKey: queueKey) }
    }

    private func call(_ token: String, _ path: String, body: Any? = nil) async throws -> Any? {
        guard let u = URL(string: api + path) else { throw Failure(status: 0) }
        var r = URLRequest(url: u)
        r.timeoutInterval = 15
        r.setValue(MusicNet.agent, forHTTPHeaderField: "User-Agent")
        r.setValue("Token \(token)", forHTTPHeaderField: "Authorization")
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body = body {
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
        }
        let result: (Data, URLResponse)
        do {
            result = try await URLSession.shared.data(for: r)
        } catch {
            throw Failure(status: 0)
        }
        let (data, response) = result
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status / 100 != 2 { throw Failure(status: status) }
        return MusicNet.parse(data)
    }

    private func metadata(_ t: MusicTrack, device: String) -> JSON {
        var info: JSON = ["media_player": "Codio", "submission_client": "Codio for iPhone", "codio_device": device]
        if isMbid(t.mbid) { info["recording_mbid"] = t.mbid! }
        if isMbid(t.artistMbid) { info["artist_mbids"] = [t.artistMbid!] }
        if let d = t.durationMs { info["duration_ms"] = d }
        if let i = t.isrc { info["isrc"] = i }
        var meta: JSON = ["artist_name": t.artist.isEmpty ? "Unknown artist" : t.artist,
                          "track_name": t.title.isEmpty ? "Unknown song" : t.title,
                          "additional_info": info]
        if let a = t.album { meta["release_name"] = a }
        return meta
    }

    // MARK: Sign in and out

    /// Checks the token with ListenBrainz and signs in. Throws a message to show.
    func signIn(_ text: String) async throws {
        let token = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard token.count >= 8, token.count <= 100, token.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }) else {
            throw MusicError.message("That doesn't look like a ListenBrainz user token")
        }
        let answer: JSON?
        do {
            answer = try await call(token, "/1/validate-token") as? JSON
        } catch {
            throw MusicError.message((error as? Failure)?.status == 0 ? "Couldn't reach ListenBrainz" : "ListenBrainz didn't accept that token")
        }
        guard jbool(answer, "valid") == true, let user = jstr(answer, "user_name") else {
            throw MusicError.message("ListenBrainz didn't accept that token")
        }
        Keychain.set(token, for: tokenAccount)
        // Songs hearted here before signing in go to the account first, so the first sync keeps them.
        for t in MusicTaste.shared.data.likedTracks {
            if let m = t.mbid, isMbid(m), queue.feedback[m] == nil { queue.feedback[m] = Queue.Feedback(score: 1, at: Date().timeIntervalSince1970) }
        }
        saveQueue()
        setAccount(Account(user: user, device: account?.device ?? UUID().uuidString, listensUntil: Int(Date().timeIntervalSince1970) - 30 * 24 * 3600, lastSync: nil, syncError: nil))
        await syncNow()
    }

    func signOut() {
        queue = Queue()
        saveQueue()
        Keychain.remove(tokenAccount)
        setAccount(nil)
    }

    // MARK: What you play and heart, sent as it happens (kept and sent later when offline)

    private func sendQueued(_ token: String) async throws {
        if !queue.listens.isEmpty {
            let batch = Array(queue.listens.prefix(100))
            let payload: [JSON] = batch.compactMap { l in
                guard let meta = MusicNet.parse(l.meta) as? JSON else { return nil }
                return ["listened_at": l.at, "track_metadata": meta]
            }
            if !payload.isEmpty {
                _ = try await call(token, "/1/submit-listens", body: ["listen_type": payload.count == 1 ? "single" : "import", "payload": payload] as JSON)
            }
            queue.listens.removeFirst(min(batch.count, queue.listens.count))
            saveQueue()
        }
        for (mbid, f) in queue.feedback {
            _ = try await call(token, "/1/feedback/recording-feedback", body: ["recording_mbid": mbid, "score": f.score] as JSON)
            if queue.feedback[mbid]?.at == f.at { queue.feedback[mbid] = nil }
            saveQueue()
        }
    }

    func playingNow(_ t: MusicTrack) {
        guard let a = account, let token = Keychain.get(tokenAccount) else { return }
        let body: JSON = ["listen_type": "playing_now", "payload": [["track_metadata": metadata(t, device: a.device)]]]
        Task { _ = try? await call(token, "/1/submit-listens", body: body) }
    }

    /// A song counts as listened to after half of it, or four minutes, whichever comes first (ListenBrainz's rule).
    func listened(_ t: MusicTrack, startedAt: Date) {
        guard let a = account, let token = Keychain.get(tokenAccount),
              let meta = try? JSONSerialization.data(withJSONObject: metadata(t, device: a.device)) else { return }
        queue.listens.append(Queue.Listen(at: Int(startedAt.timeIntervalSince1970), meta: meta))
        if queue.listens.count > 500 { queue.listens.removeFirst(queue.listens.count - 500) }
        saveQueue()
        Task { try? await sendQueued(token) }
    }

    private func hearted(_ t: MusicTrack, _ liked: Bool) {
        guard account != nil, let token = Keychain.get(tokenAccount), let m = t.mbid, isMbid(m) else { return }
        queue.feedback[m] = Queue.Feedback(score: liked ? 1 : 0, at: Date().timeIntervalSince1970)
        saveQueue()
        Task { try? await sendQueued(token) }
    }

    // MARK: Reading back: loved songs and listens from your other devices

    private func lovedTrack(_ f: JSON) -> MusicTrack? {
        guard let mbid = mstr(f["recording_mbid"]), isMbid(mbid), let meta = f["track_metadata"] as? JSON, let name = mstr(meta["track_name"]) else { return nil }
        let map = meta["mbid_mapping"] as? JSON
        let release = mstr(map?["caa_release_mbid"]) ?? mstr(map?["release_mbid"])
        return MusicTrack(id: "mbid:" + mbid, title: name, artist: mstr(meta["artist_name"]) ?? "", album: mstr(meta["release_name"]), durationMs: nil,
                          artworkURL: release.map { MB.releaseCoverURL($0) }, isrc: nil, mbid: mbid, artistMbid: mfirst(map?["artist_mbids"]), addon: nil, streamURL: nil)
    }

    private func pullLoved(_ token: String, _ user: String) async throws {
        var ids = Set<String>()
        var tracks: [MusicTrack] = []
        var offset = 0
        while offset < 2000 {
            let page = try await call(token, "/1/feedback/user/\(menc(user))/get-feedback?score=1&count=100&offset=\(offset)&metadata=true") as? JSON
            let list = marr(page?["feedback"])
            for f in list {
                if let m = mstr(f["recording_mbid"]) { ids.insert(m) }
                if let t = lovedTrack(f) { tracks.append(t) }
            }
            if list.count < 100 || offset + 100 >= (jint(page, "total_count") ?? 0) { break }
            offset += 100
        }
        // Hearts changed here that haven't reached the account yet win over what it says.
        let pending = queue.feedback
        MusicTaste.shared.applyLovedFromAccount(ids, tracks) { t in t.mbid.map { pending[$0] != nil } ?? false }
    }

    private func pullListens(_ token: String, _ a: Account) async throws -> Int {
        let answer = try await call(token, "/1/user/\(menc(a.user))/listens?count=100&min_ts=\(a.listensUntil)") as? JSON
        let listens = marr((answer?["payload"] as? JSON)?["listens"])
        var newest = a.listensUntil
        var plays: [(artistMbid: String, name: String, at: Double)] = []
        for l in listens {
            let at = jint(l, "listened_at") ?? 0
            newest = max(newest, at)
            let meta = (l["track_metadata"] as? JSON) ?? [:]
            let info = meta["additional_info"] as? JSON
            if mstr(info?["codio_device"]) == a.device { continue }
            let mapping = meta["mbid_mapping"] as? JSON
            if let artist = mfirst(mapping?["artist_mbids"]) ?? mfirst(info?["artist_mbids"]), isMbid(artist) {
                plays.append((artistMbid: artist, name: mstr(meta["artist_name"]) ?? "", at: Double(at) * 1000))
            }
        }
        MusicTaste.shared.addPlaysFromAccount(plays)
        return newest
    }

    func syncNow() async {
        guard !syncing, let a = account, let token = Keychain.get(tokenAccount) else { return }
        syncing = true
        defer { syncing = false }
        do {
            try await sendQueued(token)
            try await pullLoved(token, a.user)
            let newest = try await pullListens(token, a)
            if var now = account, now.user == a.user {
                now.listensUntil = newest
                now.lastSync = Date()
                now.syncError = nil
                setAccount(now)
            }
        } catch {
            let status = (error as? Failure)?.status ?? 0
            if var now = account, now.user == a.user {
                now.syncError = status == 401 ? "ListenBrainz no longer accepts your token. Sign in again." : "Couldn't reach ListenBrainz. Trying again later."
                setAccount(now)
            }
        }
    }

    /// Started once by the app: syncs now, every ten minutes, and when Codio comes back to the front.
    func start() {
        guard !started else { return }
        started = true
        Task { await syncNow() }
        timer = Timer.scheduledTimer(withTimeInterval: 600, repeats: true) { _ in
            Task { @MainActor in await MusicAccount.shared.syncNow() }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in await MusicAccount.shared.syncNow() }
        }
    }
}
