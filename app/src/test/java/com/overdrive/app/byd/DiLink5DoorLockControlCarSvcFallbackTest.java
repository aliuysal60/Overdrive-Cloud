// DiLink5DoorLockControlCarSvcFallbackTest.java — manual DI5 lock/unlock with the daemon's carsvc reading as fallback
package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Manual lock/unlock on DI5: a car whose typed readback works keeps the plain typed path; a car
 * whose typed readback is INVALID (Tang / Sealion 7: no {@code 0x21403702}) writes on the daemon's
 * fresh carsvc reading and leaves confirmation to the daemon.
 */
public class DiLink5DoorLockControlCarSvcFallbackTest {
    private static final int APP_UNLOCKED = 1;
    private static final int APP_LOCKED = 2;
    private static final long NOW = 100_000L;

    private static final class FakeClock implements DiLink5DoorLockControl.Clock {
        long now = NOW;
        public long elapsedMs() { return now; }
        public void sleep(long millis) { now += millis; }
    }

    /** Typed cabin port; {@code state} is the native value its getter returns (0 = INVALID). */
    private static final class Port implements DiLink5DoorLockControl.NativePort {
        boolean ready = true;
        int state;
        int writes;
        int writtenState;
        boolean updateOnWrite = true;
        RuntimeException readFailure;
        public boolean ready() { return ready; }
        public int readDriverLock() {
            if (readFailure != null) throw readFailure;
            return state;
        }
        public int writeAllDoors(int value) {
            writes++;
            writtenState = value;
            if (updateOnWrite) state = value;
            return 0;
        }
    }

    private static DiLink5DoorLockControl.Result manual(Port port, FakeClock clock, boolean locked,
                                                        int carsvcStateApp, long carsvcAgeMs) {
        return DiLink5DoorLockControl.applyWithCarSvcFallback(
                port, locked, carsvcStateApp, clock.now - carsvcAgeMs, () -> true, () -> true, clock);
    }

    @Test
    public void workingTypedReadbackKeepsTheTypedPathAndConfirmsItself() {
        Port port = new Port();
        port.state = DiLink5DoorLockControl.LOCK;
        DiLink5DoorLockControl.Result result = manual(port, new FakeClock(), false, APP_LOCKED, 500L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.CONFIRMED, result.outcome);
        assertEquals("driver-door readback confirmed", result.reason);
        assertEquals(1, port.writes);
        assertEquals(DiLink5DoorLockControl.UNLOCK, port.writtenState);
    }

    @Test
    public void workingTypedReadbackIgnoresTheCarSvcReading() {
        // Typed works: the carsvc reading neither authorises nor vetoes, exactly as before the fallback.
        Port port = new Port();
        port.state = DiLink5DoorLockControl.UNLOCK;
        DiLink5DoorLockControl.Result result = manual(port, new FakeClock(), true, APP_LOCKED, 60_000L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.CONFIRMED, result.outcome);
        assertEquals(DiLink5DoorLockControl.LOCK, port.writtenState);
    }

    @Test
    public void invalidTypedReadbackWritesOnTheCarSvcReading() {
        Port port = new Port();
        port.updateOnWrite = false; // the typed getter stays INVALID after the write, as on the Tang
        DiLink5DoorLockControl.Result unlock = manual(port, new FakeClock(), false, APP_LOCKED, 2_000L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.WRITTEN_UNCONFIRMED, unlock.outcome);
        assertFalse(unlock.confirmed);
        assertEquals(1, port.writes);
        assertEquals(DiLink5DoorLockControl.CARSVC_ALL_DOORS_UNLOCK, port.writtenState);

        Port lockPort = new Port();
        lockPort.updateOnWrite = false;
        DiLink5DoorLockControl.Result lock = manual(lockPort, new FakeClock(), true, APP_UNLOCKED, 2_000L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.WRITTEN_UNCONFIRMED, lock.outcome);
        assertEquals(DiLink5DoorLockControl.CARSVC_ALL_DOORS_LOCK, lockPort.writtenState);
    }

    @Test
    public void invalidTypedReadbackAlreadyAtTargetWritesNothing() {
        Port port = new Port();
        DiLink5DoorLockControl.Result result = manual(port, new FakeClock(), true, APP_LOCKED, 500L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.WRITTEN_UNCONFIRMED, result.outcome);
        assertEquals(0, port.writes);
    }

    @Test
    public void staleOrInvalidCarSvcReadingNeverWrites() {
        Port stale = new Port();
        DiLink5DoorLockControl.Result old = manual(stale, new FakeClock(), false, APP_LOCKED, 6_001L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, old.outcome);
        assertTrue(old.reason, old.reason.startsWith("gate evidence too old"));
        assertEquals(0, stale.writes);

        Port invalid = new Port();
        DiLink5DoorLockControl.Result noState = manual(invalid, new FakeClock(), false, 0, 500L);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, noState.outcome);
        assertEquals(0, invalid.writes);
    }

    @Test
    public void requestAdapterAndThrowingGetterFailClosed() {
        Port port = new Port();
        FakeClock clock = new FakeClock();
        DiLink5DoorLockControl.Result notCurrent = DiLink5DoorLockControl.applyWithCarSvcFallback(
                port, false, APP_LOCKED, NOW - 500L, () -> false, () -> true, clock);
        assertEquals("request expired or mode changed", notCurrent.reason);

        port.ready = false;
        DiLink5DoorLockControl.Result notReady = manual(port, clock, false, APP_LOCKED, 500L);
        assertEquals("cabin adapter unavailable", notReady.reason);
        assertEquals(0, port.writes);

        Port throwing = new Port();
        throwing.readFailure = new IllegalStateException("binder");
        DiLink5DoorLockControl.Result failed = manual(throwing, clock, false, APP_LOCKED, 500L);
        assertEquals("typed door-lock request failed (IllegalStateException)", failed.reason);
        assertEquals(0, throwing.writes);
    }

    @Test
    public void unverifiedDeviceLayoutFailsClosed() {
        DiLink5DoorLockControl.Result result = DiLink5DoorLockControl.setLockedWithCarSvcFallback(
                new Object(), false, APP_LOCKED, NOW, () -> true, () -> true);
        assertEquals(DiLink5DoorLockControl.Result.Outcome.FAILED, result.outcome);
        assertEquals("typed door-lock rail unavailable (IllegalArgumentException)", result.reason);
    }
}
