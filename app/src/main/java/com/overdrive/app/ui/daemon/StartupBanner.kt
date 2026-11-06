// StartupBanner.kt — the "OverDrive is getting ready, please wait" strip shown until the start-up services run
package com.overdrive.app.ui.daemon

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.overdrive.app.R
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.localizedName
import com.overdrive.app.ui.viewmodel.DaemonsViewModel

/**
 * After the car (or the app) starts, the core daemons come up only after ~45 s and the cloud
 * tunnel after ~60 s; until then the app looks dead and users uninstall it. This strip, on top of
 * every screen, says what is happening and stays until every service the start-up pass brings up
 * runs, then says "ready" for [READY_HOLD_MS] and closes by itself. Services the pass leaves down on
 * purpose ([StartupProgress.skip]) are not waited for; after [SLOW_AFTER_MS] it names what has not
 * started and may be closed.
 */
class StartupBanner(
    private val root: View,
    private val progress: CircularProgressIndicator,
    private val title: TextView,
    private val text: TextView,
    private val close: MaterialButton,
) {
    enum class Phase { HIDDEN, PREPARING, STARTING, SLOW, READY }

    /** What the strip shows, decided without Android so it can be tested. */
    data class Model(
        val phase: Phase,
        val ready: Int = 0,
        val total: Int = 0,
        val items: List<Pair<DaemonType, DaemonStatus?>> = emptyList(),
    )

    companion object {
        /** Don't flash the strip when the app reopens over services that already run. */
        const val SHOW_AFTER_MS = 4_000L

        /** Core daemons start 45 s into the pass; before that nothing is "starting" yet. */
        const val FIRST_START_MS = 45_000L

        /** By now every service should run; say which one hasn't and let the user close the strip. */
        const val SLOW_AFTER_MS = 5 * 60_000L

        /** How long "OverDrive is ready" stays before the strip closes by itself. */
        const val READY_HOLD_MS = 10_000L

        fun model(pass: StartupProgress.Pass?, states: Map<DaemonType, DaemonStatus>, now: Long): Model {
            if (pass == null || pass.done) return Model(Phase.HIDDEN)
            val expected = StartupProgress.TRACKED.filter { it !in pass.skipped }
            val ready = expected.count { states[it] == DaemonStatus.RUNNING }
            val elapsed = now - pass.startedAt
            if (elapsed < SHOW_AFTER_MS) return Model(Phase.HIDDEN)
            val items = expected.map { it to states[it] }
            if (ready == expected.size) return Model(Phase.READY, ready, expected.size, items)
            val anyStarted = items.any { it.second == DaemonStatus.RUNNING || it.second == DaemonStatus.STARTING }
            val phase = when {
                elapsed >= SLOW_AFTER_MS -> Phase.SLOW
                !anyStarted && elapsed < FIRST_START_MS + 5_000L -> Phase.PREPARING
                else -> Phase.STARTING
            }
            return Model(phase, ready, expected.size, items)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var states: Map<DaemonType, DaemonStatus> = emptyMap()
    private var pass: StartupProgress.Pass? = null
    // the strip was on screen in this pass (a reopen over running services never shows "ready")
    private var shownFor: Long? = null
    // when everything was first seen running; the strip closes READY_HOLD_MS later
    private var readyAt: Long? = null
    private val tick = object : Runnable {
        override fun run() {
            render()
            if (pass != null) handler.postDelayed(this, 1_000L)
        }
    }

    fun bind(owner: LifecycleOwner, daemons: DaemonsViewModel) {
        close.setOnClickListener {
            pass?.let { StartupProgress.markDone(it.startedAt) }
            root.visibility = View.GONE
        }
        daemons.daemonStates.observe(owner) { map ->
            states = map.mapValues { it.value.status }
            render()
        }
        StartupProgress.pass.observe(owner) { p ->
            pass = p
            handler.removeCallbacks(tick)
            if (p != null) handler.post(tick)
        }
    }

    private fun render() {
        val p = pass
        val now = SystemClock.elapsedRealtime()
        val m = model(p, states, now)
        if (m.phase == Phase.HIDDEN) {
            root.visibility = View.GONE
            if (p == null || p.done) handler.removeCallbacks(tick)
            return
        }
        if (m.phase == Phase.READY) {
            val ready = readyAt ?: now.also { readyAt = it }
            // never shown in this pass (reopened over running services), or shown long enough: over
            if (p == null || shownFor != p.startedAt || now - ready >= READY_HOLD_MS) {
                p?.let { StartupProgress.markDone(it.startedAt) }
                root.visibility = View.GONE
                handler.removeCallbacks(tick)
                return
            }
        } else {
            readyAt = null
            shownFor = p?.startedAt
        }
        val ctx = root.context
        root.visibility = View.VISIBLE
        if (m.phase == Phase.READY) {
            progress.visibility = View.GONE
            close.visibility = View.GONE
            title.text = ctx.getString(R.string.startup_banner_title_ready, m.ready, m.total)
            text.text = ctx.getString(R.string.startup_banner_ready)
            return
        }
        progress.visibility = if (m.phase == Phase.SLOW) View.GONE else View.VISIBLE
        close.visibility = if (m.phase == Phase.SLOW) View.VISIBLE else View.GONE
        title.text = ctx.getString(
            if (m.phase == Phase.SLOW) R.string.startup_banner_title_slow else R.string.startup_banner_title,
            m.ready, m.total,
        )
        val list = m.items.joinToString("  ·  ") { (type, status) ->
            val mark = when (status) {
                DaemonStatus.RUNNING -> "✓"
                DaemonStatus.STARTING -> ctx.getString(R.string.startup_banner_starting)
                DaemonStatus.ERROR -> ctx.getString(R.string.startup_banner_retrying)
                else -> ctx.getString(R.string.startup_banner_waiting)
            }
            "${type.localizedName(ctx)} $mark"
        }
        text.text = when (m.phase) {
            Phase.PREPARING -> ctx.getString(R.string.startup_banner_preparing)
            Phase.SLOW -> ctx.getString(R.string.startup_banner_slow, list)
            else -> ctx.getString(R.string.startup_banner_starting_list, list)
        }
    }
}
