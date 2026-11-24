package com.mrwengkb.smarttvcontrol

import android.content.Context
import com.mrwengkb.smarttvcontrol.core.AndroidTvIdentity
import com.mrwengkb.smarttvcontrol.core.TvPlatform
import org.json.JSONArray
import org.json.JSONObject

/** A TV the app has paired with. */
data class SavedTv(
    val host: String,
    val name: String,
    val clientKey: String? = null,
    val macAddress: String? = null,
    /** TVs saved before Google TV support have no platform stored and are webOS. */
    val platform: TvPlatform = TvPlatform.WEBOS,
    /** Android TV only: the TV accepted this app's certificate. */
    val paired: Boolean = false,
)

/** Remembers paired TVs (with their client keys) and which one was used last. */
class TvStore(context: Context) {
    private val prefs = context.getSharedPreferences("tvs", Context.MODE_PRIVATE)

    fun all(): List<SavedTv> {
        val raw = prefs.getString(KEY_TVS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SavedTv(
                    host = o.getString("host"),
                    name = o.optString("name", o.getString("host")),
                    clientKey = o.optString("clientKey").ifBlank { null },
                    macAddress = o.optString("mac").ifBlank { null },
                    platform = TvPlatform.fromName(o.optString("platform").ifBlank { null }),
                    paired = o.optBoolean("paired", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun lastHost(): String? = prefs.getString(KEY_LAST, null)

    fun last(): SavedTv? = lastHost()?.let { host -> all().firstOrNull { it.host == host } }

    fun save(tv: SavedTv, makeLast: Boolean = true) {
        val tvs = all().filterNot { it.host == tv.host } + tv
        write(tvs)
        if (makeLast) prefs.edit().putString(KEY_LAST, tv.host).apply()
    }

    fun remove(host: String) {
        write(all().filterNot { it.host == host })
        if (lastHost() == host) prefs.edit().remove(KEY_LAST).apply()
    }

    private fun write(tvs: List<SavedTv>) {
        val array = JSONArray()
        tvs.forEach { tv ->
            array.put(JSONObject().apply {
                put("host", tv.host)
                put("name", tv.name)
                tv.clientKey?.let { put("clientKey", it) }
                tv.macAddress?.let { put("mac", it) }
                put("platform", tv.platform.name)
                if (tv.paired) put("paired", true)
            })
        }
        prefs.edit().putString(KEY_TVS, array.toString()).apply()
    }

    /**
     * The certificate this app shows to Android TVs, made on first use. Every TV paired with
     * it remembers it, so it is kept for good (forgetting a TV doesn't change it).
     */
    @Synchronized
    fun androidTvIdentity(): AndroidTvIdentity {
        val key = prefs.getString(KEY_ATV_KEY, null)
        val cert = prefs.getString(KEY_ATV_CERT, null)
        if (key != null && cert != null) {
            runCatching { return AndroidTvIdentity.decode(key, cert) }
        }
        val identity = AndroidTvIdentity.generate()
        val (newKey, newCert) = identity.encode()
        prefs.edit().putString(KEY_ATV_KEY, newKey).putString(KEY_ATV_CERT, newCert).apply()
        return identity
    }

    private companion object {
        const val KEY_TVS = "tvs"
        const val KEY_LAST = "last"
        const val KEY_ATV_KEY = "androidTvKey"
        const val KEY_ATV_CERT = "androidTvCert"
    }
}
