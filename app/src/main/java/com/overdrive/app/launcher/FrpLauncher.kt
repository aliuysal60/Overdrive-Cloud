// FrpLauncher.kt — installs and runs the self-hosted FRP tunnel client (frpc) via ADB shell
package com.overdrive.app.launcher

import android.content.Context
import com.overdrive.app.BuildConfig
import com.overdrive.app.frp.FrpPaths
import com.overdrive.app.logging.LogManager
import com.overdrive.app.ui.model.DaemonType
import com.overdrive.app.ui.model.ParkedShutdown
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs frpc (upstream fatedier/frp, android_arm64) against the user's own frps, publishing the
 * dashboard (127.0.0.1:8080) as an HTTP vhost. Settings and the frps token live in
 * [FrpPaths.CONFIG] (0600, written by the camera daemon); nothing secret passes through here.
 *
 * frpc retries by itself (`loginFailExit = false`); [FrpPaths.RUN_SCRIPT] restarts it if it exits
 * and stops when the user stopped the tunnel ([DaemonType.FRP_TUNNEL] sentinel) or the car parks in
 * "Vehicle ON only" mode.
 */
class FrpLauncher(
    private val context: Context,
    private val adbShellExecutor: AdbShellExecutor,
    private val logManager: LogManager
) {
    companion object {
        private const val TAG = "FrpLauncher"
        private const val DEPLOYMENT_CURRENT = "current"
        private const val LOG_ROTATE_BYTES = 512 * 1024
        private const val STATE_POLL_ATTEMPTS = 15
        private const val STATE_POLL_DELAY_MS = 1000L

        private val pollScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "FrpStatePoll").apply { isDaemon = true }
            }

        /** The restart loop, written to [FrpPaths.RUN_SCRIPT]. Contains no secret. */
        @JvmStatic
        fun buildRunScript(): String = """
            #!/system/bin/sh
            # OverDrive FRP tunnel: keeps frpc running until the user stops it or the car parks (onOnly)
            # adb shells run with umask 0: keep the log (the connection-state source) owner-writable
            umask 022
            STOP='${DaemonType.FRP_TUNNEL.sentinelPath}'
            PARKED='${ParkedShutdown.MARKER_PATH}'
            LOG='${FrpPaths.LOG}'
            while [ ! -f "${'$'}STOP" ] && [ ! -f "${'$'}PARKED" ]; do
              if [ -f "${'$'}LOG" ] && [ "${'$'}(wc -c < "${'$'}LOG")" -gt $LOG_ROTATE_BYTES ]; then mv -f "${'$'}LOG" "${'$'}LOG.1"; fi
              ${FrpPaths.BINARY} -c ${FrpPaths.CONFIG} >> "${'$'}LOG" 2>&1
              sleep 5
            done
        """.trimIndent() + "\n"

        /** Kill the restart loop first (so it cannot respawn), then frpc. */
        @JvmStatic
        fun buildStopCommand(): String =
            "for pid in ${'$'}(ps -A -o PID,ARGS | grep -F '${FrpPaths.RUN_SCRIPT}' | grep -v grep | awk '{print ${'$'}1}'); do " +
                "[ \"${'$'}pid\" != \"${'$'}${'$'}\" ] && kill ${'$'}pid 2>/dev/null; done; " +
                "for pid in ${'$'}(pidof ${FrpPaths.PROCESS_NAME} 2>/dev/null); do kill ${'$'}pid 2>/dev/null; done; sleep 1; " +
                "for pid in ${'$'}(pidof ${FrpPaths.PROCESS_NAME} 2>/dev/null); do kill -9 ${'$'}pid 2>/dev/null; done; echo stopped"
    }

    interface FrpCallback {
        fun onLog(message: String)
        fun onStarted(url: String?)
        fun onStopped()
        fun onError(error: String)
    }

    fun launch(callback: FrpCallback) {
        hasConfig { configured ->
            if (!configured) {
                callback.onError("FRP tunnel is not configured")
                return@hasConfig
            }
            isRunning { running ->
                if (running) {
                    callback.onLog("FRP tunnel already running")
                    pollState(0, callback)
                    return@isRunning
                }
                install(callback) { start(callback) }
            }
        }
    }

    /** Stop and start again so new settings take effect. */
    fun restart(callback: FrpCallback) {
        adbShellExecutor.execute(
            command = buildStopCommand(),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = launch(callback)
                override fun onError(error: String) = launch(callback)
            }
        )
    }

    private fun install(callback: FrpCallback, onComplete: () -> Unit) {
        val src = "${context.applicationInfo.nativeLibraryDir}/${FrpPaths.NATIVE_LIB}"
        val version = BuildConfig.VERSION_CODE.toLong()
        val tmp = "${FrpPaths.BINARY}.new"
        // binary: copy + rename (never "text file busy"), stamp last; run script always rewritten
        val command = "mkdir -p ${FrpPaths.HOME} && chmod 711 ${FrpPaths.HOME} && " +
            "if ! test -x ${FrpPaths.BINARY} || [ \"\$(cat ${FrpPaths.VERSION_FILE} 2>/dev/null)\" != \"$version\" ]; then " +
            "test -f $src && cp -f $src $tmp && chmod 755 $tmp && mv -f $tmp ${FrpPaths.BINARY} && " +
            "printf '$version\\n' > ${FrpPaths.VERSION_FILE} && chmod 644 ${FrpPaths.VERSION_FILE} || exit 1; fi && " +
            "cat > ${FrpPaths.RUN_SCRIPT} <<'ODFRP_EOF'\n${buildRunScript()}ODFRP_EOF\n" +
            "chmod 755 ${FrpPaths.RUN_SCRIPT} && echo $DEPLOYMENT_CURRENT"
        adbShellExecutor.execute(
            command = command,
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    if (output.trim().lineSequence().lastOrNull() == DEPLOYMENT_CURRENT) {
                        onComplete()
                    } else {
                        callback.onError("Failed to install the FRP client")
                    }
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to install the FRP client: $error")
                    callback.onError("Failed to install the FRP client: $error")
                }
            }
        )
    }

    private fun start(callback: FrpCallback) {
        callback.onLog("Starting FRP tunnel...")
        // A loop sleeping between frpc restarts can look like a dead tunnel to the health check:
        // clear any old loop first so two clients never register the same proxy.
        adbShellExecutor.execute(
            command = buildStopCommand() + " > /dev/null; rm -f ${FrpPaths.LOG}; " +
                "nohup sh ${FrpPaths.RUN_SCRIPT} > /dev/null 2>&1 &",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    logManager.info(TAG, "FRP tunnel started")
                    pollState(0, callback)
                }

                override fun onError(error: String) {
                    logManager.error(TAG, "Failed to start the FRP tunnel: $error")
                    callback.onError("Failed to start the FRP tunnel: $error")
                }
            }
        )
    }

    private fun pollState(attempt: Int, callback: FrpCallback) {
        readState { state ->
            when {
                state == "connected" -> readUrl { callback.onStarted(it) }
                state == "error" -> {
                    readLastError { error ->
                        logManager.error(TAG, "FRP tunnel error: $error")
                        callback.onError(error)
                    }
                }
                attempt + 1 >= STATE_POLL_ATTEMPTS -> readUrl { callback.onStarted(it) }
                else -> pollScheduler.schedule(
                    { pollState(attempt + 1, callback) }, STATE_POLL_DELAY_MS, TimeUnit.MILLISECONDS
                )
            }
        }
    }

    fun stop(callback: FrpCallback) {
        logManager.info(TAG, "Stopping FRP tunnel...")
        callback.onLog("Stopping FRP tunnel...")
        adbShellExecutor.execute(
            command = buildStopCommand(),
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = callback.onStopped()
                override fun onError(error: String) {
                    logManager.warn(TAG, "FRP stop finished with warning: $error")
                    callback.onStopped()
                }
            }
        )
    }

    fun isRunning(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "pidof ${FrpPaths.PROCESS_NAME} 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = callback(output.trim().isNotEmpty())
                override fun onError(error: String) = callback(false)
            }
        )
    }

    fun hasConfig(callback: (Boolean) -> Unit) {
        adbShellExecutor.execute(
            command = "test -s ${FrpPaths.CONFIG} && echo yes",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = callback(output.trim() == "yes")
                override fun onError(error: String) = callback(false)
            }
        )
    }

    /**
     * "cloud" (OverDrive Cloud enrollment), "manual" (the user's own frps) or "none"; null when the
     * probe failed. Only file existence is checked: both files stay 0600.
     */
    fun tunnelMode(callback: (String?) -> Unit) {
        adbShellExecutor.execute(
            command = "if [ -f ${FrpPaths.CLOUD} ]; then echo cloud; elif [ -f ${FrpPaths.CONFIG} ]; then echo manual; else echo none; fi",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) = callback(output.trim().lineSequence().lastOrNull())
                override fun onError(error: String) = callback(null)
            }
        )
    }

    /** Public URL from the non-secret summary, or null. */
    fun readUrl(callback: (String?) -> Unit) {
        readSummary { summary -> callback(summary?.optString("url", "")?.ifEmpty { null }) }
    }

    /** The non-secret summary.json (URL; in cloud mode also the pairing code and link), or null. */
    fun readSummary(callback: (JSONObject?) -> Unit) {
        adbShellExecutor.execute(
            command = "cat ${FrpPaths.SUMMARY} 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    callback(try { JSONObject(output.trim()) } catch (_: Exception) { null })
                }

                override fun onError(error: String) = callback(null)
            }
        )
    }

    /** "connected", "error" or "connecting" from the latest frpc log event. */
    fun readState(callback: (String) -> Unit) {
        adbShellExecutor.execute(
            command = "tail -c 16384 ${FrpPaths.LOG} 2>/dev/null",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) =
                    callback(com.overdrive.app.frp.FrpStore.connectionStateOf(output))

                override fun onError(error: String) = callback("connecting")
            }
        )
    }

    private fun readLastError(callback: (String) -> Unit) {
        adbShellExecutor.execute(
            command = "grep -iE 'failed|error' ${FrpPaths.LOG} 2>/dev/null | tail -1",
            callback = object : AdbShellExecutor.ShellCallback {
                override fun onSuccess(output: String) {
                    // frpc lines look like "<date> [E] [file.go:12] message"; keep the message
                    val line = output.trim().substringAfterLast("] ").trim()
                    callback(line.ifEmpty { "FRP tunnel error" })
                }

                override fun onError(error: String) = callback("FRP tunnel error")
            }
        )
    }
}
