package com.codio.tv.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.IBinder;

import com.codio.tv.R;
import com.codio.tv.music.Model.Track;

/**
 * Keeps the music playing with the screen off or Codio in the background: a foreground "media playback" service with
 * the notification (previous, play/pause, next) and a media session for the lock screen and headset buttons. The player
 * itself is MusicPlayer; this only shows it.
 */
public final class MusicService extends Service {
    private static final String CHANNEL = "codio_music";
    private static final int ID = 4711;
    private static final String A_TOGGLE = "com.codio.music.TOGGLE", A_NEXT = "com.codio.music.NEXT", A_PREV = "com.codio.music.PREV", A_STOP = "com.codio.music.STOP";

    private static MusicService live;
    private MediaSession session;
    private String artFor;
    private Bitmap art;
    private boolean foreground;
    private final BroadcastReceiver noisy = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            MusicPlayer.pause();
        }
    };

    /** Called by the player whenever something changed (main thread). */
    static void update() {
        Context c = MusicNet.context();
        if (c == null) return;
        if (!MusicPlayer.hasQueue()) {
            if (live != null) c.stopService(new Intent(c, MusicService.class));
            return;
        }
        if (live != null) {
            live.refresh();
        } else if (MusicPlayer.playing() || MusicPlayer.loading()) {
            try {
                c.startForegroundService(new Intent(c, MusicService.class));
            } catch (Exception ignored) {
                // not allowed from the background: the music plays on, just without the notification
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        live = this;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        session = new MediaSession(this, "Codio");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                MusicPlayer.resume();
            }

            @Override
            public void onPause() {
                MusicPlayer.pause();
            }

            @Override
            public void onSkipToNext() {
                MusicPlayer.next();
            }

            @Override
            public void onSkipToPrevious() {
                MusicPlayer.previous();
            }

            @Override
            public void onSeekTo(long pos) {
                MusicPlayer.seek(pos / 1000.0);
            }

            @Override
            public void onStop() {
                MusicPlayer.stop();
            }
        });
        session.setActive(true);
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (A_TOGGLE.equals(a)) MusicPlayer.toggle();
        else if (A_NEXT.equals(a)) MusicPlayer.next();
        else if (A_PREV.equals(a)) MusicPlayer.previous();
        else if (A_STOP.equals(a)) MusicPlayer.stop();
        if (MusicPlayer.hasQueue()) refresh();
        else {
            // started with startForegroundService, so it must call startForeground before it stops again
            try {
                startForeground(ID, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notify).setContentTitle("Music").build());
            } catch (Exception ignored) {
            }
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private PendingIntent action(String a, int code) {
        Intent i = new Intent(this, MusicService.class).setAction(a);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void refresh() {
        Track t = MusicPlayer.current();
        if (t == null) return;
        boolean playing = MusicPlayer.playing();
        if (t.artworkURL != null && !t.artworkURL.equals(artFor)) {
            final String url = t.artworkURL;
            artFor = url;
            art = null;
            MusicNet.POOL.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        byte[] b = MusicNet.get(url, 15000).body;
                        final Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                        MusicNet.MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                if (url.equals(artFor) && bm != null && live == MusicService.this) {
                                    art = bm;
                                    refresh();
                                }
                            }
                        });
                    } catch (Exception ignored) {
                    }
                }
            });
        }
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, t.album == null ? "" : t.album)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (MusicPlayer.duration() * 1000));
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : MusicPlayer.loading() ? PlaybackState.STATE_BUFFERING : PlaybackState.STATE_PAUSED,
                        (long) (MusicPlayer.position() * 1000), 1f)
                .build());

        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent content = open == null ? null : PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(t.title)
                .setContentText(t.artist)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(action(A_STOP, 4))
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", action(A_PREV, 1)).build())
                .addAction(new Notification.Action.Builder(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", action(A_TOGGLE, 2)).build())
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", action(A_NEXT, 3)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (art != null) b.setLargeIcon(art);
        Notification n = b.build();
        try {
            if (playing || MusicPlayer.loading()) {
                if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(ID, n);
                foreground = true;
            } else {
                if (foreground) stopForeground(false);
                foreground = false;
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID, n);
            }
        } catch (Exception ignored) {
            // a foreground start refused: keep the plain notification
            try {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(ID, n);
            } catch (Exception ignored2) {
            }
        }
    }

    @Override
    public void onDestroy() {
        if (live == this) live = null;
        try {
            unregisterReceiver(noisy);
        } catch (Exception ignored) {
        }
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        stopForeground(true);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
