// Dilink5PowerReaderTest.java — a power level counts only once two consecutive reads agree
package com.overdrive.app.byd.dilink5;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Dilink5PowerReaderTest {
    private static final int NONE = Dilink5PowerReader.UNAVAILABLE;

    @Test
    public void twoMatchingReadsAreStable() {
        assertEquals(2, Dilink5PowerReader.stableLevel(2, 2));
        assertEquals(0, Dilink5PowerReader.stableLevel(0, 0));
    }

    @Test
    public void aSingleOrChangingReadIsNotStable() {
        assertEquals(NONE, Dilink5PowerReader.stableLevel(NONE, 0));
        assertEquals(NONE, Dilink5PowerReader.stableLevel(2, 0));
        assertEquals(NONE, Dilink5PowerReader.stableLevel(0, 2));
    }

    @Test
    public void missingReadsAreNeverStable() {
        assertEquals(NONE, Dilink5PowerReader.stableLevel(NONE, NONE));
    }
}
