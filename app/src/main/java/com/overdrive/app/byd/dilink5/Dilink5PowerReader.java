// Dilink5PowerReader.java — DiLink 5 power level via BYDAutoPowerDevice.getPowerLevel() (ParkCam method)
package com.overdrive.app.byd.dilink5;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.power.Dilink5BatteryVoltageHint;
import com.overdrive.app.power.Dilink5PowerLevelHint;

import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DiLink 5 vehicle power level, read the way ParkCam decides "car on" on a Tang 51.x:
 * {@code BYDAutoPowerDevice.getPowerLevel()} through {@link Dilink5BydAutoSdk}, 0 = OFF, 2 = ON,
 * any other value = transition. On that firmware the properties and car_service rows the ACC probe
 * relies on are empty or only say "IVI awake", so ACC stayed weak/unknown.
 *
 * <p>App process only. A poller reads every {@link #POLL_MS}; a level is accepted only when two
 * consecutive reads agree, and readings during the first {@link #WARMUP_MS} after the device is
 * acquired are dropped (the device binds its service asynchronously). The accepted level goes to
 * {@link Dilink5PowerLevelHint} for every process's {@code AccMonitor} and to the producer's own
 * ACC state.
 */
public final class Dilink5PowerReader {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Dilink5Power");

    static final String POWER_CLASS = "android.hardware.bydauto.power.BYDAutoPowerDevice";
    static final String OTA_CLASS = "android.hardware.bydauto.ota.BYDAutoOtaDevice";
    public static final int LEVEL_OFF = 0;
    public static final int LEVEL_ON = 2;
    /** No usable reading. */
    public static final int UNAVAILABLE = Integer.MIN_VALUE;
    static final long POLL_MS = 3_000L;
    static final long HEARTBEAT_MS = 10_000L;
    static final long WARMUP_MS = 5_000L;
    static final long INIT_RETRY_MS = 30_000L;
    /** 12 V battery voltage cadence (BatteryVoltageMonitorV2's hysteresis is minute-scale). */
    static final long VOLTAGE_EVERY_MS = 30_000L;

    private static final Object LOCK = new Object();
    private static Object power;
    private static Method getPowerLevel;
    private static long acquiredAtMs;
    private static long lastInitAttemptMs;
    private static String lastInitError = "";
    private static Object ota;
    private static long lastOtaInitAttemptMs;
    private static String lastOtaInitError = "";
    private static long lastVoltageAtMs;

    private static ScheduledExecutorService poller;
    private static int previousRaw = UNAVAILABLE;
    private static int acceptedLevel = UNAVAILABLE;
    private static long lastPublishedAtMs;

    private Dilink5PowerReader() {}

    /** Starts the poller (idempotent). */
    public static synchronized void start(Context context) {
        if (poller != null || context == null) return;
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Di5PowerLevel");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(() -> {
            try {
                tick(app);
            } catch (Throwable t) {
                logger.warn("power level poll error: " + Dilink5BydAutoSdk.rootMessage(t));
            }
        }, 0L, POLL_MS, TimeUnit.MILLISECONDS);
        logger.info("power level poller started");
    }

    /** Stops the poller; the hint then goes stale on its own. */
    public static synchronized void stop() {
        if (poller == null) return;
        poller.shutdownNow();
        poller = null;
        previousRaw = UNAVAILABLE;
        acceptedLevel = UNAVAILABLE;
        lastVoltageAtMs = 0L;
        logger.info("power level poller stopped");
    }

    private static void tick(Context context) {
        long nowMs = SystemClock.elapsedRealtime();
        if (lastVoltageAtMs == 0L || nowMs - lastVoltageAtMs >= VOLTAGE_EVERY_MS) {
            lastVoltageAtMs = nowMs;
            double volts = readBatteryVoltage(context);
            if (!Double.isNaN(volts) && !Dilink5BatteryVoltageHint.publish(context, volts)) {
                logger.warn("battery voltage hint write failed");
            }
        }
        int raw = read(context);
        int stable = stableLevel(previousRaw, raw);
        previousRaw = raw;
        if (stable == UNAVAILABLE) return;
        long now = SystemClock.elapsedRealtime();
        if (stable != acceptedLevel) {
            logger.info("power level " + describe(acceptedLevel) + " -> " + describe(stable));
            acceptedLevel = stable;
            publish(context, stable, now);
            if (stable == LEVEL_ON || stable == LEVEL_OFF) {
                com.overdrive.app.byd.BydDataCollector.updateDiLink5ProducerAccState(stable == LEVEL_ON);
            }
        } else if (now - lastPublishedAtMs >= HEARTBEAT_MS) {
            publish(context, stable, now);
        }
    }

    private static void publish(Context context, int level, long now) {
        if (Dilink5PowerLevelHint.publish(context, level)) {
            lastPublishedAtMs = now;
        } else {
            logger.warn("power level hint write failed");
        }
    }

    /** The level once two consecutive reads agree, else {@link #UNAVAILABLE}. */
    static int stableLevel(int previousRaw, int raw) {
        return raw != UNAVAILABLE && raw == previousRaw ? raw : UNAVAILABLE;
    }

    /** One raw {@code getPowerLevel()} read, or {@link #UNAVAILABLE}. */
    static int read(Context context) {
        synchronized (LOCK) {
            if (!ensureDevice(context)) return UNAVAILABLE;
            if (SystemClock.elapsedRealtime() - acquiredAtMs < WARMUP_MS) return UNAVAILABLE;
            try {
                Object value = getPowerLevel.invoke(power);
                return value instanceof Number ? ((Number) value).intValue() : UNAVAILABLE;
            } catch (Throwable t) {
                logger.warn("getPowerLevel failed: " + Dilink5BydAutoSdk.rootMessage(t));
                power = null;
                getPowerLevel = null;
                return UNAVAILABLE;
            }
        }
    }

    /** 12 V battery voltage from the OTA device, or NaN. */
    static double readBatteryVoltage(Context context) {
        synchronized (LOCK) {
            if (ota == null) {
                long now = SystemClock.elapsedRealtime();
                if (lastOtaInitAttemptMs != 0L && now - lastOtaInitAttemptMs < INIT_RETRY_MS) return Double.NaN;
                lastOtaInitAttemptMs = now;
                try {
                    ota = Dilink5BydAutoSdk.device(context, OTA_CLASS);
                    lastOtaInitError = "";
                    logger.info("BYDAutoOtaDevice ready (ParkCam path)");
                } catch (Throwable t) {
                    String error = Dilink5BydAutoSdk.rootMessage(t);
                    if (!error.equals(lastOtaInitError)) {
                        lastOtaInitError = error;
                        logger.warn("BYDAutoOtaDevice unavailable: " + error);
                    }
                    return Double.NaN;
                }
            }
            double volts = callVolts(ota, "getBatteryPowerVoltage");
            // DiLink 5 firmware that leaves getBatteryPowerVoltage empty answers getBatteryVoltage(0)
            return Double.isNaN(volts) ? callVolts(ota, "getBatteryVoltage", 0) : volts;
        }
    }

    private static double callVolts(Object device, String method, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) types[i] = int.class;
            Object value = device.getClass().getMethod(method, types).invoke(device, args);
            if (!(value instanceof Number)) return Double.NaN;
            double volts = ((Number) value).doubleValue();
            return volts > 0.0 && volts < 20.0 ? volts : Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    private static boolean ensureDevice(Context context) {
        if (power != null && getPowerLevel != null) return true;
        long now = SystemClock.elapsedRealtime();
        if (lastInitAttemptMs != 0L && now - lastInitAttemptMs < INIT_RETRY_MS) return false;
        lastInitAttemptMs = now;
        try {
            Object device = Dilink5BydAutoSdk.device(context, POWER_CLASS);
            getPowerLevel = device.getClass().getMethod("getPowerLevel");
            power = device;
            acquiredAtMs = now;
            lastInitError = "";
            logger.info("BYDAutoPowerDevice ready (ParkCam path)");
            return true;
        } catch (Throwable t) {
            String error = Dilink5BydAutoSdk.rootMessage(t);
            if (!error.equals(lastInitError)) {
                lastInitError = error;
                logger.warn("BYDAutoPowerDevice unavailable: " + error);
            }
            power = null;
            getPowerLevel = null;
            return false;
        }
    }

    private static String describe(int level) {
        if (level == UNAVAILABLE) return "UNKNOWN";
        if (level == LEVEL_ON) return "ON(2)";
        if (level == LEVEL_OFF) return "OFF(0)";
        return "transition(" + level + ")";
    }
}
