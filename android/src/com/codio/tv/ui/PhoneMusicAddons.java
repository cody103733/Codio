package com.codio.tv.ui;

import android.content.Context;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.codio.tv.music.Addons;
import com.codio.tv.music.ListenBrainz;
import com.codio.tv.music.Model.Addon;
import com.codio.tv.music.MusicNet;
import com.codio.tv.music.Taste;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Music addons, the music information server, the ListenBrainz account and what Codio has learned about your taste. */
final class PhoneMusicAddons extends PhoneUi.Page {
    private LinearLayout addonsBox, accountBox, tasteBox;
    private EditText link, server, token, lastfm;
    private TextView lastfmStatus;
    private int lastfmGen;
    private TextView error, accountError;
    private boolean busy, sure;
    private final Runnable tasteChanged = new Runnable() {
        @Override
        public void run() {
            drawTaste();
        }
    };
    private final Runnable accountChanged = new Runnable() {
        @Override
        public void run() {
            drawAccount();
        }
    };

    PhoneMusicAddons(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        Context c = ctx();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(ui.largeTitle("Music addons", true));
        ScrollView sv = new ScrollView(c);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(40));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        body.addView(heading("MUSIC ADDONS"));
        body.addView(footer("A music addon finds the audio for a song. Paste its link to add it. Codio asks them in this order and plays from the first one that has the song. Your addon links stay on this phone and are only sent to the addon itself."));
        LinearLayout add = new LinearLayout(c);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(dp(16), dp(4), dp(16), dp(4));
        link = input("Paste a music addon link", false);
        add.addView(link, new LinearLayout.LayoutParams(0, dp(40), 1));
        TextView addButton = PhoneKit.button(c, "Add", true);
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addAddon();
            }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, dp(40));
        al.leftMargin = dp(8);
        add.addView(addButton, al);
        body.addView(add);
        error = PhoneKit.text(c, "", 14, PhoneKit.DESTRUCTIVE, PhoneKit.REGULAR);
        error.setPadding(dp(16), dp(2), dp(16), dp(2));
        error.setVisibility(View.GONE);
        body.addView(error);
        addonsBox = new LinearLayout(c);
        addonsBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(addonsBox);
        drawAddons();

        body.addView(heading("MUSIC INFORMATION"));
        body.addView(footer("Artists, albums and covers come from MusicBrainz. If you run your own MusicBrainz server, put its address here. Leave it empty to use musicbrainz.org."));
        server = input("https://musicbrainz.org", false);
        server.setText(MusicNet.mbBase());
        server.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focus) {
                if (!focus) MusicNet.setMbBase(server.getText().toString());
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, dp(40));
        sl.setMargins(dp(16), dp(4), dp(16), dp(4));
        body.addView(server, sl);

        body.addView(heading("CHARTS"));
        body.addView(footer("Popular songs and artists come from Apple Music's chart for your country, with a way to listen there. Paste your own Last.fm API key (free at last.fm/api) to use Last.fm's chart instead. Leave it empty for Apple's. It syncs with your Sync passcode."));
        lastfm = input("Last.fm API key", false);
        lastfm.setText(MusicNet.lastfmKey());
        lastfm.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean focus) {
                if (!focus) {
                    MusicNet.setLastfmKey(lastfm.getText().toString());
                    checkLastfm();
                }
            }
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, dp(40));
        ll.setMargins(dp(16), dp(4), dp(16), dp(4));
        body.addView(lastfm, ll);
        final LinearLayout getRow = new LinearLayout(c);
        getRow.setPadding(dp(8), dp(2), dp(8), dp(2));
        getRow.addView(act("Get a key", Theme.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(WebLink.LASTFM_KEY);
            }
        }));
        getRow.setVisibility(MusicNet.lastfmKey().isEmpty() ? View.VISIBLE : View.GONE);
        lastfm.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                getRow.setVisibility(s.toString().trim().isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
        body.addView(getRow);
        lastfmStatus = PhoneKit.text(c, "", 14, PhoneKit.GRAY, PhoneKit.REGULAR);
        lastfmStatus.setPadding(dp(16), dp(2), dp(16), dp(2));
        body.addView(lastfmStatus);
        checkLastfm();

        body.addView(heading("YOUR ACCOUNT (LISTENBRAINZ)"));
        accountBox = new LinearLayout(c);
        accountBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(accountBox);
        drawAccount();

        body.addView(heading("YOUR TASTE"));
        tasteBox = new LinearLayout(c);
        tasteBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(tasteBox);
        drawTaste();
        return col;
    }

    @Override
    void onShow() {
        Taste.listeners.add(tasteChanged);
        ListenBrainz.listeners.add(accountChanged);
    }

    @Override
    void onHide() {
        Taste.listeners.remove(tasteChanged);
        ListenBrainz.listeners.remove(accountChanged);
        if (server != null) MusicNet.setMbBase(server.getText().toString());
        if (lastfm != null) MusicNet.setLastfmKey(lastfm.getText().toString());
    }

    // ---- small pieces

    private TextView heading(String s) {
        TextView t = PhoneKit.text(ctx(), s, 13, PhoneKit.SECONDARY, PhoneKit.REGULAR);
        t.setPadding(dp(32), dp(22), dp(16), dp(6));
        return t;
    }

    private TextView footer(String s) {
        TextView t = PhoneKit.text(ctx(), s, 13, PhoneKit.SECONDARY, PhoneKit.REGULAR);
        t.setPadding(dp(32), 0, dp(24), dp(8));
        return t;
    }

    private EditText input(String hint, boolean secret) {
        EditText e = new EditText(ctx());
        e.setHint(hint);
        e.setHintTextColor(PhoneKit.GRAY);
        e.setTextColor(Color.WHITE);
        e.setTextSize(16);
        e.setSingleLine(true);
        e.setTypeface(PhoneKit.face(PhoneKit.REGULAR));
        e.setBackground(PhoneKit.round(PhoneKit.GROUPED, dp(10)));
        e.setPadding(dp(12), 0, dp(12), 0);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_URI));
        return e;
    }

    private View line(String text, String meta) {
        Context c = ctx();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(PhoneKit.GROUPED);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.addView(PhoneKit.text(c, text, 16, Color.WHITE, PhoneKit.REGULAR));
        if (meta != null) {
            TextView m = PhoneKit.text(c, meta, 13, Theme.TEXT_DIM, PhoneKit.REGULAR);
            row.addView(m);
        }
        return row;
    }

    private TextView act(String label, int color, View.OnClickListener l) {
        TextView t = PhoneKit.link(ctx(), label, color);
        t.setTextSize(15);
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setOnClickListener(l);
        return t;
    }

    private View separator() {
        View v = new View(ctx());
        v.setBackgroundColor(PhoneKit.SEPARATOR);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));
        return v;
    }

    // ---- addons

    private void addAddon() {
        final String text = link.getText().toString();
        if (text.trim().length() == 0 || busy) return;
        busy = true;
        error.setVisibility(View.GONE);
        MusicNet.async(new MusicNet.Job<Addon>() {
            @Override
            public Addon run() throws Exception {
                return Addons.fetchManifest(text);
            }
        }, new MusicNet.Done<Addon>() {
            @Override
            public void done(Addon a, Exception e) {
                busy = false;
                if (a == null) {
                    error.setText(e != null ? e.getMessage() : "Couldn't add that addon");
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                List<Addon> list = Addons.load();
                for (Addon x : list) {
                    if (x.id().equals(a.id())) {
                        error.setText("Already installed");
                        error.setVisibility(View.VISIBLE);
                        return;
                    }
                }
                list.add(a);
                Addons.save(list);
                link.setText("");
                drawAddons();
            }
        });
    }

    private String describe(Addon a) {
        JSONArray r = a.manifest.optJSONArray("resources");
        StringBuilder sb = new StringBuilder();
        String v = a.manifest.optString("version", "");
        if (v.length() > 0) sb.append("v").append(v).append(" · ");
        for (int i = 0; r != null && i < r.length(); i++) sb.append(i > 0 ? ", " : "").append(r.optString(i));
        return sb.toString();
    }

    private void drawAddons() {
        Context c = ctx();
        addonsBox.removeAllViews();
        final List<Addon> list = Addons.load();
        if (list.isEmpty()) addonsBox.addView(footer("No music addons yet."));
        for (int i = 0; i < list.size(); i++) {
            final int at = i;
            final Addon a = list.get(i);
            if (i > 0) addonsBox.addView(separator());
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundColor(PhoneKit.GROUPED);
            row.addView(line((i + 1) + ". " + a.name(), describe(a)));
            LinearLayout buttons = new LinearLayout(c);
            buttons.setPadding(dp(8), 0, dp(8), dp(4));
            if (i > 0) buttons.addView(act("Up", Theme.RED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    move(list, at, -1);
                }
            }));
            if (i < list.size() - 1) buttons.addView(act("Down", Theme.RED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    move(list, at, 1);
                }
            }));
            buttons.addView(act("Remove", PhoneKit.DESTRUCTIVE, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    list.remove(at);
                    Addons.save(list);
                    drawAddons();
                }
            }));
            row.addView(buttons);
            addonsBox.addView(row);
        }
    }

    private void move(List<Addon> list, int at, int by) {
        Addon a = list.remove(at);
        list.add(at + by, a);
        Addons.save(list);
        drawAddons();
    }

    // ---- account

    private static String ago(long at) {
        if (at <= 0) return "never";
        long minutes = Math.round((System.currentTimeMillis() - at) / 60000.0);
        return minutes < 1 ? "just now" : minutes < 60 ? minutes + " min ago" : new java.util.Date(at).toString();
    }

    private void drawAccount() {
        Context c = ctx();
        accountBox.removeAllViews();
        ListenBrainz.Account a = ListenBrainz.account();
        if (a != null) {
            accountBox.addView(footer("Signed in as " + a.user + ". The songs you listen to and the ones you heart go to your ListenBrainz account, and hearts and plays from your other devices come back here. Last synced: " + ago(a.lastSync) + "."));
            accountBox.addView(footer(ListenBrainz.sendStatus()));
            String problem = ListenBrainz.sendError() != null ? ListenBrainz.sendError() : a.syncError;
            if (problem != null) {
                TextView e = PhoneKit.text(c, problem, 14, PhoneKit.DESTRUCTIVE, PhoneKit.REGULAR);
                e.setPadding(dp(32), 0, dp(16), dp(6));
                accountBox.addView(e);
            }
            LinearLayout row = new LinearLayout(c);
            row.setBackgroundColor(PhoneKit.GROUPED);
            row.setPadding(dp(8), dp(2), dp(8), dp(2));
            row.addView(act("Sync now", Theme.RED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ListenBrainz.syncNow();
                }
            }));
            final String user = a.user;
            row.addView(act("Open my page", Theme.RED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openUrl("https://listenbrainz.org/user/" + user + "/");
                }
            }));
            row.addView(act("Sign out", PhoneKit.DESTRUCTIVE, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ListenBrainz.signOut();
                }
            }));
            accountBox.addView(row);
            return;
        }
        accountBox.addView(footer("Sign in to keep what you listen to and the songs you heart in step across your devices. ListenBrainz is free and open (from the people behind MusicBrainz). Make an account at listenbrainz.org, copy your user token from its settings page, and paste it here. The token is only sent to ListenBrainz, and to your other devices locked with your sync passcode if you set one (Settings)."));
        token = input("Paste your ListenBrainz user token", true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, dp(40));
        tl.setMargins(dp(16), dp(4), dp(16), dp(4));
        accountBox.addView(token, tl);
        accountError = PhoneKit.text(c, "", 14, PhoneKit.DESTRUCTIVE, PhoneKit.REGULAR);
        accountError.setPadding(dp(16), dp(2), dp(16), dp(2));
        accountError.setVisibility(View.GONE);
        accountBox.addView(accountError);
        LinearLayout row = new LinearLayout(c);
        row.setPadding(dp(8), dp(2), dp(8), dp(2));
        row.addView(act("Sign in", Theme.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                signIn();
            }
        }));
        row.addView(act("Get my token", Theme.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://listenbrainz.org/settings/");
            }
        }));
        accountBox.addView(row);
    }

    private void signIn() {
        final String t = token.getText().toString();
        if (t.trim().length() == 0) return;
        accountError.setVisibility(View.GONE);
        ListenBrainz.signIn(t, new MusicNet.Done<String>() {
            @Override
            public void done(String user, Exception e) {
                if (user == null) {
                    accountError.setText(e != null ? e.getMessage() : "Couldn't sign in");
                    accountError.setVisibility(View.VISIBLE);
                } else {
                    drawAccount();
                }
            }
        });
    }

    private void checkLastfm() {
        final String key = MusicNet.lastfmKey();
        final int gen = ++lastfmGen;
        if (key.isEmpty()) {
            lastfmStatus.setVisibility(View.GONE);
            return;
        }
        lastfmStatus.setVisibility(View.VISIBLE);
        lastfmStatus.setText("Checking the key…");
        lastfmStatus.setTextColor(PhoneKit.GRAY);
        MusicNet.async(new MusicNet.Job<Integer>() {
            @Override
            public Integer run() {
                return com.codio.tv.music.Discovery.checkLastfm(key);
            }
        }, new MusicNet.Done<Integer>() {
            @Override
            public void done(Integer v, Exception e) {
                if (gen != lastfmGen) return;
                int st = v == null ? -1 : v;
                lastfmStatus.setText(st == 1 ? "✔  Connected. Popular songs and artists use Last.fm." : st == 0 ? "Last.fm didn't accept this key. Use the API key, not the shared secret." : "Couldn't reach Last.fm to check the key.");
                lastfmStatus.setTextColor(st == 1 ? 0xFF46D369 : st == 0 ? PhoneKit.DESTRUCTIVE : PhoneKit.GRAY);
            }
        });
    }

    private void openUrl(String url) {
        try {
            ctx().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            ui.toast("Couldn't open " + url);
        }
    }

    // ---- taste

    private void drawTaste() {
        Context c = ctx();
        tasteBox.removeAllViews();
        boolean signed = ListenBrainz.account() != null;
        tasteBox.addView(footer("Music's rows follow what you play, heart and hide. " + (signed
                ? "Plays and hearted songs also sync with your ListenBrainz account; hidden items stay on this phone."
                : "It all stays on this phone.") + " Play counts fade over time."));
        LinearLayout row = new LinearLayout(c);
        row.setBackgroundColor(PhoneKit.GROUPED);
        row.setPadding(dp(8), dp(2), dp(8), dp(2));
        row.addView(act("Forget what I've played", Theme.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Taste.clearHistory();
            }
        }));
        tasteBox.addView(row);
        tasteBox.addView(separator());
        LinearLayout row2 = new LinearLayout(c);
        row2.setBackgroundColor(PhoneKit.GROUPED);
        row2.setPadding(dp(8), dp(2), dp(8), dp(2));
        row2.addView(act(sure ? "Tap again to forget everything" : "Start over", PhoneKit.DESTRUCTIVE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (sure) {
                    sure = false;
                    Taste.clearAll();
                } else {
                    sure = true;
                    drawTaste();
                }
            }
        }));
        tasteBox.addView(row2);
        final List<String[]> hidden = new ArrayList<>();
        for (Map.Entry<String, String> e : Taste.hiddenArtists.entrySet()) hidden.add(new String[] {"artist", e.getKey(), e.getValue()});
        for (Map.Entry<String, String> e : Taste.hiddenAlbums.entrySet()) hidden.add(new String[] {"album", e.getKey(), e.getValue()});
        for (Map.Entry<String, String> e : Taste.hiddenTracks.entrySet()) hidden.add(new String[] {"track", e.getKey(), e.getValue()});
        if (hidden.isEmpty()) return;
        tasteBox.addView(heading("HIDDEN WITH “NOT INTERESTED”"));
        LinearLayout all = new LinearLayout(c);
        all.setBackgroundColor(PhoneKit.GROUPED);
        all.setPadding(dp(8), dp(2), dp(8), dp(2));
        all.addView(act("Show all hidden again", Theme.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Taste.unhideAll();
            }
        }));
        tasteBox.addView(all);
        for (final String[] h : hidden) {
            tasteBox.addView(separator());
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackgroundColor(PhoneKit.GROUPED);
            r.setPadding(dp(16), dp(4), dp(8), dp(4));
            TextView name = PhoneKit.text(c, h[2] + " (" + h[0] + ")", 15, Color.WHITE, PhoneKit.REGULAR);
            name.setMaxLines(2);
            r.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            r.addView(act("Show again", Theme.RED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Taste.unhide(h[0], h[1]);
                }
            }));
            tasteBox.addView(r);
        }
    }
}
