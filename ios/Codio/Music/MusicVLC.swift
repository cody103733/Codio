import Foundation
import VLCKit

/// Plays a song with VLC when the iPhone's own player can't (Opus or WebM audio, Ogg, odd servers). Audio only.
final class MusicVLC: NSObject, VLCMediaPlayerDelegate {
    private let player = VLCMediaPlayer()
    var onTime: ((Double) -> Void)?
    var onLength: ((Double) -> Void)?
    var onPlaying: ((Bool) -> Void)?
    var onEnd: (() -> Void)?
    var onError: (() -> Void)?
    private var loadedAt = Date()
    private var startedPlaying = false

    override init() {
        super.init()
        player.delegate = self
    }

    private func onMain(_ f: @escaping () -> Void) {
        if Thread.isMainThread { f() } else { DispatchQueue.main.async(execute: f) }
    }

    func load(_ url: URL, volume: Float) {
        guard let media = VLCMedia(url: url) else {
            onError?()
            return
        }
        loadedAt = Date()
        startedPlaying = false
        player.media = media
        player.audio?.volume = Int32(max(0, min(100, volume * 100)))
        player.play()
    }

    func play() { player.play() }
    func pause() { player.pause() }

    func stop() {
        onEnd = nil
        onError = nil
        player.stop()
    }

    func seek(_ seconds: Double) { player.time = VLCTime(int: Int32(max(0, seconds) * 1000)) }

    func setVolume(_ v: Float) { player.audio?.volume = Int32(max(0, min(100, v * 100))) }

    var isPlaying: Bool { player.isPlaying }

    func mediaPlayerStateChanged(_ newState: VLCMediaPlayerState) {
        onMain {
            switch self.player.state {
            case .playing:
                self.startedPlaying = true
                self.onPlaying?(true)
            case .paused:
                self.onPlaying?(false)
            case .stopped:
                // A stop right after loading is the old song going away, not this one ending.
                guard Date().timeIntervalSince(self.loadedAt) > 1.5 else { return }
                self.onPlaying?(false)
                if self.startedPlaying { self.onEnd?() } else { self.onError?() }
            case .error:
                self.onPlaying?(false)
                self.onError?()
            default:
                break
            }
        }
    }

    func mediaPlayerTimeChanged(_ aNotification: Notification) {
        onMain {
            self.onTime?(Double(self.player.time.intValue) / 1000)
        }
    }

    func mediaPlayerLengthChanged(_ length: Int64) {
        onMain {
            if length > 0 { self.onLength?(Double(length) / 1000) }
        }
    }
}
