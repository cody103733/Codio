package com.codio.tv.ui;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/** Intro / recap / credits times for an episode, from the community database IntroDB (no account needed). */
final class SkipTimes {
    static final class Segment {
        final double start, end;

        Segment(double start, double end) {
            this.start = start;
            this.end = end;
        }

        boolean contains(double t) {
            return t >= start && t < end - 0.5;
        }
    }

    static final class Times {
        Segment intro, recap, outro;
    }

    static final int INTRO = 1, RECAP = 2, NEXT = 4;

    /**
     * Settings, Playback, Skip automatically (pref auto_skip: 0 off, 1 intros and recaps, 2 credits too, straight to the
     * next episode). What to skip right now at time t: INTRO, RECAP, NEXT or 0. done holds what this play already skipped,
     * so going back into an intro on purpose plays it.
     */
    static int auto(Times s, int mode, double t, boolean hasNext, int done) {
        if (s == null || mode <= 0) return 0;
        if (s.intro != null && s.intro.contains(t) && t < s.intro.end - 1.5 && (done & INTRO) == 0) return INTRO;
        if (s.recap != null && s.recap.contains(t) && t < s.recap.end - 1.5 && (done & RECAP) == 0) return RECAP;
        if (mode >= 2 && hasNext && s.outro != null && s.outro.contains(t) && (done & NEXT) == 0) return NEXT;
        return 0;
    }

    static String autoToast(int kind) {
        return kind == INTRO ? "Skipped intro" : kind == RECAP ? "Skipped recap" : "Skipping the credits";
    }

    interface Done {
        void done(Times t);
    }

    private static final String API = "https://api.introdb.app/segments";
    private static final Map<String, Times> cache = new HashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** The IMDb id to ask IntroDB with: the title's id, the episode id's prefix, or the addon's imdb_id field. */
    static String imdbOf(Store.Meta meta, String videoId) {
        if (meta != null && meta.id != null && meta.id.matches("tt\\d+")) return meta.id;
        if (videoId != null) {
            String head = videoId.split(":")[0];
            if (head.matches("tt\\d+")) return head;
        }
        if (meta != null && meta.raw != null) {
            for (String k : new String[]{"imdb_id", "imdbId", "imdb"}) {
                String v = meta.raw.optString(k, "");
                if (v.matches("tt\\d+")) return v;
            }
            JSONObject hints = meta.raw.optJSONObject("behaviorHints");
            if (hints != null && hints.optString("imdbId", "").matches("tt\\d+")) return hints.optString("imdbId");
        }
        return null;
    }

    /** {season, episode} of the video, from the title's episode list or the "id:season:episode" video id; null if unknown. */
    static int[] episodeOf(Store.Meta meta, String videoId) {
        if (videoId == null) return null;
        if (meta != null) {
            for (Store.Video v : meta.videos) {
                if (videoId.equals(v.id) && v.season > 0 && v.episode > 0) return new int[]{v.season, v.episode};
            }
        }
        String[] p = videoId.split(":");
        if (p.length >= 3) {
            try {
                int s = Integer.parseInt(p[p.length - 2]), e = Integer.parseInt(p[p.length - 1]);
                if (s > 0 && e > 0) return new int[]{s, e};
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    /** imdbId like tt0903747; season/episode > 0. done() runs on the main thread, with null when there's nothing. */
    static void load(final String imdbId, final int season, final int episode, final Done done) {
        if (imdbId == null || !imdbId.matches("tt\\d+") || season <= 0 || episode <= 0) {
            Log.i(TvView.TAG, "skip times: not asked (id " + imdbId + ", S" + season + " E" + episode + ")");
            return;
        }
        final String key = imdbId + ":" + season + ":" + episode;
        if (cache.containsKey(key)) {
            done.done(cache.get(key));
            return;
        }
        attempt(key, imdbId, season, episode, done, 0);
    }

    private static void attempt(final String key, final String imdbId, final int season, final int episode, final Done done, final int tries) {
        Net.IO.execute(new Runnable() {
            @Override
            public void run() {
                Times t = null;
                boolean answered = false;
                try {
                    String body = fetch(API + "?imdb_id=" + imdbId + "&season=" + season + "&episode=" + episode);
                    answered = true;
                    if (body != null) {
                        JSONObject o = new JSONObject(body);
                        t = new Times();
                        t.intro = segment(o.optJSONObject("intro"));
                        t.recap = segment(o.optJSONObject("recap"));
                        t.outro = segment(o.optJSONObject("outro"));
                        if (t.intro == null && t.recap == null && t.outro == null) t = null;
                    }
                    Log.i(TvView.TAG, "skip times " + key + ": " + (t == null ? "none"
                            : (t.intro != null ? "intro " + (int) t.intro.start + "-" + (int) t.intro.end + " " : "")
                            + (t.recap != null ? "recap " + (int) t.recap.start + "-" + (int) t.recap.end + " " : "")
                            + (t.outro != null ? "credits from " + (int) t.outro.start : "")));
                } catch (Exception e) {
                    Log.w(TvView.TAG, "skip times " + key + ": request failed (" + e + ")" + (tries < 2 ? ", trying again" : ""));
                }
                final Times result = t;
                final boolean ok = answered;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        // Only real answers are remembered: a failed request (timeout, rate limit) is tried again.
                        if (ok) {
                            cache.put(key, result);
                            done.done(result);
                        } else if (tries < 2) {
                            main.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    attempt(key, imdbId, season, episode, done, tries + 1);
                                }
                            }, 4000L * (tries + 1));
                        }
                    }
                });
            }
        });
    }

    /** The response body, or null for 404 (nothing timed for this episode). */
    private static String fetch(String url) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "Codio/1.4 (Android; +https://introdb.app)");
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code == 404) return null;
            if (code / 100 != 2) throw new java.io.IOException("HTTP " + code);
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            in.close();
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static Segment segment(JSONObject o) {
        if (o == null) return null;
        double s = o.has("start_sec") ? o.optDouble("start_sec", -1) : o.optDouble("start_ms", -1000) / 1000;
        double e = o.has("end_sec") ? o.optDouble("end_sec", -1) : o.optDouble("end_ms", -1000) / 1000;
        if (Double.isNaN(s) || Double.isNaN(e) || s < 0 || e <= s + 2) return null;
        return new Segment(s, e);
    }
}
