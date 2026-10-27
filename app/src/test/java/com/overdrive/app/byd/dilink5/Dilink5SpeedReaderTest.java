// Dilink5SpeedReaderTest.java — range check of the DiLink 5 speed-device reading
package com.overdrive.app.byd.dilink5;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Dilink5SpeedReaderTest {

    @Test
    public void keepsPlausibleSpeeds() {
        assertEquals(0.0, Dilink5SpeedReader.validate(0.0), 0.0);
        assertEquals(87.5, Dilink5SpeedReader.validate(87.5), 0.0);
        assertEquals(Dilink5SpeedReader.MAX_KMH, Dilink5SpeedReader.validate(Dilink5SpeedReader.MAX_KMH), 0.0);
    }

    @Test
    public void dropsSentinelsAndOutOfRangeValues() {
        assertTrue(Double.isNaN(Dilink5SpeedReader.validate(-1.0)));
        assertTrue(Double.isNaN(Dilink5SpeedReader.validate(Integer.MIN_VALUE)));
        assertTrue(Double.isNaN(Dilink5SpeedReader.validate(301.0)));
    }
}
