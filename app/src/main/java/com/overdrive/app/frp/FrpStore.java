// FrpStore.java — stores the FRP tunnel settings on the daemon side (token stays 0600, never read back)
package com.overdrive.app.frp;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Reads and writes the FRP tunnel settings for the web API. Runs inside the camera daemon (uid
 * 2000), which owns the scratch root. The rendered frpc.toml holds the frps token and is written
 * 0600 and swapped in atomically; a separate summary (server, domain, URL) is world-readable for the
 * app. A running frpc picks up new settings when the launcher restarts it.
 */
public final class FrpStore {
    /** frpc log lines that tell the connection state (latest wins). */
    static final String LOG_CONNECTED = "start proxy success";
    static final String[] LOG_FAILED = {
        "login to server failed", "connect to server error", "start error", "authorization failed",
    };

    private FrpStore() {}

    /** Manual mode (the user's own frps). Leaves cloud mode: its enrollment file is removed. */
    public static synchronized FrpConfig save(FrpConfig config) throws IOException {
        ensureHome();
        writeAtomically(FrpPaths.CONFIG, config.toToml(), 0600);
        writeAtomically(FrpPaths.SUMMARY, config.summaryJson().toString(), 0644);
        new File(FrpPaths.CLOUD).delete();
        return config;
    }

    /** Cloud mode: the enrollment (0600) and the frpc settings derived from it. */
    public static synchronized CloudConfig saveCloud(CloudConfig cloud) throws IOException {
        ensureHome();
        writeAtomically(FrpPaths.CLOUD, cloud.toJson().toString(), 0600);
        writeAtomically(FrpPaths.CONFIG, cloud.toToml(), 0600);
        writeAtomically(FrpPaths.SUMMARY, cloud.summaryJson().toString(), 0644);
        return cloud;
    }

    /** Rewrites the cloud frpc.toml when it differs from what this build generates (e.g. app version). */
    public static synchronized void refreshCloudToml(CloudConfig cloud) throws IOException {
        String toml = cloud.toToml();
        File f = new File(FrpPaths.CONFIG);
        if (f.isFile() && f.length() < 65536 && toml.equals(new String(readAll(f), StandardCharsets.UTF_8))) return;
        writeAtomically(FrpPaths.CONFIG, toml, 0600);
    }

    /** The stored cloud enrollment, or null (not enrolled, or unreadable). */
    public static CloudConfig readCloud() {
        File f = new File(FrpPaths.CLOUD);
        if (!f.isFile() || f.length() > 8192) return null;
        try {
            return CloudConfig.fromJson(new JSONObject(new String(readAll(f), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return null;
        }
    }

    /** Settings exist but are not a cloud enrollment: the user runs their own frps. */
    public static boolean isManualMode() {
        return hasConfig() && !new File(FrpPaths.CLOUD).isFile();
    }

    /** Remove the stored settings (both modes). Returns true when none remain. */
    public static synchronized boolean delete() {
        boolean ok = true;
        for (String path : new String[] {FrpPaths.CONFIG, FrpPaths.SUMMARY, FrpPaths.CLOUD}) {
            File f = new File(path);
            ok &= !f.exists() || f.delete();
        }
        return ok;
    }

    private static void ensureHome() throws IOException {
        File home = new File(FrpPaths.HOME);
        if (!home.isDirectory() && !home.mkdirs() && !home.isDirectory()) {
            throw new IOException("cannot create " + home);
        }
        chmod(home, 0711);
    }

    public static boolean hasConfig() {
        return new File(FrpPaths.CONFIG).isFile();
    }

    /** Server, port, domain and URL of the stored settings, or null. */
    public static JSONObject readSummary() {
        File f = new File(FrpPaths.SUMMARY);
        if (!f.isFile() || f.length() > 4096) return null;
        try {
            return new JSONObject(new String(readAll(f), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** True when an frpc process ({@link FrpPaths#PROCESS_NAME}) is alive. */
    public static boolean isRunning() {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return false;
        for (File p : procs) {
            String name = p.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            try {
                String cmdline = new String(readAll(new File(p, "cmdline")), StandardCharsets.UTF_8);
                if (cmdline.startsWith(FrpPaths.BINARY) || cmdline.contains("/" + FrpPaths.PROCESS_NAME + "\0")) {
                    return true;
                }
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    /** "connected", "error" or "connecting", from the latest frpc log event. */
    public static String connectionState() {
        return connectionStateOf(readLogTail(16 * 1024));
    }

    /** Same rule over a log tail read elsewhere (the app reads it through ADB). */
    public static String connectionStateOf(String logTail) {
        if (logTail == null) return "connecting";
        int connected = logTail.lastIndexOf(LOG_CONNECTED);
        int failed = -1;
        for (String marker : LOG_FAILED) failed = Math.max(failed, logTail.lastIndexOf(marker));
        if (connected < 0 && failed < 0) return "connecting";
        return connected > failed ? "connected" : "error";
    }

    private static String readLogTail(int maxBytes) {
        File f = new File(FrpPaths.LOG);
        if (!f.isFile()) return null;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long start = Math.max(0, raf.length() - maxBytes);
            raf.seek(start);
            byte[] buf = new byte[(int) (raf.length() - start)];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeAtomically(String path, String text, int mode) throws IOException {
        File tmp = new File(path + ".tmp");
        File dest = new File(path);
        try {
            // tighten the mode before any secret is written into the file
            if (tmp.exists() && !tmp.delete()) throw new IOException("cannot replace " + tmp);
            if (!tmp.createNewFile()) throw new IOException("cannot create " + tmp);
            chmod(tmp, mode);
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(dest)) throw new IOException("cannot move " + dest.getName() + " into place");
        } finally {
            if (tmp.exists()) tmp.delete();
        }
        chmod(dest, mode);
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toByteArray();
        }
    }

    private static void chmod(File f, int mode) throws IOException {
        try {
            android.system.Os.chmod(f.getPath(), mode);
        } catch (android.system.ErrnoException e) {
            throw new IOException("chmod " + f + ": " + e.getMessage(), e);
        }
    }
}
