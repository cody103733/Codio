package com.codio.tv.ui;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Settings: auto-play, downloads, player languages, account and addons. */
final class PhoneSettings extends PhoneUi.Page {
    private LinearLayout list;

    PhoneSettings(PhoneUi ui) {
        super(ui);
    }

    /** One setting with a fixed set of values. kind: "ap" auto-play setting, "pref" this phone only, "profile" synced. */
    private final class Choice {
        final String label, help, key, kind;
        final Object dflt;
        final Object[] values;
        final String[] labels;

        Choice(String kind, String label, String help, String key, Object dflt, Object[] values, String[] labels) {
            this.kind = kind;
            this.label = label;
            this.help = help;
            this.key = key;
            this.dflt = dflt;
            this.values = values;
            this.labels = labels;
        }

        Object current() {
            if ("ap".equals(kind)) return ui.store.autoPlay.has(key) ? ui.store.autoPlay.opt(key) : dflt;
            if ("profile".equals(kind)) {
                JSONObject st = ui.store.profileJson.optJSONObject("settings");
                return st != null && st.has(key) ? st.opt(key) : dflt;
            }
            SharedPreferences p = ui.host.prefs();
            if (dflt instanceof Boolean) return p.getBoolean(key, (Boolean) dflt);
            if (dflt instanceof Integer) return p.getInt(key, (Integer) dflt);
            return p.getString(key, String.valueOf(dflt));
        }

        int index() {
            Object v = current();
            for (int i = 0; i < values.length; i++) {
                if (String.valueOf(values[i]).equals(String.valueOf(v))) return i;
                if (values[i] instanceof Number && v instanceof Number && ((Number) values[i]).doubleValue() == ((Number) v).doubleValue()) return i;
            }
            return -1;
        }

        String value() {
            int i = index();
            return i >= 0 ? labels[i] : String.valueOf(current());
        }

        void set(int n) {
            Object v = values[n];
            try {
                if ("ap".equals(kind)) {
                    ui.store.autoPlay.put(key, v);
                    ui.host.saveAutoPlay(ui.store.autoPlay.toString());
                } else if ("profile".equals(kind)) {
                    JSONObject st = ui.store.profileJson.optJSONObject("settings");
                    if (st == null) {
                        ui.toast("Still loading your settings, try again in a moment");
                        return;
                    }
                    JSONObject all = ui.store.settingsCopy();
                    all.put(key, v);
                    st.put(key, v);
                    ui.ctx("UpdateSettings", all);
                } else {
                    SharedPreferences.Editor e = ui.host.prefs().edit();
                    if (v instanceof Boolean) e.putBoolean(key, (Boolean) v);
                    else if (v instanceof Integer) e.putInt(key, (Integer) v);
                    else e.putString(key, String.valueOf(v));
                    e.apply();
                }
            } catch (Exception ignored) {
            }
            fill();
        }
    }

    private final List<Object> items = new ArrayList<>();

    @Override
    View build() {
        ScrollView sv = new ScrollView(ctx());
        list = new LinearLayout(ctx());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        sv.addView(list);

        items.add("Playback");
        items.add(new Choice("ap", "Smart auto-play", "Tap Play and Codio picks the best source, and moves on to the next one if a source fails.",
                "enabled", Boolean.FALSE, new Object[]{true, false}, new String[]{"On", "Off"}));
        items.add(new Choice("ap", "Preferred quality", "The quality auto-play aims for when streaming.",
                "preferredQuality", "1080p", new Object[]{"2160p", "1440p", "1080p", "720p", "480p"}, new String[]{"4K (2160p)", "1440p", "1080p", "720p", "480p"}));
        items.add(new Choice("ap", "If that quality isn't available", "What auto-play falls back to.",
                "fallbackMode", "closest", new Object[]{"closest", "lower", "exact"}, new String[]{"Closest quality", "Lower quality only", "Don't play"}));
        Object[] rates = {0, 10, 15, 25, 40, 60, 70, 80, 100, 150};
        String[] rateLabels = new String[rates.length];
        for (int i = 0; i < rates.length; i++) rateLabels[i] = (Integer) rates[i] == 0 ? "No limit" : rates[i] + " Mbit/s";
        items.add(new Choice("ap", "Skip sources above", "Sources above this bitrate are tried last, so streaming doesn't stall. Pick what your connection can carry; lower it on mobile data.",
                "maxBitrateMbps", 0, rates, rateLabels));
        items.add(new Choice("ap", "Prefer instant sources", "Among sources of the same quality, start with ones your debrid service already has ready.",
                "preferCached", Boolean.TRUE, new Object[]{true, false}, new String[]{"On", "Off"}));
        items.add(new Choice("profile", "Next episode", "At the end of an episode, play the next one automatically.", "bingeWatching", Boolean.TRUE,
                new Object[]{true, false}, new String[]{"Plays automatically", "Off"}));

        items.add(new Choice("pref", "Skip Intro button", "Shows Skip Intro (and Skip Recap) during an episode's opening, and Next Episode once the credits start. Times come from IntroDB, a community database, so not every show has them.",
                "skip_buttons", "on", new Object[]{"on", "off"}, new String[]{"On", "Off"}));

        items.add("Downloads");
        items.add(new Choice("pref", "Download over", "Big video files can use a lot of mobile data. With Wi-Fi only, downloads wait until you're on Wi-Fi.",
                "dl_wifi_only", Boolean.TRUE, new Object[]{true, false}, new String[]{"Wi-Fi only", "Wi-Fi and mobile data"}));

        items.add("Languages & subtitles");
        items.add(new Choice("pref", "Subtitles", "Off: subtitles only appear when you turn them on in the player. Automatic: picked by your subtitle language.",
                "subs_auto", "off", new Object[]{"off", "on"}, new String[]{"Off until I turn them on", "Automatic"}));
        String[] langCodes = {"eng", "spa", "fre", "ger", "ita", "por", "nld", "pol", "rus", "tur", "ara", "hin", "jpn", "kor", "zho"};
        String[] langNames = {"English", "Spanish", "French", "German", "Italian", "Portuguese", "Dutch", "Polish", "Russian", "Turkish", "Arabic", "Hindi", "Japanese", "Korean", "Chinese"};
        items.add(new Choice("profile", "Audio language", "Auto-play and the player prefer an audio track in this language.", "audioLanguage", "eng", langCodes, langNames));
        Object[] subCodes = new Object[langCodes.length + 1];
        String[] subNames = new String[langCodes.length + 1];
        subCodes[0] = JSONObject.NULL;
        subNames[0] = "None";
        for (int i = 0; i < langCodes.length; i++) {
            subCodes[i + 1] = langCodes[i];
            subNames[i + 1] = langNames[i];
        }
        items.add(new Choice("profile", "Subtitle language", "The language picked when you turn subtitles on.", "subtitlesLanguage", JSONObject.NULL, subCodes, subNames));
        items.add(new Choice("profile", "Subtitle size", "How big subtitles are drawn.", "subtitlesSize", 100,
                new Object[]{75, 100, 125, 150, 175, 200, 250}, new String[]{"75%", "100%", "125%", "150%", "175%", "200%", "250%"}));
        items.add("Account");
        items.add("account");
        items.add("tmdb");
        items.add("updates");
        items.add("Addons");
        items.add("addons");
        fill();
        return sv;
    }

    private String sig = null;

    @Override
    void refresh() {
        String now = ui.store.profileJson.toString().hashCode() + "|" + ui.store.autoPlay.toString();
        if (now.equals(sig)) return;
        fill();
    }

    @Override
    void downloadsChanged() {
    }

    @Override
    void coreEvent(String name) {
        fill();
    }

    private void fill() {
        if (list == null) return;
        sig = ui.store.profileJson.toString().hashCode() + "|" + ui.store.autoPlay.toString();
        list.removeAllViews();
        TextView title = PhoneKit.text(ctx(), "Settings", 24, Color.WHITE, true);
        title.setPadding(dp(16), dp(14), dp(16), dp(4));
        list.addView(title);
        for (Object o : items) {
            if (o instanceof Choice) list.addView(choiceRow((Choice) o));
            else if ("account".equals(o)) list.addView(accountRow());
            else if ("tmdb".equals(o)) list.addView(tmdbRow());
            else if ("updates".equals(o)) list.addView(updatesRow());
            else if ("addons".equals(o)) addAddons();
            else list.addView(PhoneUi.sectionTitle(ctx(), (String) o));
        }
        TextView ver = PhoneKit.text(ctx(), "Codio " + ui.host.version(), 12, 0xFF777777, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(24), 0, 0);
        list.addView(ver);
    }

    private LinearLayout row(String label, String value, String help) {
        LinearLayout r = new LinearLayout(ctx());
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(10), dp(16), dp(10));
        r.setBackground(PhoneKit.pressable(null, 0));
        r.setClickable(true);
        LinearLayout line = new LinearLayout(ctx());
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = PhoneKit.text(ctx(), label, 15, Color.WHITE, true);
        line.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = PhoneKit.text(ctx(), value, 14, PhoneKit.RED, true);
        v.setGravity(Gravity.END);
        v.setPadding(dp(8), 0, 0, 0);
        line.addView(v, new LinearLayout.LayoutParams(-2, -2));
        r.addView(line);
        if (help != null) {
            TextView h = PhoneKit.text(ctx(), help, 12, PhoneKit.TEXT2, false);
            h.setPadding(0, dp(3), 0, 0);
            r.addView(h);
        }
        return r;
    }

    private View choiceRow(final Choice c) {
        LinearLayout r = row(c.label, c.value(), c.help);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.dialog().setTitle(c.label)
                        .setSingleChoiceItems(c.labels, c.index(), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                c.set(which);
                            }
                        })
                        .setNegativeButton("Cancel", null).show();
            }
        });
        return r;
    }

    private String email() {
        JSONObject auth = ui.store.profileJson.optJSONObject("auth");
        JSONObject user = auth != null ? auth.optJSONObject("user") : null;
        return user != null ? user.optString("email", null) : null;
    }

    private View accountRow() {
        final String e = email();
        LinearLayout r = row(e != null ? e : "Not signed in", e != null ? "Sign out" : "Sign in",
                "Signed in, your library, addons and settings sync with your other devices.");
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (e != null) {
                    ui.dialog().setTitle("Sign out?").setMessage("Your downloads stay on this phone.")
                            .setPositiveButton("Sign out", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    ui.ctx("Logout", null);
                                }
                            }).setNegativeButton("Cancel", null).show();
                } else {
                    signIn();
                }
            }
        });
        return r;
    }

    private View tmdbRow() {
        LinearLayout r = row("Cast photos (TMDB key)", CastInfo.key() != null ? "Key saved" : "Add key",
                "Actors' photos come from TMDB with your own free API key (themoviedb.org, Settings, API). Without one, the cast shows names only.");
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText t = field("32 letters and numbers", false);
                t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                if (CastInfo.key() != null) t.setText(CastInfo.key());
                LinearLayout box = new LinearLayout(ctx());
                box.setPadding(dp(20), dp(8), dp(20), 0);
                box.addView(t, new LinearLayout.LayoutParams(-1, -2));
                ui.dialog().setTitle("TMDB API key").setView(box)
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                String k = t.getText().toString().trim();
                                if (!k.isEmpty() && !CastInfo.looksLikeKey(k)) {
                                    ui.toast("That doesn't look like a TMDB API key (32 letters and numbers)");
                                    return;
                                }
                                ui.host.prefs().edit().putString("tmdb_key", k).apply();
                                fill();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        return r;
    }

    private View updatesRow() {
        LinearLayout r = row("Updates", "Check now", "Codio checks for a newer version when it starts and offers to install it.");
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.host.checkUpdates();
            }
        });
        return r;
    }

    private EditText field(String hint, boolean password) {
        EditText t = new EditText(ctx());
        t.setHint(hint);
        t.setSingleLine(true);
        t.setTextColor(Color.WHITE);
        t.setHintTextColor(0xFF8C8C8C);
        t.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        return t;
    }

    private void signIn() {
        LinearLayout box = new LinearLayout(ctx());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText email = field("Email", false);
        final EditText pass = field("Password", true);
        box.addView(email);
        box.addView(pass);
        ui.dialog().setTitle("Sign in").setView(box)
                .setPositiveButton("Sign in", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String em = email.getText().toString().trim(), pw = pass.getText().toString();
                        if (em.isEmpty() || pw.isEmpty()) return;
                        try {
                            JSONObject a = new JSONObject();
                            a.put("type", "Login");
                            a.put("email", em);
                            a.put("password", pw);
                            ui.ctx("Authenticate", a);
                            ui.toast("Signing in…");
                        } catch (Exception ignored) {
                        }
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ addons

    private void addAddons() {
        JSONArray a = ui.store.addonDescriptors();
        for (int i = 0; i < a.length(); i++) {
            final JSONObject d = a.optJSONObject(i);
            if (d == null) continue;
            JSONObject m = d.optJSONObject("manifest");
            String name = m != null ? m.optString("name", "Addon") : "Addon";
            JSONObject f = d.optJSONObject("flags");
            final boolean prot = f != null && f.optBoolean("protected");
            String desc = m != null ? m.optString("description", "") : "";
            if (desc.length() > 120) desc = desc.substring(0, 117) + "…";
            LinearLayout r = row(name, prot ? "Built in" : "Remove", desc.isEmpty() ? null : desc);
            final String url = d.optString("transportUrl");
            final String nm = name;
            r.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (prot) return;
                    ui.dialog().setTitle("Remove " + nm + "?")
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dd, int w) {
                                    ui.ctx("UninstallAddon", d);
                                    JSONArray all = ui.store.addonDescriptors();
                                    for (int j = all.length() - 1; j >= 0; j--) {
                                        JSONObject x = all.optJSONObject(j);
                                        if (x != null && url.equals(x.optString("transportUrl"))) all.remove(j);
                                    }
                                    fill();
                                }
                            }).setNegativeButton("Cancel", null).show();
                }
            });
            list.addView(r);
        }
        LinearLayout add = row("Add an addon", "+", "Paste the addon's link (it ends in manifest.json).");
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLink();
            }
        });
        list.addView(add);
    }

    private void addLink() {
        final EditText t = field("https://…/manifest.json", false);
        t.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(ctx());
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(t, new LinearLayout.LayoutParams(-1, -2));
        ui.dialog().setTitle("Addon link").setView(box)
                .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String text = t.getText().toString().trim();
                        if (text.isEmpty()) return;
                        String u = text.startsWith("stremio://") ? "https://" + text.substring(10) : text;
                        if (!u.startsWith("http")) u = "https://" + u;
                        if (!u.endsWith("manifest.json")) u = u.replaceAll("/+$", "") + "/manifest.json";
                        install(u);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    private void install(final String url) {
        ui.toast("Checking the addon…");
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject d = null;
                try {
                    JSONObject manifest = new JSONObject(Net.get(url));
                    if (manifest.has("id") && manifest.has("name")) {
                        d = new JSONObject();
                        d.put("transportUrl", url);
                        d.put("manifest", manifest);
                        JSONObject flags = new JSONObject();
                        flags.put("official", false);
                        flags.put("protected", false);
                        d.put("flags", flags);
                    }
                } catch (Exception ignored) {
                }
                final JSONObject desc = d;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (desc == null) {
                            ui.toast("That link isn't an addon (no manifest found)");
                            return;
                        }
                        if (ui.store.isInstalled(url)) {
                            ui.toast("Already installed");
                            return;
                        }
                        JSONObject bh = desc.optJSONObject("manifest").optJSONObject("behaviorHints");
                        if (bh != null && bh.optBoolean("configurationRequired")) {
                            ui.toast("This addon needs setting up on its website first");
                            return;
                        }
                        ui.ctx("InstallAddon", desc);
                        try {
                            JSONArray a = ui.store.addonDescriptors();
                            a.put(new JSONObject(desc.toString()));
                            if (!ui.store.profileJson.has("addons")) ui.store.profileJson.put("addons", a);
                        } catch (Exception ignored) {
                        }
                        fill();
                    }
                });
            }
        });
    }
}
