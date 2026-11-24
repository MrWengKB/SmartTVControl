package com.mrwengkb.smarttvcontrol.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HidKeysTest {
    @Test
    fun lettersDigitsAndShiftedSymbols() {
        assertEquals(Hid.KeyPress(0x04), HidKey.forChar('a'))
        assertEquals(Hid.KeyPress(0x1D, Hid.MOD_LEFT_SHIFT), HidKey.forChar('Z'))
        assertEquals(Hid.KeyPress(0x1E), HidKey.forChar('1'))
        assertEquals(Hid.KeyPress(0x27), HidKey.forChar('0'))
        assertEquals(Hid.KeyPress(0x1F, Hid.MOD_LEFT_SHIFT), HidKey.forChar('@'))
        assertEquals(Hid.KeyPress(0x27, Hid.MOD_LEFT_SHIFT), HidKey.forChar(')'))
        assertEquals(Hid.KeyPress(0x37), HidKey.forChar('.'))
        assertEquals(Hid.KeyPress(0x38, Hid.MOD_LEFT_SHIFT), HidKey.forChar('?'))
        assertEquals(Hid.KeyPress(0x34, Hid.MOD_LEFT_SHIFT), HidKey.forChar('"'))
        assertEquals(Hid.KeyPress(0x2C), HidKey.forChar(' '))
        assertNull(HidKey.forChar('é'))
    }

    @Test
    fun reports() {
        assertArrayEquals(byteArrayOf(2, 0, 0x04, 0, 0, 0, 0, 0), Hid.keyboardReport(Hid.KeyPress(0x04, 2)))
        assertArrayEquals(ByteArray(8), Hid.keyboardReport(null))
        assertArrayEquals(byteArrayOf(0x23, 0x02), Hid.consumerReport(HidConsumer.HOME))
        assertArrayEquals(byteArrayOf(0xE9.toByte(), 0x00), Hid.consumerReport(HidConsumer.VOLUME_UP))
    }

    @Test
    fun buttons() {
        assertEquals(HidAction.Key(Hid.KeyPress(HidKey.UP)), HidAction.fromButton(TvButton.UP))
        assertEquals(HidAction.Consumer(HidConsumer.BACK), HidAction.fromButton(TvButton.BACK))
        assertEquals(HidAction.Key(Hid.KeyPress(0x22)), HidAction.fromButton(TvButton.number(5)))
        assertNull(HidAction.fromButton("NOPE"))
    }

    @Test
    fun descriptorCollectionsBalance() {
        val d = Hid.DESCRIPTOR
        assertEquals(2, d.count { it == 0xA1.toByte() })
        assertEquals(0xC0.toByte(), d.last())
    }
}
