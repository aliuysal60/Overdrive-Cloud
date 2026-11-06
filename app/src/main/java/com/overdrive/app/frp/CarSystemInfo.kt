// CarSystemInfo.kt — the head unit's version facts the car reports to OverDrive Cloud (About's web values, never the VIN)
@file:JvmName("CarSystemInfo")

package com.overdrive.app.frp

import com.overdrive.app.ui.about.VehicleVersionInfoProvider

/**
 * firmware, dsp, mcu, android, securityPatch, headUnit — the same values About shows on the web
 * (VIN excluded there and here). Values the unit does not expose are absent; any failure is empty.
 */
fun read(): Map<String, String> = try {
    VehicleVersionInfoProvider.webValues(VehicleVersionInfoProvider.read(vin = null))
        .filterValues { !it.isNullOrBlank() }
        .mapValues { it.value!! }
} catch (_: Throwable) {
    emptyMap()
}
