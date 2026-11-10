import Foundation
import VLCKit
import UIKit

/// Plays video with VLC and speaks the same protocol as Stremio's Windows shell (mpv over WebView2 IPC),
/// so the Codio web player (Stremio's ShellVideo) drives it unchanged: auto-play, source fallback,
/// Skip Intro and progress saving all keep working. Ported from the Android TV app's VlcShell.
final class VlcShell: NSObject, VLCMediaPlayerDelegate {
    /// Sends a signal (name, argument) to the web page.
    var signal: ((String, Any) -> Void)?
    /// Told when playback starts or stops (keeps the screen awake, shows the video layer).
    var onActiveChanged: ((Bool) -> Void)?
    /// A new video is loading (the native player resets its state).
    var onLoad: (() -> Void)?
    /// When the viewer last picked a subtitle themselves (automatic picks are ignored unless enabled).
    var userPickAt = Date.distantPast

    private let player = VLCMediaPlayer()
    private var observed = Set<String>()
    private var lastValues: [String: Any] = [:]

    private var active = false
    private var started = false
    private var wantPause = false
    private var volume: Int32 = 100
    private var muted = false
    private var lengthMs: Int32 = 0
    private var timeMs: Int32 = 0
    private var lastTimeSent = Date.distantPast
    private var lastProgressAt = Date()
    private var buffering = false
    private var stallTimer: Timer?
    private var loadAt = Date.distantPast
    /// Picture-in-picture: VLC draws through this, and it hands iOS's mini player window back to us.
    let pip = PiPBridge()
    /// True while the mini player is showing.
    private(set) var inPiP = false

    init(view: UIView) {
        super.init()
        pip.view = view
        pip.player = player
        pip.onPause = { [weak self] p in self?.setProp("pause", p) }
        pip.onState = { [weak self] on in self?.inPiP = on }
        player.delegate = self
        player.drawable = pip
        // Leaving the app while a video plays: carry on in the mini player (Settings can turn this off).
        let nc = NotificationCenter.default
        nc.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self = self, self.active, self.started, !self.wantPause,
                  UserDefaults.standard.object(forKey: "pip_auto") as? Bool ?? true else { return }
            self.pip.window?.startPictureInPicture()
        }
        nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            guard let self = self, self.inPiP else { return }
            self.pip.window?.stopPictureInPicture() // back in Codio: the video goes back to full screen
        }
    }

    // MARK: VLCMediaPlayerDelegate (VLCKit 4)

    private func onMain(_ f: @escaping () -> Void) {
        if Thread.isMainThread { f() } else { DispatchQueue.main.async(execute: f) }
    }
    func mediaPlayerStateChanged(_ newState: VLCMediaPlayerState) { onMain { self.stateChanged() } }
    func mediaPlayerTimeChanged(_ aNotification: Notification) { onMain { self.timeChanged() } }
    func mediaPlayerTrackAdded(_ trackId: String, with trackType: VLCMedia.TrackType) {
        onMain { self.sendTracks(); self.sendSelection() }
    }
    func mediaPlayerTrackSelected(_ trackType: VLCMedia.TrackType, selectedId: String, unselectedId: String) {
        onMain { self.sendSelection() }
    }
    func mediaPlayerLengthChanged(_ length: Int64) {
        onMain {
            if length > 0, self.lengthMs <= 0 {
                self.lengthMs = Int32(length)
                self.forward("duration", Double(length) / 1000)
            }
        }
    }

    private static func ms(_ t: VLCTime?) -> Int32 { t?.intValue ?? 0 }
    private var audio: VLCAudio? { player.audio }
    private var lengthNow: Int32 { VlcShell.ms(player.media?.length) }

    // MARK: - web UI -> VLC

    func handle(method: String, params: Any?) {
        switch method {
        case "mpv-observe-prop":
            if let name = params as? String { observe(name) }
        case "mpv-set-prop":
            if let a = params as? [Any], a.count >= 2, let name = a[0] as? String { setProp(name, a[1]) }
        case "mpv-command":
            if let a = params as? [Any] { command(a.map { "\($0)" }) }
        default:
            break
        }
    }

    private func observe(_ name: String) {
        observed.insert(name)
        switch name {
        // ShellVideo waits for mpv-version before it loads anything.
        case "mpv-version": forward(name, "mpv 0.40.0 (VLCKit)")
        case "ffmpeg-version": forward(name, "VLCKit")
        case "volume": forward(name, Double(volume))
        case "speed": forward(name, 1.0)
        case "sub-scale": forward(name, 1.0)
        case "sub-pos": forward(name, 100.0)
        case "sub-delay": forward(name, 0.0)
        default:
            if let v = lastValues[name] { forward(name, v) }
        }
    }

    private static func truthy(_ v: Any) -> Bool {
        if let b = v as? Bool { return b }
        if let n = v as? NSNumber { return n.doubleValue != 0 }
        let s = "\(v)"
        return s == "yes" || s == "true"
    }

    private static func num(_ v: Any, _ fallback: Double) -> Double {
        if let n = v as? NSNumber { return n.doubleValue }
        return Double("\(v)") ?? fallback
    }

    private static func trackId(_ v: Any) -> Int32 {
        if v is NSNull { return -1 }
        let s = "\(v)"
        if s == "no" || s == "false" { return -1 }
        return Int32(Double(s) ?? -1)
    }

    private func setProp(_ name: String, _ value: Any) {
        if value is NSNull { return }
        switch name {
        case "pause":
            wantPause = VlcShell.truthy(value)
            if wantPause, player.isPlaying { player.pause() }
            else if !wantPause, !player.isPlaying, started { player.play() }
            forward("pause", wantPause)
        case "time-pos":
            let s = VlcShell.num(value, -1)
            guard s >= 0 else { return }
            timeMs = Int32(s * 1000)
            player.time = VLCTime(int: timeMs)
            forward("seeking", false)
            forward("time-pos", s)
        case "speed":
            let r = VlcShell.num(value, 1)
            if r > 0 { player.rate = Float(r) }
            forward("speed", r)
        case "volume":
            volume = Int32(max(0, min(200, VlcShell.num(value, 100).rounded())))
            if !muted { audio?.volume = volume }
            forward("volume", Double(volume))
        case "mute":
            muted = VlcShell.truthy(value)
            audio?.volume = muted ? 0 : volume
        case "aid":
            let id = Int(VlcShell.trackId(value))
            let tracks = player.audioTracks
            if id >= 1, id <= tracks.count { tracks[id - 1].isSelectedExclusively = true }
            sendSelection()
        case "sid":
            let id = VlcShell.trackId(value)
            // Subtitles stay off unless the viewer turns them on: the web player would otherwise switch them on by itself.
            let auto = Date().timeIntervalSince(userPickAt) > 4
            if id >= 0, auto, !UserDefaults.standard.bool(forKey: "subs_auto") {
                sendSelection()
                return
            }
            let tracks = player.textTracks
            if id >= 1, Int(id) <= tracks.count { tracks[Int(id) - 1].isSelectedExclusively = true } else { player.deselectAllTextTracks() }
            sendSelection()
        case "sub-delay":
            let s = VlcShell.num(value, 0)
            player.currentVideoSubTitleDelay = Int(s * 1_000_000)
            forward("sub-delay", s)
        default:
            break // hwdec, vo, subtitle colours, ...: VLC is set up once
        }
    }

    private func command(_ cmd: [String]) {
        guard let name = cmd.first else { return }
        switch name {
        case "loadfile":
            if cmd.count > 1 { load(cmd) }
        case "stop":
            stopPlayback()
        case "sub-add":
            if cmd.count > 1, let url = URL(string: cmd[1]) { _ = player.addPlaybackSlave(url, type: .subtitle, enforce: true) }
        case "audio-add":
            if cmd.count > 1, let url = URL(string: cmd[1]) { _ = player.addPlaybackSlave(url, type: .audio, enforce: false) }
        case "seek":
            if cmd.count > 1 {
                let s = VlcShell.num(cmd[1], 0)
                let absolute = cmd.count > 2 && cmd[2].hasPrefix("absolute")
                let current = VlcShell.ms(player.time)
                let target = absolute ? Int32(s * 1000) : current + Int32(s * 1000)
                player.time = VLCTime(int: max(0, target))
            }
        default:
            break
        }
    }

    private func load(_ cmd: [String]) {
        guard let url = URL(string: cmd[1]) else { return }
        var startAt = 0.0
        for a in cmd.dropFirst(2) {
            if let r = a.range(of: "start=") {
                startAt = Double(a[r.upperBound...].replacingOccurrences(of: "+", with: "")) ?? 0
            }
        }
        active = true
        started = false
        wantPause = false
        timeMs = 0
        lengthMs = 0
        lastValues.removeAll()
        onLoad?()
        loadAt = Date()
        guard let media = VLCMedia(url: url) else { return }
        // A shorter buffer than the TV app (8 s) so the picture starts sooner on a phone.
        media.addOption(":network-caching=3000")
        media.addOption(":file-caching=3000")
        if startAt > 0 { media.addOption(":start-time=\(startAt)") }
        player.media = media
        player.play()
        NSLog("Codio loadfile" + (startAt > 0 ? " from \(startAt)s" : ""))
        setBuffering(true)
        forward("pause", false)
        onActiveChanged?(true)
        startStallTimer()
    }

    private func stopPlayback() {
        let was = active
        active = false
        started = false
        stallTimer?.invalidate()
        stallTimer = nil
        if was {
            if inPiP { pip.window?.stopPictureInPicture() }
            player.stop()
            onActiveChanged?(false)
        }
    }

    /// VLC 3 on iOS gives no buffer percentage, so "buffering" means: not paused and the clock isn't moving.
    private func startStallTimer() {
        stallTimer?.invalidate()
        lastProgressAt = Date()
        stallTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self, self.active else { return }
            if self.started, !self.wantPause, Date().timeIntervalSince(self.lastProgressAt) > 1.2 {
                self.setBuffering(true)
            }
        }
    }

    private func setBuffering(_ b: Bool) {
        if buffering == b, lastValues["paused-for-cache"] != nil { return }
        buffering = b
        forward("paused-for-cache", b)
    }

    // MARK: - VLC -> web UI

    private func stateChanged() {
        pip.window?.invalidatePlaybackState()
        switch player.state {
        case .opening:
            if !started { setBuffering(true) }
        case .playing:
            onPlaying()
        case .paused:
            forward("pause", true)
        case .stopped:
            // The previous video stopping right after a new one loads is not the new one ending.
            if Date().timeIntervalSince(loadAt) > 1.5 { ended(error: false) }
        case .error:
            ended(error: true)
        default:
            break
        }
    }

    private func timeChanged() {
        let ms = VlcShell.ms(player.time)
        if ms != timeMs {
            timeMs = ms
            lastProgressAt = Date()
            if !started { onPlaying() }
            if buffering { setBuffering(false) }
        }
        if lengthMs <= 0, lengthNow > 0 {
            let len = lengthNow
            lengthMs = len
            forward("duration", Double(len) / 1000)
        }
        if Date().timeIntervalSince(lastTimeSent) >= 0.25 {
            lastTimeSent = Date()
            forward("time-pos", Double(ms) / 1000)
        }
    }

    private func onPlaying() {
        let first = !started
        started = true
        forward("pause", false)
        if first {
            audio?.volume = muted ? 0 : volume
            sendTracks()
            sendSelection()
            let size = player.videoSize
            if size.width > 0 {
                forward("video-params", ["w": Int(size.width), "h": Int(size.height), "dw": Int(size.width), "dh": Int(size.height)])
            }
            if lengthMs <= 0, lengthNow > 0 {
            let len = lengthNow
                lengthMs = len
                forward("duration", Double(len) / 1000)
            }
            if wantPause { player.pause() }
        }
    }

    private func ended(error: Bool) {
        guard active else { return }
        active = false
        stallTimer?.invalidate()
        stallTimer = nil
        var reason = "eof"
        var err: [String: Any]?
        if error || !started {
            reason = "error"
            err = ["message": "This stream could not be opened.", "critical": true, "code": 83]
        } else if lengthMs > 0 && timeMs < lengthMs - 15000 {
            reason = "error"
            err = ["message": "Playback stopped unexpectedly.", "critical": false, "code": 81]
        }
        if error && started {
            err = ["message": "Playback stopped unexpectedly.", "critical": false, "code": 81]
        }
        NSLog("Codio end-file: \(reason) at \(timeMs)/\(lengthMs)")
        started = false
        onActiveChanged?(false)
        forward("eof-reached", reason == "eof")
        var o: [String: Any] = ["reason": reason]
        if let err = err { o["error"] = err }
        signal?("mpv-event-ended", o)
    }

    /// mpv numbers tracks from 1 within each type; VLCKit 4 lists them in arrays, so id = position + 1.
    private static func selectedId(_ tracks: [VLCMediaPlayer.Track]) -> Any {
        if let i = tracks.firstIndex(where: { $0.isSelected }) { return i + 1 }
        return "no"
    }

    private func sendSelection() {
        forward("aid", VlcShell.selectedId(player.audioTracks))
        forward("sid", VlcShell.selectedId(player.textTracks))
        forward("vid", VlcShell.selectedId(player.videoTracks))
    }

    private func sendTracks() {
        var out: [[String: Any]] = []
        func add(_ type: String, _ tracks: [VLCMediaPlayer.Track]) {
            for (i, tr) in tracks.enumerated() {
                var t: [String: Any] = ["id": i + 1, "type": type, "external": false, "selected": tr.isSelected]
                if !tr.trackName.isEmpty { t["title"] = tr.trackName }
                if let l = tr.language, !l.isEmpty {
                    t["lang"] = l
                } else if let open = tr.trackName.lastIndex(of: "["), let close = tr.trackName.lastIndex(of: "]"), open < close {
                    t["lang"] = String(tr.trackName[tr.trackName.index(after: open)..<close])
                }
                out.append(t)
            }
        }
        add("video", player.videoTracks)
        add("audio", player.audioTracks)
        add("sub", player.textTracks)
        forward("track-list", out)
    }

    private func forward(_ name: String, _ data: Any) {
        lastValues[name] = data
        guard observed.contains(name) else { return }
        signal?("mpv-prop-change", ["name": name, "data": data])
    }
}

/// What VLC draws into, and the bridge to iOS picture-in-picture (VLCKit 4).
final class PiPBridge: NSObject, VLCDrawable, VLCPictureInPictureDrawable, VLCPictureInPictureMediaControlling {
    weak var view: UIView?
    weak var player: VLCMediaPlayer?
    var window: VLCPictureInPictureWindowControlling?
    var onPause: ((Bool) -> Void)?
    var onState: ((Bool) -> Void)?

    // VLCDrawable: VLC adds its video view here.
    func addSubview(_ v: UIView) {
        guard let host = view else { return }
        v.frame = host.bounds
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        host.addSubview(v)
    }

    func bounds() -> CGRect { view?.bounds ?? .zero }

    // VLCPictureInPictureDrawable
    func mediaController() -> VLCPictureInPictureMediaControlling! { self }

    // The header has no nullability notes, so Swift sees these as implicitly unwrapped / optional.
    func pictureInPictureReady() -> ((VLCPictureInPictureWindowControlling?) -> Void)! {
        return { [weak self] w in
            guard let w = w else { return }
            self?.window = w
            w.stateChangeEventHandler = { started in
                DispatchQueue.main.async { self?.onState?(started) }
            }
        }
    }

    // VLCPictureInPictureMediaControlling: the mini player's own buttons.
    func play() { DispatchQueue.main.async { self.onPause?(false) } }
    func pause() { DispatchQueue.main.async { self.onPause?(true) } }

    func seek(by offset: Int64, completion: (() -> Void)!) {
        guard let p = player else { completion?(); return }
        let t = Int64(p.time.intValue) + offset
        p.time = VLCTime(int: Int32(max(0, t)))
        completion?()
    }

    func mediaLength() -> Int64 { Int64(player?.media?.length.intValue ?? 0) }
    func mediaTime() -> Int64 { Int64(player?.time.intValue ?? 0) }
    func isMediaSeekable() -> Bool { player?.isSeekable ?? false }
    func isMediaPlaying() -> Bool { player?.isPlaying ?? false }
}
