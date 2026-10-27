// BydDataCollectorDiLink5GearTest.java — ACC-off stale clear keeps a gearbox-device gear
package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.monitor.GearMonitor;

import org.junit.Test;

public class BydDataCollectorDiLink5GearTest {

    private static BydVehicleData driving() {
        return new BydVehicleData.Builder()
                .socPercent(87.0)
                .speedKmh(42.5)
                .accelPercent(31)
                .brakePercent(7)
                .steeringAngleDegrees(-125.5)
                .gearMode(GearMonitor.GEAR_D)
                .build();
    }

    @Test
    public void keepsTheDirectGearAndClearsTheRest() {
        BydVehicleData source = driving();

        BydVehicleData cleared =
                BydDataCollector.clearStaleDiLink5DrivingFields(source, GearMonitor.GEAR_P);

        assertNotSame(source, cleared);
        assertEquals(GearMonitor.GEAR_P, cleared.gearMode);
        assertTrue(Double.isNaN(cleared.speedKmh));
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.accelPercent);
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.brakePercent);
        assertTrue(Double.isNaN(cleared.steeringAngleDegrees));
        assertEquals(source.timestamp, cleared.timestamp);
        assertEquals(87.0, cleared.socPercent, 0.0);
    }

    @Test
    public void steadyParkedStateIsReturnedAsIs() {
        BydVehicleData cleared =
                BydDataCollector.clearStaleDiLink5DrivingFields(driving(), GearMonitor.GEAR_P);

        // a parked car polled again must not look changed (no republish every poll)
        assertSame(cleared,
                BydDataCollector.clearStaleDiLink5DrivingFields(cleared, GearMonitor.GEAR_P));
    }

    @Test
    public void keepsTheDirectSpeedAlongsideTheGear() {
        BydVehicleData cleared = BydDataCollector.clearStaleDiLink5DrivingFields(
                driving(), GearMonitor.GEAR_D, 37.0);

        assertEquals(GearMonitor.GEAR_D, cleared.gearMode);
        assertEquals(37.0, cleared.speedKmh, 0.0);
        assertEquals(BydVehicleData.UNAVAILABLE, cleared.accelPercent);
        assertTrue(Double.isNaN(cleared.steeringAngleDegrees));
        assertSame(cleared, BydDataCollector.clearStaleDiLink5DrivingFields(
                cleared, GearMonitor.GEAR_D, 37.0));
    }

    @Test
    public void speedAloneIsKeptWithoutAGear() {
        BydVehicleData cleared = BydDataCollector.clearStaleDiLink5DrivingFields(
                driving(), BydVehicleData.UNAVAILABLE, 12.0);

        assertEquals(BydVehicleData.UNAVAILABLE, cleared.gearMode);
        assertEquals(12.0, cleared.speedKmh, 0.0);
    }

    @Test
    public void invalidDirectGearFallsBackToTheFullClear() {
        BydVehicleData cleared = BydDataCollector.clearStaleDiLink5DrivingFields(
                driving(), BydVehicleData.UNAVAILABLE);

        assertEquals(BydVehicleData.UNAVAILABLE, cleared.gearMode);
        assertTrue(Double.isNaN(cleared.speedKmh));
    }
}
