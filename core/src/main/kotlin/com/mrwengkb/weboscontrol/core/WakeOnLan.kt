package com.mrwengkb.weboscontrol.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sends Wake-on-LAN magic packets. The TV needs "Turn on via Wi-Fi / Mobile TV On" enabled. */
object WakeOnLan {
    fun parseMac(mac: String): ByteArray {
        val hex = mac.filter { it.isLetterOrDigit() }
        require(hex.length == 12 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "Not a MAC address: $mac"
        }
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun magicPacket(mac: String): ByteArray {
        val macBytes = parseMac(mac)
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (rep in 0 until 16) macBytes.copyInto(packet, 6 + rep * 6)
        return packet
    }

    /**
     * Broadcasts the packet a few times on ports 9 and 7, to the global broadcast address and to
     * any extra (for example subnet) broadcast addresses given.
     */
    suspend fun wake(mac: String, extraBroadcasts: List<InetAddress> = emptyList()) =
        withContext(Dispatchers.IO) {
            val data = magicPacket(mac)
            val targets = listOf(InetAddress.getByName("255.255.255.255")) + extraBroadcasts
            DatagramSocket().use { socket ->
                socket.broadcast = true
                repeat(3) {
                    for (address in targets) for (port in intArrayOf(9, 7)) {
                        runCatching { socket.send(DatagramPacket(data, data.size, address, port)) }
                    }
                }
            }
        }
}
