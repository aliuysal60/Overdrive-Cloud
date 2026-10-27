// Dilink5GearReaderTest.java — decode/validate rules of the DiLink 5 getGear() gear source
package com.overdrive.app.byd.dilink5;

import static org.junit.Assert.assertEquals;

import com.overdrive.app.byd.BydVehicleData;
import com.overdrive.app.monitor.GearMonitor;

import org.junit.Test;

public class Dilink5GearReaderTest {

    @Test
    public void decodesTheFieldCalibratedValues() {
        assertEquals(GearMonitor.GEAR_P, Dilink5GearReader.decode(1));
        assertEquals(GearMonitor.GEAR_R, Dilink5GearReader.decode(2));
        assertEquals(GearMonitor.GEAR_N, Dilink5GearReader.decode(3));
        assertEquals(GearMonitor.GEAR_D, Dilink5GearReader.decode(4));
    }

    @Test
    public void rejectsEverythingElseInsteadOfGuessingPark() {
        assertEquals(BydVehicleData.UNAVAILABLE, Dilink5GearReader.decode(0));
        assertEquals(BydVehicleData.UNAVAILABLE, Dilink5GearReader.decode(5));
        assertEquals(BydVehicleData.UNAVAILABLE, Dilink5GearReader.decode(-1));
        assertEquals(BydVehicleData.UNAVAILABLE, Dilink5GearReader.decode(Integer.MIN_VALUE));
    }

    @Test
    public void parkIsDroppedWhenTheCarIsMoving() {
        assertEquals(BydVehicleData.UNAVAILABLE,
                Dilink5GearReader.validate(GearMonitor.GEAR_P, 30.0));
        assertEquals(GearMonitor.GEAR_P,
                Dilink5GearReader.validate(GearMonitor.GEAR_P, Dilink5GearReader.PARK_MAX_SPEED_KMH));
        assertEquals(GearMonitor.GEAR_P, Dilink5GearReader.validate(GearMonitor.GEAR_P, 0.0));
    }

    @Test
    public void unknownSpeedDoesNotRejectPark() {
        assertEquals(GearMonitor.GEAR_P, Dilink5GearReader.validate(GearMonitor.GEAR_P, Double.NaN));
    }

    @Test
    public void drivingGearsAreKeptAtAnySpeed() {
        assertEquals(GearMonitor.GEAR_D, Dilink5GearReader.validate(GearMonitor.GEAR_D, 120.0));
        assertEquals(GearMonitor.GEAR_R, Dilink5GearReader.validate(GearMonitor.GEAR_R, 8.0));
        assertEquals(GearMonitor.GEAR_N, Dilink5GearReader.validate(GearMonitor.GEAR_N, 40.0));
    }
}
