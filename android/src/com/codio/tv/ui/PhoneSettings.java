package com.codio.tv.ui;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Settings, laid out like the iPhone's (the standard for the phones): a grouped list with Playback, Downloads,
 * Languages & subtitles, the TMDB key, Updates, Account and Addons. A row's value is red; tap it for the choices.
 */
final class PhoneSettings extends PhoneUi.Page {
    private LinearLayout list;
    private String sig;
    private EditText keyField;
    private TextView keyStatus;
    private final Handler h = new Handler(Looper.getMainLooper());
    /** Bumped for every key typed, so an older check that answers late is ignored. */
    private int keyCheck = 0;

    PhoneSettings(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        ScrollView sv = new ScrollView(ctx());
        sv.setBackgroundColor(Color.BLACK);
        list = new LinearLayout(ctx());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        sv.addView(list);
        fill();
        return sv;
    }

    private String signature() {
        return ui.store.profileJson.toString().hashCode() + "|" + ui.store.autoPlay + "|" + Vault.signedIn() + Vault.on() + Vault.problem();
    }

    @Override
    void refresh() {
        if (!signature().equals(sig)) fill();
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
        sig = signature();
        list.removeAllViews();
        list.addView(ui.largeTitle("Settings", false));
        header("You", true);
        LinearLayout you = group();
        add(you, statsRow());
        String section = null;
        LinearLayout group = null;
        for (Setting s : Setting.all(true)) {
            if (!s.section.equals(section)) {
                header(section = s.section, false);
                group = group();
            }
            add(group, choiceRow(s));
        }

        header("Cast & actors", false);
        LinearLayout cast = group();
        add(cast, keyRow());
        keyStatus = PhoneKit.text(ctx(), "", 17, Theme.TEXT_DIM, PhoneKit.REGULAR);
        keyStatus.setPadding(dp(16), dp(11), dp(16), dp(11));
        add(cast, keyStatus);
        footer("Paste the free API Key from themoviedb.org (Settings, API). Codio checks it right away. With a sync "
                + "passcode (below, under Account), your other devices get it too.\n\nThis product uses the TMDB API but is not endorsed or certified by TMDB.");
        showKeyStatus(keyField.getText().toString().trim().isEmpty() ? KEY_EMPTY : CastInfo.key() != null ? KEY_SAVED : KEY_EMPTY, null);

        header("Updates", false);
        LinearLayout upd = group();
        add(upd, button("Check for updates", Theme.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.host.checkUpdates();
            }
        }));
        footer("New versions install over this one and keep your data.");

        header(Setting.ACCOUNT, false);
        LinearLayout acct = group();
        final String e = Profiles.email(ui.store);
        if (e != null) {
            add(acct, plain(e, Color.WHITE));
            add(acct, button("Profiles", Theme.RED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.pickProfile();
                }
            }));
            add(acct, button("Sign out", PhoneKit.DESTRUCTIVE, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ui.dialog().setTitle("Sign out?").setMessage("Your downloads stay on this phone.")
                            .setPositiveButton("Sign out", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int w) {
                                    ui.ctx("Logout", null);
                                }
                            }).setNegativeButton("Cancel", null).show();
                }
            }));
        } else {
            add(acct, plain("Not signed in", Theme.TEXT_DIM));
            add(acct, button("Sign in", Theme.RED, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    signIn();
                }
            }));
        }

        syncSection();

        header("Addons", false);
        LinearLayout addons = group();
        addAddons(addons);
        header("Legal", false);
        LinearLayout legal = group();
        add(legal, button("Licenses and notices", Theme.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!WebLink.open(ctx(), Legal.NOTICE_URL)) android.widget.Toast.makeText(ctx(), "Couldn't open " + Legal.NOTICE_URL, android.widget.Toast.LENGTH_SHORT).show();
            }
        }));
        footer(Legal.TEXT);
        TextView ver = PhoneKit.text(ctx(), "Codio " + ui.host.version() + " for Android", 13, PhoneKit.SECONDARY, PhoneKit.REGULAR);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(20), 0, 0);
        list.addView(ver, new LinearLayout.LayoutParams(-1, -2));
    }

    // ------------------------------------------------------------------ the grouped list

    private void header(String s, boolean first) {
        TextView t = PhoneKit.text(ctx(), s.toUpperCase(java.util.Locale.ROOT), 13, PhoneKit.SECONDARY, PhoneKit.REGULAR);
        t.setPadding(dp(32), dp(first ? 10 : 24), dp(32), dp(7));
        list.addView(t);
    }

    private void footer(String s) {
        TextView t = PhoneKit.text(ctx(), s, 13, PhoneKit.SECONDARY, PhoneKit.REGULAR);
        t.setPadding(dp(32), dp(7), dp(32), 0);
        list.addView(t);
    }

    private LinearLayout group() {
        LinearLayout g = new LinearLayout(ctx());
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(PhoneKit.round(PhoneKit.GROUPED, dp(10)));
        g.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(16), 0, dp(16), 0);
        list.addView(g, lp);
        return g;
    }

    /** Adds a row, with the thin line above it that the iPhone draws between rows. */
    private void add(LinearLayout group, View row) {
        if (group.getChildCount() > 0) {
            View line = new View(ctx());
            line.setBackgroundColor(PhoneKit.SEPARATOR);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.5f)));
            ll.leftMargin = dp(16);
            group.addView(line, ll);
        }
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** "Your stats", with this year's hours watched in red; opens the stats page. */
    private View statsRow() {
        LinearLayout r = new LinearLayout(ctx());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(11), dp(16), dp(11));
        r.setBackground(PhoneKit.pressable(null, 0));
        r.addView(PhoneKit.text(ctx(), "Your stats", 17, Color.WHITE, PhoneKit.REGULAR), new LinearLayout.LayoutParams(0, -2, 1));
        int y = Stats.thisYear();
        String h = Stats.hours(Stats.watch(ui.store.libraryEntries(), Store.profileId(), y).ms);
        r.addView(PhoneKit.text(ctx(), h + " h in " + y + "  ›", 17, Theme.RED, PhoneKit.REGULAR));
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.push(new PhoneStats(ui));
            }
        });
        return r;
    }

    private TextView plain(String text, int color) {
        TextView t = PhoneKit.text(ctx(), text, 17, color, PhoneKit.REGULAR);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        return t;
    }

    private TextView button(String text, int color, View.OnClickListener onClick) {
        TextView t = plain(text, color);
        t.setBackground(PhoneKit.pressable(null, 0));
        t.setClickable(true);
        t.setOnClickListener(onClick);
        return t;
    }

    /** A setting with a fixed list of values: its name, the value in red with ⌃⌄, the help underneath; tap for the list. */
    private View choiceRow(final Setting s) {
        LinearLayout r = new LinearLayout(ctx());
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(16), dp(14), dp(16), dp(14));
        r.setBackground(PhoneKit.pressable(null, 0));
        LinearLayout line = new LinearLayout(ctx());
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = PhoneKit.text(ctx(), s.label, 17, Color.WHITE, PhoneKit.REGULAR);
        line.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        final LinearLayout val = new LinearLayout(ctx());
        val.setGravity(Gravity.CENTER_VERTICAL);
        TextView v = PhoneKit.text(ctx(), s.value(ui.store, ui.host.prefs()), 17, Theme.RED, PhoneKit.REGULAR);
        v.setGravity(Gravity.END);
        val.addView(v);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.leftMargin = dp(5);
        val.addView(new PhoneKit.Chevrons(ctx(), Theme.RED), cl);
        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
        vl.leftMargin = dp(8);
        line.addView(val, vl);
        r.addView(line);
        if (s.help != null) {
            TextView hv = PhoneKit.text(ctx(), s.help, 13, Theme.TEXT_DIM, PhoneKit.REGULAR);
            hv.setPadding(0, dp(8), 0, 0);
            r.addView(hv);
        }
        r.setClickable(true);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                PopupMenu pm = new PopupMenu(new ContextThemeWrapper(ctx(), android.R.style.Theme_DeviceDefault), val, Gravity.END);
                int cur = s.index(ui.store, ui.host.prefs());
                for (int i = 0; i < s.labels.length; i++) pm.getMenu().add(0, i, i, s.labels[i]).setCheckable(true).setChecked(i == cur);
                pm.getMenu().setGroupCheckable(0, true, true);
                pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        if (!s.set(item.getItemId(), ui.store, ui.host)) ui.toast(Setting.LOADING);
                        fill();
                        return true;
                    }
                });
                pm.show();
            }
        });
        return r;
    }

    // ------------------------------------------------------------------ TMDB key

    private static final int KEY_EMPTY = 0, KEY_CHECKING = 1, KEY_OK = 2, KEY_BAD = 3, KEY_OFFLINE = 4, KEY_SAVED = 5;

    private View keyRow() {
        LinearLayout r = new LinearLayout(ctx());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(2), dp(8), dp(2));
        keyField = new EditText(ctx());
        keyField.setHint("TMDB API key");
        keyField.setHintTextColor(PhoneKit.GRAY);
        keyField.setTextColor(Color.WHITE);
        keyField.setTextSize(17);
        keyField.setSingleLine(true);
        keyField.setBackground(null);
        keyField.setPadding(0, dp(9), 0, dp(9));
        keyField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        keyField.setTransformationMethod(PasswordTransformationMethod.getInstance());
        String k = CastInfo.key();
        if (k != null) keyField.setText(k);
        keyField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleKeyCheck(false);
            }
        });
        r.addView(keyField, new LinearLayout.LayoutParams(0, -2, 1));
        TextView paste = PhoneKit.link(ctx(), "Paste", Theme.RED);
        paste.setPadding(dp(8), dp(8), dp(8), dp(8));
        paste.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) ctx().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(ctx());
                    if (t != null) keyField.setText(t.toString().trim());
                }
            }
        });
        r.addView(paste);
        final TextView get = PhoneKit.link(ctx(), "Get a key", Theme.RED);
        get.setPadding(dp(8), dp(8), dp(8), dp(8));
        get.setVisibility(k == null || k.isEmpty() ? View.VISIBLE : View.GONE);
        get.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!WebLink.open(ctx(), WebLink.TMDB_KEY)) android.widget.Toast.makeText(ctx(), "Couldn't open " + WebLink.TMDB_KEY, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        keyField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                get.setVisibility(s.toString().trim().isEmpty() ? View.VISIBLE : View.GONE);
            }
        });
        r.addView(get);
        return r;
    }

    private void showKeyStatus(int st, String why) {
        if (keyStatus == null) return;
        switch (st) {
            case KEY_CHECKING:
                keyStatus.setText("Checking the key…");
                keyStatus.setTextColor(Theme.TEXT_DIM);
                keyStatus.setOnClickListener(null);
                break;
            case KEY_OK:
            case KEY_SAVED:
                keyStatus.setText("✔  Key works. Cast photos are on.");
                keyStatus.setTextColor(0xFF30D158);
                keyStatus.setOnClickListener(null);
                break;
            case KEY_BAD:
                keyStatus.setText("✖  " + why);
                keyStatus.setTextColor(Theme.RED);
                keyStatus.setOnClickListener(null);
                break;
            case KEY_OFFLINE:
                keyStatus.setText("Couldn't reach TMDB to check it.  Retry");
                keyStatus.setTextColor(0xFFFF9F0A);
                keyStatus.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        scheduleKeyCheck(true);
                    }
                });
                break;
            default:
                keyStatus.setText("No key yet");
                keyStatus.setTextColor(Theme.TEXT_DIM);
                keyStatus.setOnClickListener(null);
                break;
        }
    }

    private void scheduleKeyCheck(boolean now) {
        final int id = ++keyCheck;
        final String k = keyField.getText().toString().trim();
        if (k.isEmpty()) {
            CastInfo.saveKey(ui.host.prefs(), ui.store, "");
            showKeyStatus(KEY_EMPTY, null);
            return;
        }
        if (k.length() > 60) {
            showKeyStatus(KEY_BAD, "That's the long Read Access Token. Paste the shorter API Key instead.");
            return;
        }
        if (!CastInfo.looksLikeKey(k)) {
            showKeyStatus(KEY_BAD, "That doesn't look like a TMDB API Key (32 letters and numbers).");
            return;
        }
        showKeyStatus(KEY_CHECKING, null);
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (id != keyCheck) return;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        int code = 0;
                        try {
                            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://api.themoviedb.org/3/configuration?api_key=" + k).openConnection();
                            c.setConnectTimeout(8000);
                            c.setReadTimeout(8000);
                            code = c.getResponseCode();
                            c.disconnect();
                        } catch (Exception ignored) {
                        }
                        final int result = code == 200 ? KEY_OK : code == 401 ? KEY_BAD : KEY_OFFLINE;
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                if (id != keyCheck) return;
                                if (result != KEY_BAD) CastInfo.saveKey(ui.host.prefs(), ui.store, k);
                                showKeyStatus(result, "TMDB says this key isn't valid.");
                            }
                        });
                    }
                }, "codio-tmdb-check").start();
            }
        }, now ? 0 : 600);
    }

    // ------------------------------------------------------------------ sync passcode (ui/Vault)

    private TextView syncStatus;
    private final Runnable syncChanged = new Runnable() {
        @Override
        public void run() {
            if (syncStatus != null) syncStatus.setText(Vault.busy() ? "Syncing…" : Vault.detail());
            refresh();
        }
    };

    private void syncSection() {
        Vault.listeners.add(syncChanged);
        header("Sync passcode", false);
        LinearLayout g = group();
        syncStatus = plain(Vault.busy() ? "Syncing…" : Vault.detail(), Theme.TEXT_DIM);
        syncStatus.setSingleLine(false);
        add(g, syncStatus);
        if (Vault.signedIn()) {
            if (!Vault.on()) {
                add(g, button("Set sync passcode", Theme.RED, passcodeClick("Sync passcode", "Enter the passcode you set on another device, or pick a new one. It can't be recovered, so remember it.", 0)));
            } else if (Vault.problem() == Vault.LOCKED) {
                add(g, button("Enter the new passcode", Theme.RED, passcodeClick("New sync passcode", "The passcode was changed on another device.", 0)));
                add(g, button("Start over with a new passcode", Theme.RED, passcodeClick("Start over", "Replaces the synced settings with this phone's, locked with the new passcode. Your other devices then ask for it.", 2)));
            } else {
                add(g, button("Sync now", Theme.RED, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Vault.maybeSync(true);
                    }
                }));
                add(g, button("Change passcode", Theme.RED, passcodeClick("New sync passcode", "Your other devices will ask for the new one.", 1)));
            }
            if (Vault.on()) {
                add(g, button("Turn off on this phone", PhoneKit.DESTRUCTIVE, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Vault.turnOff();
                        fill();
                    }
                }));
            }
        }
        footer(Vault.HELP);
    }

    /** kind 0 = turn on / unlock, 1 = change, 2 = start over. */
    private View.OnClickListener passcodeClick(final String title, final String message, final int kind) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText pass = field("At least " + Vault.MIN_PASSCODE + " characters", InputType.TYPE_TEXT_VARIATION_PASSWORD);
                ui.dialog().setTitle(title).setMessage(message).setView(boxed(pass))
                        .setPositiveButton(kind == 1 ? "Change" : kind == 2 ? "Start over" : "Unlock", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                String p = pass.getText().toString();
                                if (p.trim().isEmpty()) return;
                                ui.toast(kind == 1 ? "Changing passcode…" : "Unlocking…");
                                Vault.Done done = new Vault.Done() {
                                    @Override
                                    public void done(String error) {
                                        if (error != null) ui.toast(error);
                                        else ui.toast(kind == 0 ? "Sync is on" : kind == 1 ? "Passcode changed" : "Started over with the new passcode");
                                        fill();
                                    }
                                };
                                if (kind == 0) Vault.setPasscode(p, done);
                                else if (kind == 1) Vault.changePasscode(p, done);
                                else Vault.startOver(p, done);
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        };
    }

    // ------------------------------------------------------------------ dialogs

    /** A one-line text field for a dialog; variation is an InputType.TYPE_TEXT_VARIATION_* (or flag). */
    private EditText field(String hint, int variation) {
        EditText t = new EditText(ctx());
        t.setHint(hint);
        t.setSingleLine(true);
        t.setTextColor(Color.WHITE);
        t.setHintTextColor(Theme.TEXT_FAINT);
        t.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        return t;
    }

    private LinearLayout boxed(View... fields) {
        LinearLayout box = new LinearLayout(ctx());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        for (View f : fields) box.addView(f, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void signIn() {
        final EditText email = field("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditText pass = field("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ui.dialog().setTitle("Sign in").setMessage("Your Stremio account (addons and library sync).").setView(boxed(email, pass))
                .setPositiveButton("Sign in", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String em = email.getText().toString().trim(), pw = pass.getText().toString();
                        if (em.isEmpty() || pw.isEmpty()) return;
                        ui.ctx("Authenticate", Core.login(em, pw));
                        ui.toast("Signing in…");
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ addons

    private void addAddons(LinearLayout group) {
        JSONArray a = ui.store.addonDescriptors();
        for (int i = 0; i < a.length(); i++) {
            final JSONObject d = a.optJSONObject(i);
            if (d == null) continue;
            JSONObject m = d.optJSONObject("manifest");
            final String name = m != null ? m.optString("name", "Addon") : "Addon";
            final boolean prot = AddonKit.isProtected(d);
            String desc = m != null ? m.optString("description", "") : "";
            LinearLayout r = new LinearLayout(ctx());
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(16), dp(10), dp(16), dp(10));
            LinearLayout t = new LinearLayout(ctx());
            t.setOrientation(LinearLayout.VERTICAL);
            t.addView(PhoneKit.text(ctx(), name, 17, Color.WHITE, PhoneKit.REGULAR));
            if (!desc.isEmpty()) {
                TextView dv = PhoneKit.text(ctx(), desc, 12, Theme.TEXT_DIM, PhoneKit.REGULAR);
                dv.setMaxLines(2);
                dv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                dv.setPadding(0, dp(2), 0, 0);
                t.addView(dv);
            }
            r.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
            if (prot) {
                TextView b = PhoneKit.text(ctx(), "Built in", 12, Theme.TEXT_DIM, PhoneKit.REGULAR);
                b.setPadding(dp(10), 0, 0, 0);
                r.addView(b);
            } else {
                TextView rm = PhoneKit.link(ctx(), "Remove", PhoneKit.DESTRUCTIVE);
                rm.setPadding(dp(10), dp(6), dp(2), dp(6));
                rm.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ui.dialog().setTitle("Remove " + name + "?")
                                .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dd, int w) {
                                        AddonKit.uninstall(ui.store, ui.host, d);
                                        fill();
                                    }
                                }).setNegativeButton("Cancel", null).show();
                    }
                });
                r.addView(rm);
            }
            add(group, r);
        }
        add(group, button("Add an addon", Theme.RED, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLink();
            }
        }));
    }

    private void addLink() {
        final EditText t = field("Addon link", InputType.TYPE_TEXT_VARIATION_URI);
        ui.dialog().setTitle("Add an addon").setMessage("Paste the addon's link (it ends in manifest.json).").setView(boxed(t))
                .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String text = t.getText().toString().trim();
                        if (text.isEmpty()) return;
                        final String url = AddonKit.manifestUrl(text);
                        ui.toast(AddonKit.CHECKING);
                        AddonKit.fetch(url, new AddonKit.Done() {
                            @Override
                            public void done(JSONObject desc) {
                                String problem = desc == null ? AddonKit.NOT_AN_ADDON : ui.store.isInstalled(url) ? AddonKit.ALREADY
                                        : AddonKit.needsSetup(desc) ? AddonKit.NEEDS_SETUP : null;
                                if (problem != null) {
                                    ui.toast(problem);
                                    return;
                                }
                                AddonKit.install(ui.store, ui.host, desc);
                                fill();
                            }
                        });
                    }
                }).setNegativeButton("Cancel", null).show();
    }
}
