package com.mrwengkb.smarttvcontrol.core

/**
 * Bluetooth remote: the phone acts as a Bluetooth keyboard with media keys (a HID "combo"
 * device), which works without Wi-Fi once the TV has paired with it. This file holds the
 * HID report layout and key codes; the Android side that sends the reports lives in the app.
 *
 * Two reports are used:
 * - report 1, a boot-style keyboard: modifier byte, a reserved byte, six key codes;
 * - report 2, consumer control: one 16-bit usage (volume, media keys, Home, Back...).
 */
object Hid {
    const val KEYBOARD_REPORT_ID = 1
    const val CONSUMER_REPORT_ID = 2

    /** The HID report descriptor the TV reads when it connects. */
    val DESCRIPTOR: ByteArray = bytes(
        // Keyboard
        0x05, 0x01, // Usage Page (Generic Desktop)
        0x09, 0x06, // Usage (Keyboard)
        0xA1, 0x01, // Collection (Application)
        0x85, KEYBOARD_REPORT_ID, // Report ID (1)
        0x05, 0x07, //   Usage Page (Keyboard)
        0x19, 0xE0, //   Usage Minimum (Left Control)
        0x29, 0xE7, //   Usage Maximum (Right GUI)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x01, //   Logical Maximum (1)
        0x75, 0x01, //   Report Size (1)
        0x95, 0x08, //   Report Count (8)
        0x81, 0x02, //   Input (Data, Variable, Absolute): modifiers
        0x95, 0x01, //   Report Count (1)
        0x75, 0x08, //   Report Size (8)
        0x81, 0x01, //   Input (Constant): reserved
        0x95, 0x05, //   Report Count (5)
        0x75, 0x01, //   Report Size (1)
        0x05, 0x08, //   Usage Page (LEDs)
        0x19, 0x01, //   Usage Minimum (Num Lock)
        0x29, 0x05, //   Usage Maximum (Kana)
        0x91, 0x02, //   Output (Data, Variable, Absolute): LEDs
        0x95, 0x01, //   Report Count (1)
        0x75, 0x03, //   Report Size (3)
        0x91, 0x01, //   Output (Constant): LED padding
        0x95, 0x06, //   Report Count (6)
        0x75, 0x08, //   Report Size (8)
        0x15, 0x00, //   Logical Minimum (0)
        0x25, 0x73, //   Logical Maximum (0x73)
        0x05, 0x07, //   Usage Page (Keyboard)
        0x19, 0x00, //   Usage Minimum (0)
        0x29, 0x73, //   Usage Maximum (0x73)
        0x81, 0x00, //   Input (Data, Array): keys
        0xC0, // End Collection
        // Consumer control
        0x05, 0x0C, // Usage Page (Consumer)
        0x09, 0x01, // Usage (Consumer Control)
        0xA1, 0x01, // Collection (Application)
        0x85, CONSUMER_REPORT_ID, // Report ID (2)
        0x15, 0x00, //   Logical Minimum (0)
        0x26, 0xFF, 0x03, //   Logical Maximum (0x3FF)
        0x19, 0x00, //   Usage Minimum (0)
        0x2A, 0xFF, 0x03, //   Usage Maximum (0x3FF)
        0x75, 0x10, //   Report Size (16)
        0x95, 0x01, //   Report Count (1)
        0x81, 0x00, //   Input (Data, Array)
        0xC0, // End Collection
    )

    /** A key press for the keyboard report, plus the modifiers (e.g. Shift) it needs. */
    data class KeyPress(val usage: Int, val modifiers: Int = 0)

    const val MOD_LEFT_SHIFT = 0x02

    fun keyboardReport(press: KeyPress?): ByteArray =
        if (press == null) ByteArray(8) else bytes(press.modifiers, 0, press.usage, 0, 0, 0, 0, 0)

    fun consumerReport(usage: Int): ByteArray = bytes(usage and 0xFF, (usage shr 8) and 0xFF)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}

/** HID keyboard usages (USB HID Usage Tables, page 0x07). */
object HidKey {
    const val A = 0x04
    const val NUM_1 = 0x1E
    const val NUM_0 = 0x27
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val MINUS = 0x2D

    /** US layout. Characters without a key (accents, emoji...) return null. */
    fun forChar(c: Char): Hid.KeyPress? {
        val shift = Hid.MOD_LEFT_SHIFT
        return when (c) {
            in 'a'..'z' -> Hid.KeyPress(A + (c - 'a'))
            in 'A'..'Z' -> Hid.KeyPress(A + (c - 'A'), shift)
            in '1'..'9' -> Hid.KeyPress(NUM_1 + (c - '1'))
            '0' -> Hid.KeyPress(NUM_0)
            ' ' -> Hid.KeyPress(SPACE)
            '\n' -> Hid.KeyPress(ENTER)
            '\t' -> Hid.KeyPress(TAB)
            else -> SHIFTED.indexOf(c).takeIf { it >= 0 }?.let { Hid.KeyPress(SYMBOL_KEYS[it], shift) }
                ?: PLAIN.indexOf(c).takeIf { it >= 0 }?.let { Hid.KeyPress(SYMBOL_KEYS[it]) }
                ?: DIGIT_SHIFTED.indexOf(c).takeIf { it >= 0 }?.let { Hid.KeyPress(NUM_1 + it, shift) }
        }
    }

    // Keys 0x2D..0x38 with and without Shift (0x32 is the non-US # key, which we skip).
    private val SYMBOL_KEYS = intArrayOf(0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38)
    private const val PLAIN = "-=[]\\;'`,./"
    private const val SHIFTED = "_+{}|:\"~<>?"
    private const val DIGIT_SHIFTED = "!@#$%^&*()"
}

/** HID consumer usages (page 0x0C) that TVs understand. */
object HidConsumer {
    const val POWER = 0x030
    const val MENU = 0x040
    const val INFO = 0x060 // "Data On Screen"
    const val RED = 0x069
    const val GREEN = 0x06A
    const val BLUE = 0x06B
    const val YELLOW = 0x06C
    const val GUIDE = 0x08D
    const val CHANNEL_UP = 0x09C
    const val CHANNEL_DOWN = 0x09D
    const val PLAY = 0x0B0
    const val PAUSE = 0x0B1
    const val FAST_FORWARD = 0x0B3
    const val REWIND = 0x0B4
    const val STOP = 0x0B7
    const val PLAY_PAUSE = 0x0CD
    const val MUTE = 0x0E2
    const val VOLUME_UP = 0x0E9
    const val VOLUME_DOWN = 0x0EA
    const val HOME = 0x223 // "AC Home"
    const val BACK = 0x224 // "AC Back"
}

/** What a remote button sends over Bluetooth: a keyboard key or a consumer (media) key. */
sealed interface HidAction {
    data class Key(val press: Hid.KeyPress) : HidAction
    data class Consumer(val usage: Int) : HidAction

    companion object {
        /** Maps the app's [TvButton] names; null for buttons Bluetooth can't send. */
        fun fromButton(name: String): HidAction? = when (name) {
            TvButton.UP -> Key(Hid.KeyPress(HidKey.UP))
            TvButton.DOWN -> Key(Hid.KeyPress(HidKey.DOWN))
            TvButton.LEFT -> Key(Hid.KeyPress(HidKey.LEFT))
            TvButton.RIGHT -> Key(Hid.KeyPress(HidKey.RIGHT))
            TvButton.ENTER -> Key(Hid.KeyPress(HidKey.ENTER))
            TvButton.BACK, TvButton.EXIT -> Consumer(HidConsumer.BACK)
            TvButton.HOME -> Consumer(HidConsumer.HOME)
            TvButton.MENU -> Consumer(HidConsumer.MENU)
            TvButton.INFO -> Consumer(HidConsumer.INFO)
            TvButton.GUIDE -> Consumer(HidConsumer.GUIDE)
            TvButton.RED -> Consumer(HidConsumer.RED)
            TvButton.GREEN -> Consumer(HidConsumer.GREEN)
            TvButton.YELLOW -> Consumer(HidConsumer.YELLOW)
            TvButton.BLUE -> Consumer(HidConsumer.BLUE)
            TvButton.DASH -> Key(Hid.KeyPress(HidKey.MINUS))
            else -> name.singleOrNull()?.takeIf { it.isDigit() }?.let { HidKey.forChar(it) }?.let(::Key)
        }
    }
}
