package com.codio.tv.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Addons: what's installed (remove), the addon catalogs (install), or install from a link. */
final class AddonsScreen extends Screen {
    private static final int NAV = 0, TABS = 1, LIST = 2;
    private static final float TABS_Y = 160, LIST_Y = 260, ROW_H = 150, LIST_X = TvView.MARGIN, LIST_W = 1728;

    /** A tab: the installed addons, an addon catalog ({base}/addon_catalog/{type}/{id}.json), or "from a link". */
    private static final class Tab {
        final String label, url;

        Tab(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    private final NavBar nav;
    private final List<Tab> tabs = new ArrayList<>();
    private int tab = 0, tabFocus = 0, zone = TABS, focus = 0;
    private final List<JSONObject> rows = new ArrayList<>();
    private boolean loading, failed;
    private int request = 0;
    private String confirmUrl;
    private long confirmUntil;
    private final Smooth scroll = new Smooth(0.09f, 0.4f);
    private final RectF r = new RectF();

    AddonsScreen(TvView tv) {
        super(tv);
        nav = new NavBar(tv, NavBar.ADDONS);
        buildTabs();
        fill();
    }

    private void buildTabs() {
        tabs.clear();
        tabs.add(new Tab("Installed", null));
        for (Store.Addon a : tv.store.addons()) {
            JSONArray cats = a.manifest.optJSONArray("addonCatalogs");
            if (cats == null) continue;
            for (int i = 0; i < cats.length(); i++) {
                JSONObject c = cats.optJSONObject(i);
                if (c == null) continue;
                String type = c.optString("type", "all"), id = c.optString("id", "");
                if (id.isEmpty()) continue;
                String name = c.optString("name", id);
                if (!"all".equals(type) && !type.isEmpty()) name = name + " · " + Store.typeLabel(type);
                tabs.add(new Tab(name, a.base + "/addon_catalog/" + android.net.Uri.encode(type) + "/" + android.net.Uri.encode(id) + ".json"));
            }
        }
        tabs.add(new Tab("Add from a link", null));
        if (tab >= tabs.size()) tab = tabFocus = 0;
    }

    private boolean linkTab(int i) {
        return i == tabs.size() - 1;
    }

    private void fill() {
        Tab t = tabs.get(tab);
        if (tab == 0) {
            rows.clear();
            JSONArray a = tv.store.addonDescriptors();
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) rows.add(a.optJSONObject(i));
            loading = false;
            failed = false;
        } else if (t.url != null) {
            load(t.url);
        } else {
            rows.clear();
        }
        if (focus >= rows.size()) focus = Math.max(0, rows.size() - 1);
    }

    private void load(final String url) {
        rows.clear();
        loading = true;
        failed = false;
        final int id = ++request;
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                final List<JSONObject> got = new ArrayList<>();
                boolean err = false;
                try {
                    JSONArray list = new JSONObject(Net.get(url)).optJSONArray("addons");
                    if (list != null) for (int i = 0; i < list.length(); i++) {
                        JSONObject d = list.optJSONObject(i);
                        if (d != null && d.optJSONObject("manifest") != null && !d.optString("transportUrl").isEmpty()) got.add(d);
                    }
                } catch (Exception e) {
                    err = true;
                }
                final boolean error = err;
                tv.post(new Runnable() {
                    @Override
                    public void run() {
                        if (id != request) return;
                        loading = false;
                        failed = error && got.isEmpty();
                        rows.clear();
                        rows.addAll(got);
                        if (focus >= rows.size()) focus = 0;
                        tv.kick();
                    }
                });
            }
        });
    }

    @Override
    void onShow() {
        nav.focus = nav.current;
    }

    @Override
    void dataChanged(boolean addonsChanged) {
        if (!addonsChanged) return;
        String keep = tabs.get(tab).label;
        buildTabs();
        for (int i = 0; i < tabs.size(); i++) if (tabs.get(i).label.equals(keep)) tab = tabFocus = i;
        if (tab == 0) fill();
    }

    @Override
    void coreEvent(String name, String message) {
        if (name.equals("AddonInstalled") || name.equals("AddonUninstalled")) tv.kick();
    }

    private static boolean isProtected(JSONObject d) {
        JSONObject f = d.optJSONObject("flags");
        return f != null && f.optBoolean("protected");
    }

    private static boolean needsSetup(JSONObject d) {
        JSONObject bh = d.optJSONObject("manifest").optJSONObject("behaviorHints");
        return bh != null && bh.optBoolean("configurationRequired");
    }

    private String action(JSONObject d) {
        String url = d.optString("transportUrl");
        boolean installed = tv.store.isInstalled(url);
        if (installed) {
            if (isProtected(d)) return "Built in";
            if (url.equals(confirmUrl) && SystemClock.uptimeMillis() < confirmUntil) return "Press OK again to remove";
            return tab == 0 ? "Remove" : "Installed · Remove";
        }
        if (needsSetup(d)) return "Set up on a computer first";
        return "Install";
    }

    @Override
    void draw(Canvas c, float dt) {
        c.drawColor(0xFF0B0B0B);
        float x = TvView.MARGIN;
        for (int i = 0; i < tabs.size(); i++) {
            tv.drawChip(c, x, TABS_Y, tabs.get(i).label, zone == TABS && i == tabFocus, i == tab);
            x += tv.chipWidth(tabs.get(i).label) + 14;
        }
        if (linkTab(tab)) {
            tv.drawWrapped(c, "Paste or type an addon's link (it ends in manifest.json). Addons that need setting up (debrid keys and the like) are configured on their own website first; then use the link it gives you.",
                    tv.tBody, LIST_X, LIST_Y + 40, 1200, 4);
            tv.drawButton(c, LIST_X, LIST_Y + 180, "Type a link", false, zone == LIST, true);
        } else {
            scroll.to(Math.max(0, (focus - 3) * ROW_H));
            float sy = step(scroll, dt);
            c.save();
            c.clipRect(0, LIST_Y - 16, TvView.W, TvView.H);
            int first = Math.max(0, (int) (sy / ROW_H) - 1);
            for (int i = first; i < rows.size(); i++) {
                float y = LIST_Y + i * ROW_H - sy;
                if (y > TvView.H) break;
                drawRow(c, rows.get(i), y, zone == LIST && i == focus);
            }
            c.restore();
            if (rows.isEmpty()) {
                if (loading) tv.spinner(c, TvView.W / 2, 560, 34, dt);
                else if (failed) c.drawText("Couldn't load this list. Check the connection and try again.", LIST_X, LIST_Y + 80, tv.tBody);
                else c.drawText(tab == 0 && !tv.store.profileLoaded ? "Loading your addons…" : "Nothing here.", LIST_X, LIST_Y + 80, tv.tBody);
            }
            if (confirmUrl != null && SystemClock.uptimeMillis() < confirmUntil) tv.moving();
        }
        tv.fill.setColor(0xFF0B0B0B);
        nav.draw(c, zone == NAV);
    }

    private void drawRow(Canvas c, JSONObject d, float y, boolean focused) {
        JSONObject m = d.optJSONObject("manifest");
        r.set(LIST_X, y, LIST_X + LIST_W, y + ROW_H - 14);
        tv.fill.setColor(focused ? Color.WHITE : 0xFF161616);
        c.drawRoundRect(r, 12, 12, tv.fill);
        // Logo.
        float lx = LIST_X + 24, ly = y + 18, ls = 100;
        r.set(lx, ly, lx + ls, ly + ls);
        tv.fill.setColor(focused ? 0xFF1A1A1A : 0xFF242424);
        c.drawRoundRect(r, 12, 12, tv.fill);
        String logo = m.optString("logo", "");
        if (logo.startsWith("http")) {
            int px = Math.round(ls * tv.scale());
            Bitmap b = tv.images.logo(logo, px, px);
            if (b != null) {
                float bw = b.getWidth() / tv.scale(), bh = b.getHeight() / tv.scale();
                r.set(lx + (ls - bw) / 2, ly + (ls - bh) / 2, lx + (ls + bw) / 2, ly + (ls + bh) / 2);
                c.drawBitmap(b, null, r, tv.bmp);
            }
        } else {
            String n = m.optString("name", "?");
            tv.tTitle.setColor(focused ? Color.WHITE : 0xFFDDDDDD);
            String ch = n.isEmpty() ? "?" : n.substring(0, 1).toUpperCase();
            c.drawText(ch, lx + (ls - tv.tTitle.measureText(ch)) / 2, ly + ls / 2 + 12, tv.tTitle);
            tv.tTitle.setColor(Color.WHITE);
        }
        // Action on the right.
        String act = action(d);
        float aw = tv.tNav.measureText(act);
        boolean danger = act.startsWith("Press OK");
        tv.tNav.setColor(danger ? TvView.RED : focused ? 0xFF222222 : 0xFFB3B3B3);
        c.drawText(act, LIST_X + LIST_W - 32 - aw, y + 50, tv.tNav);
        tv.tNav.setColor(0xFFB3B3B3);
        // Name, version, types, description.
        float tx = lx + ls + 28, tw = LIST_W - (tx - LIST_X) - aw - 80;
        String head = m.optString("name", "Addon") + (m.has("version") ? "  v" + m.optString("version") : "");
        tv.tLabel.setColor(focused ? Color.BLACK : Color.WHITE);
        c.drawText(tv.fit(head, tv.tLabel, tw), tx, y + 50, tv.tLabel);
        tv.tLabel.setColor(Color.WHITE);
        StringBuilder types = new StringBuilder();
        JSONArray ty = m.optJSONArray("types");
        if (ty != null) for (int i = 0; i < ty.length() && i < 4; i++) {
            if (types.length() > 0) types.append(", ");
            types.append(Store.typeLabel(ty.optString(i)));
        }
        String desc = m.optString("description", "").replace("\n", " ");
        if (types.length() > 0) desc = types + (desc.isEmpty() ? "" : "  ·  " + desc);
        tv.tSmall.setColor(focused ? 0xFF444444 : 0xFFAAAAAA);
        tv.drawWrapped(c, desc, tv.tSmall, tx, y + 66, LIST_W - (tx - LIST_X) - 40, 2);
        tv.tSmall.setColor(0xFFAAAAAA);
    }

    private void activate(JSONObject d) {
        String url = d.optString("transportUrl");
        if (tv.store.isInstalled(url)) {
            if (isProtected(d)) return;
            if (url.equals(confirmUrl) && SystemClock.uptimeMillis() < confirmUntil) {
                confirmUrl = null;
                JSONObject installed = descriptor(url);
                tv.ctx("UninstallAddon", installed != null ? installed : d);
                removeLocally(url);
            } else {
                confirmUrl = url;
                confirmUntil = SystemClock.uptimeMillis() + 4000;
            }
            return;
        }
        if (needsSetup(d)) {
            tv.toast("Open " + url.replace("manifest.json", "configure") + " on a computer or phone");
            return;
        }
        install(d);
    }

    private void install(JSONObject d) {
        tv.ctx("InstallAddon", d);
        try {
            // Shown as installed straight away; the real profile arrives a moment later.
            JSONArray a = tv.store.addonDescriptors();
            a.put(new JSONObject(d.toString()));
            if (!tv.store.profileJson.has("addons")) tv.store.profileJson.put("addons", a);
        } catch (Exception ignored) {
        }
    }

    private JSONObject descriptor(String url) {
        JSONArray a = tv.store.addonDescriptors();
        for (int i = 0; i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (d != null && url.equals(d.optString("transportUrl"))) return d;
        }
        return null;
    }

    private void removeLocally(String url) {
        JSONArray a = tv.store.addonDescriptors();
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject d = a.optJSONObject(i);
            if (d != null && url.equals(d.optString("transportUrl"))) a.remove(i);
        }
        if (tab == 0) fill();
    }

    /** "Add from a link": fetch the manifest, then install it. */
    private void typeLink() {
        tv.push(new TextEntryScreen(tv, "Addon link", "The link ends in manifest.json, e.g. https://example.com/manifest.json", "https://", false,
                new TextEntryScreen.Done() {
                    @Override
                    public void done(String text) {
                        if (text.isEmpty() || text.equals("https://")) return;
                        String u = text.startsWith("stremio://") ? "https://" + text.substring(10) : text;
                        if (!u.startsWith("http")) u = "https://" + u;
                        if (!u.endsWith("manifest.json")) u = u.replaceAll("/+$", "") + "/manifest.json";
                        fetchAndInstall(u);
                    }
                }));
    }

    private void fetchAndInstall(final String url) {
        tv.toast("Checking the addon…");
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
                tv.post(new Runnable() {
                    @Override
                    public void run() {
                        if (desc == null) {
                            tv.toast("That link isn't an addon (no manifest found)");
                            return;
                        }
                        if (tv.store.isInstalled(url)) {
                            tv.toast("Already installed");
                            return;
                        }
                        if (needsSetup(desc)) {
                            tv.toast("This addon needs setting up on its website first");
                            return;
                        }
                        install(desc);
                        tab = tabFocus = 0;
                        zone = LIST;
                        fill();
                        focus = Math.max(0, rows.size() - 1);
                    }
                });
            }
        });
    }

    @Override
    boolean key(int k, KeyEvent e) {
        switch (zone) {
            case NAV:
                if (k == TvView.DOWN) {
                    zone = TABS;
                    tabFocus = tab;
                    return true;
                }
                return nav.key(k);
            case TABS:
                if (k == TvView.LEFT && tabFocus > 0) tabFocus--;
                else if (k == TvView.RIGHT && tabFocus < tabs.size() - 1) tabFocus++;
                else if (k == TvView.UP) {
                    zone = NAV;
                    nav.focus = nav.current;
                } else if (k == TvView.DOWN) {
                    if (linkTab(tab) || !rows.isEmpty()) zone = LIST;
                } else if (k == TvView.OK) {
                    if (tabFocus != tab) {
                        tab = tabFocus;
                        focus = 0;
                        scroll.snap(0);
                        fill();
                    }
                    if (linkTab(tab) || !rows.isEmpty()) zone = LIST;
                }
                return true;
            case LIST:
            default:
                if (linkTab(tab)) {
                    if (k == TvView.OK) typeLink();
                    else if (k == TvView.UP) zone = TABS;
                    return true;
                }
                if (k == TvView.UP) {
                    if (focus > 0) focus--;
                    else {
                        zone = TABS;
                        tabFocus = tab;
                    }
                } else if (k == TvView.DOWN) {
                    if (focus < rows.size() - 1) focus++;
                } else if (k == TvView.OK && focus < rows.size()) {
                    activate(rows.get(focus));
                }
                if (k != TvView.OK) confirmUrl = null;
                return true;
        }
    }

    @Override
    boolean back() {
        if (zone == LIST) {
            zone = TABS;
            tabFocus = tab;
            confirmUrl = null;
            return true;
        }
        if (zone != NAV) {
            zone = NAV;
            nav.focus = nav.current;
            return true;
        }
        return false;
    }
}
