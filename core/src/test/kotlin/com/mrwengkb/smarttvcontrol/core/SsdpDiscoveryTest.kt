package com.mrwengkb.smarttvcontrol.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.concurrent.thread

class SsdpDiscoveryTest {
    private fun reply(location: String) = "HTTP/1.1 200 OK\r\n" +
        "CACHE-CONTROL: max-age=1800\r\n" +
        "LOCATION: $location\r\n" +
        "SERVER: Linux/4.4.84 UPnP/1.0 webOS/5.0 (LGE WebOS TV)\r\n" +
        "ST: urn:lge-com:service:webos-second-screen:1\r\n" +
        "USN: uuid:abc::urn:lge-com:service:webos-second-screen:1\r\n\r\n"

    @Test
    fun parsesWebOsResponse() {
        val r = SsdpDiscovery.parseResponse(reply("http://192.168.1.20:1225/"), "192.168.1.20")
        assertNotNull(r)
        assertEquals("http://192.168.1.20:1225/", r!!.location)
    }

    @Test
    fun ignoresOtherDevices() {
        val other = "HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nSERVER: Linux UPnP/1.0 Sonos\r\n\r\n"
        assertNull(SsdpDiscovery.parseResponse(other, "192.168.1.30"))
        assertNull(SsdpDiscovery.parseResponse("NOTIFY * HTTP/1.1\r\n\r\n", "192.168.1.30"))
    }

    @Test
    fun parsesDescription() {
        val xml = "<root><device><friendlyName>[LG] webOS TV OLED55C1 &amp; co</friendlyName>" +
            "<modelName>OLED55C1PUB</modelName></device></root>"
        assertEquals("[LG] webOS TV OLED55C1 & co", SsdpDiscovery.parseFriendlyName(xml))
        assertEquals("OLED55C1PUB", SsdpDiscovery.parseModelName(xml))
    }

    @Test
    fun discoversFakeTvOverUdp() = runBlocking {
        val http = MockWebServer()
        http.enqueue(MockResponse().setBody("<root><device><friendlyName>Living Room TV</friendlyName></device></root>"))
        http.start()
        val responder = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val worker = thread {
            val buf = ByteArray(1024)
            runCatching {
                val packet = DatagramPacket(buf, buf.size)
                responder.receive(packet)
                val text = String(packet.data, 0, packet.length)
                if (text.contains(SsdpDiscovery.SEARCH_TARGET)) {
                    val body = reply(http.url("/desc.xml").toString()).toByteArray()
                    responder.send(DatagramPacket(body, body.size, packet.socketAddress))
                }
            }
        }
        val found = SsdpDiscovery.discover(
            timeoutMs = 1500,
            target = InetSocketAddress(InetAddress.getLoopbackAddress(), responder.localPort),
        )
        responder.close()
        worker.join()
        http.shutdown()
        assertEquals(1, found.size)
        assertEquals("Living Room TV", found[0].name)
    }
}
