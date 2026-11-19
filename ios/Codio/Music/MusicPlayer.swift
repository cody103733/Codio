import AVFoundation
import Combine
import MediaPlayer
import UIKit

/// The music player: one AVPlayer for the whole app, a queue, shuffle and repeat. It keeps playing while you move around
/// Codio and in the background (the mini player shows it), and pauses by itself when a video starts.
@MainActor
final class MusicPlayer: ObservableObject {
    static let shared = MusicPlayer()

    enum Repeat { case off, all, one }

    @Published private(set) var queue: [MusicTrack] = []
    @Published private(set) var index = -1
    @Published private(set) var playing = false
    @Published private(set) var loading = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var shuffle = false
    @Published private(set) var repeatMode: Repeat = .off
    @Published private(set) var message: String?
    @Published private(set) var quality: String?
    @Published private(set) var source: String?
    @Published private(set) var volume: Float = 1
    /// The full Now Playing screen is open.
    @Published var showFull = false

    private let recentKey = "music_recent_v1"
    private let volumeKey = "music_volume"
    private let player = AVPlayer()
    private var token = 0
    private var bag = Set<AnyCancellable>()
    private var itemBag = Set<AnyCancellable>()
    private var timeObserver: Any?
    private var messageTask: Task<Void, Never>?
    private var loadTask: Task<Void, Never>?
    private var artworkFor: String?
    /// VLC takes over a song the iPhone's own player can't play (Opus, WebM, Ogg...).
    private var vlc: MusicVLC?
    private var usingVLC = false

    // How long the current song has really been heard (seeking doesn't count), for ListenBrainz.
    private var heard: Double = 0
    private var lastPosition: Double = 0
    private var listenSent = false
    private var startedAt = Date()

    var current: MusicTrack? { queue.indices.contains(index) ? queue[index] : nil }

    init() {
        if let v = UserDefaults.standard.object(forKey: volumeKey) as? Double { volume = Float(min(1, max(0, v))) }
        player.volume = volume
        player.publisher(for: \.timeControlStatus)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in
                guard let self = self, !self.usingVLC else { return }
                self.playing = status != .paused
                self.publishNowPlaying()
            }
            .store(in: &bag)
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self = self, !self.usingVLC else { return }
                if let d = self.player.currentItem?.duration.seconds, d.isFinite, d > 0 { self.duration = d }
                self.tick(time.seconds)
            }
        }
        NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] note in
                let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
                if raw == AVAudioSession.InterruptionType.began.rawValue { self?.pause() }
            }
            .store(in: &bag)
        MusicAddonClient.onNotice = { [weak self] text in self?.say(text) }
        setupRemote()
    }

    private func tick(_ seconds: Double) {
        guard seconds.isFinite, current != nil else { return }
        position = seconds
        let step = seconds - lastPosition
        lastPosition = seconds
        if step > 0 && step < 2 { heard += step }
        guard !listenSent, !loading, let t = current else { return }
        let length = duration > 0 ? duration : 480
        if length < 30 || heard < min(240, length / 2) { return }
        listenSent = true
        MusicAccount.shared.listened(t, startedAt: startedAt)
    }

    private func startListening(_ t: MusicTrack) {
        heard = 0
        lastPosition = 0
        listenSent = false
        startedAt = Date()
        MusicAccount.shared.playingNow(t)
    }

    func say(_ text: String?) {
        messageTask?.cancel()
        message = text
        guard text != nil else { return }
        messageTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            if !Task.isCancelled { self?.message = nil }
        }
    }

    // MARK: Recently played

    private func remember(_ t: MusicTrack) {
        let kept = Array(([t] + recentTracks().filter { $0.key != t.key }).prefix(20))
        if let d = try? JSONEncoder().encode(kept) { UserDefaults.standard.set(d, forKey: recentKey) }
    }

    func recentTracks() -> [MusicTrack] {
        guard let d = UserDefaults.standard.data(forKey: recentKey), let l = try? JSONDecoder().decode([MusicTrack].self, from: d) else { return [] }
        return l
    }

    // MARK: Playing

    private func activateSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playback, mode: .default)
        try? s.setActive(true)
    }

    /// Loads and starts the track at `i` in the queue.
    private func load(_ i: Int) {
        guard queue.indices.contains(i) else { return }
        let t = queue[i]
        token += 1
        let mine = token
        loadTask?.cancel()
        player.pause()
        player.replaceCurrentItem(with: nil)
        itemBag.removeAll()
        stopVLC()
        index = i
        loading = true
        position = 0
        duration = t.durationMs.map { Double($0) / 1000 } ?? 0
        quality = nil
        source = nil
        publishNowPlaying()
        // A downloaded song plays from the iPhone, no addon or network needed.
        if let file = MusicDownloads.shared.fileURL(t) {
            playURL(file, t, mine, quality: "Downloaded", source: nil)
            return
        }
        let addons = MusicAddons.shared.list
        if addons.isEmpty {
            loading = false
            say("No music addon installed yet. Add one in Settings > Music.")
            return
        }
        let lookup: (() async -> String?)? = t.mbid.map { m in { await MB.recordingIsrc(m) } }
        loadTask = Task { [weak self] in
            let found = await MusicMatch.findAudio(t, addons: addons, isrcLookup: lookup)
            guard let self = self, mine == self.token else { return }
            self.started(t, found, mine)
        }
    }

    private func started(_ t: MusicTrack, _ found: MusicMatch.Playable?, _ mine: Int) {
        let link = found.map { f in URL(string: f.url) ?? URL(string: f.url.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? "") }
        guard let found = found, let url = link ?? nil else {
            loading = false
            say("No source available for \u{201C}\(t.title)\u{201D}")
            // Move on so a queue doesn't stall on one missing song.
            if index < queue.count - 1 {
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    if let self = self, mine == self.token { self.advance(auto: true) }
                }
            }
            return
        }
        playURL(url, t, mine, quality: found.quality ?? found.format, source: found.addon.name)
    }

    private func playURL(_ url: URL, _ t: MusicTrack, _ mine: Int, quality q: String?, source s: String?) {
        activateSession()
        let item = AVPlayerItem(url: url)
        item.publisher(for: \.status)
            .receive(on: RunLoop.main)
            .sink { [weak self] status in
                guard let self = self, mine == self.token, status == .failed else { return }
                self.playWithVLC(url, t, mine)
            }
            .store(in: &itemBag)
        NotificationCenter.default.publisher(for: .AVPlayerItemFailedToPlayToEndTime, object: item)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self = self, mine == self.token, !self.usingVLC else { return }
                self.playWithVLC(url, t, mine, from: self.position)
            }
            .store(in: &itemBag)
        NotificationCenter.default.publisher(for: .AVPlayerItemDidPlayToEndTime, object: item)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self = self, mine == self.token else { return }
                self.advance(auto: true)
            }
            .store(in: &itemBag)
        player.replaceCurrentItem(with: item)
        loading = false
        quality = q
        source = s
        remember(t)
        MusicTaste.shared.recordPlay(t)
        startListening(t)
        player.play()
        publishNowPlaying()
        // Some links never get ready in the iPhone's player (no error either): VLC gets a go after 10 seconds.
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 10_000_000_000)
            guard let self = self, mine == self.token, !self.usingVLC, item.status != .readyToPlay else { return }
            self.playWithVLC(url, t, mine)
        }
    }

    private func stopVLC() {
        vlc?.stop()
        usingVLC = false
    }

    /// The same link again, with VLC (it plays nearly any audio format).
    private func playWithVLC(_ url: URL, _ t: MusicTrack, _ mine: Int, from seconds: Double = 0) {
        guard mine == token else { return }
        player.pause()
        player.replaceCurrentItem(with: nil)
        itemBag.removeAll()
        let v = vlc ?? MusicVLC()
        vlc = v
        usingVLC = true
        v.onTime = { [weak self] sec in
            guard let self = self, mine == self.token else { return }
            self.tick(sec)
        }
        v.onLength = { [weak self] len in
            guard let self = self, mine == self.token else { return }
            self.duration = len
            self.publishNowPlaying()
        }
        v.onPlaying = { [weak self] on in
            guard let self = self, mine == self.token else { return }
            self.playing = on
            self.publishNowPlaying()
        }
        v.onEnd = { [weak self] in
            guard let self = self, mine == self.token else { return }
            self.advance(auto: true)
        }
        v.onError = { [weak self] in
            guard let self = self, mine == self.token else { return }
            self.usingVLC = false
            self.say("Couldn't play \u{201C}\(t.title)\u{201D}")
            self.advance(auto: true)
        }
        activateSession()
        v.load(url, volume: volume)
        if seconds > 1 { v.seek(seconds) }
    }

    private func advance(auto: Bool) {
        if queue.isEmpty { return }
        if auto && repeatMode == .one {
            if usingVLC, let v = vlc {
                v.seek(0)
                v.play()
            } else {
                player.seek(to: .zero)
                player.play()
            }
            if let t = current { startListening(t) }
            return
        }
        if index >= queue.count - 1 {
            if repeatMode == .all { load(0) } else {
                pause()
                if !usingVLC { player.seek(to: .zero) }
                position = 0
            }
            return
        }
        load(index + 1)
    }

    func play(_ tracks: [MusicTrack], at start: Int = 0, shuffled shuffleOn: Bool? = nil) {
        guard !tracks.isEmpty else { return }
        let on = shuffleOn ?? shuffle
        var list = tracks
        var i = max(0, min(start, list.count - 1))
        if on {
            let first = list[i]
            list.remove(at: i)
            list = [first] + list.shuffled()
            i = 0
        }
        queue = list
        shuffle = on
        index = -1
        load(i)
    }

    func playNext(_ t: MusicTrack) {
        if queue.isEmpty { play([t]); return }
        queue.insert(t, at: min(index + 1, queue.count))
        say("Playing \u{201C}\(t.title)\u{201D} next")
    }

    func addToQueue(_ t: MusicTrack) {
        if queue.isEmpty { play([t]); return }
        queue.append(t)
        say("Added \u{201C}\(t.title)\u{201D} to the queue")
    }

    func playIndex(_ i: Int) { load(i) }
    func next() { advance(auto: false) }

    func previous() {
        if position > 4 || index <= 0 {
            seek(0)
            return
        }
        load(index - 1)
    }

    func pause() {
        if usingVLC { vlc?.pause() } else { player.pause() }
    }

    func resume() {
        if usingVLC, let v = vlc {
            activateSession()
            v.play()
            return
        }
        guard player.currentItem != nil else {
            if current != nil { load(index) }
            return
        }
        activateSession()
        player.play()
    }

    func toggle() { if playing { pause() } else { resume() } }

    func seek(_ seconds: Double) {
        position = max(0, seconds)
        if usingVLC { vlc?.seek(seconds) } else { player.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 600)) }
        publishNowPlaying()
    }

    func toggleShuffle() {
        if !shuffle && queue.count > 2 && index >= 0 {
            let rest = Array(queue[(index + 1)...]).shuffled()
            queue = Array(queue[...index]) + rest
        }
        shuffle.toggle()
    }

    func cycleRepeat() { repeatMode = repeatMode == .off ? .all : (repeatMode == .all ? .one : .off) }

    func remove(at i: Int) {
        guard i != index, queue.indices.contains(i) else { return }
        queue.remove(at: i)
        if i < index { index -= 1 }
    }

    func stop() {
        token += 1
        loadTask?.cancel()
        player.pause()
        player.replaceCurrentItem(with: nil)
        itemBag.removeAll()
        stopVLC()
        playing = false
        queue = []
        index = -1
        loading = false
        position = 0
        duration = 0
        quality = nil
        source = nil
        showFull = false
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }

    /// Volume 0 to 1, kept for next time. This is the music's own volume, on top of the phone's.
    func setVolume(_ v: Float) {
        volume = min(1, max(0, v))
        player.volume = volume
        vlc?.setVolume(volume)
        UserDefaults.standard.set(Double(volume), forKey: volumeKey)
    }

    // MARK: Lock screen and Control Center

    private func setupRemote() {
        let c = MPRemoteCommandCenter.shared()
        c.playCommand.addTarget { [weak self] _ in self?.resume(); return .success }
        c.pauseCommand.addTarget { [weak self] _ in self?.pause(); return .success }
        c.togglePlayPauseCommand.addTarget { [weak self] _ in self?.toggle(); return .success }
        c.nextTrackCommand.addTarget { [weak self] _ in self?.next(); return .success }
        c.previousTrackCommand.addTarget { [weak self] _ in self?.previous(); return .success }
        c.changePlaybackPositionCommand.addTarget { [weak self] e in
            guard let e = e as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            self?.seek(e.positionTime)
            return .success
        }
    }

    /// Made outside the main actor: iOS asks for the picture from any thread.
    nonisolated private static func artwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }

    private func publishNowPlaying() {
        guard let t = current else { return }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: t.title,
            MPMediaItemPropertyArtist: t.artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
        ]
        if let a = t.album { info[MPMediaItemPropertyAlbumTitle] = a }
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        if let old = MPNowPlayingInfoCenter.default().nowPlayingInfo, artworkFor == t.key, let art = old[MPMediaItemPropertyArtwork] {
            info[MPMediaItemPropertyArtwork] = art
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        if artworkFor != t.key, let s = t.artworkURL, let u = URL(string: s) {
            artworkFor = t.key
            let key = t.key
            Task { [weak self] in
                guard let r = try? await MusicNet.session.data(from: u), let image = UIImage(data: r.0) else { return }
                guard let self = self, self.current?.key == key else { return }
                var now = MPNowPlayingInfoCenter.default().nowPlayingInfo ?? [:]
                now[MPMediaItemPropertyArtwork] = MusicPlayer.artwork(image)
                MPNowPlayingInfoCenter.default().nowPlayingInfo = now
            }
        }
    }
}
