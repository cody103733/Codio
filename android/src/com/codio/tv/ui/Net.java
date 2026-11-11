package com.codio.tv.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Small HTTP helpers. Responses go through the app-wide HttpResponseCache that MainActivity installs. */
public final class Net {
    private Net() {}

    /** For catalog/meta JSON (images have their own pool in Images). */
    public static final ExecutorService IO = Executors.newFixedThreadPool(4, named("codio-io", Thread.NORM_PRIORITY - 1));

    static ThreadFactory named(final String name, final int priority) {
        return new ThreadFactory() {
            int n = 0;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + (n++));
                t.setPriority(priority);
                t.setDaemon(true);
                return t;
            }
        };
    }

    public static byte[] getBytes(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CodioTV");
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " for " + shortUrl(url));
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, c.getContentLength()));
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            in.close();
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    public static String get(String url) throws IOException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    /** Host and path start only: addon URLs can carry debrid keys, which must never reach the log. */
    public static String shortUrl(String url) {
        try {
            URL u = new URL(url);
            String p = u.getPath();
            int i = p.indexOf("/catalog/");
            if (i < 0) i = p.indexOf("/meta/");
            return u.getHost() + (i >= 0 ? p.substring(i) : "/…");
        } catch (Exception e) {
            return "?";
        }
    }
}
