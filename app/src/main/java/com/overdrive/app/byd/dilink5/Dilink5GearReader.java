// Dilink5GearReader.java — DiLink 5 gear read through BYDAutoGearboxDevice.getGear() (ParkCam method)
package com.overdrive.app.byd.dilink5;

import android.content.Context;
import android.os.SystemClock;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.monitor.GearMonitor;

import java.lang.reflect.Method;

/**
 * DiLink 5 (Tang / Sealion 7, firmware 51.x) gear source, ported from ParkCam where it is
 * field-verified on a Tang 51.x head unit.
 *
 * <p>On that firmware the car_service SHIFT_MODE/dynamics rails can stay unavailable, which left
 * every gear consumer with an unknown gear and made the parked car look like it was driving. The
 * BYDAuto gearbox device still answers when it is reached the ParkCam way
 * ({@link Dilink5BydAutoSdk}); its {@code getGear()} value is calibrated on the vehicle as
 * 1=P 2=R 3=N 4=D. The SDK's own GEAR_* constants do not match the wire; the values happen to
 * equal {@link GearMonitor#GEAR_P}..{@link GearMonitor#GEAR_D}.
 *
 * <p>App process only: as uid 2000 (the daemons) the BYDAuto services reject the caller
 * ("calling package android does not match caller's uid 2000"). The DiLink 5 telemetry producer
 * calls this and bridges the result to the daemon.
 */
public final class Dilink5GearReader {
    private static final DaemonLogger logger = DaemonLogger.getInstance("Dilink5Gear");

    static final String GEARBOX_CLASS = "android.hardware.bydauto.gearbox.BYDAutoGearboxDevice";
    /** A failed device lookup is retried no sooner than this. */
    static final long INIT_RETRY_MS = 30_000L;
    /** Above this speed a Park reading is implausible and dropped. */
    static final double PARK_MAX_SPEED_KMH = 5.0;

    private static final Object LOCK = new Object();
    private static Object gearbox;
    private static Method getGear;
    private static long lastInitAttemptMs;
    private static String lastInitError = "";
    private static int lastLoggedRaw = Integer.MIN_VALUE;
    private static int lastLoggedGear = Integer.MIN_VALUE;

    private Dilink5GearReader() {}

    /**
     * Current gear as a {@link GearMonitor} constant (P/R/N/D), or
     * {@link BydVehicleData#UNAVAILABLE} when the device can't be reached or the reading fails
     * validation. Never manufactures Park from an unknown reading.
     */
    public static int read(Context context) {
        if (context == null) return BydVehicleData.UNAVAILABLE;
        synchronized (LOCK) {
            if (!ensureDevices(context)) return BydVehicleData.UNAVAILABLE;
            int raw;
            try {
                Object value = getGear.invoke(gearbox);
                if (!(value instanceof Number)) return BydVehicleData.UNAVAILABLE;
                raw = ((Number) value).intValue();
            } catch (Throwable t) {
                logger.warn("getGear failed: " + Dilink5BydAutoSdk.rootMessage(t));
                // a dead binder or a torn-down service: look the device up again later
                gearbox = null;
                getGear = null;
                return BydVehicleData.UNAVAILABLE;
            }
            double speedKmh = Dilink5SpeedReader.read(context);
            int gear = validate(decode(raw), speedKmh);
            if (raw != lastLoggedRaw || gear != lastLoggedGear) {
                lastLoggedRaw = raw;
                lastLoggedGear = gear;
                logger.info("getGear raw=" + raw + " -> "
                        + (gear == BydVehicleData.UNAVAILABLE
                            ? "rejected" : GearMonitor.gearToString(gear))
                        + (Double.isNaN(speedKmh) ? "" : " (speed " + Math.round(speedKmh) + " km/h)"));
            }
            return gear;
        }
    }

    /** Field-calibrated raw getGear() value to a GearMonitor constant. */
    static int decode(int raw) {
        switch (raw) {
            case 1: return GearMonitor.GEAR_P;
            case 2: return GearMonitor.GEAR_R;
            case 3: return GearMonitor.GEAR_N;
            case 4: return GearMonitor.GEAR_D;
            default: return BydVehicleData.UNAVAILABLE;
        }
    }

    /** Drops a Park reading the car's own speed contradicts; NaN speed means "not known". */
    static int validate(int gear, double speedKmh) {
        if (gear == GearMonitor.GEAR_P && !Double.isNaN(speedKmh) && speedKmh > PARK_MAX_SPEED_KMH) {
            return BydVehicleData.UNAVAILABLE;
        }
        return gear;
    }

    private static boolean ensureDevices(Context context) {
        if (gearbox != null && getGear != null) return true;
        long now = SystemClock.elapsedRealtime();
        if (lastInitAttemptMs != 0L && now - lastInitAttemptMs < INIT_RETRY_MS) return false;
        lastInitAttemptMs = now;
        try {
            Object device = Dilink5BydAutoSdk.device(context, GEARBOX_CLASS);
            getGear = device.getClass().getMethod("getGear");
            gearbox = device;
            lastInitError = "";
            logger.info("BYDAutoGearboxDevice ready (ParkCam path)");
            return true;
        } catch (Throwable t) {
            String error = Dilink5BydAutoSdk.rootMessage(t);
            if (!error.equals(lastInitError)) {
                lastInitError = error;
                logger.warn("BYDAutoGearboxDevice unavailable: " + error);
            }
            gearbox = null;
            getGear = null;
            return false;
        }
    }
}
