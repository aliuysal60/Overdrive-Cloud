// ResumableDownload.java — update-APK download that survives flaky mobile data: Range/If-Range resume, stall and no-progress limits, proxy/direct alternation
package com.overdrive.app.updater;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads one file, resuming after every dropped connection instead of
 * starting over.
 *
 * <p>The car is usually on its own mobile data: a 140 MB update over a slow
 * or patchy link used to die on a fixed 640 s budget or a 15 s stall and
 * restart from zero. Here a failed attempt keeps the bytes already on disk and
 * the next attempt asks for the rest ({@code Range: bytes=N-}), pinned to the
 * same file by {@code If-Range} (strong ETag, else Last-Modified). A server
 * that ignores the range, or a file that changed meanwhile, answers 200 with
 * the whole body and the download restarts cleanly — parts of two different
 * files are never stitched together. The caller still verifies the SHA-256 and
 * {@code pm install} verifies the APK signature.
 *
 * <p>Limits: each read may stall {@link Limits#readTimeoutMs}; the download
 * gives up only when no new byte has arrived for
 * {@link Limits#noProgressGiveUpMs} across attempts, or after the absolute
 * {@link Limits#totalBudgetMs} (the install gate's staleness window is sized
 * above it). An attempt through the app's proxy that fails without progress
 * switches the next attempt to a direct connection, and back again.
 *
 * <p>Plain JVM code (no Android APIs) so it is unit-tested against a local
 * HTTP server.
 */
public final class ResumableDownload {

    /** Proxy source for each attempt. */
    public interface Route {
        /** Proxy for the next attempt; null or DIRECT means a direct connection. */
        Proxy current();
        /** An attempt through {@link #current()}'s proxy failed without progress. */
        void invalidate();
    }

    public interface Listener {
        /** Bytes on disk and the full size ({@code -1} when unknown). */
        void onProgress(long bytes, long total);
        /** An attempt failed; the download waits briefly and resumes. */
        void onRetry(int attempt, String reason, long bytes, long total);
        /** The call in flight (cancel hook), or null between attempts. */
        void onCall(Call call);
    }

    public interface Cancel {
        boolean isCancelled();
    }

    public static final class Limits {
        final long connectTimeoutMs;
        final long readTimeoutMs;
        final long noProgressGiveUpMs;
        final long totalBudgetMs;
        final long[] backoffMs;

        public Limits(long connectTimeoutMs, long readTimeoutMs, long noProgressGiveUpMs,
                      long totalBudgetMs, long[] backoffMs) {
            if (backoffMs == null || backoffMs.length == 0) {
                throw new IllegalArgumentException("backoffMs must not be empty");
            }
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
            this.noProgressGiveUpMs = noProgressGiveUpMs;
            this.totalBudgetMs = totalBudgetMs;
            this.backoffMs = backoffMs.clone();
        }
    }

    /**
     * OverDrive update downloads: 60 s per-read stall, 4 min without any new
     * byte, 60 min overall (≈ 0.35 Mbit/s average for 150 MB).
     */
    public static final Limits UPDATE_LIMITS = new Limits(
            15_000L, 60_000L, 4 * 60_000L, 60 * 60_000L,
            new long[]{1_000L, 2_000L, 5_000L, 10_000L, 20_000L, 30_000L});

    /** A response that retrying cannot fix (404, 403, …). */
    public static final class HttpStatusException extends IOException {
        public final int code;

        HttpStatusException(int code, String message) {
            super("HTTP " + code + (message == null || message.isEmpty() ? "" : " " + message));
            this.code = code;
        }
    }

    private static final Pattern CONTENT_RANGE =
            Pattern.compile("^\\s*bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)\\s*$");
    private static final Pattern UNSATISFIED_RANGE =
            Pattern.compile("^\\s*bytes\\s+\\*/(\\d+)\\s*$");

    /** What the server told us about the file so far. */
    private static final class State {
        long total = -1;
        String validator;
    }

    private ResumableDownload() {}

    /**
     * Downloads {@code url} into {@code out} (any old file there is deleted
     * first). Returns the number of bytes on disk. On failure the partial file
     * stays; the caller deletes it.
     */
    public static long run(String url, File out, Limits limits, Route route,
                           Cancel cancel, Listener listener) throws IOException {
        if (out.exists() && !out.delete()) {
            throw new IOException("Cannot delete old " + out);
        }
        final long startNanos = System.nanoTime();
        final long budgetEnd = startNanos + ms(limits.totalBudgetMs);
        long lastProgressNanos = startNanos;
        State st = new State();
        boolean forceDirect = false;
        int failuresInRow = 0;
        int attempt = 0;
        IOException last = null;

        while (true) {
            if (cancel.isCancelled()) throw new IOException("Cancelled");
            long now = System.nanoTime();
            if (now - budgetEnd > 0) {
                throw new IOException("Download exceeded " + (limits.totalBudgetMs / 60_000L)
                        + " min" + lastError(last));
            }
            if (now - lastProgressNanos > ms(limits.noProgressGiveUpMs)) {
                throw new IOException("No download progress for "
                        + (limits.noProgressGiveUpMs / 1000L) + " s" + lastError(last));
            }
            long have = out.length();
            if (st.total > 0 && have == st.total) return have;

            attempt++;
            Proxy proxy = forceDirect ? Proxy.NO_PROXY : route.current();
            boolean viaProxy = proxy != null && proxy.type() != Proxy.Type.DIRECT;
            try {
                attempt(url, out, have, st, limits, viaProxy ? proxy : Proxy.NO_PROXY,
                        budgetEnd, cancel, listener);
                return out.length();
            } catch (HttpStatusException fatal) {
                throw fatal;
            } catch (IOException e) {
                if (cancel.isCancelled()) throw new IOException("Cancelled");
                last = e;
            } finally {
                listener.onCall(null);
            }

            long after = out.length();
            if (after > have) {
                lastProgressNanos = System.nanoTime();
                failuresInRow = 0;
            } else {
                failuresInRow++;
                if (viaProxy) {
                    // The proxy (sing-box / Tailscale) may have died mid-flight.
                    route.invalidate();
                    forceDirect = true;
                } else if (forceDirect) {
                    forceDirect = false;
                }
            }
            listener.onRetry(attempt, describe(last), after, st.total);
            pause(limits.backoffMs[Math.min(failuresInRow, limits.backoffMs.length - 1)], cancel);
        }
    }

    /** One request; returns normally only when the whole file is on disk. */
    private static void attempt(String url, File out, long have, State st, Limits limits,
                                Proxy proxy, long budgetEnd, Cancel cancel,
                                Listener listener) throws IOException {
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(limits.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(limits.readTimeoutMs, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .proxy(proxy)
                .build();
        // identity: byte offsets must refer to the file itself, not to a gzip stream
        Request.Builder rb = new Request.Builder().url(url).header("Accept-Encoding", "identity");
        if (have > 0) {
            rb.header("Range", "bytes=" + have + "-");
            if (st.validator != null) rb.header("If-Range", st.validator);
        }
        Call call = client.newCall(rb.build());
        listener.onCall(call);
        try (Response resp = call.execute()) {
            int code = resp.code();
            boolean append;
            if (code == 206) {
                long[] cr = parseContentRange(resp.header("Content-Range"));
                if (cr == null || cr[0] != have) {
                    restart(out, st);
                    throw new IOException("Unexpected Content-Range " + resp.header("Content-Range"));
                }
                if (cr[2] > 0) st.total = cr[2];
                append = true;
            } else if (code == 200) {
                // Server ignored the range, or the file changed (If-Range): start over.
                if (have > 0) restart(out, st);
                ResponseBody body = resp.body();
                st.total = body != null ? body.contentLength() : -1;
                append = false;
            } else if (code == 416) {
                long size = parseUnsatisfiedRange(resp.header("Content-Range"));
                if (size < 0) size = st.total;
                if (have > 0 && size == have) {
                    st.total = size;
                    return;
                }
                restart(out, st);
                throw new IOException("HTTP 416 at " + have + " bytes");
            } else if (code >= 400 && code < 500 && code != 408 && code != 429) {
                throw new HttpStatusException(code, resp.message());
            } else {
                throw new IOException("HTTP " + code + " " + resp.message());
            }
            String validator = strongValidator(resp);
            if (!append || st.validator == null) st.validator = validator;

            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Empty response body");
            long bytes = append ? have : 0;
            try (InputStream in = body.byteStream();
                 FileOutputStream fos = new FileOutputStream(out, append)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (cancel.isCancelled()) throw new IOException("Cancelled");
                    if (System.nanoTime() - budgetEnd > 0) throw new IOException("Download budget exceeded");
                    fos.write(buf, 0, n);
                    bytes += n;
                    if (st.total > 0 && bytes > st.total) {
                        fos.close();
                        restart(out, st);
                        throw new IOException("Server sent more than " + st.total + " bytes");
                    }
                    listener.onProgress(bytes, st.total);
                }
                fos.flush();
            }
            if (st.total > 0 && bytes < st.total) {
                throw new IOException("Connection closed at " + bytes + " of " + st.total + " bytes");
            }
        }
    }

    /** Drop the partial file and forget the old file's identity. */
    private static void restart(File out, State st) {
        //noinspection ResultOfMethodCallIgnored
        out.delete();
        st.validator = null;
        st.total = -1;
    }

    /** Strong ETag, else Last-Modified; weak ETags can't be used with If-Range. */
    static String strongValidator(Response resp) {
        String etag = resp.header("ETag");
        if (etag != null && etag.startsWith("\"")) return etag;
        return resp.header("Last-Modified");
    }

    /** {start, end, total} from "bytes S-E/T" (total -1 for "*"), or null. */
    static long[] parseContentRange(String header) {
        if (header == null) return null;
        Matcher m = CONTENT_RANGE.matcher(header);
        if (!m.matches()) return null;
        try {
            long start = Long.parseLong(m.group(1));
            long end = Long.parseLong(m.group(2));
            long total = "*".equals(m.group(3)) ? -1 : Long.parseLong(m.group(3));
            if (end < start || (total >= 0 && end >= total)) return null;
            return new long[]{start, end, total};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Size from a 416's "bytes *&#47;N", or -1. */
    static long parseUnsatisfiedRange(String header) {
        if (header == null) return -1;
        Matcher m = UNSATISFIED_RANGE.matcher(header);
        if (!m.matches()) return -1;
        try {
            return Long.parseLong(m.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void pause(long millis, Cancel cancel) throws IOException {
        long end = System.nanoTime() + ms(millis);
        while (true) {
            if (cancel.isCancelled()) throw new IOException("Cancelled");
            long left = (end - System.nanoTime()) / 1_000_000L;
            if (left <= 0) return;
            try {
                Thread.sleep(Math.min(250L, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted");
            }
        }
    }

    private static String lastError(IOException last) {
        return last == null ? "" : " (last error: " + describe(last) + ")";
    }

    /** Exception message, or its type when there is none (EOFException etc.). */
    private static String describe(IOException e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }
}
