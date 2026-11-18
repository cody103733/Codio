package com.codio.tv.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.KeyEvent;

import com.codio.tv.music.Addons;
import com.codio.tv.music.ListenBrainz;
import com.codio.tv.music.Model;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.Taste;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Music, Addons: your music addons (add, order, remove), where the music information comes from, your ListenBrainz
 * account, and what Codio has learned about your taste. The same things as Music, Addons on Codio for Windows.
 */
final class MusicSettingsScreen extends Screen implements TvMusicKit.Listening {
    private static final float X = TvView.MARGIN, W = 1280, ROW_H = 96, HEAD_H = 150;

    private static final class Item {
        String title, sub, head, note;
        Runnable action;
        int subColor;
    }

    private final List<Item> items = new ArrayList<Item>();
    private int focus;
    private boolean busy, sure;
    // The Last.fm key last checked, and what Last.fm said: 1 accepted, 0 not, -1 couldn't ask, 2 checking.
    private static String lastfmChecked = "";
    private static int lastfmState = -1;
    private final Smooth scroll = new Smooth(0.1f, 0.4f);
    private final RectF r = new RectF();

    MusicSettingsScreen(TvView tv) {
        super(tv);
        TvMusicKit.start(tv);
        fill();
    }

    @Override
    public void musicChanged() {
        fill();
    }

    @Override
    void onShow() {
        fill();
    }

    private Item item(String title, String sub, Runnable action) {
        Item i = new Item();
        i.title = title;
        i.sub = sub;
        i.action = action;
        items.add(i);
        return i;
    }

    private static String describe(Model.Addon a) {
        StringBuilder b = new StringBuilder();
        String v = Model.str(a.manifest, "version");
        if (v != null) b.append('v').append(v);
        StringBuilder can = new StringBuilder();
        for (String res : new String[]{"search", "stream", "catalog", "isrc", "resolve"}) {
            if (a.has(res)) can.append(can.length() > 0 ? ", " : "").append(res);
        }
        if (can.length() > 0) b.append(b.length() > 0 ? " · " : "").append(can);
        return b.toString();
    }

    private static String ago(long at) {
        if (at <= 0) return "not yet";
        long minutes = Math.round((System.currentTimeMillis() - at) / 60000.0);
        return minutes < 1 ? "just now" : minutes < 60 ? minutes + " min ago" : android.text.format.DateFormat.format("MMM d, HH:mm", at).toString();
    }

    private void fill() {
        items.clear();
        final List<Model.Addon> addons = Addons.load();
        Item add = item("Add a music addon", busy ? "Checking…" : "Type its link", new Runnable() {
            @Override
            public void run() {
                addAddon();
            }
        });
        add.head = "Music addons";
        add.note = "A music addon finds the audio for a song. Codio asks them in this order and plays from the first one that has the song. Your addon links stay on this TV and are only sent to the addon itself.";
        for (int i = 0; i < addons.size(); i++) {
            final int at = i;
            final Model.Addon a = addons.get(i);
            item((i + 1) + ". " + a.name(), describe(a), new Runnable() {
                @Override
                public void run() {
                    MusicMenu menu = new MusicMenu(tv, a.name(), describe(a));
                    if (at > 0) menu.add("Move up", new Runnable() {
                        @Override
                        public void run() {
                            move(at, -1);
                        }
                    });
                    if (at < addons.size() - 1) menu.add("Move down", new Runnable() {
                        @Override
                        public void run() {
                            move(at, 1);
                        }
                    });
                    menu.add("Remove", new Runnable() {
                        @Override
                        public void run() {
                            List<Model.Addon> list = Addons.load();
                            if (at < list.size()) list.remove(at);
                            Addons.save(list);
                            fill();
                        }
                    });
                    tv.push(menu);
                }
            });
        }
        String base = MusicNet.mbBase();
        Item server = item("Music information server", base.length() > 0 ? base : "musicbrainz.org", new Runnable() {
            @Override
            public void run() {
                tv.push(new TextEntryScreen(tv, "Music information server", "Your own MusicBrainz server. Leave it empty to use musicbrainz.org.", MusicNet.mbBase(), false, new TextEntryScreen.Done() {
                    @Override
                    public void done(String text) {
                        MusicNet.setMbBase(text);
                        fill();
                    }
                }));
            }
        });
        server.head = "Music information";
        server.note = "Artists, albums and covers come from MusicBrainz.";
        final String lf = MusicNet.lastfmKey();
        if (lf.length() > 0 && !lf.equals(lastfmChecked)) {
            lastfmChecked = lf;
            lastfmState = 2;
            MusicNet.async(new MusicNet.Job<Integer>() {
                @Override
                public Integer run() {
                    return com.codio.tv.music.Discovery.checkLastfm(lf);
                }
            }, new MusicNet.Done<Integer>() {
                @Override
                public void done(Integer v, Exception e) {
                    lastfmState = v == null ? -1 : v;
                    if (v != null && v == -1) lastfmChecked = "";
                    fill();
                }
            });
        }
        String lfSub = lf.length() == 0 ? "Not set: Popular rows use Apple Music" : lastfmState == 1 ? "✔ Connected: Popular rows use Last.fm" : lastfmState == 0 ? "Last.fm didn't accept this key (use the API key, not the shared secret)" : lastfmState == 2 ? "Checking the key…" : "Couldn't reach Last.fm to check the key";
        Item charts = item("Last.fm API key", lfSub, new Runnable() {
            @Override
            public void run() {
                tv.push(new TextEntryScreen(tv, "Last.fm API key", "Your own key from last.fm/api. Popular songs and artists then use Last.fm's chart instead of Apple's. Leave it empty for Apple's.", MusicNet.lastfmKey(), false, new TextEntryScreen.Done() {
                    @Override
                    public void done(String text) {
                        MusicNet.setLastfmKey(text);
                        fill();
                    }
                }));
            }
        });
        charts.note = "Syncs with your Sync passcode.";
        if (lf.length() > 0 && lastfmState == 1) charts.subColor = 0xFF46D369;
        else if (lf.length() > 0 && lastfmState == 0) charts.subColor = Theme.RED;
        if (lf.length() == 0) item("Get a Last.fm key", "last.fm/api/account/create", new Runnable() {
            @Override
            public void run() {
                WebLink.openOnTv(tv, WebLink.LASTFM_KEY);
            }
        });

        final ListenBrainz.Account account = ListenBrainz.account();
        if (account == null) {
            Item in = item("Sign in to ListenBrainz", busy ? "Checking…" : "Type your user token", new Runnable() {
                @Override
                public void run() {
                    signIn();
                }
            });
            in.head = "Your account";
            in.note = "Sign in and what you play and heart follows you to your other devices. Make a free account at listenbrainz.org and copy your user token from listenbrainz.org/settings. The token is only sent to ListenBrainz, and to your other devices locked with your sync passcode if you set one (Settings).";
            item("Get my ListenBrainz token", "listenbrainz.org/settings", new Runnable() {
                @Override
                public void run() {
                    WebLink.openOnTv(tv, WebLink.LISTENBRAINZ_TOKEN);
                }
            });
        } else {
            String sendError = ListenBrainz.sendError();
            Item who = item("Signed in as " + account.user, sendError != null ? sendError : account.syncError != null ? account.syncError : "Last sync: " + ago(account.lastSync), null);
            who.head = "Your account";
            who.note = ListenBrainz.sendStatus() + " What you play and the songs you heart sync with your ListenBrainz account. Hidden items stay on this TV.";
            item("Sync now", null, new Runnable() {
                @Override
                public void run() {
                    ListenBrainz.syncNow();
                    tv.toast("Syncing with ListenBrainz…");
                }
            });
            item("Sign out", null, new Runnable() {
                @Override
                public void run() {
                    ListenBrainz.signOut();
                    fill();
                }
            });
        }

        Taste.load();
        Item forget = item("Forget what I've played", "Play counts so far: " + Taste.plays.size() + " artists", new Runnable() {
            @Override
            public void run() {
                Taste.clearHistory();
                tv.toast("Forgotten");
            }
        });
        forget.head = "Your taste";
        forget.note = "Music's rows follow what you play, heart and hide.";
        item(sure ? "Press again to forget everything" : "Start over", "Forgets plays, hearts and hidden items", new Runnable() {
            @Override
            public void run() {
                if (sure) {
                    Taste.clearAll();
                    sure = false;
                    tv.toast("Started over");
                } else sure = true;
                fill();
            }
        });
        if (Taste.hiddenCount() > 0) {
            item("Show all hidden again", Taste.hiddenCount() + " hidden with Not interested", new Runnable() {
                @Override
                public void run() {
                    Taste.unhideAll();
                }
            });
            hidden("artist", Taste.hiddenArtists);
            hidden("album", Taste.hiddenAlbums);
            hidden("track", Taste.hiddenTracks);
        }
        focus = Math.max(0, Math.min(focus, items.size() - 1));
        tv.kick();
    }

    private void hidden(final String kind, Map<String, String> map) {
        for (final Map.Entry<String, String> e : new ArrayList<Map.Entry<String, String>>(map.entrySet())) {
            item(e.getValue(), "Hidden " + ("track".equals(kind) ? "song" : kind) + ". Press OK to show it again", new Runnable() {
                @Override
                public void run() {
                    Taste.unhide(kind, e.getKey());
                }
            });
        }
    }

    private void move(int index, int by) {
        List<Model.Addon> list = Addons.load();
        int target = index + by;
        if (index >= list.size() || target < 0 || target >= list.size()) return;
        Model.Addon a = list.get(index);
        list.set(index, list.get(target));
        list.set(target, a);
        Addons.save(list);
        focus = Math.max(0, Math.min(items.size() - 1, focus + by));
        fill();
    }

    private void addAddon() {
        tv.push(new TextEntryScreen(tv, "Music addon link", "The link the addon gave you, e.g. https://example.com/abc123", "https://", false, new TextEntryScreen.Done() {
            @Override
            public void done(final String text) {
                if (text.isEmpty() || text.equals("https://")) return;
                busy = true;
                fill();
                MusicNet.async(new MusicNet.Job<Model.Addon>() {
                    @Override
                    public Model.Addon run() throws Exception {
                        return Addons.fetchManifest(text);
                    }
                }, new MusicNet.Done<Model.Addon>() {
                    @Override
                    public void done(Model.Addon addon, Exception error) {
                        busy = false;
                        if (addon == null) {
                            tv.toast(error != null && error.getMessage() != null ? error.getMessage() : "Couldn't add that addon");
                        } else {
                            List<Model.Addon> list = Addons.load();
                            boolean have = false;
                            for (Model.Addon a : list) if (a.id().equals(addon.id())) have = true;
                            if (have) tv.toast("Already installed");
                            else {
                                list.add(addon);
                                Addons.save(list);
                                tv.toast("Added " + addon.name());
                            }
                        }
                        fill();
                    }
                });
            }
        }));
    }

    private void signIn() {
        tv.push(new TextEntryScreen(tv, "ListenBrainz user token", "From listenbrainz.org/settings. Letters, numbers and dashes.", null, false, new TextEntryScreen.Done() {
            @Override
            public void done(String text) {
                if (text.isEmpty()) return;
                busy = true;
                fill();
                ListenBrainz.signIn(text, new MusicNet.Done<String>() {
                    @Override
                    public void done(String user, Exception error) {
                        busy = false;
                        tv.toast(user != null ? "Signed in as " + user : error != null && error.getMessage() != null ? error.getMessage() : "Couldn't sign in");
                        fill();
                    }
                });
            }
        }));
    }

    private float yOf(int index) {
        float y = 150;
        for (int i = 0; i < index; i++) y += ROW_H + (items.get(i + 1).head != null ? HEAD_H : 0);
        return y + (items.get(0).head != null ? HEAD_H : 0);
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(Theme.BG);
        scroll.to(items.isEmpty() ? 0 : Math.max(0, yOf(focus) - 520));
        float sy = step(scroll, dt);
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            float y = yOf(i) - sy;
            if (y > TvView.H || y < -ROW_H - HEAD_H) continue;
            if (it.head != null) {
                c.drawText(it.head, X, y - HEAD_H + 70, tv.tTitle);
                if (it.note != null) tv.drawWrapped(c, it.note, tv.tSmall, X, y - HEAD_H + 84, 1500, 2);
            }
            boolean f = i == focus;
            r.set(X, y, X + W, y + ROW_H - 10);
            tv.fill.setColor(f ? Theme.FOCUS : Theme.CARD);
            c.drawRoundRect(r, 12, 12, tv.fill);
            tv.tLabel.setColor(f ? Theme.ON_FOCUS : Color.WHITE);
            tv.tSmall.setColor(f ? Theme.ON_FOCUS_DIM : it.subColor != 0 ? it.subColor : Theme.TEXT_FAINT);
            if (it.sub != null && it.sub.length() > 0) {
                c.drawText(tv.fit(it.title, tv.tLabel, W - 60), X + 30, y + 38, tv.tLabel);
                c.drawText(tv.fit(it.sub, tv.tSmall, W - 60), X + 30, y + 70, tv.tSmall);
            } else {
                c.drawText(tv.fit(it.title, tv.tLabel, W - 60), X + 30, y + 53, tv.tLabel);
            }
        }
        tv.tLabel.setColor(Color.WHITE);
        tv.tSmall.setColor(Theme.TEXT_DIM);
        // The page's name stays on top.
        tv.fill.setColor(Theme.BG);
        c.drawRect(0, 0, TvView.W, 112, tv.fill);
        c.drawText("Music addons and account", X, 84, tv.tTitle);
    }

    @Override
    boolean key(int k, KeyEvent e) {
        if (TvMusicKit.mediaKey(k)) return true;
        if (k == TvView.UP && focus > 0) {
            focus--;
            if (sure) {
                sure = false;
                fill();
            }
        } else if (k == TvView.DOWN && focus < items.size() - 1) {
            focus++;
            if (sure) {
                sure = false;
                fill();
            }
        } else if (k == TvView.OK && focus < items.size() && items.get(focus).action != null) items.get(focus).action.run();
        return true;
    }
}
