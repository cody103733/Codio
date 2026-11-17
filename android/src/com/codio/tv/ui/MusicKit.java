package com.codio.tv.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.codio.tv.music.ArtistPhotos;
import com.codio.tv.music.Model.Album;
import com.codio.tv.music.Model.Artist;
import com.codio.tv.music.Model.Track;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.MusicPlayer;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.List;

/** Views the Music screens share: covers, tiles, rows of tiles, and song rows with a heart and a menu. */
final class MusicKit {
    private MusicKit() {}

    /** A cover or artist photo: a dark square (or circle) with a letter until the picture arrives. */
    static final class Cover extends FrameLayout {
        final ImageView img;
        private final TextView letter;
        private final int px;

        Cover(Context c, int sizeDp, boolean round, String label) {
            super(c);
            px = PhoneKit.dp(c, sizeDp);
            final float radius = round ? px / 2f : PhoneKit.dp(c, sizeDp >= 100 ? 8 : 5);
            setBackground(PhoneKit.round(Theme.RAISED, radius));
            letter = PhoneKit.text(c, label == null || label.length() == 0 ? "♪" : label.substring(0, 1).toUpperCase(), Math.max(14, sizeDp / 3f), Theme.TEXT_FAINT, PhoneKit.BOLD);
            letter.setGravity(Gravity.CENTER);
            addView(letter, new LayoutParams(-1, -1));
            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(img, new LayoutParams(-1, -1));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            setClipToOutline(true);
            setLayoutParams(new ViewGroup.LayoutParams(px, px));
        }

        void set(String url) {
            PhoneKit.image(img, url, px * 2);
        }

        /** An artist's photo: the address if known, else found by name (Deezer, else their top album's cover). */
        void setArtist(final String mbid, final String name, String url) {
            if (url != null) {
                set(url);
                return;
            }
            final String tag = "artist:" + mbid;
            img.setTag(tag);
            img.setImageDrawable(null);
            if (mbid == null) return;
            MusicNet.async(new MusicNet.Job<String>() {
                @Override
                public String run() {
                    return ArtistPhotos.find(mbid, name);
                }
            }, new MusicNet.Done<String>() {
                @Override
                public void done(String found, Exception error) {
                    if (found != null && tag.equals(img.getTag())) set(found);
                }
            });
        }
    }

    static TextView title(Context c, String s) {
        TextView t = PhoneKit.text(c, s, 14, Color.WHITE, PhoneKit.SEMIBOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static TextView sub(Context c, String s) {
        TextView t = PhoneKit.text(c, s == null ? "" : s, 12, Theme.TEXT_DIM, PhoneKit.REGULAR);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static View tile(Context c, Cover cover, String title, String sub, int widthDp, View.OnClickListener click, View.OnLongClickListener hold) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(cover);
        TextView t = title(c, title);
        t.setPadding(0, PhoneKit.dp(c, 6), 0, 0);
        col.addView(t);
        col.addView(sub(c, sub));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(PhoneKit.dp(c, widthDp), -2);
        lp.rightMargin = PhoneKit.dp(c, 12);
        col.setLayoutParams(lp);
        col.setOnClickListener(click);
        col.setOnLongClickListener(hold);
        return col;
    }

    /** A heading and a sideways row of tiles. */
    static LinearLayout row(Context c, String heading, List<View> tiles) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(PhoneUi.sectionTitle(c, heading));
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout in = new LinearLayout(c);
        in.setPadding(PhoneKit.dp(c, 14), 0, PhoneKit.dp(c, 2), 0);
        for (View v : tiles) in.addView(v);
        hs.addView(in);
        box.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    static View albumTile(final PhoneUi ui, final Album a) {
        Context c = ui.getContext();
        Cover cv = new Cover(c, 128, false, a.title);
        cv.set(a.artworkURL);
        String sub = a.artist;
        if (a.year != null) sub = (sub.length() > 0 ? sub + " · " : "") + a.year;
        return tile(c, cv, a.title, sub, 128, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.push(new PhoneAlbum(ui, a));
            }
        }, new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                albumMenu(ui, a);
                return true;
            }
        });
    }

    static View artistTile(final PhoneUi ui, final Artist a) {
        Context c = ui.getContext();
        Cover cv = new Cover(c, 112, true, a.name);
        cv.setArtist(a.addon == null ? a.id : null, a.name, a.artworkURL);
        View t = tile(c, cv, a.name, a.detail, 112, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.push(new PhoneArtist(ui, a));
            }
        }, new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                artistMenu(ui, a);
                return true;
            }
        });
        return t;
    }

    /** A song as a tile (rows like Daily mix): tap plays the list from here. */
    static View trackTile(final PhoneUi ui, final List<Track> list, final int index) {
        Context c = ui.getContext();
        final Track t = list.get(index);
        Cover cv = new Cover(c, 128, false, t.title);
        cv.set(t.artworkURL);
        return tile(c, cv, t.title, t.artist, 128, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MusicPlayer.playQueue(list, index, false);
            }
        }, new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                trackMenu(ui, t);
                return true;
            }
        });
    }

    static List<View> albumTiles(PhoneUi ui, List<Album> list) {
        List<View> out = new ArrayList<>();
        for (Album a : list) out.add(albumTile(ui, a));
        return out;
    }

    static List<View> artistTiles(PhoneUi ui, List<Artist> list) {
        List<View> out = new ArrayList<>();
        for (Artist a : list) out.add(artistTile(ui, a));
        return out;
    }

    static List<View> trackTiles(PhoneUi ui, List<Track> list) {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) out.add(trackTile(ui, list, i));
        return out;
    }

    // ---- menus (long press, and the dots on a song)

    static void trackMenu(final PhoneUi ui, final Track t) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        labels.add("Play next");
        acts.add(new Runnable() {
            @Override
            public void run() {
                MusicPlayer.playNext(t);
                ui.toast("Plays next");
            }
        });
        labels.add("Add to queue");
        acts.add(new Runnable() {
            @Override
            public void run() {
                MusicPlayer.addToQueue(t);
                ui.toast("Added to the queue");
            }
        });
        labels.add(Taste.isLikedTrack(t) ? "Remove heart" : "Heart");
        acts.add(new Runnable() {
            @Override
            public void run() {
                Taste.toggleLikeTrack(t);
            }
        });
        if (t.artistMbid != null) {
            labels.add("Go to " + t.artist);
            acts.add(new Runnable() {
                @Override
                public void run() {
                    Artist a = new Artist();
                    a.id = t.artistMbid;
                    a.name = t.artist;
                    ui.push(new PhoneArtist(ui, a));
                }
            });
        }
        labels.add("Not interested");
        acts.add(new Runnable() {
            @Override
            public void run() {
                Taste.hideTrack(t);
                ui.toast("Hidden. Bring it back in Music > Addons.");
            }
        });
        choose(ui, t.title, labels, acts);
    }

    static void albumMenu(final PhoneUi ui, final Album a) {
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add(Taste.isLikedAlbum(a) ? "Remove heart" : "Heart");
        acts.add(new Runnable() {
            @Override
            public void run() {
                Taste.toggleLikeAlbum(a);
            }
        });
        labels.add("Not interested");
        acts.add(new Runnable() {
            @Override
            public void run() {
                Taste.hideAlbum(a);
                ui.toast("Hidden. Bring it back in Music > Addons.");
            }
        });
        choose(ui, a.title, labels, acts);
    }

    static void artistMenu(final PhoneUi ui, final Artist a) {
        List<String> labels = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        labels.add(Taste.isLikedArtist(a) ? "Remove heart" : "Heart");
        acts.add(new Runnable() {
            @Override
            public void run() {
                Taste.toggleLikeArtist(a);
            }
        });
        labels.add("Not interested");
        acts.add(new Runnable() {
            @Override
            public void run() {
                Taste.hideArtist(a);
                ui.toast("Hidden. Bring it back in Music > Addons.");
            }
        });
        choose(ui, a.name, labels, acts);
    }

    private static void choose(PhoneUi ui, String title, List<String> labels, final List<Runnable> acts) {
        new AlertDialog.Builder(ui.getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert).setTitle(title)
                .setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        acts.get(which).run();
                    }
                }).show();
    }

    // ---- a song in a vertical list

    static final class TrackRow extends LinearLayout {
        final Track track;
        private final TextView title;
        private final MusicIcons heart;

        TrackRow(final PhoneUi ui, final Track t, int number, final View.OnClickListener play) {
            super(ui.getContext());
            this.track = t;
            Context c = getContext();
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(PhoneKit.dp(c, 16), PhoneKit.dp(c, 6), PhoneKit.dp(c, 4), PhoneKit.dp(c, 6));
            setBackground(PhoneKit.pressable(null, 0));
            if (number > 0) {
                TextView n = PhoneKit.text(c, String.valueOf(number), 15, Theme.TEXT_DIM, PhoneKit.REGULAR);
                n.setGravity(Gravity.CENTER);
                addView(n, new LayoutParams(PhoneKit.dp(c, 28), -2));
            } else {
                Cover cv = new Cover(c, 44, false, t.title);
                cv.set(t.artworkURL);
                LayoutParams lp = new LayoutParams(PhoneKit.dp(c, 44), PhoneKit.dp(c, 44));
                lp.rightMargin = PhoneKit.dp(c, 12);
                addView(cv, lp);
            }
            LinearLayout text = new LinearLayout(c);
            text.setOrientation(VERTICAL);
            title = PhoneKit.text(c, t.title, 16, Color.WHITE, PhoneKit.REGULAR);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(title);
            String line = t.artist;
            if (t.durationMs > 0) line = (line.length() > 0 ? line + " · " : "") + com.codio.tv.music.Model.formatTime(t.durationMs / 1000.0);
            text.addView(sub(c, line));
            addView(text, new LayoutParams(0, -2, 1));
            heart = new MusicIcons(c, Taste.isLikedTrack(t) ? MusicIcons.HEART_FILL : MusicIcons.HEART, Taste.isLikedTrack(t) ? Theme.RED : PhoneKit.GRAY);
            heart.setPadding(PhoneKit.dp(c, 8), 0, PhoneKit.dp(c, 8), 0);
            heart.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Taste.toggleLikeTrack(t);
                    refresh();
                }
            });
            addView(heart, new LayoutParams(PhoneKit.dp(c, 40), PhoneKit.dp(c, 40)));
            MusicIcons more = new MusicIcons(c, MusicIcons.MORE, PhoneKit.GRAY);
            more.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    trackMenu(ui, t);
                }
            });
            addView(more, new LayoutParams(PhoneKit.dp(c, 40), PhoneKit.dp(c, 40)));
            setOnClickListener(play);
            setOnLongClickListener(new OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    trackMenu(ui, t);
                    return true;
                }
            });
            refresh();
        }

        /** The heart and the red title of the song that is playing. */
        void refresh() {
            boolean liked = Taste.isLikedTrack(track);
            heart.set(liked ? MusicIcons.HEART_FILL : MusicIcons.HEART, liked ? Theme.RED : PhoneKit.GRAY);
            Track now = MusicPlayer.current();
            title.setTextColor(now != null && now.same(track) ? Theme.RED_SOFT : Color.WHITE);
        }
    }

    /** Adds the songs as rows (numbered when numbered); tap plays the list from there. */
    static void trackList(final PhoneUi ui, LinearLayout into, final List<Track> list, boolean numbered) {
        for (int i = 0; i < list.size(); i++) {
            final int at = i;
            into.addView(new TrackRow(ui, list.get(i), numbered ? i + 1 : 0, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    MusicPlayer.playQueue(list, at, false);
                }
            }));
        }
    }

    /** Refreshes the hearts and the playing song's colour in every row of a list. */
    static void refreshRows(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v instanceof TrackRow) ((TrackRow) v).refresh();
            else if (v instanceof ViewGroup) refreshRows((ViewGroup) v);
        }
    }

    static TextView note(Context c, String s) {
        TextView t = PhoneKit.text(c, s, 14, Theme.TEXT_DIM, PhoneKit.REGULAR);
        t.setPadding(PhoneKit.dp(c, 16), PhoneKit.dp(c, 6), PhoneKit.dp(c, 16), PhoneKit.dp(c, 6));
        return t;
    }
}
