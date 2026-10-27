// FrpSettingsDialog.kt — own-server FRP settings dialog, and the FRP card's entry point (cloud or manual)
package com.overdrive.app.ui.daemon

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.overdrive.app.R
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import org.json.JSONObject

/**
 * The settings go to the camera daemon (POST /api/frp/config), which renders frpc.toml with the
 * token at mode 0600; the saved token is never read back — an empty token field keeps it.
 */
class FrpSettingsDialog private constructor(
    private val context: Context,
    private val daemonsViewModel: DaemonsViewModel
) {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dialog: AlertDialog
    private lateinit var server: TextInputEditText
    private lateinit var port: TextInputEditText
    private lateinit var token: TextInputEditText
    private lateinit var tokenLayout: TextInputLayout
    private lateinit var domain: TextInputEditText
    private lateinit var status: TextView
    private var tokenSaved = false

    private fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_frp_settings, null)
        server = view.findViewById(R.id.etFrpServer)
        port = view.findViewById(R.id.etFrpPort)
        token = view.findViewById(R.id.etFrpToken)
        tokenLayout = view.findViewById(R.id.tilFrpToken)
        domain = view.findViewById(R.id.etFrpDomain)
        status = view.findViewById(R.id.tvFrpStatus)
        port.setText("443")

        dialog = MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(R.string.dialog_frp_settings_title)
            .setView(view)
            .setPositiveButton(R.string.dialog_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .setNeutralButton(R.string.frp_delete, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { confirmDelete() }
        view.findViewById<android.view.View>(R.id.btnFrpUseCloud).setOnClickListener { confirmUseCloud() }
        load()
    }

    private fun load() {
        Thread {
            val reply = request("GET", "/api/frp", null)?.second
            main.post {
                if (!dialog.isShowing) return@post
                if (reply == null) {
                    status.text = context.getString(R.string.toast_frp_daemon_unreachable)
                    return@post
                }
                val summary = reply.optJSONObject("summary")
                tokenSaved = reply.optBoolean("configured", false)
                if (summary != null) {
                    server.setText(summary.optString("serverAddr"))
                    port.setText(summary.optInt("serverPort", 443).toString())
                    domain.setText(summary.optString("domain"))
                }
                tokenLayout.hint = context.getString(
                    if (tokenSaved) R.string.frp_token_hint_keep else R.string.frp_token_hint
                )
                status.text = context.getString(R.string.frp_status_line, describe(reply))
            }
        }.start()
    }

    private fun describe(reply: JSONObject): String {
        if (!reply.optBoolean("configured", false)) return context.getString(R.string.frp_state_unconfigured)
        return when (reply.optString("state")) {
            "connected" -> context.getString(
                R.string.frp_state_connected,
                reply.optJSONObject("summary")?.optString("url").orEmpty()
            )
            "connecting" -> context.getString(R.string.frp_state_connecting)
            "error" -> context.getString(R.string.frp_state_error)
            else -> context.getString(R.string.frp_state_stopped)
        }
    }

    private fun save() {
        val body = JSONObject()
            .put("serverAddr", server.text?.toString().orEmpty().trim())
            .put("serverPort", port.text?.toString()?.trim()?.toIntOrNull() ?: 443)
            .put("token", token.text?.toString().orEmpty().trim())
            .put("domain", domain.text?.toString().orEmpty().trim())
            .toString()
        val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        saveButton.isEnabled = false
        Thread {
            val reply = request("POST", "/api/frp/config", body)
            main.post {
                saveButton.isEnabled = true
                when {
                    reply == null -> toast(context.getString(R.string.toast_frp_daemon_unreachable))
                    reply.first in 200..299 -> {
                        toast(context.getString(R.string.toast_frp_saved))
                        daemonsViewModel.restartFrpIfRunning()
                        dialog.dismiss()
                    }
                    else -> toast(context.getString(
                        R.string.toast_frp_save_failed,
                        reply.second?.optString("error").orEmpty().ifEmpty { "HTTP ${reply.first}" }
                    ))
                }
            }
        }.start()
    }

    /** Leave the own frps for OverDrive Cloud, then show the pairing QR. */
    private fun confirmUseCloud() {
        MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setMessage(R.string.frp_use_cloud_confirm)
            .setPositiveButton(R.string.frp_use_cloud) { _, _ ->
                Thread {
                    val ok = request("POST", "/api/frp/cloud/enroll", "{}")?.first in 200..299
                    val fresh = if (ok) request("GET", "/api/frp", null)?.second else null
                    main.post {
                        if (fresh == null) {
                            toast(context.getString(R.string.frp_use_cloud_failed))
                            return@post
                        }
                        // the cloud tunnel is meant to be always on: enable it and (re)start frpc
                        com.overdrive.app.ui.util.PreferencesManager.setDaemonEnabled(
                            com.overdrive.app.ui.model.DaemonType.FRP_TUNNEL, true)
                        com.overdrive.app.ui.util.PreferencesManager.setFrpCloudAutoEnabled()
                        daemonsViewModel.startOrRestartFrp()
                        if (dialog.isShowing) dialog.dismiss()
                        CloudAccessDialog.show(context, daemonsViewModel, fresh)
                    }
                }.start()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setMessage(R.string.frp_delete_confirm)
            .setPositiveButton(R.string.frp_delete) { _, _ ->
                Thread {
                    val ok = request("DELETE", "/api/frp/config", null)?.first in 200..299
                    main.post {
                        toast(context.getString(
                            if (ok) R.string.toast_frp_deleted else R.string.toast_frp_daemon_unreachable
                        ))
                        if (ok) dialog.dismiss()
                    }
                }.start()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun request(method: String, path: String, body: String?) = FrpDaemonApi.request(method, path, body)

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** FRP card: the OverDrive Cloud view while enrolled, the own-server form otherwise. */
        fun show(context: Context, daemonsViewModel: DaemonsViewModel) {
            Thread {
                val reply = FrpDaemonApi.request("GET", "/api/frp", null)?.second
                Handler(Looper.getMainLooper()).post {
                    if (context is Activity && (context.isFinishing || context.isDestroyed)) return@post
                    if (reply?.optString("mode") == "cloud") CloudAccessDialog.show(context, daemonsViewModel, reply)
                    else showManual(context, daemonsViewModel)
                }
            }.start()
        }

        /** The own-server (manual) form; saving it leaves OverDrive Cloud. */
        fun showManual(context: Context, daemonsViewModel: DaemonsViewModel) {
            if (context is Activity && (context.isFinishing || context.isDestroyed)) return
            FrpSettingsDialog(context, daemonsViewModel).show()
        }
    }
}
