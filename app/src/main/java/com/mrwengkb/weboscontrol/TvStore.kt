package com.mrwengkb.weboscontrol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A TV the app has paired with. */
data class SavedTv(
    val host: String,
    val name: String,
    val clientKey: String? = null,
    val macAddress: String? = null,
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
            })
        }
        prefs.edit().putString(KEY_TVS, array.toString()).apply()
    }

    private companion object {
        const val KEY_TVS = "tvs"
        const val KEY_LAST = "last"
    }
}
