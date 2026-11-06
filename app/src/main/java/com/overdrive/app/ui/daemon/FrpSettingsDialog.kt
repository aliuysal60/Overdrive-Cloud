// FrpSettingsDialog.kt — the FRP card's entry point: the OverDrive Cloud view, enrolling the car first when needed
package com.overdrive.app.ui.daemon

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.overdrive.app.R
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.util.PreferencesManager
import com.overdrive.app.ui.viewmodel.DaemonsViewModel

/**
 * The FRP tunnel only runs through OverDrive Cloud (the self-hosted frps mode is gone). The car
 * enrolls itself in the background; when it hasn't yet, the card asks the daemon to enroll now
 * and then shows the pairing QR.
 */
object FrpSettingsDialog {

    fun show(context: Context, daemonsViewModel: DaemonsViewModel) {
        Thread {
            var reply = FrpDaemonApi.request("GET", "/api/frp", null)?.second
            var enrolledNow = false
            if (reply != null && reply.optString("mode") != "cloud") {
                val ok = FrpDaemonApi.request("POST", "/api/frp/cloud/enroll", "{}")?.first in 200..299
                val fresh = if (ok) FrpDaemonApi.request("GET", "/api/frp", null)?.second else null
                if (fresh != null) {
                    reply = fresh
                    enrolledNow = fresh.optString("mode") == "cloud"
                }
            }
            val result = reply
            val enrolled = enrolledNow
            Handler(Looper.getMainLooper()).post {
                if (context is Activity && (context.isFinishing || context.isDestroyed)) return@post
                if (result == null) {
                    toast(context, R.string.toast_frp_daemon_unreachable)
                    return@post
                }
                if (enrolled) {
                    // the cloud tunnel is meant to be always on: enable it and (re)start frpc
                    PreferencesManager.setDaemonEnabled(DaemonType.FRP_TUNNEL, true)
                    PreferencesManager.setFrpCloudAutoEnabled()
                    daemonsViewModel.startOrRestartFrp()
                }
                // not enrolled yet (no internet): the dialog says so and the daemon keeps trying
                CloudAccessDialog.show(context, daemonsViewModel, result)
            }
        }.start()
    }

    private fun toast(context: Context, text: Int) {
        Toast.makeText(context, context.getString(text), Toast.LENGTH_SHORT).show()
    }
}
