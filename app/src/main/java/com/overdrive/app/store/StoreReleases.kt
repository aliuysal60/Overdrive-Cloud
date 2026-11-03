// StoreReleases.kt — OverDrive Store's latest GitHub release: version check and APK download
package com.overdrive.app.store

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * A store's release APK is named `<Name>-<versionCode>.apk` (OverDriveStore-78001.apk,
 * OverDrivePlayStore-7601.apk); its versionCode is upstream's * 100 + OverDrive's revision.
 * Comparing that number with the installed store's versionCode tells whether to update.
 *
 * The APK is downloaded here, in the app process, with OkHttp (HTTPS and GitHub's redirect
 * to its download host), not with the shell's wget: the head unit's toybox wget is a bare
 * applet. When GitHub reports the asset's SHA-256 (`digest`), the download must match it.
 */
object StoreReleases {
    private const val TAG = "StoreReleases"
    private const val CHECK_TIMEOUT_S = 5L
    private const val DOWNLOAD_READ_TIMEOUT_S = 30L
    private const val DOWNLOAD_DEADLINE_MIN = 10L
    private const val MAX_APK_BYTES = 200L * 1024 * 1024
    private val APK_VERSION_CODE = Regex("""-(\d+)\.apk$""")
    private val SHA256_DIGEST = Regex("""^sha256:([0-9a-fA-F]{64})$""")

    class Release(val versionCode: Long?, val apkUrl: String, val sha256: String?)

    /** versionCode in a release asset name, or null when the name doesn't carry one. */
    @JvmStatic
    fun versionCodeOf(assetName: String?): Long? =
        assetName?.let { APK_VERSION_CODE.find(it)?.groupValues?.get(1)?.toLongOrNull() }

    /** The hex SHA-256 in a GitHub asset `digest` ("sha256:<hex>"), or null. */
    @JvmStatic
    fun sha256Of(digest: String?): String? =
        digest?.let { SHA256_DIGEST.find(it.trim())?.groupValues?.get(1)?.lowercase() }

    /** The first APK of a GitHub release JSON, or null. */
    private fun parseRelease(json: String): Release? {
        val assets = JSONObject(json).optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            val url = asset.optString("browser_download_url")
            if (name.endsWith(".apk") && url.isNotEmpty()) {
                return Release(versionCodeOf(name), url, sha256Of(asset.optString("digest")))
            }
        }
        return null
    }

    /** Blocking: the latest release of [repo] ("owner/name"), or null on any failure. */
    fun fetchLatest(repo: String): Release? = try {
        val client = OkHttpClient.Builder()
            .connectTimeout(CHECK_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(CHECK_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(CHECK_TIMEOUT_S * 2, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(TAG, "latest release: HTTP ${response.code}")
                null
            } else {
                parseRelease(response.body?.string().orEmpty())
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "latest release check failed: ${e.message}")
        null
    }

    /** Blocking: download [release]'s APK to [out], checking its SHA-256 when known. */
    @Throws(IOException::class)
    fun download(release: Release, out: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(DOWNLOAD_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .callTimeout(DOWNLOAD_DEADLINE_MIN, TimeUnit.MINUTES)
            .build()
        val request = Request.Builder().url(release.apkUrl).build()
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty response")
            var total = 0L
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        total += read
                        if (total > MAX_APK_BYTES) throw IOException("APK too large")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && release.sha256 != actual) {
            out.delete()
            throw IOException("SHA-256 mismatch")
        }
    }
}
