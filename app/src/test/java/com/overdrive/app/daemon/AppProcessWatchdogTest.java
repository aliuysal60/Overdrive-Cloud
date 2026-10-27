// AppProcessWatchdogTest.java — when the DiLink 5 app-process watchdog respawns, and how it backs off
package com.overdrive.app.daemon;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AppProcessWatchdogTest {
    private static final long STEP = AppProcessWatchdog.CHECK_MS;

    @Test
    public void aliveNeverRespawns() {
        AppProcessWatchdog.State s = new AppProcessWatchdog.State();
        for (int i = 0; i < 10; i++) assertFalse(s.onCheck(true, i * STEP));
        assertEquals(0, s.respawns);
    }

    @Test
    public void aSingleMissNeedsConfirmation() {
        AppProcessWatchdog.State s = new AppProcessWatchdog.State();
        assertFalse(s.onCheck(false, 0));
        assertTrue(s.onCheck(false, STEP));
        assertEquals(1, s.respawns);
    }

    @Test
    public void aBriefGapThatRecoversDoesNothing() {
        AppProcessWatchdog.State s = new AppProcessWatchdog.State();
        assertFalse(s.onCheck(false, 0));
        assertFalse(s.onCheck(true, STEP));
        assertFalse(s.onCheck(false, 2 * STEP));
        assertEquals(0, s.respawns);
    }

    @Test
    public void aPersistentOutageBacksOff() {
        AppProcessWatchdog.State s = new AppProcessWatchdog.State();
        long t = 0;
        s.onCheck(false, t);
        assertTrue(s.onCheck(false, t += STEP));                   // first respawn
        assertFalse(s.onCheck(false, t += STEP));                  // inside the 60 s gap
        t += AppProcessWatchdog.MIN_RESPAWN_GAP_MS;
        assertTrue(s.onCheck(false, t));                           // second, gap now doubles
        assertEquals(2 * AppProcessWatchdog.MIN_RESPAWN_GAP_MS, s.respawnGapMs);
        assertFalse(s.onCheck(false, t + AppProcessWatchdog.MIN_RESPAWN_GAP_MS));
        assertTrue(s.onCheck(false, t + 2 * AppProcessWatchdog.MIN_RESPAWN_GAP_MS));
    }

    @Test
    public void backoffIsCappedAndResetByRecovery() {
        AppProcessWatchdog.State s = new AppProcessWatchdog.State();
        long t = 0;
        s.onCheck(false, t);
        for (int i = 0; i < 12; i++) {
            t += AppProcessWatchdog.MAX_RESPAWN_GAP_MS;
            s.onCheck(false, t);
        }
        assertEquals(AppProcessWatchdog.MAX_RESPAWN_GAP_MS, s.respawnGapMs);

        s.onCheck(true, t += STEP);
        assertEquals(AppProcessWatchdog.MIN_RESPAWN_GAP_MS, s.respawnGapMs);
        // a new outage starts again at the minimum gap
        s.onCheck(false, t += AppProcessWatchdog.MAX_RESPAWN_GAP_MS);
        assertTrue(s.onCheck(false, t += STEP));
        assertEquals(AppProcessWatchdog.MIN_RESPAWN_GAP_MS, s.respawnGapMs);
    }

    @Test
    public void standDownClearsTheStreak() {
        AppProcessWatchdog.State s = new AppProcessWatchdog.State();
        s.onCheck(false, 0);
        s.standDown();
        assertFalse(s.onCheck(false, STEP)); // needs a fresh confirmation
    }
}
