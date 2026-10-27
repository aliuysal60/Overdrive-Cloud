// AccMonitorDiLink5PowerLevelTest.java — BYDAutoPowerDevice level in the DiLink 5 ACC resolution
package com.overdrive.app.monitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.power.Dilink5PowerLevelHint;

import org.junit.Test;

public class AccMonitorDiLink5PowerLevelTest {
    private static final int NO_ACCANIM = -1;

    private static AccMonitor.DiLink5AccObservation resolve(
            int accAnim, int level, boolean startup, boolean iviAwake, boolean powerOff) {
        return AccMonitor.resolveDiLink5AccObservation(accAnim, level, startup, iviAwake, powerOff, "");
    }

    @Test
    public void levelTwoIsStrongOnOverAnIviAwakeRow() {
        AccMonitor.DiLink5AccObservation o = resolve(NO_ACCANIM, 2, false, true, false);
        assertEquals(Boolean.TRUE, o.accOn);
        assertTrue(o.strong);
        assertEquals("BYDAutoPowerDevice powerLevel", o.source);
    }

    @Test
    public void levelZeroIsStrongOffEvenWhileTheIviIsAwake() {
        AccMonitor.DiLink5AccObservation o = resolve(NO_ACCANIM, 0, false, true, false);
        assertEquals(Boolean.FALSE, o.accOn);
        assertTrue(o.strong);
    }

    @Test
    public void levelZeroDoesNotOverrideStartUp() {
        AccMonitor.DiLink5AccObservation o = resolve(NO_ACCANIM, 0, true, false, false);
        assertNull(o.accOn);
    }

    @Test
    public void accanimStillRanksFirst() {
        AccMonitor.DiLink5AccObservation o = resolve(0, 2, false, false, false);
        assertEquals(Boolean.FALSE, o.accOn);
        assertEquals("sys.accanim.status", o.source);
    }

    @Test
    public void transitionOrMissingLevelKeepsTheExistingResolution() {
        AccMonitor.DiLink5AccObservation transition = resolve(NO_ACCANIM, 1, false, true, false);
        assertEquals(Boolean.TRUE, transition.accOn);
        assertFalse(transition.strong);

        AccMonitor.DiLink5AccObservation none =
                resolve(NO_ACCANIM, Dilink5PowerLevelHint.UNKNOWN, false, false, false);
        assertNull(none.accOn);

        // the 5-argument overload behaves as "no level"
        AccMonitor.DiLink5AccObservation legacy =
                AccMonitor.resolveDiLink5AccObservation(NO_ACCANIM, false, true, false, "");
        assertEquals(Boolean.TRUE, legacy.accOn);
        assertFalse(legacy.strong);
    }
}
