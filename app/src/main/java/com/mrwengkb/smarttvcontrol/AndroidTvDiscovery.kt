package com.mrwengkb.smarttvcontrol

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.mrwengkb.smarttvcontrol.core.TvDevice
import com.mrwengkb.smarttvcontrol.core.TvPlatform
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * Finds Google TV and Android TV devices with mDNS: they advertise the remote protocol as
 * `_androidtvremote2._tcp`. Uses Android's NsdManager, which needs no extra permission.
 */
class AndroidTvDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    suspend fun discover(timeoutMs: Long = 3_500): List<TvDevice> {
        val found = LinkedHashMap<String, NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(found) { found.putIfAbsent(info.serviceName, info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        val started = runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        if (!started) return emptyList()
        try {
            delay(timeoutMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
        val services = synchronized(found) { found.values.toList() }
        // The classic resolve API handles one request at a time, so resolve them in turn.
        return services.mapNotNull { info ->
            val resolved = withTimeoutOrNull(2_500) { resolve(info) } ?: return@mapNotNull null
            @Suppress("DEPRECATION")
            val address = resolved.host ?: return@mapNotNull null
            if (address !is Inet4Address && address.isLinkLocalAddress) return@mapNotNull null
            TvDevice(
                host = address.hostAddress ?: return@mapNotNull null,
                name = info.serviceName,
                platform = TvPlatform.ANDROID_TV,
            )
        }.distinctBy { it.host }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (cont.isActive) cont.resume(serviceInfo)
            }
        }
        runCatching { nsd.resolveService(info, listener) }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    private companion object {
        const val SERVICE_TYPE = "_androidtvremote2._tcp"
    }
}
