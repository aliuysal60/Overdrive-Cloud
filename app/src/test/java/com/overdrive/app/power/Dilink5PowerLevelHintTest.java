// Dilink5PowerLevelHintTest.java — format and freshness of the DiLink 5 power-level hint
package com.overdrive.app.power;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class Dilink5PowerLevelHintTest {
    private static final long NOW_ELAPSED = 1_000_000L;
    private static final long NOW_WALL = 1_792_000_000_000L;

    @Test
    public void parsesLevelElapsedAndWall() {
        Dilink5PowerLevelHint.Stamp stamp = Dilink5PowerLevelHint.parse(" 2 999000 1792000000000\n");
        assertEquals(2, stamp.level);
        assertEquals(999_000L, stamp.elapsedMs);
        assertEquals(1_792_000_000_000L, stamp.wallMs);
    }

    @Test
    public void rejectsMalformedContent() {
        assertNull(Dilink5PowerLevelHint.parse(null));
        assertNull(Dilink5PowerLevelHint.parse(""));
        assertNull(Dilink5PowerLevelHint.parse("2 999000"));
        assertNull(Dilink5PowerLevelHint.parse("on 999000 1792000000000"));
    }

    @Test
    public void freshReadingIsReturned() {
        Dilink5PowerLevelHint.Stamp stamp =
                new Dilink5PowerLevelHint.Stamp(0, NOW_ELAPSED - 5_000L, NOW_WALL - 5_000L);
        assertEquals(0, Dilink5PowerLevelHint.freshLevel(stamp, NOW_ELAPSED, NOW_WALL));
    }

    @Test
    public void staleReadingIsUnknown() {
        Dilink5PowerLevelHint.Stamp stamp = new Dilink5PowerLevelHint.Stamp(
                2, NOW_ELAPSED - Dilink5PowerLevelHint.MAX_AGE_MS - 1L, NOW_WALL);
        assertEquals(Dilink5PowerLevelHint.UNKNOWN,
                Dilink5PowerLevelHint.freshLevel(stamp, NOW_ELAPSED, NOW_WALL));
    }

    @Test
    public void previousBootOrFutureReadingIsUnknown() {
        Dilink5PowerLevelHint.Stamp previousBoot = new Dilink5PowerLevelHint.Stamp(
                2, NOW_ELAPSED - 1_000L, NOW_WALL - Dilink5PowerLevelHint.WALL_SANITY_MS - 1L);
        assertEquals(Dilink5PowerLevelHint.UNKNOWN,
                Dilink5PowerLevelHint.freshLevel(previousBoot, NOW_ELAPSED, NOW_WALL));
        Dilink5PowerLevelHint.Stamp future =
                new Dilink5PowerLevelHint.Stamp(2, NOW_ELAPSED + 1_000L, NOW_WALL);
        assertEquals(Dilink5PowerLevelHint.UNKNOWN,
                Dilink5PowerLevelHint.freshLevel(future, NOW_ELAPSED, NOW_WALL));
        assertEquals(Dilink5PowerLevelHint.UNKNOWN,
                Dilink5PowerLevelHint.freshLevel(null, NOW_ELAPSED, NOW_WALL));
    }
}
