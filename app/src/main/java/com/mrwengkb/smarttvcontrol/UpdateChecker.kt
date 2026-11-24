package com.mrwengkb.smarttvcontrol

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A newer release found on GitHub. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val pageUrl: String,
)

/**
 * Checks GitHub Releases for a newer version, like HanziQuery: the newest release's tag (vX.Y.Z)
 * is compared with this app's version, and its notes (the versions.md section) are shown.
 * A version the user chose to ignore is not offered again unless they check by hand.
 */
class UpdateChecker(context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    // A normal client with standard certificate checks (not the LAN client used for the TV).
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    var ignoredVersion: String?
        get() = prefs.getString(KEY_IGNORED, null)
        set(value) = prefs.edit().putString(KEY_IGNORED, value).apply()

    /** Returns the newer release, or null when up to date. Throws on network errors. */
    suspend fun latestNewerThan(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SmartTVControl-Android")
            .build()
        val json = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("GitHub returned ${response.code}")
            response.body?.string() ?: return@withContext null
        }
        val release = JSONObject(json)
        val version = release.optString("tag_name").removePrefix("v").removePrefix("V")
        if (compareVersions(version, currentVersion) <= 0) return@withContext null
        val assets = release.optJSONArray("assets")
        var apkUrl: String? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
        }
        val page = release.optString("html_url", RELEASES_PAGE)
        UpdateInfo(
            version = version,
            notes = release.optString("body").trim(),
            downloadUrl = apkUrl ?: page,
            pageUrl = page,
        )
    }

    companion object {
        private const val KEY_IGNORED = "ignoredVersion"
        const val LATEST_RELEASE_API = "https://api.github.com/repos/MrWengKB/SmartTVControl/releases/latest"
        const val RELEASES_PAGE = "https://github.com/MrWengKB/SmartTVControl/releases"

        /** Compares MAJOR.MINOR.PATCH strings numerically; anything after '-' is ignored. */
        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val pa = parts(a)
            val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }
    }
}
