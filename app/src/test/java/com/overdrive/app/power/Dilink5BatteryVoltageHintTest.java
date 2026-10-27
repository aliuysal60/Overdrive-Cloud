// Dilink5BatteryVoltageHintTest.java — voltage parsing and the shared hint-file freshness rule
package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Dilink5BatteryVoltageHintTest {
    private static final long NOW_ELAPSED = 2_000_000L;
    private static final long NOW_WALL = 1_792_000_000_000L;

    @Test
    public void parsesAPlausibleVoltage() {
        assertEquals(12.64, Dilink5BatteryVoltageHint.parseVolts("12.64"), 1e-9);
    }

    @Test
    public void rejectsMissingGarbageAndImplausibleValues() {
        assertTrue(Double.isNaN(Dilink5BatteryVoltageHint.parseVolts(null)));
        assertTrue(Double.isNaN(Dilink5BatteryVoltageHint.parseVolts("abc")));
        assertTrue(Double.isNaN(Dilink5BatteryVoltageHint.parseVolts("0")));
        assertTrue(Double.isNaN(Dilink5BatteryVoltageHint.parseVolts("25.0")));
        assertTrue(Double.isNaN(Dilink5BatteryVoltageHint.parseVolts("-2147483648")));
    }

    @Test
    public void hintFileKeepsOnlyFreshValuesFromThisBoot() {
        Dilink5HintFile.Stamp fresh = Dilink5HintFile.parse("12.40 1990000 1791999990000");
        assertEquals("12.40", Dilink5HintFile.fresh(fresh, 90_000L, NOW_ELAPSED, NOW_WALL));

        Dilink5HintFile.Stamp stale = Dilink5HintFile.parse("12.40 1800000 1791999800000");
        assertNull(Dilink5HintFile.fresh(stale, 90_000L, NOW_ELAPSED, NOW_WALL));

        Dilink5HintFile.Stamp previousBoot = Dilink5HintFile.parse("12.40 1999000 1790000000000");
        assertNull(Dilink5HintFile.fresh(previousBoot, 90_000L, NOW_ELAPSED, NOW_WALL));

        assertNull(Dilink5HintFile.parse("12.40 1999000"));
    }
}
