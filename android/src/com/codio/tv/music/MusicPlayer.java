package com.codio.tv.music;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.codio.tv.music.Matcher.Playable;
import com.codio.tv.music.Model.Addon;
import com.codio.tv.music.Model.Track;

import org.json.JSONArray;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The music player (windows/src/routes/Codio/music/player.ts): one MediaPlayer for the whole app, a queue, shuffle and
 * repeat. Everything here runs on the main thread. It keeps playing while you move around Codio, shows a notification
 * (MusicService) and pauses by itself when a video starts. No phone or TV screens in here: the screens listen to it.
 */
public final class MusicPlayer {
    private MusicPlayer() {}

    public static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;

    /** Screens extend this and override what they care about. changed = queue, track, play state or message; tick = position. */
    public abstract static class Listener {
        public void changed() {}

        public void tick() {}
    }

    private static final String RECENT_KEY = "codioMusicRecent";
    private static final String VOLUME_KEY = "codioMusicVolume";

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static List<Track> queue = new ArrayList<>();
    private static int index = -1;
    private static boolean playing, loading, shuffle, prepared;
    private static int repeat = REPEAT_OFF;
    private static String message, quality, source;
    private static volatile String preparing;
    private static double position, duration;
    private static float volume = 1f;
    private static MediaPlayer mp;
    /** The second engine: libVLC plays what Android's player can't (FLAC variants, Opus, WebM, Ogg, odd links). */
    private static org.videolan.libvlc.MediaPlayer vp;
    private static LibVLC vlc;
    private static boolean vlcMode;
    private static Runnable stall;
    private static volatile int token = 0;
    private static boolean inited, wantPlay, focusLost;
    private static AudioFocusRequest focusRequest;

    // Heard time of the current song, for ListenBrainz (half of it, or four minutes).
    private static double heard, lastPosition;
    private static boolean listenSent;
    private static long startedAt;

    public static void init(Context c) {
        MusicNet.init(c);
        if (inited) return;
        inited = true;
        try {
            volume = Math.max(0f, Math.min(1f, MusicNet.prefs().getFloat(VOLUME_KEY, 1f)));
        } catch (ClassCastException e) {
            volume = 1f;
        }
        Addons.onNotice(new Addons.Notice() {
            @Override
            public void notice(final String text, String level) {
                H.post(new Runnable() {
                    @Override
                    public void run() {
                        say(text);
                    }
                });
            }
        });
    }

    // ---- state

    public static List<Track> queue() {
        return queue;
    }

    public static int index() {
        return index;
    }

    public static Track current() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    public static boolean hasQueue() {
        return !queue.isEmpty();
    }

    public static boolean playing() {
        return playing;
    }

    public static boolean loading() {
        return loading;
    }

    /** While an addon says the song is still being prepared (e.g. TorBox caching it): the text to show, else null. */
    public static String preparing() {
        return loading ? preparing : null;
    }

    /** What to show while a song loads: the addon's "preparing" text, else the given default. */
    public static String loadingText(String fallback) {
        String p = preparing();
        return p != null ? p : fallback;
    }

    public static boolean shuffle() {
        return shuffle;
    }

    public static int repeat() {
        return repeat;
    }

    public static String message() {
        return message;
    }

    public static String quality() {
        return quality;
    }

    public static String source() {
        return source;
    }

    public static double position() {
        return position;
    }

    public static double duration() {
        return duration;
    }

    public static float volume() {
        return volume;
    }

    public static void listen(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void unlisten(Listener l) {
        LISTENERS.remove(l);
    }

    private static void changed() {
        for (Listener l : new ArrayList<>(LISTENERS)) l.changed();
        MusicService.update();
    }

    private static void tick() {
        for (Listener l : new ArrayList<>(LISTENERS)) l.tick();
    }

    private static final Runnable CLEAR = new Runnable() {
        @Override
        public void run() {
            message = null;
            changed();
        }
    };

    private static void say(String text) {
        H.removeCallbacks(CLEAR);
        message = text;
        changed();
        if (text != null) H.postDelayed(CLEAR, 6000);
    }

    // ---- listening time

    private static void startListening(Track t) {
        heard = 0;
        lastPosition = 0;
        listenSent = false;
        startedAt = System.currentTimeMillis();
        ListenBrainz.playingNow(t);
    }

    private static void countListening(double pos) {
        double step = pos - lastPosition;
        lastPosition = pos;
        if (step > 0 && step < 2) heard += step;
        Track t = current();
        if (listenSent || t == null || loading) return;
        double length = duration > 0 ? duration : 480;
        if (length < 30 || heard < Math.min(240, length / 2)) return;
        listenSent = true;
        ListenBrainz.listened(t, startedAt);
    }

    private static final Runnable TICKER = new Runnable() {
        @Override
        public void run() {
            if (vlcMode && vp != null) {
                long t = vp.getTime(), l = vp.getLength();
                if (t >= 0) position = t / 1000.0;
                if (l > 0) duration = l / 1000.0;
                countListening(position);
                tick();
            } else if (mp != null && prepared) {
                try {
                    position = mp.getCurrentPosition() / 1000.0;
                    int d = mp.getDuration();
                    if (d > 0) duration = d / 1000.0;
                } catch (IllegalStateException ignored) {
                }
                countListening(position);
                tick();
            }
            if (playing) H.postDelayed(this, 500);
        }
    };

    // ---- recent

    private static void remember(Track t) {
        try {
            List<Track> list = recent();
            List<Track> kept = new ArrayList<>();
            kept.add(t);
            for (Track x : list) if (!x.same(t) && kept.size() < 20) kept.add(x);
            JSONArray a = new JSONArray();
            for (Track x : kept) a.put(x.toJson());
            MusicNet.prefs().edit().putString(RECENT_KEY, a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static List<Track> recent() {
        List<Track> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(MusicNet.prefs().getString(RECENT_KEY, "[]"));
            for (int i = 0; i < a.length(); i++) {
                Track t = Track.fromJson(a.optJSONObject(i));
                if (t != null) out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---- audio focus

    private static final AudioManager.OnAudioFocusChangeListener FOCUS = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int change) {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                focusLost = false;
                pause();
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (playing) {
                    focusLost = true;
                    pause();
                }
            } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                engineVolume(volume * 0.3f);
            } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                engineVolume(volume);
                if (focusLost) {
                    focusLost = false;
                    resume();
                }
            }
        }
    };

    private static boolean takeFocus() {
        try {
            AudioManager am = (AudioManager) MusicNet.context().getSystemService(Context.AUDIO_SERVICE);
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(FOCUS).build();
            }
            return am.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        } catch (Exception e) {
            return true;
        }
    }

    private static void giveFocus() {
        try {
            AudioManager am = (AudioManager) MusicNet.context().getSystemService(Context.AUDIO_SERVICE);
            if (focusRequest != null) am.abandonAudioFocusRequest(focusRequest);
        } catch (Exception ignored) {
        }
    }

    // ---- loading and playing

    private static void release() {
        prepared = false;
        playing = false;
        H.removeCallbacks(TICKER);
        if (stall != null) H.removeCallbacks(stall);
        stall = null;
        vlcMode = false;
        if (vp != null) {
            final org.videolan.libvlc.MediaPlayer old = vp;
            vp = null;
            try {
                old.setEventListener(null);
                // libVLC stops and frees on a worker thread so the screen never waits for it
                MusicNet.POOL.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            old.stop();
                            old.release();
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
        }
        if (mp != null) {
            try {
                mp.setOnCompletionListener(null);
                mp.setOnErrorListener(null);
                mp.setOnPreparedListener(null);
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
    }

    /** Loads and starts the track at i in the queue. */
    private static void load(final int i) {
        final Track t = i >= 0 && i < queue.size() ? queue.get(i) : null;
        if (t == null) return;
        final int mine = ++token;
        release();
        index = i;
        loading = true;
        position = 0;
        duration = t.durationMs > 0 ? t.durationMs / 1000.0 : 0;
        quality = null;
        source = null;
        preparing = null;
        wantPlay = true;
        changed();
        // A downloaded song plays from the phone, no addon or network needed.
        String local = MusicDownloads.fileFor(t);
        if (local != null) {
            Playable own = new Playable();
            own.url = local;
            own.quality = "Downloaded";
            begin(t, own, mine);
            return;
        }
        final List<Addon> addons = Addons.load();
        if (addons.isEmpty()) {
            loading = false;
            say("No music addon installed yet. Add one in Music > Addons.");
            return;
        }
        MusicNet.async(new MusicNet.Job<Playable>() {
            @Override
            public Playable run() {
                return Matcher.findAudio(t, addons, t.mbid != null ? new Matcher.IsrcLookup() {
                    @Override
                    public String find() throws Exception {
                        return MusicBrainz.getRecordingIsrc(t.mbid);
                    }
                } : null, new Addons.Waiting() {
                    @Override
                    public boolean cancelled() {
                        return mine != token;
                    }

                    @Override
                    public void preparing(final String text) {
                        H.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mine != token) return;
                                preparing = text;
                                changed();
                            }
                        });
                    }
                });
            }
        }, new MusicNet.Done<Playable>() {
            @Override
            public void done(Playable found, Exception error) {
                if (mine != token) return;
                if (found == null) {
                    loading = false;
                    preparing = null;
                    say("No source available for \u201c" + t.title + "\u201d");
                    // Move on so a queue doesn't stall on one missing song.
                    if (index < queue.size() - 1) {
                        H.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (mine == token) advance(true);
                            }
                        }, 1500);
                    }
                    return;
                }
                begin(t, found, mine);
            }
        });
    }

    private static void begin(final Track t, Playable found, final int mine) {
        quality = found.quality != null ? found.quality : found.format;
        source = found.addon != null ? found.addon.name() : null;
        try {
            MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setWakeMode(MusicNet.context(), PowerManager.PARTIAL_WAKE_LOCK);
            p.setVolume(volume, volume);
            p.setDataSource(found.url.startsWith("/") ? found.url : fixed(found.url));
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer m) {
                    if (mine != token || m != mp) return;
                    if (stall != null) H.removeCallbacks(stall);
                    prepared = true;
                    loading = false;
                    int d = m.getDuration();
                    if (d > 0) duration = d / 1000.0;
                    remember(t);
                    counted(t, mine);
                    startListening(t);
                    if (wantPlay && takeFocus()) {
                        m.start();
                        playing = true;
                        H.removeCallbacks(TICKER);
                        H.post(TICKER);
                    }
                    changed();
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer m) {
                    if (mine == token && m == mp) advance(true);
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer m, int what, int extra) {
                    if (mine != token || m != mp) return true;
                    // Android's player can't take this one: libVLC tries before giving up.
                    useVlc(t, found, mine);
                    return true;
                }
            });
            changed();
            p.prepareAsync();
            // Some links just hang in Android's player: after 10 seconds, libVLC gets the song.
            final Playable f = found;
            stall = new Runnable() {
                @Override
                public void run() {
                    if (mine == token && !prepared && !vlcMode) useVlc(t, f, mine);
                }
            };
            H.postDelayed(stall, 10000);
        } catch (Exception e) {
            useVlc(t, found, mine);
        }
    }

    private static int countedToken = -1;

    /** Counts a song once per play, even when it moves from Android's player to VLC halfway. */
    private static void counted(Track t, int mine) {
        if (countedToken == mine) return;
        countedToken = mine;
        Taste.recordPlay(t);
        com.codio.tv.ui.Stats.record(t);
    }

    /** Links with spaces (some addons send them) are escaped. */
    private static String fixed(String url) {
        return url.trim().replace(" ", "%20");
    }

    private static void engineVolume(float v) {
        if (vlcMode && vp != null) vp.setVolume(Math.round(v * 100));
        else if (mp != null) mp.setVolume(v, v);
    }

    private static void giveUp(Track t) {
        loading = false;
        playing = false;
        say("Couldn't play \u201c" + t.title + "\u201d");
        advance(true);
    }

    /** Plays the song with libVLC (audio only), which handles FLAC, Opus, WebM, Ogg and more. */
    private static void useVlc(final Track t, Playable found, final int mine) {
        if (vlcMode) return;
        if (stall != null) H.removeCallbacks(stall);
        if (mp != null) {
            try {
                mp.setOnCompletionListener(null);
                mp.setOnErrorListener(null);
                mp.setOnPreparedListener(null);
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        prepared = false;
        try {
            if (vlc == null) {
                ArrayList<String> o = new ArrayList<>();
                o.add("--no-video");
                o.add("--network-caching=3000");
                vlc = new LibVLC(MusicNet.context(), o);
            }
            vlcMode = true;
            final org.videolan.libvlc.MediaPlayer p = new org.videolan.libvlc.MediaPlayer(vlc);
            vp = p;
            Media m = new Media(vlc, found.url.startsWith("/") ? android.net.Uri.fromFile(new java.io.File(found.url)) : android.net.Uri.parse(fixed(found.url)));
            m.addOption(":no-video");
            p.setMedia(m);
            m.release();
            p.setVolume(Math.round(volume * 100));
            p.setEventListener(new org.videolan.libvlc.MediaPlayer.EventListener() {
                @Override
                public void onEvent(org.videolan.libvlc.MediaPlayer.Event e) {
                    if (mine != token || p != vp) return;
                    switch (e.type) {
                        case org.videolan.libvlc.MediaPlayer.Event.Playing:
                            if (!prepared) {
                                prepared = true;
                                loading = false;
                                remember(t);
                                counted(t, mine);
                                startListening(t);
                            }
                            if (!wantPlay) {
                                p.pause();
                                break;
                            }
                            playing = true;
                            H.removeCallbacks(TICKER);
                            H.post(TICKER);
                            changed();
                            break;
                        case org.videolan.libvlc.MediaPlayer.Event.Paused:
                            playing = false;
                            H.removeCallbacks(TICKER);
                            changed();
                            break;
                        case org.videolan.libvlc.MediaPlayer.Event.LengthChanged:
                            if (e.getLengthChanged() > 0) duration = e.getLengthChanged() / 1000.0;
                            break;
                        case org.videolan.libvlc.MediaPlayer.Event.EndReached:
                            advance(true);
                            break;
                        case org.videolan.libvlc.MediaPlayer.Event.EncounteredError:
                            giveUp(t);
                            break;
                        default:
                            break;
                    }
                }
            });
            if (takeFocus()) p.play();
            else giveUp(t);
        } catch (Throwable e) {
            vlcMode = false;
            giveUp(t);
        }
    }

    private static void advance(boolean auto) {
        if (queue.isEmpty()) return;
        if (auto && repeat == REPEAT_ONE && prepared && (mp != null || (vlcMode && vp != null))) {
            if (vlcMode) {
                vp.setTime(0);
                vp.play();
            } else {
                mp.seekTo(0);
                mp.start();
            }
            Track t = current();
            if (t != null) startListening(t);
            return;
        }
        boolean last = index >= queue.size() - 1;
        if (last) {
            if (repeat == REPEAT_ALL) load(0);
            else {
                playing = false;
                position = 0;
                wantPlay = false;
                H.removeCallbacks(TICKER);
                if (vlcMode && vp != null) {
                    vp.pause();
                    vp.setTime(0);
                } else if (mp != null && prepared) {
                    try {
                        mp.pause();
                        mp.seekTo(0);
                    } catch (Exception ignored) {
                    }
                }
                changed();
            }
            return;
        }
        load(index + 1);
    }

    private static <T> List<T> shuffled(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.shuffle(copy);
        return copy;
    }

    // ---- what the screens call

    public static void playQueue(List<Track> tracks, int start, boolean shuffleOn) {
        if (tracks == null || tracks.isEmpty()) return;
        List<Track> q = new ArrayList<>(tracks);
        int i = Math.max(0, Math.min(start, q.size() - 1));
        if (shuffleOn) {
            Track first = q.remove(i);
            Collections.shuffle(q);
            q.add(0, first);
            i = 0;
        }
        queue = q;
        index = -1;
        shuffle = shuffleOn;
        load(i);
    }

    public static void playQueue(List<Track> tracks, int start) {
        playQueue(tracks, start, shuffle);
    }

    public static void playNext(Track t) {
        if (queue.isEmpty()) {
            playQueue(Collections.singletonList(t), 0, false);
            return;
        }
        queue = new ArrayList<>(queue);
        queue.add(index + 1, t);
        changed();
    }

    public static void addToQueue(Track t) {
        if (queue.isEmpty()) {
            playQueue(Collections.singletonList(t), 0, false);
            return;
        }
        queue = new ArrayList<>(queue);
        queue.add(t);
        changed();
    }

    public static void playIndex(int i) {
        load(i);
    }

    public static void next() {
        advance(false);
    }

    public static void previous() {
        if (prepared && (mp != null || vp != null) && (position > 4 || index <= 0)) {
            seek(0);
            return;
        }
        if (index > 0) load(index - 1);
    }

    public static void pause() {
        wantPlay = false;
        if (vlcMode && vp != null) {
            if (playing) vp.pause();
        } else if (mp != null && prepared && playing) {
            try {
                mp.pause();
            } catch (Exception ignored) {
            }
        }
        playing = false;
        H.removeCallbacks(TICKER);
        changed();
    }

    public static void resume() {
        wantPlay = true;
        if (vlcMode && vp != null && prepared && !playing && takeFocus()) {
            vp.play();
            return;
        }
        if (mp != null && prepared && !playing && takeFocus()) {
            try {
                mp.start();
                playing = true;
                H.removeCallbacks(TICKER);
                H.post(TICKER);
            } catch (Exception ignored) {
            }
            changed();
        }
    }

    public static void toggle() {
        if (playing) pause();
        else resume();
    }

    public static void seek(double seconds) {
        if (vlcMode && vp != null && prepared) {
            vp.setTime((long) (Math.max(0, seconds) * 1000));
            position = Math.max(0, seconds);
            lastPosition = position;
            tick();
            return;
        }
        if (mp == null || !prepared) return;
        try {
            mp.seekTo((int) (Math.max(0, seconds) * 1000));
            position = Math.max(0, seconds);
            lastPosition = position;
            tick();
        } catch (Exception ignored) {
        }
    }

    public static void toggleShuffle() {
        if (!shuffle && queue.size() > 2) {
            List<Track> q = new ArrayList<>(queue.subList(0, index + 1));
            q.addAll(shuffled(queue.subList(index + 1, queue.size())));
            queue = q;
        }
        shuffle = !shuffle;
        changed();
    }

    public static void cycleRepeat() {
        repeat = (repeat + 1) % 3;
        changed();
    }

    public static void removeFromQueue(int i) {
        if (i == index || i < 0 || i >= queue.size()) return;
        List<Track> q = new ArrayList<>(queue);
        q.remove(i);
        queue = q;
        if (i < index) index--;
        changed();
    }

    public static void stop() {
        token++;
        release();
        giveFocus();
        queue = new ArrayList<>();
        index = -1;
        loading = false;
        position = 0;
        duration = 0;
        quality = null;
        source = null;
        wantPlay = false;
        changed();
    }

    /** Volume 0 to 1, kept for next time. This is the music's own volume, on top of the phone's. */
    public static void setVolume(float v) {
        volume = Math.max(0f, Math.min(1f, v));
        engineVolume(volume);
        MusicNet.prefs().edit().putFloat(VOLUME_KEY, volume).apply();
        changed();
    }

    private static float beforeMute = 1f;

    public static void toggleMute() {
        if (volume > 0) {
            beforeMute = volume;
            setVolume(0);
        } else {
            setVolume(beforeMute > 0 ? beforeMute : 1f);
        }
    }
}
