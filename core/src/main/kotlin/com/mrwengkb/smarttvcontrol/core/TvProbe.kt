package com.mrwengkb.smarttvcontrol.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Works out a TV's OS from the ports it has open: webOS TVs listen on 3000/3001 for SSAP, and
 * Google/Android TVs on 6466/6467 for the remote protocol. Returns null when none answer (for
 * example when the TV is off), so the user can choose.
 */
object TvProbe {
    suspend fun detect(
        host: String,
        timeoutMs: Int = 1_500,
        webOsPorts: List<Int> = listOf(3001, 3000),
        androidTvPorts: List<Int> = listOf(AndroidTvClient.PORT, AndroidTvPairing.PORT),
    ): TvPlatform? = coroutineScope {
        val webOs = async { webOsPorts.anyOpen(host, timeoutMs) }
        val androidTv = async { androidTvPorts.anyOpen(host, timeoutMs) }
        when {
            androidTv.await() -> TvPlatform.ANDROID_TV
            webOs.await() -> TvPlatform.WEBOS
            else -> null
        }
    }

    private suspend fun List<Int>.anyOpen(host: String, timeoutMs: Int): Boolean = coroutineScope {
        map { port -> async { isOpen(host, port, timeoutMs) } }.any { it.await() }
    }

    suspend fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true } }.getOrDefault(false)
    }
}
