package com.codio.tv;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One continuous download of a stream, shared by every request the player makes for it. Android's player keeps
 * reopening the file at positions a few MB apart (audio and video read separately), and a fresh connection each time
 * meant it never got ahead. Here one connection keeps downloading up to AHEAD past the furthest reader, keeps BEHIND
 * of already-read data, and any request that starts inside (or just past) that window is served from it.
 */
final class StreamFeed {
    private static final String TAG = "CodioDV";
    private static final int CHUNK = 512 * 1024;
    // AHEAD past the furthest reader; data a reader still needs is kept while it's at most MAX_GAP behind the furthest
    // (the player reads audio and video at two places ~20 MB apart, and dropping the slower one froze the picture);
    // with nobody reading, BEHIND stays for a quick reopen.
    private static final long AHEAD = 32L << 20, BEHIND = 48L << 20, MAX_GAP = 64L << 20, JOIN_GAP = 6L << 20;
    private static final int MAX_FEEDS = 2;
    private static final long IDLE_MS = 20000;

    private static final List<StreamFeed> feeds = new ArrayList<>();

    final String target;
    private final List<byte[]> chunks = new ArrayList<>();
    private long base;          // file offset of chunks.get(0)[0]
    private long end;           // file offset just after the last downloaded byte
    private long furthest;      // furthest position a reader has asked for
    private long total = -1;    // file size
    private String contentType;
    private boolean eof, dead;
    private int readers;
    private final List<long[]> positions = new ArrayList<>(); // where each active reader is
    private long lastUsed = SystemClock.uptimeMillis();
    private HttpURLConnection conn;

    private StreamFeed(String target, long start) {
        this.target = target;
        this.base = this.end = this.furthest = start;
    }

    /** Serves "Range: bytes=start-[last]" for target from a shared feed. Returns false if it couldn't (caller falls back). */
    static boolean serve(String target, String range, OutputStream out) throws IOException {
        long start, last = -1;
        try {
            String r = range.trim().toLowerCase();
            if (!r.startsWith("bytes=") || r.contains(",")) return false;
            String[] p = r.substring(6).split("-", -1);
            start = Long.parseLong(p[0].trim());
            if (p.length > 1 && !p[1].trim().isEmpty()) last = Long.parseLong(p[1].trim());
        } catch (Exception e) {
            return false;
        }
        StreamFeed f = find(target, start);
        if (!f.waitStarted()) {
            f.release();
            return false;
        }
        try {
            f.respond(start, last, out);
        } finally {
            f.release();
        }
        return true;
    }

    private static StreamFeed find(String target, long start) {
        synchronized (feeds) {
            StreamFeed best = null;
            for (StreamFeed f : feeds) {
                synchronized (f) {
                    if (f.dead || !f.target.equals(target)) continue;
                    if (start >= f.base && start <= f.end + JOIN_GAP && (f.total < 0 || start < f.total)) {
                        best = f;
                        break;
                    }
                }
            }
            if (best == null) {
                // Drop a feed beyond the limit: an idle one if there is one, else the least recently used.
                while (feeds.size() >= MAX_FEEDS) {
                    StreamFeed old = null;
                    for (StreamFeed f : feeds) {
                        boolean idle = f.readers == 0, oldIdle = old != null && old.readers == 0;
                        if (old == null || (idle && !oldIdle) || (idle == oldIdle && f.lastUsed < old.lastUsed)) old = f;
                    }
                    feeds.remove(old);
                    old.kill();
                }
                best = new StreamFeed(target, start);
                feeds.add(best);
                best.startDownload();
                Log.e(TAG, "feed: new download at " + (start >> 20) + " MB");
            }
            synchronized (best) {
                best.readers++;
                best.lastUsed = SystemClock.uptimeMillis();
                if (start > best.furthest) best.furthest = start;
            }
            return best;
        }
    }

    private synchronized boolean waitStarted() {
        long until = SystemClock.uptimeMillis() + 30000;
        while (total < 0 && !dead) {
            long left = until - SystemClock.uptimeMillis();
            if (left <= 0) return false;
            try {
                wait(left);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return !dead || total >= 0;
    }

    private void respond(long start, long last, OutputStream out) throws IOException {
        long tot;
        String type;
        synchronized (this) {
            tot = total;
            type = contentType;
        }
        if (last < 0 || last >= tot) last = tot - 1;
        StringBuilder sb = new StringBuilder("HTTP/1.1 206 Partial Content\r\n");
        if (type != null) sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Accept-Ranges: bytes\r\n");
        sb.append("Content-Range: bytes ").append(start).append('-').append(last).append('/').append(tot).append("\r\n");
        sb.append("Content-Length: ").append(last - start + 1).append("\r\n");
        sb.append("Cache-Control: no-store\r\nConnection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        long pos = start;
        byte[] buf = new byte[CHUNK];
        long[] me = {start};
        synchronized (this) {
            positions.add(me);
        }
        try {
        while (pos <= last) {
            int n;
            synchronized (this) {
                while (pos >= end && !eof && !dead) {
                    try {
                        wait(1000);
                    } catch (InterruptedException e) {
                        throw new IOException("interrupted");
                    }
                }
                if (pos < base) {
                    Log.e(TAG, "feed: reader at " + (pos >> 20) + " MB fell behind the window (" + (base >> 20) + " MB)");
                    throw new IOException("position already dropped");
                }
                if (pos >= end) throw new IOException(dead ? "download stopped" : "end of file");
                n = copy(pos, buf, (int) Math.min(buf.length, last - pos + 1));
                if (pos + n > furthest) furthest = pos + n;
                lastUsed = SystemClock.uptimeMillis();
                notifyAll(); // the downloader may be waiting for room
            }
            out.write(buf, 0, n);
            pos += n;
            synchronized (this) {
                me[0] = pos;
            }
        }
        out.flush();
        } finally {
            synchronized (this) {
                positions.remove(me);
                notifyAll();
            }
        }
    }

    /** Copies from the window at pos; holds the lock. */
    private int copy(long pos, byte[] buf, int max) {
        long off = pos - base;
        int i = (int) (off / CHUNK), at = (int) (off % CHUNK);
        int n = 0;
        while (n < max && i < chunks.size()) {
            byte[] c = chunks.get(i);
            int len = chunkLen(i);
            int take = Math.min(len - at, max - n);
            if (take <= 0) break;
            System.arraycopy(c, at, buf, n, take);
            n += take;
            at = 0;
            i++;
        }
        return n;
    }

    private int chunkLen(int i) {
        if (i < chunks.size() - 1) return CHUNK;
        long l = end - base - (long) i * CHUNK;
        return (int) Math.min(CHUNK, l);
    }

    private synchronized void release() {
        readers--;
        lastUsed = SystemClock.uptimeMillis();
    }

    private void kill() {
        synchronized (this) {
            dead = true;
            notifyAll();
        }
        Log.e(TAG, "feed: dropped download at " + (end >> 20) + " MB");
    }

    private void startDownload() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    download();
                } catch (Throwable t) {
                    Log.e(TAG, "feed: download ended: " + t);
                } finally {
                    HttpURLConnection c;
                    synchronized (StreamFeed.this) {
                        dead = true;
                        StreamFeed.this.notifyAll();
                        c = conn;
                        conn = null;
                    }
                    if (c != null) {
                        try {
                            c.disconnect();
                        } catch (Throwable ignored) {
                        }
                    }
                    synchronized (feeds) {
                        feeds.remove(StreamFeed.this);
                    }
                }
            }
        }, "codio-feed").start();
    }

    private void download() throws IOException {
        long start;
        synchronized (this) {
            start = end;
        }
        HttpURLConnection c = LocalServer.openUpstream(target, "bytes=" + start + "-");
        synchronized (this) {
            conn = c;
        }
        int code = c.getResponseCode();
        if (code != 206) throw new IOException("upstream answered " + code + " to a range request");
        String cr = c.getHeaderField("Content-Range");
        long tot = -1;
        if (cr != null && cr.indexOf('/') > 0) {
            try {
                tot = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (tot <= 0) throw new IOException("no file size in " + cr);
        synchronized (this) {
            total = tot;
            contentType = c.getContentType();
            notifyAll();
        }
        InputStream in = c.getInputStream();
        while (true) {
            synchronized (this) {
                // Wait for room: AHEAD past the furthest reader. Stop when nobody has used this for a while.
                while (!dead && end - furthest >= AHEAD) {
                    if (readers == 0 && SystemClock.uptimeMillis() - lastUsed > IDLE_MS) return;
                    try {
                        wait(500);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (dead) return;
                if (readers == 0 && SystemClock.uptimeMillis() - lastUsed > IDLE_MS) return;
                // Drop what nobody needs any more (see AHEAD / BEHIND / MAX_GAP).
                long floor;
                if (positions.isEmpty()) {
                    floor = furthest - BEHIND;
                } else {
                    long slowest = Long.MAX_VALUE;
                    for (long[] p : positions) slowest = Math.min(slowest, p[0]);
                    floor = Math.max(Math.min(furthest - BEHIND, slowest - (2L << 20)), furthest - MAX_GAP);
                }
                while (chunks.size() > 1 && base + CHUNK <= floor) {
                    chunks.remove(0);
                    base += CHUNK;
                }
            }
            byte[] chunk;
            boolean fresh;
            int at;
            synchronized (this) {
                int lastLen = chunks.isEmpty() ? CHUNK : chunkLen(chunks.size() - 1);
                fresh = lastLen >= CHUNK;
                chunk = fresh ? null : chunks.get(chunks.size() - 1);
                at = fresh ? 0 : lastLen;
            }
            if (fresh) chunk = new byte[CHUNK];
            int n = in.read(chunk, at, CHUNK - at);
            synchronized (this) {
                if (n <= 0) {
                    eof = true;
                    notifyAll();
                    return;
                }
                if (fresh) chunks.add(chunk);
                end += n;
                notifyAll();
            }
        }
    }

    /** For logging only. */
    @Override public String toString() {
        return "feed " + base + "-" + end;
    }
}
