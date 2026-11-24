package com.mrwengkb.smarttvcontrol.core

/** A webOS TV found on the local network (or entered by hand). */
data class TvDevice(
    val host: String,
    val name: String = host,
    val modelName: String? = null,
)

class WebOsException(message: String, cause: Throwable? = null) : Exception(message, cause)
