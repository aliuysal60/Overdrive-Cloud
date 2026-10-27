// FrpDaemonApi.kt — blocking JSON calls to the camera daemon's /api/frp endpoints (call off the main thread)
package com.overdrive.app.ui.daemon

import com.overdrive.app.util.DaemonHttpClient
import org.json.JSONObject

object FrpDaemonApi {
    /** Returns (http status, parsed JSON body or null), or null when the daemon is unreachable. */
    fun request(method: String, path: String, body: String?): Pair<Int, JSONObject?>? {
        return try {
            val conn = DaemonHttpClient.open(path, method, 3000, 20000)
            try {
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = conn.responseCode
                val stream = if (code >= 400) conn.errorStream else conn.inputStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                code to (try { JSONObject(text) } catch (e: Exception) { null })
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}
