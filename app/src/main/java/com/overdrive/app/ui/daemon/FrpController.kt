// FrpController.kt — Daemons-screen controller of the self-hosted FRP tunnel
package com.overdrive.app.ui.daemon

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.overdrive.app.launcher.AdbDaemonLauncher
import com.overdrive.app.launcher.AdbShellExecutor
import com.overdrive.app.launcher.FrpLauncher
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.model.DaemonStatus
import com.overdrive.app.ui.model.DaemonType

/**
 * Controller for the FRP tunnel (frpc → the user's own frps, e.g. on CapRover). The public URL is
 * the configured domain, so it is known before the connection is up; [tunnelUrl] is set only once
 * frpc reports the proxy as started.
 */
class FrpController(
    private val context: Context,
    private val adbLauncher: AdbDaemonLauncher
) : DaemonController {

    override val type = DaemonType.FRP_TUNNEL

    private val _tunnelUrl = MutableLiveData<String?>()
    val tunnelUrl: LiveData<String?> = _tunnelUrl

    /** OverDrive Cloud pairing shown on the dashboard (QR link + typeable code); null in manual mode. */
    data class CloudPairing(val code: String, val url: String)

    private val _cloudPairing = MutableLiveData<CloudPairing?>()
    val cloudPairing: LiveData<CloudPairing?> = _cloudPairing

    private val frpLauncher by lazy {
        FrpLauncher(context, AdbShellExecutor(context), LogManager.getInstance())
    }

    private fun callbackFor(callback: DaemonCallback, phase: DaemonStatus) =
        object : FrpLauncher.FrpCallback {
            override fun onLog(message: String) = callback.onStatusChanged(phase, message)

            override fun onStarted(url: String?) {
                _tunnelUrl.postValue(url)
                callback.onStatusChanged(DaemonStatus.RUNNING, url ?: "")
            }

            override fun onStopped() {
                _tunnelUrl.postValue(null)
                callback.onStatusChanged(DaemonStatus.STOPPED, "FRP tunnel stopped")
            }

            override fun onError(error: String) = callback.onError(error)
        }

    override fun start(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Starting FRP tunnel...")
        frpLauncher.launch(callbackFor(callback, DaemonStatus.STARTING))
    }

    /** Restart so saved settings take effect. */
    fun restart(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STARTING, "Restarting FRP tunnel...")
        frpLauncher.restart(callbackFor(callback, DaemonStatus.STARTING))
    }

    override fun stop(callback: DaemonCallback) {
        callback.onStatusChanged(DaemonStatus.STOPPING, "Stopping FRP tunnel...")
        frpLauncher.stop(callbackFor(callback, DaemonStatus.STOPPING))
    }

    override fun isRunning(callback: (Boolean) -> Unit) {
        frpLauncher.isRunning(callback)
    }

    fun hasConfig(callback: (Boolean) -> Unit) {
        frpLauncher.hasConfig(callback)
    }

    /** Re-reads the URL while frpc runs and reports the proxy as started; clears it otherwise. */
    fun refreshTunnelUrl(callback: ((String?) -> Unit)? = null) {
        frpLauncher.readState { state ->
            if (state != "connected") {
                _tunnelUrl.postValue(null)
                callback?.invoke(null)
                return@readState
            }
            frpLauncher.readSummary { summary ->
                val url = summary?.optString("url", "")?.ifEmpty { null }
                val pairing = summary?.takeIf { it.optString("mode") == "cloud" }?.let {
                    val code = it.optString("pairCode")
                    val link = it.optString("pairUrl")
                    if (code.isNotEmpty() && link.isNotEmpty()) CloudPairing(code, link) else null
                }
                if (_cloudPairing.value != pairing) _cloudPairing.postValue(pairing)
                _tunnelUrl.postValue(url)
                callback?.invoke(url)
            }
        }
    }

    override fun cleanup() {
        adbLauncher.executeShellCommand(
            FrpLauncher.buildStopCommand(),
            object : AdbDaemonLauncher.LaunchCallback {
                override fun onLog(message: String) {}
                override fun onLaunched() {}
                override fun onError(error: String) {}
            }
        )
        _tunnelUrl.postValue(null)
    }
}
