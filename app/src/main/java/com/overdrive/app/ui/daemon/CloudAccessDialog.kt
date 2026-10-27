// CloudAccessDialog.kt — OverDrive Cloud: pairing QR + code, connection state, renew code, leave for own frps
package com.overdrive.app.ui.daemon

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.overdrive.app.R
import com.overdrive.app.ui.util.QrCodeGenerator
import com.overdrive.app.ui.viewmodel.DaemonsViewModel
import org.json.JSONObject

/**
 * Shown for the FRP card while the car is enrolled with OverDrive Cloud. Everything comes from the
 * daemon's GET /api/frp summary (no secrets); renewing the code goes through the daemon, which
 * holds the car credential.
 */
class CloudAccessDialog private constructor(
    private val context: Context,
    private val daemonsViewModel: DaemonsViewModel,
    private var reply: JSONObject
) {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dialog: AlertDialog
    private lateinit var intro: TextView
    private lateinit var qr: ImageView
    private lateinit var code: TextView
    private lateinit var status: TextView
    private lateinit var accounts: LinearLayout

    private fun show() {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_cloud_access, null)
        intro = view.findViewById(R.id.tvCloudIntro)
        qr = view.findViewById(R.id.ivCloudQr)
        code = view.findViewById(R.id.tvCloudCode)
        status = view.findViewById(R.id.tvCloudStatus)
        accounts = view.findViewById(R.id.cloudAccounts)

        dialog = MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setTitle(R.string.dialog_cloud_title)
            .setView(view)
            .setPositiveButton(R.string.action_close, null)
            .setNeutralButton(R.string.cloud_rotate, null)
            .setNegativeButton(R.string.cloud_own_server, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { confirmRotate() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { confirmOwnServer() }
        render()
        loadAccounts()
    }

    /** Fills the "accounts with access" list from the gateway (through the daemon). */
    private fun loadAccounts() {
        Thread {
            val reply = FrpDaemonApi.request("GET", "/api/frp/cloud/links", null)
            main.post {
                if (!dialog.isShowing) return@post
                accounts.removeAllViews()
                accounts.addView(label(context.getString(R.string.cloud_accounts_title), header = true))
                val links = reply?.takeIf { it.first in 200..299 }?.second?.optJSONArray("links")
                when {
                    links == null -> accounts.addView(label(context.getString(R.string.cloud_accounts_unavailable)))
                    links.length() == 0 -> accounts.addView(label(context.getString(R.string.cloud_accounts_empty)))
                    else -> for (i in 0 until links.length()) accounts.addView(accountRow(links.getJSONObject(i)))
                }
            }
        }.start()
    }

    private fun accountRow(link: JSONObject): View {
        val email = link.optString("email").ifEmpty { link.optString("name") }
        val lastUsed = link.optLong("lastUsed", 0L)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(label(email))
        if (lastUsed > 0) {
            val ago = DateUtils.getRelativeTimeSpanString(lastUsed, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            texts.addView(label(context.getString(R.string.cloud_account_last_used, ago), small = true))
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = context.getString(R.string.cloud_account_remove)
            setOnClickListener { confirmRevoke(link.optString("id"), email) }
        })
        return row
    }

    private fun confirmRevoke(id: String, email: String) {
        MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setMessage(context.getString(R.string.cloud_account_remove_confirm, email))
            .setPositiveButton(R.string.cloud_account_remove) { _, _ ->
                Thread {
                    val body = JSONObject().put("id", id).toString()
                    val ok = FrpDaemonApi.request("POST", "/api/frp/cloud/links/revoke", body)?.first in 200..299
                    main.post {
                        toast(context.getString(if (ok) R.string.cloud_account_removed else R.string.cloud_accounts_unavailable))
                        if (dialog.isShowing) loadAccounts()
                    }
                }.start()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun label(text: CharSequence, header: Boolean = false, small: Boolean = false) = TextView(context).apply {
        this.text = text
        setTextAppearance(
            when {
                header -> com.google.android.material.R.style.TextAppearance_Material3_TitleSmall
                small -> com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                else -> com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
            }
        )
        if (header) setPadding(0, dp(8), 0, dp(4))
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun render() {
        val summary = reply.optJSONObject("summary") ?: JSONObject()
        val pairCode = summary.optString("pairCode")
        val pairUrl = summary.optString("pairUrl")
        intro.text = context.getString(R.string.cloud_intro, summary.optString("url").ifEmpty { "OverDrive" })
        if (pairCode.isEmpty() || pairUrl.isEmpty()) {
            code.text = ""
            qr.setImageDrawable(null)
            status.text = context.getString(R.string.cloud_not_enrolled)
            return
        }
        code.text = pairCode
        qr.setImageBitmap(QrCodeGenerator.generate(pairUrl, 600))
        status.text = context.getString(R.string.frp_status_line, describeState(reply))
    }

    private fun describeState(r: JSONObject): String = when (r.optString("state")) {
        "connected" -> context.getString(R.string.frp_state_connected, r.optJSONObject("summary")?.optString("url").orEmpty())
        "connecting" -> context.getString(R.string.frp_state_connecting)
        "error" -> context.getString(R.string.frp_state_error)
        else -> context.getString(R.string.frp_state_stopped)
    }

    private fun confirmRotate() {
        MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setMessage(R.string.cloud_rotate_confirm)
            .setPositiveButton(R.string.cloud_rotate) { _, _ -> rotate() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun rotate() {
        val button = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
        button.isEnabled = false
        Thread {
            val result = FrpDaemonApi.request("POST", "/api/frp/cloud/pair-code", "{}")
            val fresh = if (result?.first in 200..299) FrpDaemonApi.request("GET", "/api/frp", null)?.second else null
            main.post {
                button.isEnabled = true
                if (!dialog.isShowing) return@post
                if (fresh == null) {
                    toast(context.getString(R.string.cloud_rotate_failed))
                    return@post
                }
                reply = fresh
                render()
                toast(context.getString(R.string.cloud_rotated))
            }
        }.start()
    }

    private fun confirmOwnServer() {
        MaterialAlertDialogBuilder(context, R.style.Theme_Overdrive_M3_Dialog)
            .setMessage(R.string.cloud_own_server_confirm)
            .setPositiveButton(R.string.cloud_own_server) { _, _ ->
                dialog.dismiss()
                FrpSettingsDialog.showManual(context, daemonsViewModel)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun show(context: Context, daemonsViewModel: DaemonsViewModel, reply: JSONObject) {
            if (context is Activity && (context.isFinishing || context.isDestroyed)) return
            CloudAccessDialog(context, daemonsViewModel, reply).show()
        }
    }
}
