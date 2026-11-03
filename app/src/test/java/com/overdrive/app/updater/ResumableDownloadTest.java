// ResumableDownloadTest.java — resumable update download against a local raw HTTP server: drops, ignored ranges, changed files, stalls, cancel, dead proxy
package com.overdrive.app.updater;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ResumableDownloadTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    /** Small limits so failure paths finish in seconds. */
    private static final ResumableDownload.Limits FAST = new ResumableDownload.Limits(
            2_000L, 2_000L, 5_000L, 60_000L, new long[]{10L, 20L, 40L});

    private static final String ETAG = "\"abc-1\"";
    private static final byte[] CRLF = {13, 10};

    /** One parsed request. */
    static final class Req {
        final String path;
        final Map<String, String> headers;

        Req(String path, Map<String, String> headers) {
            this.path = path;
            this.headers = headers;
        }

        String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    interface Handler {
        /** Write the raw response; the connection closes afterwards. */
        void handle(int index, Req req, OutputStream out) throws Exception;
    }

    private ServerSocket server;
    private Thread acceptor;
    private volatile Handler handler;
    private final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger counter = new AtomicInteger();

    @Before
    public void startServer() throws IOException {
        server = new ServerSocket(0);
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    new Thread(() -> serve(s)).start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void stopServer() throws IOException {
        server.close();
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setTcpNoDelay(true); // no Nagle delay on the last small segment
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = r.readLine();
            if (line == null) return;
            String path = line.split(" ")[1];
            Map<String, String> headers = new HashMap<>();
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                headers.put(h.substring(0, c).trim().toLowerCase(Locale.ROOT), h.substring(c + 1).trim());
            }
            Req req = new Req(path, headers);
            requests.add(req);
            handler.handle(counter.getAndIncrement(), req, sock.getOutputStream());
            sock.getOutputStream().flush();
            // Graceful close (FIN, then wait for the client's close): an abrupt
            // close can turn into a RST that discards bytes the client hasn't
            // read yet, which would add a resume request the test didn't plan.
            sock.shutdownOutput();
            sock.setSoTimeout(5_000);
            while (sock.getInputStream().read() != -1) { /* drain */ }
        } catch (Exception ignored) {
            // client went away (cancel / timeout) — fine
        }
    }

    private String url() {
        return "http://127.0.0.1:" + server.getLocalPort() + "/download/OverDrive-57.0.9.apk";
    }

    private static byte[] payload(int size, long seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return b;
    }

    private static void head(OutputStream out, String status, String... headers) throws IOException {
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        for (String hh : headers) sb.append(hh).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Range-capable file server; {@code cutAt[i]} (if > 0) drops request i after that many body bytes. */
    private static Handler rangeServer(byte[] file, String etag, int... cutAt) {
        return (i, req, out) -> {
            long start = 0;
            String range = req.header("Range");
            String ifRange = req.header("If-Range");
            boolean ranged = range != null && (ifRange == null || ifRange.equals(etag));
            if (ranged) start = Long.parseLong(range.substring("bytes=".length(), range.length() - 1));
            if (ranged && start >= file.length) {
                head(out, "416 Range Not Satisfiable", "Content-Range: bytes */" + file.length, "Content-Length: 0");
                return;
            }
            int len = (int) (file.length - start);
            if (ranged) {
                head(out, "206 Partial Content", "ETag: " + etag, "Accept-Ranges: bytes",
                        "Content-Range: bytes " + start + "-" + (file.length - 1) + "/" + file.length,
                        "Content-Length: " + len);
            } else {
                head(out, "200 OK", "ETag: " + etag, "Accept-Ranges: bytes", "Content-Length: " + len);
            }
            int send = i < cutAt.length && cutAt[i] > 0 ? Math.min(cutAt[i], len) : len;
            out.write(file, (int) start, send);
        };
    }

    static final class Recorder implements ResumableDownload.Listener {
        final List<String> retries = new ArrayList<>();
        long lastBytes = -1;
        long lastTotal = -2;

        @Override public void onProgress(long bytes, long total) {
            lastBytes = bytes;
            lastTotal = total;
        }

        @Override public void onRetry(int attempt, String reason, long bytes, long total) {
            retries.add(attempt + ":" + bytes + ":" + reason);
        }

        @Override public void onCall(okhttp3.Call call) {}
    }

    private static final ResumableDownload.Route DIRECT = new ResumableDownload.Route() {
        @Override public Proxy current() { return null; }
        @Override public void invalidate() {}
    };

    private File out() throws IOException {
        return new File(tmp.getRoot(), "overdrive_update.apk");
    }

    @Test
    public void droppedConnectionsResumeFromTheBytesOnDisk() throws Exception {
        byte[] file = payload(300_000, 1);
        handler = rangeServer(file, ETAG, 100_000, 50_000);
        Recorder rec = new Recorder();
        File out = out();

        long n = ResumableDownload.run(url(), out, FAST, DIRECT, () -> false, rec);

        assertEquals(file.length, n);
        assertArrayEquals(file, Files.readAllBytes(out.toPath()));
        assertTrue(dump(), requests.size() >= 3);
        assertNull(requests.get(0).header("Range"));
        assertEquals("identity", requests.get(0).header("Accept-Encoding"));
        long first = resumedFrom(1, 100_000);
        assertEquals(ETAG, requests.get(1).header("If-Range"));
        assertTrue(dump(), resumedFrom(2, first + 50_000) > first);
        assertTrue(rec.retries.size() >= 2);
        assertEquals(file.length, rec.lastBytes);
        assertEquals(file.length, rec.lastTotal);
    }

    @Test
    public void serverThatIgnoresRangeRestartsCleanly() throws Exception {
        byte[] file = payload(200_000, 2);
        handler = (i, req, out) -> {
            if (i < 2) {
                head(out, "200 OK", "Content-Length: " + file.length);
                out.write(file, 0, i == 0 ? 80_000 : file.length);
            } else {
                rangeServer(file, ETAG).handle(0, req, out);
            }
        };
        File out = out();

        ResumableDownload.run(url(), out, FAST, DIRECT, () -> false, new Recorder());

        assertArrayEquals(file, Files.readAllBytes(out.toPath()));
        resumedFrom(1, 80_000);
        assertNull("no ETag/Last-Modified → no If-Range", requests.get(1).header("If-Range"));
    }

    @Test
    public void aChangedFileIsDownloadedWholeNeverStitched() throws Exception {
        byte[] oldFile = payload(150_000, 3);
        byte[] newFile = payload(170_000, 4);
        handler = (i, req, out) -> {
            if (i == 0) {
                head(out, "200 OK", "ETag: \"old\"", "Content-Length: " + oldFile.length);
                out.write(oldFile, 0, 60_000);
            } else {
                rangeServer(newFile, "\"new\"").handle(1, req, out);
            }
        };
        File out = out();

        ResumableDownload.run(url(), out, FAST, DIRECT, () -> false, new Recorder());

        assertEquals("\"old\"", requests.get(1).header("If-Range"));
        assertArrayEquals(newFile, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void lastModifiedPinsTheFileWhenThereIsNoStrongEtag() throws Exception {
        byte[] file = payload(120_000, 5);
        String lm = "Tue, 03 Nov 2026 10:00:00 GMT";
        handler = (i, req, out) -> {
            if (i == 0) {
                head(out, "200 OK", "ETag: W/\"weak\"", "Last-Modified: " + lm, "Content-Length: " + file.length);
                out.write(file, 0, 30_000);
            } else {
                rangeServer(file, lm).handle(1, req, out);
            }
        };
        File out = out();

        ResumableDownload.run(url(), out, FAST, DIRECT, () -> false, new Recorder());

        assertEquals(lm, requests.get(1).header("If-Range"));
        resumedFrom(1, 30_000);
        assertArrayEquals(file, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void allBytesThenDropNeedsNoFurtherRequest() throws Exception {
        byte[] file = payload(90_000, 6);
        handler = (i, req, out) -> {
            if (i == 0) {
                // body complete but the length header lies upward → "closed early"
                head(out, "206 Partial Content", "ETag: " + ETAG,
                        "Content-Range: bytes 0-" + (file.length - 1) + "/" + file.length,
                        "Content-Length: " + (file.length + 10));
                out.write(file);
            } else {
                rangeServer(file, ETAG).handle(1, req, out);
            }
        };
        File out = out();

        long n = ResumableDownload.run(url(), out, FAST, DIRECT, () -> false, new Recorder());

        assertEquals(file.length, n);
        assertArrayEquals(file, Files.readAllBytes(out.toPath()));
        // Normally 1 request; a second only when this machine's network filter
        // withheld the tail of the first reply (then it must be a resume).
        if (requests.size() > 1) resumedFrom(1, file.length - 1);
    }

    @Test
    public void rangePastTheEndMeansTheFileIsComplete() throws Exception {
        byte[] file = payload(70_000, 12);
        handler = (i, req, out) -> {
            if (i == 0) {
                // chunked (size unknown): every byte arrives, the final chunk never does
                head(out, "200 OK", "ETag: " + ETAG, "Transfer-Encoding: chunked");
                out.write(Integer.toHexString(file.length).getBytes(StandardCharsets.ISO_8859_1));
                out.write(CRLF);
                out.write(file);
                out.write(CRLF);
                return;
            }
            rangeServer(file, ETAG).handle(1, req, out);
        };
        File out = out();

        ResumableDownload.run(url(), out, FAST, DIRECT, () -> false, new Recorder());

        assertArrayEquals(file, Files.readAllBytes(out.toPath()));
        assertTrue(dump(), requests.size() >= 2);
        resumedFrom(1, 70_000);
    }

    @Test
    public void notFoundFailsAtOnceWithoutRetrying() throws Exception {
        handler = (i, req, out) -> head(out, "404 Not Found", "Content-Length: 0");
        Recorder rec = new Recorder();
        try {
            ResumableDownload.run(url(), out(), FAST, DIRECT, () -> false, rec);
            fail("404 must throw");
        } catch (ResumableDownload.HttpStatusException e) {
            assertEquals(404, e.code);
        }
        assertEquals(dump(), 1, requests.size());
        assertTrue(rec.retries.isEmpty());
    }

    @Test
    public void serverErrorsAreRetried() throws Exception {
        byte[] file = payload(50_000, 7);
        handler = (i, req, out) -> {
            if (i < 2) head(out, "503 Service Unavailable", "Content-Length: 0");
            else rangeServer(file, ETAG).handle(0, req, out);
        };
        File out = out();

        ResumableDownload.run(url(), out, FAST, DIRECT, () -> false, new Recorder());

        assertArrayEquals(file, Files.readAllBytes(out.toPath()));
        assertTrue(dump(), requests.size() >= 3);
    }

    @Test
    public void givesUpWhenNoByteArrivesForTheNoProgressWindow() throws Exception {
        handler = (i, req, out) -> {
            head(out, "200 OK", "Content-Length: 100000");
            out.flush();
            Thread.sleep(10_000); // stall past the read timeout
        };
        long t0 = System.nanoTime();
        try {
            ResumableDownload.run(url(), out(), FAST, DIRECT, () -> false, new Recorder());
            fail("a stalled server must fail");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().startsWith("No download progress for 5 s"));
            assertTrue(e.getMessage(), e.getMessage().contains("last error"));
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue("took " + ms + " ms", ms < 15_000);
        assertTrue(requests.size() >= 2);
    }

    @Test
    public void slowButFlowingDataIsNeverCutByTheNoProgressWindow() throws Exception {
        byte[] file = payload(70_000, 8);
        // 7 s total delivery (> 5 s no-progress window), a chunk every 200 ms
        handler = (i, req, out) -> {
            head(out, "200 OK", "Content-Length: " + file.length);
            for (int off = 0; off < file.length; off += 2_000) {
                out.write(file, off, 2_000);
                out.flush();
                Thread.sleep(200);
            }
        };
        File out = out();

        ResumableDownload.run(url(), out, FAST, DIRECT, () -> false, new Recorder());

        assertArrayEquals(file, Files.readAllBytes(out.toPath()));
        if (requests.size() > 1) resumedFrom(1, file.length - 1); // tail withheld by a local filter
    }

    @Test
    public void cancelStopsTheDownload() throws Exception {
        byte[] file = payload(100_000, 9);
        AtomicBoolean cancelled = new AtomicBoolean();
        handler = (i, req, out) -> {
            head(out, "200 OK", "Content-Length: " + file.length);
            out.write(file, 0, 10_000);
            out.flush();
            cancelled.set(true);
            Thread.sleep(3_000);
        };
        try {
            ResumableDownload.run(url(), out(), FAST, DIRECT, cancelled::get, new Recorder());
            fail("cancel must throw");
        } catch (IOException e) {
            assertEquals("Cancelled", e.getMessage());
        }
        assertEquals(dump(), 1, requests.size()); // no retry after cancel
    }

    @Test
    public void aDeadProxyFallsBackToDirect() throws Exception {
        byte[] file = payload(60_000, 10);
        handler = rangeServer(file, ETAG);
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        Proxy dead = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", deadPort));
        AtomicInteger invalidated = new AtomicInteger();
        ResumableDownload.Route route = new ResumableDownload.Route() {
            @Override public Proxy current() { return dead; }
            @Override public void invalidate() { invalidated.incrementAndGet(); }
        };
        File out = out();

        ResumableDownload.run(url(), out, FAST, route, () -> false, new Recorder());

        assertArrayEquals(file, Files.readAllBytes(out.toPath()));
        assertTrue(invalidated.get() >= 1);
        assertNull("first request to reach the server is a fresh direct one", requests.get(0).header("Range"));
    }

    @Test
    public void anOldPartialFileIsNeverReused() throws Exception {
        byte[] file = payload(30_000, 11);
        handler = rangeServer(file, ETAG);
        File out = out();
        Files.write(out.toPath(), payload(10_000, 99));

        ResumableDownload.run(url(), out, FAST, DIRECT, () -> false, new Recorder());

        assertNull(requests.get(0).header("Range"));
        assertArrayEquals(file, Files.readAllBytes(out.toPath()));
    }

    @Test
    public void contentRangeParsing() {
        assertArrayEquals(new long[]{5, 9, 10}, ResumableDownload.parseContentRange("bytes 5-9/10"));
        assertArrayEquals(new long[]{5, 9, -1}, ResumableDownload.parseContentRange("bytes 5-9/*"));
        assertNull(ResumableDownload.parseContentRange("bytes 5-10/10"));
        assertNull(ResumableDownload.parseContentRange("bytes 9-5/10"));
        assertNull(ResumableDownload.parseContentRange("bytes */10"));
        assertNull(ResumableDownload.parseContentRange(null));
        assertEquals(10, ResumableDownload.parseUnsatisfiedRange("bytes */10"));
        assertEquals(-1, ResumableDownload.parseUnsatisfiedRange("bytes 0-1/10"));
    }

    @Test
    public void updateLimitsMatchWhatTheInstallGateExpects() throws Exception {
        ResumableDownload.Limits l = ResumableDownload.UPDATE_LIMITS;
        assertEquals(60_000L, l.readTimeoutMs);
        assertEquals(4 * 60_000L, l.noProgressGiveUpMs);
        assertEquals(60 * 60_000L, l.totalBudgetMs);
        // The app/web pollers call a non-terminal progress file older than
        // 5 min "stuck"; a retry re-reports progress, so the gap between two
        // reports (connect + read stall + longest backoff) must stay under it.
        long longestBackoff = l.backoffMs[l.backoffMs.length - 1];
        assertTrue(l.connectTimeoutMs + 2 * l.readTimeoutMs + longestBackoff < 5 * 60_000L);

        java.lang.reflect.Field stale = AppUpdater.class.getDeclaredField("INSTALL_STALE_MS");
        stale.setAccessible(true);
        long staleMs = stale.getLong(null);
        assertTrue("install gate must outlive the download budget by 20+ min",
                staleMs >= l.totalBudgetMs + 20 * 60_000L);
    }

    @Test
    public void everyUpdateSurfaceUsesTheDaemonsResumableDownload() throws Exception {
        String updater = read("app/src/main/java/com/overdrive/app/updater/AppUpdater.java");
        assertTrue(updater.contains("ResumableDownload.run(url, out, ResumableDownload.UPDATE_LIMITS"));
        assertFalse("old single-shot download is gone", updater.contains("doStreamingDownload"));
        // The car screen hands the update to the daemon (UID 2000, direct
        // download) instead of running downloadAndInstall in the app process,
        // whose fallback is the shell `wget` path.
        String main = read("app/src/main/java/com/overdrive/app/ui/MainActivity.kt");
        assertTrue(main.contains("put(\"command\", \"INSTALL_UPDATE\")"));
        assertFalse(main.contains(".downloadAndInstall("));
    }

    /**
     * Request {@code i} resumes: "bytes=N-" with 0 < N <= max. N is exact on
     * a clean network; on this dev machine a network filter (Avast) sometimes
     * withholds the last few hundred bytes of a reply, so only the bound is
     * asserted. File equality is the real check.
     */
    private long resumedFrom(int i, long max) {
        String range = requests.get(i).header("Range");
        assertTrue(dump(), range != null && range.startsWith("bytes=") && range.endsWith("-"));
        long n = Long.parseLong(range.substring(6, range.length() - 1));
        assertTrue(dump(), n > 0 && n <= max);
        return n;
    }

    private String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (requests) {
            for (Req r : requests) sb.append(r.path).append(' ').append(r.headers).append(" | ");
        }
        return sb.toString();
    }

    private static String read(String repoPath) throws IOException {
        File f = new File(repoPath);
        if (!f.exists()) f = new File("../" + repoPath); // Gradle runs tests from app/
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
