// StartupProgress.kt — what the start-up pass is bringing up, so the app can say "please wait" until it is done
package com.overdrive.app.ui.daemon

import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.ui.model.DaemonType

/**
 * The services a user waits for after the car (or the app) starts: the three core daemons and the
 * OverDrive Cloud tunnel. DaemonStartupManager reports when a pass begins and which of them it
 * deliberately leaves down (stopped by the user, parked in "Vehicle ON only", cloud tunnel off or
 * not set up), so the start-up banner waits only for what will really come up.
 */
object StartupProgress {

    val TRACKED: List<DaemonType> = listOf(
        DaemonType.CAMERA_DAEMON,
        DaemonType.SENTRY_DAEMON,
        DaemonType.ACC_SENTRY_DAEMON,
        DaemonType.FRP_TUNNEL,
    )

    /** One start-up pass: when it began (elapsedRealtime), what it skipped on purpose, and whether it is over. */
    data class Pass(val startedAt: Long, val skipped: Set<DaemonType>, val done: Boolean = false)

    private val _pass = MutableLiveData<Pass?>(null)

    /** Null until the first start-up pass of this process begins. */
    val pass: LiveData<Pass?> = _pass

    @Synchronized
    fun begin() {
        current = Pass(SystemClock.elapsedRealtime(), emptySet())
        _pass.postValue(current)
    }

    /** [type] will not be started in this pass, on purpose: the banner stops waiting for it. */
    @Synchronized
    fun skip(type: DaemonType) {
        if (type !in TRACKED) return
        val now = current ?: return
        if (type in now.skipped) return
        current = now.copy(skipped = now.skipped + type)
        _pass.postValue(current)
    }

    /**
     * Everything this pass waited for ran (or the user closed the strip): a later restart, a
     * user stop or a recreated screen does not bring the strip back for the same pass.
     */
    @Synchronized
    fun markDone(startedAt: Long) {
        val now = current ?: return
        if (now.startedAt != startedAt || now.done) return
        current = now.copy(done = true)
        _pass.postValue(current)
    }

    // the latest value written, readable off the main thread (LiveData.value lags postValue)
    private var current: Pass? = null
}
