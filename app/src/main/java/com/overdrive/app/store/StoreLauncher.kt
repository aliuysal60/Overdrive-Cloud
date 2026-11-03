// StoreLauncher.kt — opens OverDrive's stores, silently installing or updating them from their GitHub releases first
package com.overdrive.app.store

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.overdrive.app.R
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The rail's store rows ("App Store" = [Store.FDROID], "Play Store" = [Store.PLAY]). A missing
 * store is downloaded from its latest GitHub release ([StoreReleases]) and installed through
 * OverDrive's ADB shell ([StoreInstaller.installStore], streamed `install -r -d`) — no prompt —
 * then opened. An installed store is checked against that release at most every
 * [CHECK_INTERVAL_MS]; when the release is newer the store is updated the same way BEFORE it
 * opens (updating it while open would kill it under the user). Without network, or when an
 * update fails, the installed store simply opens.
 */
object StoreLauncher {
    private const val TAG = "StoreLauncher"
    private const val PREFS = "overdrive_store"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    // StoreInstaller answers within its own deadlines (copy + streamed install).
    private const val INSTALL_WAIT_MIN = 12L

    /** OverDrive's stores: package, GitHub release track, names, update-check key. */
    enum class Store(
        val packageName: String,
        val releaseRepo: String,
        @StringRes val railLabel: Int,
        @StringRes val appName: Int,
        val checkKey: String,
        val downloadName: String,
    ) {
        /** OverDrive Store, the Droid-ify fork: F-Droid apps. */
        FDROID(StoreInstaller.STORE_PACKAGE, "aliuysal60/droid_ify_client",
            R.string.rail_store, R.string.store_name_fdroid,
            "last_update_check_ms", "OverDriveStore.apk"),

        /** OverDrive Play Store, the Aurora Store fork: Google Play apps. */
        PLAY(StoreInstaller.AURORA_PACKAGE, "aliuysal60/AuroraStore",
            R.string.rail_play_store, R.string.store_name_play,
            "last_update_check_ms_play", "OverDrivePlayStore.apk"),
    }

    @Volatile private var busy = false

    fun openOrInstall(activity: Activity, store: Store) {
        if (busy) return
        val installed = installedVersionCode(activity, store)
        if (installed != null && !updateCheckDue(activity, store)) {
            open(activity, store)
            return
        }
        busy = true
        val app = activity.applicationContext
        val dialog = AtomicReference<AlertDialog?>(
            if (installed == null) progressDialog(activity, store, R.string.store_installing) else null)
        Thread({
            val release = StoreReleases.fetchLatest(store.releaseRepo)
            if (installed != null) markChecked(app, store)
            val newer = release?.versionCode?.let { installed == null || it > installed } ?: false
            val error: String? = when {
                release == null -> "no release found on GitHub"
                installed == null || newer -> {
                    if (installed != null) {
                        Log.i(TAG, "${store.packageName} update $installed -> ${release.versionCode}")
                        // Posted before the final dismiss below, so that one closes it.
                        activity.runOnUiThread {
                            if (!activity.isFinishing && !activity.isDestroyed) {
                                dialog.set(progressDialog(activity, store, R.string.store_updating))
                            }
                        }
                    }
                    downloadAndInstall(app, store, release)
                }
                else -> null // up to date
            }
            activity.runOnUiThread {
                busy = false
                dialog.get()?.let(::dismissQuietly)
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when {
                    error == null -> open(activity, store)
                    installed != null -> {
                        // The check or the update failed; the installed store still works.
                        Log.w(TAG, "${store.packageName} update skipped: $error")
                        open(activity, store)
                    }
                    else -> {
                        Log.w(TAG, "${store.packageName} install failed: $error")
                        MaterialAlertDialogBuilder(activity)
                            .setTitle(store.railLabel)
                            .setMessage(activity.getString(R.string.store_install_failed,
                                activity.getString(store.appName), error))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
        }, "StoreLauncher").start()
    }

    /** Blocking: download [release] and install it; null on success, else the reason. */
    private fun downloadAndInstall(
        context: Context,
        store: Store,
        release: StoreReleases.Release,
    ): String? {
        val apk = File(context.cacheDir, "store_download/${store.downloadName}")
        apk.parentFile?.mkdirs()
        try {
            StoreReleases.download(release, apk)
        } catch (e: IOException) {
            apk.delete()
            return "download failed (${e.message})"
        }
        val done = CountDownLatch(1)
        val result = AtomicReference<String?>("no answer from the installer")
        StoreInstaller.get(context).installStore(apk, store.packageName) { success, message ->
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

    private fun installedVersionCode(context: Context, store: Store): Long? = try {
        context.packageManager.getPackageInfo(store.packageName, 0).longVersionCode
    } catch (absent: PackageManager.NameNotFoundException) {
        null
    }

    private fun updateCheckDue(context: Context, store: Store): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(store.checkKey, 0L)
        val now = System.currentTimeMillis()
        // A clock that jumped backwards also makes the check due.
        return now - last >= CHECK_INTERVAL_MS || now < last
    }

    private fun markChecked(context: Context, store: Store) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(store.checkKey, System.currentTimeMillis())
            .apply()
    }

    private fun open(activity: Activity, store: Store) {
        val intent = activity.packageManager
            .getLaunchIntentForPackage(store.packageName) ?: return
        activity.startActivity(intent)
    }

    private fun progressDialog(activity: Activity, store: Store, @StringRes messageRes: Int): AlertDialog =
        MaterialAlertDialogBuilder(activity)
            .setTitle(store.railLabel)
            .setMessage(activity.getString(messageRes, activity.getString(store.appName)))
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
