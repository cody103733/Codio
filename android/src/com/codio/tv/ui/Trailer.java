package com.codio.tv.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * A title's trailer (a YouTube video), for the Trailer button on the movie/show page. Rules in DESIGN.md "Trailers":
 * TMDB first (needs the key from Settings), else the trailer the addon gave (Cinemeta has one for most titles).
 * It plays in the YouTube app when there is one, else in Codio's own little player.
 */
final class Trailer {
    interface Done {
        /** The YouTube id, or null when the title has no trailer. Runs on the main thread. */
        void done(String youtubeId);
    }

    private static final String API = "https://api.themoviedb.org/3/";
    private static final Map<String, String> cache = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void find(final Store.Meta meta, final Done done) {
        final String imdb = SkipTimes.imdbOf(meta, null);
        final String cacheKey = imdb != null ? imdb : meta.id;
        String known = cache.get(cacheKey);
        if (known != null) {
            done.done(known.isEmpty() ? null : known);
            return;
        }
        final String tmdb = meta.raw != null ? meta.raw.optString("moviedb_id", "") : "";
        final boolean series = !"movie".equals(meta.type);
        final JSONObject raw = meta.raw;
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                String found = null;
                try {
                    found = fromTmdb(imdb, tmdb, series);
                } catch (Exception e) {
                    Log.w(TvView.TAG, "trailer: TMDB lookup failed (" + e.getClass().getSimpleName() + ")");
                }
                if (found == null) found = fromAddon(raw);
                final String id = found;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        // An empty answer is remembered only once the full details are in.
                        if (id != null || raw != null) cache.put(cacheKey, id != null ? id : "");
                        done.done(id);
                    }
                });
            }
        });
    }

    static String fromAddon(JSONObject raw) {
        if (raw == null) return null;
        JSONArray s = raw.optJSONArray("trailerStreams");
        for (int i = 0; s != null && i < s.length(); i++) {
            JSONObject o = s.optJSONObject(i);
            String y = o != null ? o.optString("ytId", "") : "";
            if (!y.isEmpty()) return y;
        }
        JSONArray t = raw.optJSONArray("trailers");
        for (int i = 0; t != null && i < t.length(); i++) {
            JSONObject o = t.optJSONObject(i);
            if (o == null) continue;
            String y = o.optString("source", "");
            if (!y.isEmpty() && "Trailer".equals(o.optString("type", "Trailer"))) return y;
        }
        return null;
    }

    private static String fromTmdb(String imdb, String tmdb, boolean series) throws Exception {
        String key = CastInfo.key();
        if (key == null) return null;
        String path = null;
        if (imdb != null) {
            JSONObject f = new JSONObject(Net.get(API + "find/" + imdb + "?external_source=imdb_id&api_key=" + key));
            JSONObject show = first(f.optJSONArray("tv_results")), movie = first(f.optJSONArray("movie_results"));
            JSONObject preferred = series ? show : movie, other = series ? movie : show;
            if (preferred != null) path = (series ? "tv/" : "movie/") + preferred.optInt("id");
            else if (other != null) path = (series ? "movie/" : "tv/") + other.optInt("id");
        }
        if (path == null && tmdb.matches("\\d+")) path = (series ? "tv/" : "movie/") + tmdb;
        if (path == null) return null;
        JSONObject v = new JSONObject(Net.get(API + path + "/videos?include_video_language=en,null&api_key=" + key));
        return pick(v.optJSONArray("results"));
    }

    /** YouTube only; a Trailer before a Teaser, official before fan-made, otherwise TMDB's order. */
    static String pick(JSONArray videos) {
        String best = null;
        int bestScore = -1;
        for (int i = 0; videos != null && i < videos.length(); i++) {
            JSONObject v = videos.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site"))) continue;
            String id = v.optString("key", ""), type = v.optString("type", "");
            if (id.isEmpty() || !("Trailer".equals(type) || "Teaser".equals(type))) continue;
            int score = ("Trailer".equals(type) ? 2 : 0) + (v.optBoolean("official", false) ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best;
    }

    private static JSONObject first(JSONArray a) {
        return a != null && a.length() > 0 ? a.optJSONObject(0) : null;
    }

    // ------------------------------------------------------------------ playing

    /** TV: the YouTube TV app (Google TV, then Fire TV's), else Codio's player. Phone: YouTube or the browser, else Codio's player. */
    static void play(Context c, String id, boolean tv) {
        String url = "https://www.youtube.com/watch?v=" + id;
        if (tv) {
            for (String pkg : new String[]{"com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube"}) {
                if (start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg))) return;
            }
        } else {
            if (start(c, new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + id)))) return;
            if (start(c, new Intent(Intent.ACTION_VIEW, Uri.parse(url)))) return;
        }
        playHere(c, id);
    }

    private static boolean start(Context c, Intent i) {
        try {
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Activity activity(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** YouTube's own embedded player, full screen in a dialog. Back closes it; OK / Play pauses and resumes. */
    private static void playHere(Context c, String id) {
        Activity a = activity(c);
        if (a == null || a.isFinishing()) return;
        final Dialog d = new Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final WebView web = new WebView(a);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{border:0;width:100%;height:100%}</style></head><body>"
                + "<iframe id='p' allow='autoplay; encrypted-media; fullscreen' allowfullscreen "
                + "src='https://www.youtube.com/embed/" + id.replaceAll("[^A-Za-z0-9_-]", "")
                + "?autoplay=1&rel=0&playsinline=1&enablejsapi=1&origin=https://com.codio.tv'></iframe>"
                + "<script>var playing=true;function cmd(f){document.getElementById('p').contentWindow.postMessage(JSON.stringify({event:'command',func:f,args:[]}),'*')}"
                + "function toggle(){cmd(playing?'pauseVideo':'playVideo');playing=!playing}</script></body></html>";
        // YouTube's embeds need to know which app they are in (an https address), or they refuse to play.
        web.loadDataWithBaseURL("https://com.codio.tv/", html, "text/html", "utf-8", null);
        d.setContentView(web, new ViewGroup.LayoutParams(-1, -1));
        d.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(android.content.DialogInterface di, int k, KeyEvent e) {
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        || k == KeyEvent.KEYCODE_MEDIA_PLAY || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                    if (e.getAction() == KeyEvent.ACTION_UP) web.evaluateJavascript("toggle()", null);
                    return true;
                }
                return false;
            }
        });
        d.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                web.loadUrl("about:blank");
                web.destroy();
            }
        });
        Window w = d.getWindow();
        if (w != null) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        d.show();
    }

    private Trailer() {
    }
}
