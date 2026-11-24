package com.mrwengkb.smarttvcontrol.core

/** The operating system of a TV, which decides the protocol the app speaks to it. */
enum class TvPlatform(val label: String) {
    WEBOS("LG webOS"),
    ANDROID_TV("Google TV / Android TV");

    companion object {
        /** Reads a stored name; anything unknown (including TVs saved before this existed) is webOS. */
        fun fromName(name: String?): TvPlatform = entries.firstOrNull { it.name == name } ?: WEBOS
    }
}

/** A TV found on the local network (or entered by hand). */
data class TvDevice(
    val host: String,
    val name: String = host,
    val modelName: String? = null,
    val platform: TvPlatform = TvPlatform.WEBOS,
)

class WebOsException(message: String, cause: Throwable? = null) : Exception(message, cause)
