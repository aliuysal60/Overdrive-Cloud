// Dilink5SpeedReader.java — DiLink 5 speed via BYDAutoSpeedDevice.getCurrentSpeed() (ParkCam method)
package com.overdrive.app.byd.dilink5;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Method;

/**
 * DiLink 5 vehicle speed in km/h, read the ParkCam way ({@link Dilink5BydAutoSdk}). On 51.x firmware
 * (Tang) the car_service dynamics rail the producer otherwise uses stays empty, which left speed
 * unknown everywhere (trips, widgets, driving detection). App process only.
 */
public final class Dilink5SpeedReader {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Dilink5Speed");

    static final String SPEED_CLASS = "android.hardware.bydauto.speed.BYDAutoSpeedDevice";
    static final long INIT_RETRY_MS = 30_000L;
    static final double MAX_KMH = 300.0;

    private static final Object LOCK = new Object();
    private static Object speed;
    private static Method getCurrentSpeed;
    private static long lastInitAttemptMs;
    private static String lastInitError = "";

    private Dilink5SpeedReader() {}

    /** Current speed in km/h, or NaN when the device can't be reached or the value is out of range. */
    public static double read(Context context) {
        if (context == null) return Double.NaN;
        synchronized (LOCK) {
            if (!ensureDevice(context)) return Double.NaN;
            try {
                Object value = getCurrentSpeed.invoke(speed);
                return value instanceof Number ? validate(((Number) value).doubleValue()) : Double.NaN;
            } catch (Throwable t) {
                logger.warn("getCurrentSpeed failed: " + Dilink5BydAutoSdk.rootMessage(t));
                speed = null;
                getCurrentSpeed = null;
                return Double.NaN;
            }
        }
    }

    static double validate(double kmh) {
        return kmh >= 0.0 && kmh <= MAX_KMH ? kmh : Double.NaN;
    }

    private static boolean ensureDevice(Context context) {
        if (speed != null && getCurrentSpeed != null) return true;
        long now = SystemClock.elapsedRealtime();
        if (lastInitAttemptMs != 0L && now - lastInitAttemptMs < INIT_RETRY_MS) return false;
        lastInitAttemptMs = now;
        try {
            Object device = Dilink5BydAutoSdk.device(context, SPEED_CLASS);
            getCurrentSpeed = device.getClass().getMethod("getCurrentSpeed");
            speed = device;
            lastInitError = "";
            logger.info("BYDAutoSpeedDevice ready (ParkCam path)");
            return true;
        } catch (Throwable t) {
            String error = Dilink5BydAutoSdk.rootMessage(t);
            if (!error.equals(lastInitError)) {
                lastInitError = error;
                logger.warn("BYDAutoSpeedDevice unavailable: " + error);
            }
            speed = null;
            getCurrentSpeed = null;
            return false;
        }
    }
}
