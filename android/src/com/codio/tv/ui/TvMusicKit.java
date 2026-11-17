package com.codio.tv.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;

import com.codio.tv.music.Addons;
import com.codio.tv.music.ArtistPhotos;
import com.codio.tv.music.ListenBrainz;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicBrainz;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What the TV's Music screens share: tiles, the bar that shows what is playing, opening things, the options list. */
final class TvMusicKit {
    private TvMusicKit() {}

    static final float TILE = 220, STEP = 250;
    /** Room the "playing" bar takes at the bottom of a Music screen. */
    static final float BAR_H = 96;

    private static boolean started;
    private static String lastMessage;

    /** Starts Music once: the player, your taste, your ListenBrainz account. Messages from the player show as toasts. */
    static void start(final TvView tv) {
        if (started) return;
        started = true;
        MusicPlayer.init(tv.getContext());
        Taste.load();
        ListenBrainz.start();
        MusicPlayer.listen(new MusicPlayer.Listener() {
            @Override
            public void changed() {
                String m = MusicPlayer.message();
                if (m != null && !m.equals(lastMessage)) tv.toast(m);
                lastMessage = m;
                if (onMusic(tv)) tv.kick();
            }

            @Override
            public void tick() {
                if (onMusic(tv)) tv.kick();
            }
        });
        Runnable redraw = new Runnable() {
            @Override
            public void run() {
                for (Screen s : new ArrayList<Screen>(tv.stack)) if (s instanceof Listening) ((Listening) s).musicChanged();
                tv.kick();
            }
        };
        Taste.listeners.add(redraw);
        ListenBrainz.listeners.add(redraw);
        Addons.listen(new Addons.Changed() {
            @Override
            public void changed() {
                MusicNet.MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        for (Screen s : new ArrayList<Screen>(tv.stack)) if (s instanceof Listening) ((Listening) s).musicChanged();
                        tv.kick();
                    }
                });
            }
        });
    }

    /** A Music screen: told when hearts, hidden things, the account or the addons change. */
    interface Listening {
        void musicChanged();
    }

    static boolean onMusic(TvView tv) {
        return !tv.stack.isEmpty() && tv.top() instanceof Listening;
    }

    // ---- what a tile shows

    static String title(Object o) {
        if (o instanceof Model.Track) return ((Model.Track) o).title;
        if (o instanceof Model.Album) return ((Model.Album) o).title;
        return ((Model.Artist) o).name;
    }

    static String sub(Object o) {
        if (o instanceof Model.Track) return ((Model.Track) o).artist;
        if (o instanceof Model.Album) {
            Model.Album a = (Model.Album) o;
            return a.year != null && a.artist.length() > 0 ? a.artist + " · " + a.year : a.year != null ? a.year : a.artist;
        }
        String d = ((Model.Artist) o).detail;
        return d == null ? "Artist" : d;
    }

    static boolean liked(Object o) {
        if (o instanceof Model.Track) return Taste.isLikedTrack((Model.Track) o);
        if (o instanceof Model.Album) return Taste.isLikedAlbum((Model.Album) o);
        return Taste.isLikedArtist((Model.Artist) o);
    }

    private static final Map<String, String> PHOTOS = new HashMap<String, String>();
    private static final Set<String> ASKED = new HashSet<String>();

    /** An artist's picture (looked up once, in the background), or null while it isn't known. */
    static String artistArt(final TvView tv, final String id, final String name, String given) {
        if (given != null) return given;
        if (id == null || !MusicBrainz.isMbid(id)) return null;
        String u = PHOTOS.get(id);
        if (u != null) return u.length() == 0 ? null : u;
        if (ASKED.add(id)) {
            MusicNet.async(new MusicNet.Job<String>() {
                @Override
                public String run() throws Exception {
                    return ArtistPhotos.find(id, name);
                }
            }, new MusicNet.Done<String>() {
                @Override
                public void done(String value, Exception error) {
                    PHOTOS.put(id, value == null ? "" : value);
                    tv.kick();
                }
            });
        }
        return null;
    }

    static String art(TvView tv, Object o) {
        if (o instanceof Model.Track) return ((Model.Track) o).artworkURL;
        if (o instanceof Model.Album) return ((Model.Album) o).artworkURL;
        Model.Artist a = (Model.Artist) o;
        return artistArt(tv, a.id, a.name, a.artworkURL);
    }

    private static final RectF R = new RectF();
    private static final Path HEART = new Path();

    /** A heart about `size` wide with its centre at cx, cy. */
    static void heart(TvView tv, Canvas c, float cx, float cy, float size, int color) {
        float s = size / 2;
        HEART.reset();
        HEART.moveTo(cx, cy + s * 0.9f);
        HEART.cubicTo(cx - s * 2.0f, cy - s * 0.3f, cx - s * 0.9f, cy - s * 1.5f, cx, cy - s * 0.45f);
        HEART.cubicTo(cx + s * 0.9f, cy - s * 1.5f, cx + s * 2.0f, cy - s * 0.3f, cx, cy + s * 0.9f);
        HEART.close();
        tv.fill.setColor(color);
        c.drawPath(HEART, tv.fill);
    }

    /** A cover at x, y (size w), round for artists; the first letter of the name until (or unless) a picture loads. */
    static void cover(TvView tv, Canvas c, String url, String name, float x, float y, float w, boolean round, int px) {
        float radius = round ? w / 2 : 12;
        Bitmap b = url == null ? null : tv.images.get(url, px, px, round ? px / 2f : 12 * tv.scale());
        R.set(x, y, x + w, y + w);
        if (b != null) {
            c.drawBitmap(b, null, R, tv.bmp);
            return;
        }
        tv.fill.setColor(Theme.RAISED);
        c.drawRoundRect(R, radius, radius, tv.fill);
        if (name != null && name.length() > 0) {
            String letter = name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
            float was = tv.tHero.getTextSize();
            tv.tHero.setTextSize(w * 0.36f);
            tv.tHero.setColor(Theme.TEXT_FAINT);
            c.drawText(letter, x + w / 2 - tv.tHero.measureText(letter) / 2, y + w / 2 + w * 0.13f, tv.tHero);
            tv.tHero.setColor(Color.WHITE);
            tv.tHero.setTextSize(was);
        }
    }

    /** A square tile (round for an artist) with its name and a second line under it; focus 0..1 grows it. */
    static void tile(TvView tv, Canvas c, Object o, float x, float y, float focus) {
        boolean round = o instanceof Model.Artist;
        float w = TILE * (1 + 0.09f * focus);
        float dx = x - (w - TILE) / 2, dy = y - (w - TILE) / 2;
        int px = Math.round(TILE * 1.09f * tv.scale());
        cover(tv, c, art(tv, o), title(o), dx, dy, w, round, px);
        R.set(dx, dy, dx + w, dy + w);
        if (focus > 0.01f) tv.ring(c, R, round ? w / 2 : 14, focus);
        if (liked(o)) {
            tv.fill.setColor(Theme.SCRIM);
            c.drawCircle(dx + w - 30, dy + 30, 20, tv.fill);
            heart(tv, c, dx + w - 30, dy + 31, 22, Theme.RED_SOFT);
        }
        Model.Track now = MusicPlayer.current();
        boolean playing = o instanceof Model.Track && now != null && now.same((Model.Track) o);
        float ty = y + TILE + 20 + 12 * focus;
        tv.tSmall.setColor(playing ? Theme.RED_SOFT : Color.WHITE);
        float tw = round ? Math.min(TILE, tv.tSmall.measureText(tv.fit(title(o), tv.tSmall, TILE))) : 0;
        c.drawText(tv.fit(title(o), tv.tSmall, TILE), round ? x + (TILE - tw) / 2 : x, ty + 22, tv.tSmall);
        tv.tSmall.setColor(Theme.TEXT_FAINT);
        String s = tv.fit(sub(o), tv.tSmall, TILE);
        c.drawText(s, round ? x + (TILE - tv.tSmall.measureText(s)) / 2 : x, ty + 54, tv.tSmall);
        tv.tSmall.setColor(Theme.TEXT_DIM);
    }

    // ---- doing things

    static List<Model.Track> tracksOf(List<?> items) {
        List<Model.Track> out = new ArrayList<Model.Track>();
        for (Object o : items) if (o instanceof Model.Track) out.add((Model.Track) o);
        return out;
    }

    /** OK on a tile or a line: a song plays (with the songs around it as the queue), an album or artist opens. */
    static void open(TvView tv, Object o, List<?> around) {
        if (o instanceof Model.Track) {
            List<Model.Track> tracks = tracksOf(around);
            int i = Math.max(0, tracks.indexOf(o));
            play(tv, tracks, i, MusicPlayer.shuffle());
        } else if (o instanceof Model.Album) {
            tv.push(new MusicAlbumScreen(tv, (Model.Album) o));
        } else if (o instanceof Model.Artist) {
            Model.Artist a = (Model.Artist) o;
            tv.push(new MusicArtistScreen(tv, a.id, a.name, a.addon));
        }
    }

    static void play(TvView tv, List<Model.Track> tracks, int index, boolean shuffle) {
        if (tracks.isEmpty()) return;
        if (Addons.load().isEmpty()) {
            tv.toast("No music addon yet. Add one in Music, Addons.");
            return;
        }
        MusicPlayer.playQueue(tracks, index, shuffle);
    }

    /** Holding OK on a tile or a line: what else you can do with it. */
    static void options(final TvView tv, final Object o, final List<?> around) {
        MusicMenu menu = new MusicMenu(tv, title(o), sub(o));
        final boolean liked = liked(o);
        if (o instanceof Model.Track) {
            final Model.Track t = (Model.Track) o;
            menu.add("Play", new Runnable() {
                @Override
                public void run() {
                    open(tv, o, around);
                }
            });
            menu.add("Play next", new Runnable() {
                @Override
                public void run() {
                    if (Addons.load().isEmpty()) tv.toast("No music addon yet. Add one in Music, Addons.");
                    else MusicPlayer.playNext(t);
                }
            });
            menu.add("Add to queue", new Runnable() {
                @Override
                public void run() {
                    if (Addons.load().isEmpty()) tv.toast("No music addon yet. Add one in Music, Addons.");
                    else MusicPlayer.addToQueue(t);
                }
            });
            menu.add(liked ? "Remove heart" : "Heart", new Runnable() {
                @Override
                public void run() {
                    Taste.toggleLikeTrack(t);
                }
            });
            if (t.artistMbid != null) menu.add("Go to artist", new Runnable() {
                @Override
                public void run() {
                    tv.push(new MusicArtistScreen(tv, t.artistMbid, t.artist, null));
                }
            });
            menu.add("Not interested", new Runnable() {
                @Override
                public void run() {
                    Taste.hideTrack(t);
                }
            });
        } else if (o instanceof Model.Album) {
            final Model.Album a = (Model.Album) o;
            menu.add("Open", new Runnable() {
                @Override
                public void run() {
                    open(tv, o, around);
                }
            });
            menu.add(liked ? "Remove heart" : "Heart", new Runnable() {
                @Override
                public void run() {
                    Taste.toggleLikeAlbum(a);
                }
            });
            if (a.artistMbid != null) menu.add("Go to artist", new Runnable() {
                @Override
                public void run() {
                    tv.push(new MusicArtistScreen(tv, a.artistMbid, a.artist, null));
                }
            });
            menu.add("Not interested", new Runnable() {
                @Override
                public void run() {
                    Taste.hideAlbum(a);
                }
            });
        } else {
            final Model.Artist a = (Model.Artist) o;
            menu.add("Open", new Runnable() {
                @Override
                public void run() {
                    open(tv, o, around);
                }
            });
            menu.add(liked ? "Remove heart" : "Heart", new Runnable() {
                @Override
                public void run() {
                    Taste.toggleLikeArtist(a);
                }
            });
            menu.add("Not interested", new Runnable() {
                @Override
                public void run() {
                    Taste.hideArtist(a);
                }
            });
        }
        tv.push(menu);
    }

    /** Play/Pause, Fast forward (next song) and Rewind (previous song) work on every Music screen. */
    static boolean mediaKey(int k) {
        if (!MusicPlayer.hasQueue()) return false;
        if (k == TvView.PLAY) MusicPlayer.toggle();
        else if (k == TvView.FF) MusicPlayer.next();
        else if (k == TvView.RW) MusicPlayer.previous();
        else return false;
        return true;
    }

    /** The bar along the bottom of a Music screen while there is a queue: cover, song, how far along it is. */
    static void nowBar(TvView tv, Canvas c, float dt) {
        Model.Track t = MusicPlayer.current();
        if (t == null) return;
        float y = TvView.H - BAR_H;
        tv.fill.setColor(0xF2141414);
        c.drawRect(0, y, TvView.W, TvView.H, tv.fill);
        double d = MusicPlayer.duration(), p = MusicPlayer.position();
        tv.fill.setColor(Theme.LINE);
        c.drawRect(0, y, TvView.W, y + 4, tv.fill);
        if (d > 0) {
            tv.fill.setColor(Theme.RED);
            c.drawRect(0, y, (float) (TvView.W * Math.max(0, Math.min(1, p / d))), y + 4, tv.fill);
        }
        cover(tv, c, t.artworkURL, t.title, TvView.MARGIN, y + 16, 64, false, Math.round(64 * tv.scale()));
        float x = TvView.MARGIN + 86;
        tv.tLabel.setColor(Color.WHITE);
        c.drawText(tv.fit(t.title, tv.tLabel, 800), x, y + 44, tv.tLabel);
        String state = MusicPlayer.loading() ? "Finding the song…" : MusicPlayer.playing() ? null : "Paused";
        String line = t.artist + (state != null ? "  ·  " + state : "");
        c.drawText(tv.fit(line, tv.tSmall, 800), x, y + 78, tv.tSmall);
        String right = Model.formatTime(p) + " / " + Model.formatTime(d) + "    Play/Pause   ·   Hold OK for options";
        tv.tSmall.setColor(Theme.TEXT_FAINT);
        c.drawText(right, TvView.W - TvView.MARGIN - tv.tSmall.measureText(right), y + 60, tv.tSmall);
        tv.tSmall.setColor(Theme.TEXT_DIM);
        if (MusicPlayer.loading()) tv.moving();
    }
}
