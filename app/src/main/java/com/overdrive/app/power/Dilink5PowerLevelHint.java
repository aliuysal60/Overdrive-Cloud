// Dilink5PowerLevelHint.java — cross-process DiLink 5 BYDAutoPowerDevice power level (app → daemons)
package com.overdrive.app.power;

import android.content.Context;

/**
 * The DiLink 5 vehicle power level as read in the app process from
 * {@code BYDAutoPowerDevice.getPowerLevel()} (0 = OFF, 2 = ON, other values = transition), handed
 * to the UID-2000 daemons through {@link Dilink5HintFile}: the device rejects uid 2000, and
 * {@code acc_sentry_daemon} has no other channel from the app.
 *
 * <p>The app rewrites it on every accepted change and as a heartbeat; a reading older than
 * {@link #MAX_AGE_MS} counts as absent, so an app that stopped reading (killed at power-off, device
 * lost) falls back to the existing ACC sources instead of pinning a stale state.
 */
public final class Dilink5PowerLevelHint {
    public static final String FILE_NAME = "di5_power_level_hint";
    /** Three missed heartbeats ({@code Dilink5PowerReader.HEARTBEAT_MS}) make a reading stale. */
    public static final long MAX_AGE_MS = 30_000L;
    static final long WALL_SANITY_MS = Dilink5HintFile.WALL_SANITY_MS;
    /** No fresh reading. */
    public static final int UNKNOWN = -1;

    private Dilink5PowerLevelHint() {}

    static final class Stamp {
        final int level;
        final long elapsedMs;
        final long wallMs;

        Stamp(int level, long elapsedMs, long wallMs) {
            this.level = level;
            this.elapsedMs = elapsedMs;
            this.wallMs = wallMs;
        }
    }

    /** App process: synchronous write; call off the main thread. */
    public static boolean publish(Context context, int level) {
        return Dilink5HintFile.publish(context, FILE_NAME, String.valueOf(level));
    }

    /** The last published level while fresh, else {@link #UNKNOWN}. */
    public static int readFreshLevel() {
        String value = Dilink5HintFile.readFresh(FILE_NAME, MAX_AGE_MS);
        if (value == null) return UNKNOWN;
        try {
            int level = Integer.parseInt(value);
            return level < 0 ? UNKNOWN : level;
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** Pure freshness rule. */
    static int freshLevel(Stamp stamp, long nowElapsedMs, long nowWallMs) {
        if (stamp == null || stamp.level < 0) return UNKNOWN;
        String fresh = Dilink5HintFile.fresh(
                new Dilink5HintFile.Stamp(String.valueOf(stamp.level), stamp.elapsedMs, stamp.wallMs),
                MAX_AGE_MS, nowElapsedMs, nowWallMs);
        return fresh == null ? UNKNOWN : stamp.level;
    }

    static Stamp parse(String raw) {
        Dilink5HintFile.Stamp stamp = Dilink5HintFile.parse(raw);
        if (stamp == null) return null;
        try {
            return new Stamp(Integer.parseInt(stamp.value), stamp.elapsedMs, stamp.wallMs);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
