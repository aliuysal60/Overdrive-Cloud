// Dilink5HintFile.java — shared write/read of the DiLink 5 app→daemon hint files
package com.overdrive.app.power;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * One value read in the app process (where the BYDAuto devices answer on DiLink 5) handed to the
 * UID-2000 daemons through the app's external files directory, next to {@link IgnitionBroadcastHint}.
 * Format {@code "<value> <elapsedMs> <wallMs>"}, written tmp + rename and world-readable. A reader
 * gets the value only while it is younger than the caller's max age and from this boot.
 */
final class Dilink5HintFile {
    static final long WALL_SANITY_MS = 10L * 60_000L;

    private Dilink5HintFile() {}

    static final class Stamp {
        final String value;
        final long elapsedMs;
        final long wallMs;

        Stamp(String value, long elapsedMs, long wallMs) {
            this.value = value;
            this.elapsedMs = elapsedMs;
            this.wallMs = wallMs;
        }
    }

    /** Synchronous write; call off the main thread. */
    static boolean publish(Context context, String fileName, String value) {
        File tmp = null;
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) return false;
            if (!dir.isDirectory() && !dir.mkdirs()) return false;
            File target = new File(dir, fileName);
            tmp = new File(dir, fileName + ".tmp." + Thread.currentThread().getId());
            String line = value + " " + SystemClock.elapsedRealtime() + " " + System.currentTimeMillis();
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(line.getBytes(StandardCharsets.US_ASCII));
                out.flush();
                out.getFD().sync();
            }
            if (!IgnitionBroadcastHint.replaceAtomically(tmp, target)) return false;
            try {
                // the UID-2000 daemons are the readers
                target.setReadable(true, false);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable failure) {
            return false;
        } finally {
            if (tmp != null && tmp.exists()) {
                try {
                    tmp.delete();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** The value while fresh, else null. */
    static String readFresh(String fileName, long maxAgeMs) {
        return fresh(read(fileName), maxAgeMs, SystemClock.elapsedRealtime(), System.currentTimeMillis());
    }

    /** Pure freshness rule. */
    static String fresh(Stamp stamp, long maxAgeMs, long nowElapsedMs, long nowWallMs) {
        if (stamp == null || stamp.elapsedMs <= 0L) return null;
        long age = nowElapsedMs - stamp.elapsedMs;
        if (age < 0L || age > maxAgeMs) return null;
        if (Math.abs(nowWallMs - stamp.wallMs) > WALL_SANITY_MS) return null;
        return stamp.value;
    }

    static Stamp parse(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        if (line.isEmpty() || line.length() > 64) return null;
        String[] parts = line.split("\\s+");
        if (parts.length != 3) return null;
        try {
            return new Stamp(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Stamp read(String fileName) {
        for (File candidate : candidates(fileName)) {
            try {
                if (!candidate.isFile()) continue;
                Stamp parsed = parse(new String(Files.readAllBytes(candidate.toPath()), StandardCharsets.US_ASCII));
                if (parsed != null) return parsed;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** The app's external files directory, spelled out for a bare app_process (see IgnitionBroadcastHint). */
    private static File[] candidates(String fileName) {
        String rel = "/Android/data/" + com.overdrive.app.BuildConfig.APPLICATION_ID + "/files/" + fileName;
        return new File[] {
            new File("/storage/emulated/0" + rel),
            new File("/sdcard" + rel),
        };
    }
}
