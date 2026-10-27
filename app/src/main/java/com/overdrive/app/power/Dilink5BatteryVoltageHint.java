// Dilink5BatteryVoltageHint.java — cross-process DiLink 5 12 V battery voltage (app → daemons)
package com.overdrive.app.power;

import android.content.Context;

import java.util.Locale;

/**
 * The 12 V battery voltage read in the app process from {@code BYDAutoOtaDevice}, handed to the
 * UID-2000 daemons through {@link Dilink5HintFile}. On DiLink 5 the daemons are refused the OTA
 * service, which left {@code BatteryPowerMonitor} and the voltage-driven MCU logic
 * ({@link BatteryVoltageMonitorV2}) in {@code acc_sentry_daemon} without any sample.
 */
public final class Dilink5BatteryVoltageHint {
    public static final String FILE_NAME = "di5_battery_voltage_hint";
    /** Three missed writes ({@code Dilink5PowerReader.VOLTAGE_EVERY_MS}) make a reading stale. */
    public static final long MAX_AGE_MS = 90_000L;

    private Dilink5BatteryVoltageHint() {}

    /** App process: synchronous write; call off the main thread. */
    public static boolean publish(Context context, double volts) {
        if (!isPlausible(volts)) return false;
        return Dilink5HintFile.publish(context, FILE_NAME, String.format(Locale.US, "%.2f", volts));
    }

    /** The last published voltage while fresh, else NaN. */
    public static double readFreshVolts() {
        return parseVolts(Dilink5HintFile.readFresh(FILE_NAME, MAX_AGE_MS));
    }

    static double parseVolts(String value) {
        if (value == null) return Double.NaN;
        try {
            double volts = Double.parseDouble(value);
            return isPlausible(volts) ? volts : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Same window the producer accepts from the OTA device. */
    static boolean isPlausible(double volts) {
        return volts > 0.0 && volts < 20.0;
    }
}
