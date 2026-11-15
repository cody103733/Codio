package com.codio.tv.ui;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

/** Installing and removing addons, shared by the TV Addons screen and the phone's Settings. */
final class AddonKit {
    private AddonKit() {}

    static final String NOT_AN_ADDON = "That link isn't an addon (no manifest found)", ALREADY = "Already installed",
            NEEDS_SETUP = "This addon needs setting up on its website first", CHECKING = "Checking the addon…";

    interface Done {
        /** descriptor is null when the link is no addon. Called on the main thread. */
        void done(JSONObject descriptor);
    }

    static boolean isProtected(JSONObject d) {
        JSONObject f = d.optJSONObject("flags");
        return f != null && f.optBoolean("protected");
    }

    static boolean needsSetup(JSONObject d) {
        JSONObject m = d.optJSONObject("manifest");
        JSONObject bh = m != null ? m.optJSONObject("behaviorHints") : null;
        return bh != null && bh.optBoolean("configurationRequired");
    }

    /** What the viewer typed or pasted, as the https link of a manifest.json. */
    static String manifestUrl(String text) {
        String u = text.startsWith("stremio://") ? "https://" + text.substring(10) : text;
        if (!u.startsWith("http")) u = "https://" + u;
        if (!u.endsWith("manifest.json")) u = u.replaceAll("/+$", "") + "/manifest.json";
        return u;
    }

    /** Fetches the manifest at url and hands back the descriptor to install. */
    static void fetch(final String url, final Done done) {
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                JSONObject d = null;
                try {
                    JSONObject manifest = new JSONObject(Net.get(url));
                    if (manifest.has("id") && manifest.has("name")) {
                        d = new JSONObject().put("transportUrl", url).put("manifest", manifest)
                                .put("flags", new JSONObject().put("official", false).put("protected", false));
                    }
                } catch (Exception ignored) {
                }
                final JSONObject descriptor = d;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        done.done(descriptor);
                    }
                });
            }
        });
    }

    /** Installs d; it shows as installed straight away (the real profile arrives a moment later). */
    static void install(Store store, TvView.Host host, JSONObject d) {
        host.dispatch(Core.ctx("InstallAddon", d));
        try {
            JSONArray a = store.addonDescriptors();
            a.put(new JSONObject(d.toString()));
            if (!store.profileJson.has("addons")) store.profileJson.put("addons", a);
        } catch (Exception ignored) {
        }
    }

    /** Removes the addon at url (d is used when it isn't in the profile's list). */
    static void uninstall(Store store, TvView.Host host, JSONObject d) {
        String url = d.optString("transportUrl");
        JSONArray a = store.addonDescriptors();
        JSONObject installed = null;
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && url.equals(x.optString("transportUrl"))) {
                installed = x;
                a.remove(i);
            }
        }
        host.dispatch(Core.ctx("UninstallAddon", installed != null ? installed : d));
    }
}
