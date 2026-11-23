package com.mrwengkb.weboscontrol.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Finds webOS TVs on the LAN with an SSDP M-SEARCH for LG's second-screen service. */
object SsdpDiscovery {
    const val SEARCH_TARGET = "urn:lge-com:service:webos-second-screen:1"
    private const val MULTICAST_HOST = "239.255.255.250"
    private const val MULTICAST_PORT = 1900

    val searchMessage: String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $MULTICAST_HOST:$MULTICAST_PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $SEARCH_TARGET\r\n" +
            "\r\n"

    data class Response(val host: String, val headers: Map<String, String>) {
        val location: String? get() = headers["location"]
    }

    /** Parses one SSDP reply. Returns null when it is not from a webOS TV. */
    fun parseResponse(text: String, host: String): Response? {
        val lines = text.split("\r\n", "\n")
        if (lines.isEmpty() || !lines[0].startsWith("HTTP/1.1 200", ignoreCase = true)) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx <= 0) null else line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim()
        }.toMap()
        val st = headers["st"].orEmpty()
        val server = headers["server"].orEmpty()
        val isWebOs = st.equals(SEARCH_TARGET, ignoreCase = true) || server.contains("webos", ignoreCase = true)
        return if (isWebOs) Response(host, headers) else null
    }

    private fun xmlTag(xml: String, tag: String): String? =
        Regex("<$tag>\\s*(.*?)\\s*</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")?.replace("&apos;", "'")
            ?.takeIf { it.isNotBlank() }

    fun parseFriendlyName(xml: String): String? = xmlTag(xml, "friendlyName")
    fun parseModelName(xml: String): String? = xmlTag(xml, "modelName")

    /**
     * Broadcasts M-SEARCH and collects replies for [timeoutMs]. On Android, hold a
     * WifiManager.MulticastLock while this runs.
     */
    suspend fun discover(
        timeoutMs: Long = 3500,
        httpClient: OkHttpClient = LanHttp.client,
        target: InetSocketAddress = InetSocketAddress(InetAddress.getByName(MULTICAST_HOST), MULTICAST_PORT),
    ): List<TvDevice> {
        val responses = withContext(Dispatchers.IO) { search(timeoutMs, target) }
        val describeClient = httpClient.newBuilder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        return coroutineScope {
            responses.map { response -> async(Dispatchers.IO) { describe(response, describeClient) } }.awaitAll()
        }.sortedBy { it.name.lowercase() }
    }

    private fun search(timeoutMs: Long, target: InetSocketAddress): List<Response> {
        val found = LinkedHashMap<String, Response>()
        val payload = searchMessage.toByteArray(Charsets.US_ASCII)
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            val deadline = System.currentTimeMillis() + timeoutMs
            var sends = 0
            var nextSend = 0L
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                if (sends < 3 && System.currentTimeMillis() >= nextSend) {
                    runCatching { socket.send(DatagramPacket(payload, payload.size, target)) }
                    sends++
                    nextSend = System.currentTimeMillis() + 700
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val host = packet.address.hostAddress ?: continue
                val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                parseResponse(text, host)?.let { found.putIfAbsent(host, it) }
            }
        }
        return found.values.toList()
    }

    private fun describe(response: Response, client: OkHttpClient): TvDevice {
        val xml = response.location?.let { url ->
            runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.string() }
            }.getOrNull()
        }
        return TvDevice(
            host = response.host,
            name = xml?.let(::parseFriendlyName) ?: "LG TV (${response.host})",
            modelName = xml?.let(::parseModelName),
        )
    }
}
