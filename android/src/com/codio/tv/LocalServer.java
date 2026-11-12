package com.codio.tv;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import android.util.Base64;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves the bundled Codio web UI on http://127.0.0.1:PORT (loopback only), like the Windows launcher does.
 * A real local origin keeps logins/settings between launches and lets the web worker and WebAssembly load normally.
 */
final class LocalServer {
    private static final String TAG = "CodioServer";
    static final int[] PORTS = {11471, 11481, 11491};

    interface IndexFilter {
        String filter(String html);
    }

    private final AssetManager assets;
    private final IndexFilter indexFilter;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private int port = -1;

    LocalServer(AssetManager assets, IndexFilter indexFilter) {
        this.assets = assets;
        this.indexFilter = indexFilter;
    }

    /** Returns the origin, e.g. "http://127.0.0.1:11471". */
    String start() throws IOException {
        IOException last = null;
        for (int p : PORTS) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 50);
                server = s;
                port = p;
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last != null ? last : new IOException("no port");
        Thread accept = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "codio-http");
        accept.setDaemon(true);
        accept.start();
        return "http://127.0.0.1:" + port;
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                final Socket socket = server.accept();
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(socket);
                    }
                });
            } catch (IOException e) {
                if (!server.isClosed()) Log.w(TAG, "accept failed", e);
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(20000);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            while (true) {
                String requestLine = readLine(in);
                if (requestLine == null || requestLine.isEmpty()) break;
                boolean keepAlive = true;
                Map<String, String> headers = new HashMap<>();
                String line;
                while ((line = readLine(in)) != null && !line.isEmpty()) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("connection:") && lower.contains("close")) keepAlive = false;
                    int colon = line.indexOf(':');
                    if (colon > 0) headers.put(lower.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
                String[] parts = requestLine.split(" ");
                if (parts.length < 2) break;
                String method = parts[0];
                String path = parts[1];
                if (path.startsWith(STREAM_PREFIX) || path.startsWith(BUFFERED_PREFIX)) {
                    proxy(out, method, path, headers, socket, path.startsWith(BUFFERED_PREFIX));
                    break; // one request per proxied connection
                }
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                int h = path.indexOf('#');
                if (h >= 0) path = path.substring(0, h);
                path = URLDecoder.decode(path, "UTF-8");
                respond(out, method, path, keepAlive);
                if (!keepAlive) break;
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException e) {
            Log.d(TAG, "connection ended: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void respond(OutputStream out, String method, String path, boolean keepAlive) throws IOException {
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            write(out, 405, "Method Not Allowed", "text/plain", new byte[0], "no-store", keepAlive, method);
            return;
        }
        if (path.equals("/") || path.isEmpty()) path = "/index.html";
        if (path.contains("..") || path.contains("\\")) {
            write(out, 404, "Not Found", "text/plain", new byte[0], "no-store", keepAlive, method);
            return;
        }
        byte[] body;
        try {
            body = readAll(assets.open("web" + path));
        } catch (IOException e) {
            write(out, 404, "Not Found", "text/plain", new byte[0], "no-store", keepAlive, method);
            return;
        }
        String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String mime = MIME.containsKey(ext) ? MIME.get(ext) : "application/octet-stream";
        String cache = "public, max-age=31536000, immutable";
        if (path.equals("/index.html")) {
            body = indexFilter.filter(new String(body, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            cache = "no-store";
        } else if (path.equals("/manifest.json") || !path.matches("^/[0-9a-f]{40}/.*")) {
            cache = "no-cache";
        }
        write(out, 200, "OK", mime, body, cache, keepAlive, method);
    }

    // ---------------------------------------------------------------- media proxy
    //
    // mpv's built-in TLS (mbedtls) can't complete handshakes with some debrid CDNs, so https streams are fetched
    // with Android's own HTTP stack and handed to mpv over plain http on loopback. Range requests (seeking) are passed
    // through unchanged.

    private static final String STREAM_PREFIX = "/stream/";
    /** Same, plus a large read-ahead (Android's own player keeps only a small buffer of its own). */
    private static final String BUFFERED_PREFIX = "/streamb/";

    static String bufferedStreamPath(String url) {
        return BUFFERED_PREFIX + streamPath(url).substring(STREAM_PREFIX.length());
    }
    private static final String USER_AGENT = "Codio/1.0 (Android TV)";

    static String streamPath(String url) {
        String id = Base64.encodeToString(url.getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        String name = url;
        int q = name.indexOf('?');
        if (q >= 0) name = name.substring(0, q);
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.isEmpty()) name = "media";
        return STREAM_PREFIX + id + "/" + name;
    }

    private void proxy(OutputStream out, String method, String path, Map<String, String> reqHeaders, Socket client,
                       boolean buffered) throws IOException {
        String rest = path.substring((buffered ? BUFFERED_PREFIX : STREAM_PREFIX).length());
        int slash = rest.indexOf('/');
        String target;
        try {
            target = new String(Base64.decode(slash >= 0 ? rest.substring(0, slash) : rest, Base64.URL_SAFE), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            write(out, 400, "Bad Request", "text/plain", new byte[0], "no-store", false, method);
            return;
        }
        boolean head = "HEAD".equals(method);
        HttpURLConnection c = null;
        boolean sent = false;
        try {
            // VLC opens a file with several byte-range requests. Reuse the debrid link's final address so only the
            // first one pays for the redirect chain.
            String cached = cachedRedirect(target);
            String url = cached != null ? cached : target;
            final long startedAt = android.os.SystemClock.uptimeMillis();
            final boolean fromCache = cached != null;
            int code = 0;
            for (int hop = 0; hop < 8; hop++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false); // followed by hand so https->http redirects work too
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestMethod(head ? "HEAD" : "GET");
                c.setRequestProperty("User-Agent", USER_AGENT);
                c.setRequestProperty("Accept-Encoding", "identity"); // byte ranges must match the file, not gzip
                String range = reqHeaders.get("range");
                if (range != null) c.setRequestProperty("Range", range);
                code = c.getResponseCode();
                if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                    url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                    c.disconnect();
                    c = null;
                    continue;
                }
                if (code >= 400 && cached != null) {
                    // The remembered address went stale: forget it and take the original link from the top.
                    forgetRedirect(target);
                    cached = null;
                    url = target;
                    c.disconnect();
                    c = null;
                    continue;
                }
                break;
            }
            if (c == null) throw new IOException("too many redirects");
            if (code < 400 && !url.equals(target)) rememberRedirect(target, url);
            Log.e("CodioTiming", "proxy " + (head ? "HEAD " : "GET ") + reqHeaders.get("range") + " -> " + code + " in "
                    + (android.os.SystemClock.uptimeMillis() - startedAt) + " ms, redirect cache " + (fromCache ? "hit" : "miss"));
            StringBuilder sb = new StringBuilder();
            String reason = c.getResponseMessage();
            sb.append("HTTP/1.1 ").append(code).append(' ').append(reason != null ? reason : "").append("\r\n");
            for (String h : new String[]{"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag"}) {
                String v = c.getHeaderField(h);
                if (v != null) sb.append(h).append(": ").append(v).append("\r\n");
            }
            sb.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
            sent = true;
            if (!head) {
                InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (body != null) {
                    try {
                        client.setSoTimeout(0);
                        if (buffered && code < 300) {
                            readAhead(body, out);
                        } else {
                            byte[] buf = new byte[256 * 1024];
                            int n;
                            while ((n = body.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    } finally {
                        body.close();
                    }
                }
            }
            out.flush();
            if (code >= 400) Log.w(TAG, "stream " + code + " for " + hostOf(target));
        } catch (IOException e) {
            // mpv closing the connection (seek, stop) also lands here; that's normal.
            Log.d(TAG, "stream ended: " + e.getMessage());
            if (!sent) {
                forgetRedirect(target);
                try {
                    write(out, 502, "Bad Gateway", "text/plain", new byte[0], "no-store", false, method);
                } catch (IOException ignored) {
                }
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final int CHUNK = 512 * 1024, AHEAD_CHUNKS = 192; // 96 MB ahead of the player
    private static final byte[] END = new byte[0];

    /**
     * Keeps downloading while the player isn't reading (its own buffer is full), up to 96 MB ahead, so a slow moment
     * on the connection is covered by what's already here instead of the picture pausing to load.
     */
    private static void readAhead(final InputStream body, OutputStream out) throws IOException {
        final java.util.concurrent.ArrayBlockingQueue<byte[]> q = new java.util.concurrent.ArrayBlockingQueue<>(AHEAD_CHUNKS);
        final boolean[] stop = {false};
        final IOException[] failed = {null};
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        byte[] chunk = new byte[CHUNK];
                        int len = 0, n = 0;
                        while (len < CHUNK && (n = body.read(chunk, len, CHUNK - len)) > 0) len += n;
                        if (len > 0) {
                            byte[] c = len == CHUNK ? chunk : java.util.Arrays.copyOf(chunk, len);
                            while (!q.offer(c, 500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                synchronized (stop) {
                                    if (stop[0]) return;
                                }
                            }
                        }
                        if (n <= 0 && len < CHUNK) break;
                    }
                } catch (IOException e) {
                    failed[0] = e;
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    while (!q.offer(END, 500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        synchronized (stop) {
                            if (stop[0]) return;
                        }
                    }
                } catch (InterruptedException ignored) {
                }
            }
        }, "codio-readahead");
        reader.start();
        try {
            while (true) {
                byte[] c;
                try {
                    c = q.take();
                } catch (InterruptedException e) {
                    throw new IOException("interrupted");
                }
                if (c == END) break;
                out.write(c);
            }
            if (failed[0] != null) throw failed[0];
        } finally {
            synchronized (stop) {
                stop[0] = true;
            }
            q.clear();
            try {
                body.close();
            } catch (IOException ignored) {
            }
            reader.interrupt();
        }
    }

    private static final long REDIRECT_TTL_MS = 10 * 60 * 1000;
    private static final Map<String, String> redirects = new java.util.HashMap<>();
    private static final Map<String, Long> redirectTimes = new java.util.HashMap<>();

    private static synchronized String cachedRedirect(String target) {
        Long at = redirectTimes.get(target);
        if (at == null) return null;
        if (android.os.SystemClock.uptimeMillis() - at > REDIRECT_TTL_MS) {
            forgetRedirect(target);
            return null;
        }
        return redirects.get(target);
    }

    private static synchronized void rememberRedirect(String target, String url) {
        if (redirects.size() >= 32) {
            redirects.clear();
            redirectTimes.clear();
        }
        redirects.put(target, url);
        redirectTimes.put(target, android.os.SystemClock.uptimeMillis());
    }

    private static synchronized void forgetRedirect(String target) {
        redirects.remove(target);
        redirectTimes.remove(target);
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    private static void write(OutputStream out, int code, String reason, String mime, byte[] body, String cache,
                              boolean keepAlive, String method) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(mime);
        if (mime.startsWith("text/") || mime.endsWith("javascript") || mime.endsWith("json") || mime.endsWith("+xml")) {
            sb.append("; charset=utf-8");
        }
        sb.append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: ").append(cache).append("\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        sb.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (!"HEAD".equals(method)) out.write(body);
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
            if (line.size() > 16384) throw new IOException("header too long");
        }
        if (c == -1 && line.size() == 0) return null;
        return line.toString("ISO-8859-1");
    }

    static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(in.available(), 64 * 1024));
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static final Map<String, String> MIME = new HashMap<>();
    static {
        MIME.put("html", "text/html");
        MIME.put("js", "application/javascript");
        MIME.put("css", "text/css");
        MIME.put("json", "application/json");
        MIME.put("wasm", "application/wasm");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("jpg", "image/jpeg");
        MIME.put("webp", "image/webp");
        MIME.put("ico", "image/x-icon");
        MIME.put("ttf", "font/ttf");
        MIME.put("woff", "font/woff");
        MIME.put("woff2", "font/woff2");
    }
}
