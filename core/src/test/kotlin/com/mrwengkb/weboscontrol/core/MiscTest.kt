package com.mrwengkb.weboscontrol.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiscTest {
    @Test
    fun magicPacketLayout() {
        val p = WakeOnLan.magicPacket("A8:23:FE:01:02:0f")
        assertEquals(102, p.size)
        assertEquals(0xFF.toByte(), p[5])
        assertEquals(0xA8.toByte(), p[6])
        assertEquals(0x0F.toByte(), p[101])
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsBadMac() {
        WakeOnLan.parseMac("not-a-mac")
    }

    @Test
    fun parsesOldVolumeShape() {
        val p = Json.parseToJsonElement("""{"volume":9,"muted":true}""").jsonObject
        assertEquals(VolumeStatus(9, true), parseVolume(p))
    }

    @Test
    fun parsesAppsAndInputs() {
        val apps = parseApps(Json.parseToJsonElement(
            """{"launchPoints":[{"id":"netflix","title":"Netflix","icon":"http://x/n.png"},{"id":"youtube.leanback.v4","title":"YouTube"}]}"""
        ).jsonObject)
        assertEquals(listOf("Netflix", "YouTube"), apps.map { it.title })
        val inputs = parseInputs(Json.parseToJsonElement(
            """{"devices":[{"id":"HDMI_1","label":"PlayStation","connected":true},{"id":"HDMI_2","label":"HDMI 2","connected":false}]}"""
        ).jsonObject)
        assertEquals(listOf("HDMI_1", "HDMI_2"), inputs.map { it.id })
        assertEquals(false, inputs[1].connected)
    }

    @Test
    fun picksConnectedMac() {
        val conn = Json.parseToJsonElement(
            """{"wiredInfo":{"state":"disconnected","macAddress":"aa:aa:aa:aa:aa:aa"},"wifiInfo":{"state":"connected","macAddress":"BB:BB:BB:BB:BB:BB"}}"""
        ).jsonObject
        assertEquals("bb:bb:bb:bb:bb:bb", parseMac(conn, null))
        assertNull(parseMac(null, null))
    }
}
