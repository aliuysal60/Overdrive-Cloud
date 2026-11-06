// StartupBannerModelTest.kt — when the "OverDrive is getting ready" strip shows, and what it waits for
package com.overdrive.app.ui.daemon

import com.overdrive.app.ui.daemon.StartupBanner.Phase
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupBannerModelTest {

    private val start = 1_000_000L
    private fun pass(vararg skipped: DaemonType, done: Boolean = false) =
        StartupProgress.Pass(start, skipped.toSet(), done)

    private val all = StartupProgress.TRACKED

    @Test
    fun nothingBeforeAPassOrInTheFirstSeconds() {
        assertEquals(Phase.HIDDEN, StartupBanner.model(null, emptyMap(), start + 60_000).phase)
        assertEquals(Phase.HIDDEN, StartupBanner.model(pass(), emptyMap(), start + 3_000).phase)
    }

    @Test
    fun preparingUntilTheFirstServiceStartsThenCountsThem() {
        val prep = StartupBanner.model(pass(), emptyMap(), start + 20_000)
        assertEquals(Phase.PREPARING, prep.phase)
        assertEquals(4, prep.total)
        val states = mapOf(DaemonType.CAMERA_DAEMON to DaemonStatus.RUNNING, DaemonType.SENTRY_DAEMON to DaemonStatus.STARTING)
        val m = StartupBanner.model(pass(), states, start + 55_000)
        assertEquals(Phase.STARTING, m.phase)
        assertEquals(1, m.ready)
        assertEquals(4, m.total)
    }

    @Test
    fun readyOnceEverythingExpectedRunsAndGoneWhenThePassIsDone() {
        val running = all.associateWith { DaemonStatus.RUNNING }
        val m = StartupBanner.model(pass(), running, start + 70_000)
        assertEquals(Phase.READY, m.phase)
        assertEquals(4, m.ready)
        assertEquals(Phase.HIDDEN, StartupBanner.model(pass(done = true), running, start + 90_000).phase)
    }

    @Test
    fun skippedServicesAreNotWaitedFor() {
        // parked "Vehicle ON only": only the ACC judge comes up; cloud tunnel off
        val states = mapOf(DaemonType.ACC_SENTRY_DAEMON to DaemonStatus.RUNNING)
        val m = StartupBanner.model(
            pass(DaemonType.CAMERA_DAEMON, DaemonType.SENTRY_DAEMON, DaemonType.FRP_TUNNEL), states, start + 60_000)
        assertEquals(Phase.READY, m.phase)
        assertEquals(1, m.total)
        val waiting = StartupBanner.model(pass(DaemonType.FRP_TUNNEL), emptyMap(), start + 60_000)
        assertEquals(3, waiting.total)
    }

    @Test
    fun slowAfterFiveMinutesAndNeverAfterThePassIsDone() {
        val states = all.associateWith { DaemonStatus.RUNNING } + (DaemonType.FRP_TUNNEL to DaemonStatus.ERROR)
        val slow = StartupBanner.model(pass(), states, start + 5 * 60_000 + 1)
        assertEquals(Phase.SLOW, slow.phase)
        assertEquals(3, slow.ready)
        assertEquals(Phase.HIDDEN, StartupBanner.model(pass(done = true), states, start + 6 * 60_000).phase)
    }
}
