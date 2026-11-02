// StoreLauncher.kt — opens OverDrive Store, silently installing or updating it from its GitHub release first
package com.overdrive.app.store

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.overdrive.app.R
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The rail's "App Store" row. A missing store is downloaded from its latest GitHub release
 * ([StoreReleases]) and installed through OverDrive's ADB shell ([StoreInstaller.installStore],
 * `pm install -r -d`) — no prompt — then opened. An installed store is checked against that
 * release at most every [CHECK_INTERVAL_MS]; when the release is newer the store is updated
 * the same way BEFORE it opens (updating it while open would kill it under the user).
 * Without network, or when an update fails, the installed store simply opens.
 */
object StoreLauncher {
    private const val TAG = "StoreLauncher"
    const val RELEASE_REPO = "aliuysal60/droid_ify_client"
    private const val PREFS = "overdrive_store"
    private const val KEY_LAST_CHECK = "last_update_check_ms"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    // StoreInstaller answers within its own deadlines (stage 320 s + pm 320 s).
    private const val INSTALL_WAIT_MIN = 12L

    @Volatile private var busy = false

    fun openOrInstall(activity: Activity) {
        if (busy) return
        val installed = installedVersionCode(activity)
        if (installed != null && !updateCheckDue(activity)) {
            open(activity)
            return
        }
        busy = true
        val app = activity.applicationContext
        val dialog = AtomicReference<AlertDialog?>(
            if (installed == null) progressDialog(activity, R.string.store_installing) else null)
        Thread({
            val release = StoreReleases.fetchLatest()
            if (installed != null) markChecked(app)
            val newer = release?.versionCode?.let { installed == null || it > installed } ?: false
            val error: String? = when {
                release == null -> "no release found on GitHub"
                installed == null || newer -> {
                    if (installed != null) {
                        Log.i(TAG, "store update $installed -> ${release.versionCode}")
                        // Posted before the final dismiss below, so that one closes it.
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                dialog.set(progressDialog(activity, R.string.store_updating))
                            }
                        }
                    }
                    downloadAndInstall(app, release)
                }
                else -> null // up to date
            }
            activity.runOnUiThread {
                busy = false
                dialog.get()?.let(::dismissQuietly)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    error == null -> open(activity)
                    installed != null -> {
                        // The check or the update failed; the installed store still works.
                        Log.w(TAG, "store update skipped: $error")
                        open(activity)
                    }
                    else -> {
                        Log.w(TAG, "store install failed: $error")
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(R.string.rail_store)
                            .setMessage(activity.getString(R.string.store_install_failed, error))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }, "StoreLauncher").start()
    }

    /** Blocking: download [release] and install it; null on success, else the reason. */
    private fun downloadAndInstall(context: Context, release: StoreReleases.Release): String? {
        val apk = File(context.cacheDir, "store_download/OverDriveStore.apk")
        apk.parentFile?.mkdirs()
        try {
            StoreReleases.download(release, apk)
        } catch (e: IOException) {
            apk.delete()
            return "download failed (${e.message})"
        }
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>("no answer from the installer")
        StoreInstaller.get(context).installStore(apk) { success, message ->
            result.set(if (success) null else message)
            done.countDown()
        }
        return try {
            if (done.await(INSTALL_WAIT_MIN, TimeUnit.MINUTES)) result.get() else "install timed out"
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            "interrupted"
        }
    }

    private fun installedVersionCode(context: Context): Long? = try {
        context.packageManager.getPackageInfo(StoreInstaller.STORE_PACKAGE, 0).longVersionCode
    } catch (absent: PackageManager.NameNotFoundException) {
        null
    }

    private fun updateCheckDue(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        // A clock that jumped backwards also makes the check due.
        return now - last >= CHECK_INTERVAL_MS || now < last
    }

    private fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK, System.currentTimeMillis())
            .apply()
    }

    private fun open(activity: Activity) {
        val intent = activity.packageManager
            .getLaunchIntentForPackage(StoreInstaller.STORE_PACKAGE) ?: return
        activity.startActivity(intent)
    }

    private fun progressDialog(activity: Activity, messageRes: Int): AlertDialog =
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.rail_store)
            .setMessage(messageRes)
            .setCancelable(false)
            .show()

    private fun dismissQuietly(dialog: AlertDialog) {
        if (!dialog.isShowing) return
        try {
            dialog.dismiss()
        } catch (e: IllegalArgumentException) {
            // The activity went away meanwhile; its window is gone already.
        }
    }
}
