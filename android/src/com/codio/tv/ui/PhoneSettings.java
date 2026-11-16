package com.codio.tv.ui;

import android.content.DialogInterface;
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


/** Settings in sections: playback, downloads, languages, account, addons. Tap a row to change it. */
final class PhoneSettings extends PhoneUi.Page {
    private LinearLayout list;
    private String sig;

    PhoneSettings(PhoneUi ui) {
        super(ui);
    }

    @Override
    View build() {
        ScrollView sv = new ScrollView(ctx());
        list = new LinearLayout(ctx());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, dp(24));
        sv.addView(list);
        fill();
        return sv;
    }

    private String signature() {
        return ui.store.profileJson.toString().hashCode() + "|" + ui.store.autoPlay;
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
        TextView title = PhoneKit.text(ctx(), "Settings", 24, Color.WHITE, true);
        title.setPadding(dp(16), dp(14), dp(16), dp(6));
        list.addView(PhoneUi.titleRow(ui, title));
        String section = null;
        for (Setting s : Setting.all(true)) {
            if (!s.section.equals(section)) list.addView(PhoneUi.sectionTitle(ctx(), section = s.section));
            list.addView(choiceRow(s));
        }
        list.addView(PhoneUi.sectionTitle(ctx(), Setting.ACCOUNT));
        list.addView(accountRow());
        list.addView(profilesRow());
        list.addView(choiceRow(Setting.askProfile()));
        list.addView(tmdbRow());
        list.addView(PhoneUi.sectionTitle(ctx(), "Addons"));
        addAddons();
        list.addView(PhoneUi.sectionTitle(ctx(), Setting.ABOUT));
        list.addView(updatesRow());
        TextView ver = PhoneKit.text(ctx(), "Codio " + ui.host.version() + " for Android", 12, Theme.TEXT_FAINT, false);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(24), 0, 0);
        list.addView(ver);
    }

    private LinearLayout row(String label, String value, String help, View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(ctx());
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(14), dp(11), dp(14), dp(11));
        r.setBackground(PhoneKit.pressable(PhoneKit.round(Theme.CARD, dp(10)), dp(10)));
        r.setOnClickListener(onClick);
        LinearLayout.LayoutParams card = new LinearLayout.LayoutParams(-1, -2);
        card.setMargins(dp(12), dp(3), dp(12), dp(3));
        r.setLayoutParams(card);
        LinearLayout line = new LinearLayout(ctx());
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = PhoneKit.text(ctx(), label, 15, Color.WHITE, true);
        line.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = PhoneKit.text(ctx(), value, 14, Theme.TEXT_DIM, true);
        v.setGravity(Gravity.END);
        v.setPadding(dp(8), 0, 0, 0);
        line.addView(v, new LinearLayout.LayoutParams(-2, -2));
        r.addView(line);
        if (help != null) {
            TextView h = PhoneKit.text(ctx(), help, 12, Theme.TEXT_DIM, false);
            h.setPadding(0, dp(3), 0, 0);
            r.addView(h);
        }
        return r;
    }

    private View choiceRow(final Setting s) {
        return row(s.label, s.value(ui.store, ui.host.prefs()), s.help, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.dialog().setTitle(s.label)
                        .setSingleChoiceItems(s.labels, s.index(ui.store, ui.host.prefs()), new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                d.dismiss();
                                if (!s.set(which, ui.store, ui.host)) ui.toast(Setting.LOADING);
                                fill();
                            }
                        })
                        .setNegativeButton("Cancel", null).show();
            }
        });
    }

    private View accountRow() {
        final String e = Profiles.email(ui.store);
        return row(e != null ? e : "Not signed in", e != null ? "Sign out" : "Sign in",
                "Signed in, your library, addons and settings are kept in your account and synced to your other devices.", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (e == null) {
                    signIn();
                    return;
                }
                ui.dialog().setTitle("Sign out?").setMessage("Your downloads stay on this phone.")
                        .setPositiveButton("Sign out", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                ui.ctx("Logout", null);
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
    }

    private View profilesRow() {
        JSONObject p = Profiles.current(ui.store, ui.host.prefs());
        return row("Who's watching", p != null ? Profiles.name(p) : "Sign in first",
                "Each profile has its own library, Continue Watching and addons.", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.pickProfile();
            }
        });
    }

    private View tmdbRow() {
        return row("Cast photos (TMDB key)", CastInfo.key() != null ? "Key saved" : "Add key",
                "Actors' photos and characters come from TMDB with your own free API key (themoviedb.org, Settings, API). It's saved with your account, so your other devices get it. Without a key, the cast shows names only.", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText t = field("32 letters and numbers", InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
                if (CastInfo.key() != null) t.setText(CastInfo.key());
                ui.dialog().setTitle("TMDB API key").setView(boxed(t))
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                String k = t.getText().toString().trim();
                                if (!k.isEmpty() && !CastInfo.looksLikeKey(k)) {
                                    ui.toast(CastInfo.BAD_KEY);
                                    return;
                                }
                                CastInfo.saveKey(ui.host.prefs(), ui.store, k);
                                fill();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
    }

    private View updatesRow() {
        return row("Updates", "Check now", "Codio checks for a newer version when it starts and offers to install it.", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.host.checkUpdates();
            }
        });
    }

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
        ui.dialog().setTitle("Sign in").setView(boxed(email, pass))
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

    private void addAddons() {
        JSONArray a = ui.store.addonDescriptors();
        for (int i = 0; i < a.length(); i++) {
            final JSONObject d = a.optJSONObject(i);
            if (d == null) continue;
            JSONObject m = d.optJSONObject("manifest");
            final String name = m != null ? m.optString("name", "Addon") : "Addon";
            final boolean prot = AddonKit.isProtected(d);
            String desc = m != null ? m.optString("description", "") : "";
            if (desc.length() > 120) desc = desc.substring(0, 117) + "…";
            list.addView(row(name, prot ? "Built in" : "Remove", desc.isEmpty() ? null : desc, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (prot) return;
                    ui.dialog().setTitle("Remove " + name + "?")
                            .setPositiveButton("Remove", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dd, int w) {
                                    AddonKit.uninstall(ui.store, ui.host, d);
                                    fill();
                                }
                            }).setNegativeButton("Cancel", null).show();
                }
            }));
        }
        list.addView(row("Add an addon", "+", "Paste the addon's link (it ends in manifest.json).", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLink();
            }
        }));
    }

    private void addLink() {
        final EditText t = field("https://…/manifest.json", InputType.TYPE_TEXT_VARIATION_URI);
        ui.dialog().setTitle("Addon link").setView(boxed(t))
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
