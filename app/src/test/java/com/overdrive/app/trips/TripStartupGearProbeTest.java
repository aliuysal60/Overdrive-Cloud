// TripStartupGearProbeTest.java — a car already in D when the daemons come up must still get its trip
package com.overdrive.app.trips;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.overdrive.app.monitor.GearMonitor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Driving off before the daemons finished starting left the trip unstarted: the one D notification
 * arrived while the trip database was still opening, and with ACC already ON and the gear staying
 * D nothing re-sent it. The manager now probes the live gear as soon as it is published.
 */
public class TripStartupGearProbeTest {

    @Test
    public void drivingGearsStartATripWhenNoneRuns() {
        for (int gear : new int[] {GearMonitor.GEAR_D, GearMonitor.GEAR_R, GearMonitor.GEAR_N,
                GearMonitor.GEAR_M, GearMonitor.GEAR_S}) {
            assertTrue(GearMonitor.gearToString(gear), TripAnalyticsManager.shouldStartFromLiveGear(gear, false));
        }
    }

    @Test
    public void parkUnknownOrRunningTripDoNotStartOne() {
        assertFalse(TripAnalyticsManager.shouldStartFromLiveGear(GearMonitor.GEAR_P, false));
        assertFalse(TripAnalyticsManager.shouldStartFromLiveGear(-1, false));
        assertFalse(TripAnalyticsManager.shouldStartFromLiveGear(0, false));
        assertFalse(TripAnalyticsManager.shouldStartFromLiveGear(GearMonitor.GEAR_D, true));
    }

    @Test
    public void daemonProbesTheGearRightAfterPublishingTheManager() throws IOException {
        String daemon = read("app/src/main/java/com/overdrive/app/daemon/CameraDaemon.java");
        int publish = daemon.indexOf("if (!publishTripAnalyticsManager(");
        int probe = daemon.indexOf("tam.onPublished();");
        assertTrue("trip init thread publishes the manager", publish > 0);
        assertTrue("the gear is probed after publication", probe > publish);
        assertTrue("and in the same init thread", probe < daemon.indexOf("\"TripAnalyticsInit\")", publish));
    }

    private static String read(String relativePath) throws IOException {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            Path candidate = current.resolve(relativePath);
            if (Files.isRegularFile(candidate)) return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            current = current.getParent();
        }
        throw new AssertionError("Could not locate " + relativePath);
    }
}
