// AppProcessWatchdog.java — DiLink 5: brings the app process back when it is gone (acc_sentry_daemon)
package com.overdrive.app.daemon;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * On DiLink 5 the BYDAuto devices answer only in the app process, so the gear, ACC power level,
 * speed and 12 V voltage every daemon uses are read there (Dilink5GearReader / Dilink5PowerReader /
 * Dilink5SpeedReader) and handed over. If the app process dies — low-memory kill, a vendor
 * force-stop — nothing restarts it while the daemons keep running, and those signals silently go
 * stale.
 *
 * <p>This watchdog runs in {@code acc_sentry_daemon} (up both while driving and parked). It checks the
 * app process every {@link #CHECK_MS}; after {@link #MISSING_CONFIRMATIONS} consecutive misses it
 * restarts it headlessly through the exported, DUMP-protected {@code DaemonKeepaliveService} — the
 * same respawn the keymap a11y recovery uses — which re-runs the DiLink 5 producer sync. Attempts
 * back off from {@link #MIN_RESPAWN_GAP_MS} to {@link #MAX_RESPAWN_GAP_MS} while the app stays down.
 *
 * <p>Never acts when DiLink 5 is not selected, while the "Vehicle ON only" parked-shutdown marker
 * says the app was stopped on purpose, or while an update is in progress.
 */
public final class AppProcessWatchdog {
    private static final DaemonLogger logger = DaemonLogger.getInstance("AppProcessWatchdog");
    private static final String APP_PACKAGE = "com.overdrive.app";

    static final long CHECK_MS = 15_000L;
    static final int MISSING_CONFIRMATIONS = 2;
    static final long MIN_RESPAWN_GAP_MS = 60_000L;
    static final long MAX_RESPAWN_GAP_MS = 10 * 60_000L;

    private static Thread thread;

    private AppProcessWatchdog() {}

    /** Pure decision state, separated for tests. */
    static final class State {
        int missingStreak;
        long lastRespawnAtMs = Long.MIN_VALUE / 2;
        long respawnGapMs = MIN_RESPAWN_GAP_MS;
        int respawns;
        int outageRespawns;
        boolean wasMissing;

        /** Observes one check; true when a respawn should be issued now. */
        boolean onCheck(boolean alive, long nowMs) {
            if (alive) {
                missingStreak = 0;
                outageRespawns = 0;
                respawnGapMs = MIN_RESPAWN_GAP_MS;
                return false;
            }
            missingStreak++;
            if (missingStreak < MISSING_CONFIRMATIONS) return false;
            if (nowMs - lastRespawnAtMs < respawnGapMs) return false;
            if (outageRespawns > 0) {
                // the previous respawn of this outage did not bring it back: wait longer next time
                respawnGapMs = Math.min(respawnGapMs * 2, MAX_RESPAWN_GAP_MS);
            }
            lastRespawnAtMs = nowMs;
            respawns++;
            outageRespawns++;
            return true;
        }

        /** Resets when the watchdog must stand down (mode change, intentional stop). */
        void standDown() {
            missingStreak = 0;
            outageRespawns = 0;
            respawnGapMs = MIN_RESPAWN_GAP_MS;
        }
    }

    public static synchronized void start() {
        if (thread != null) return;
        thread = new Thread(AppProcessWatchdog::run, "AppProcessWatchdog");
        thread.setDaemon(true);
        thread.start();
        logger.info("started (check every " + (CHECK_MS / 1000) + " s, DiLink 5 only)");
    }

    private static void run() {
        State state = new State();
        long missingSinceMs = 0L;
        // pid tracking catches restarts that happen between checks (other paths revive the app
        // within seconds), so how often the app is stopped is measured, not just long outages
        String lastPid = null;
        int restarts = 0;
        while (true) {
            try {
                Thread.sleep(CHECK_MS);
                if (!com.overdrive.app.camera.dilink5.DiLink5Platform.isSelected()
                        || new File(com.overdrive.app.ui.model.ParkedShutdown.MARKER_PATH).exists()
                        || new File(com.overdrive.app.updater.UpdateLifecycle.UPDATE_IN_PROGRESS_FILE).exists()) {
                    state.standDown();
                    missingSinceMs = 0L;
                    continue;
                }
                long now = android.os.SystemClock.elapsedRealtime();
                String pid = appPid();
                boolean alive = pid != null;
                if (alive && lastPid != null && !pid.equals(lastPid)) {
                    restarts++;
                    logger.warn("app process restarted (pid " + lastPid + " -> " + pid
                            + "), restarts since daemon start: " + restarts);
                }
                if (alive) lastPid = pid;
                if (alive) {
                    if (state.wasMissing) {
                        logger.info("app process is back (was missing "
                                + ((now - missingSinceMs) / 1000) + " s, respawns so far: " + state.respawns + ")");
                    }
                    state.wasMissing = false;
                    missingSinceMs = 0L;
                } else if (!state.wasMissing) {
                    state.wasMissing = true;
                    missingSinceMs = now;
                    logger.warn("app process not running — DiLink 5 gear/ACC/speed/12 V producer is down");
                }
                if (state.onCheck(alive, now)) {
                    String result = respawn();
                    logger.warn("app process missing for " + ((now - missingSinceMs) / 1000)
                            + " s — headless respawn #" + state.respawns + " (next no sooner than "
                            + (state.respawnGapMs / 1000) + " s): " + result);
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                logger.warn("check failed: " + t.getMessage());
            }
        }
    }

    /** Pid of the main app process (exact name: not :sandboxed and other sub-processes), or null. */
    static String appPid() {
        String out = exec("pidof " + APP_PACKAGE, 5);
        if (out == null) return null;
        String pid = out.trim().split("\\s+")[0];
        return !pid.isEmpty() && Character.isDigit(pid.charAt(0)) ? pid : null;
    }

    private static String respawn() {
        String out = exec("am start-foreground-service -n " + APP_PACKAGE
                + "/.services.DaemonKeepaliveService", 8);
        if (out == null) return "failed to run am";
        String line = out.trim();
        int nl = line.indexOf('\n');
        return nl > 0 ? line.substring(0, nl) : (line.isEmpty() ? "ok" : line);
    }

    private static String exec(String command, int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
            }
        } catch (Throwable t) {
            if (p != null) p.destroyForcibly();
            return null;
        }
    }
}
