package org.plos_clan.cpos.drivers.input

import org.plos_clan.cpos.drivers.char.tty.ConsoleKeyboardMode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ConsoleKeyEncoderTest {
    private val encoder = ConsoleKeyEncoder()
    private val output = ByteArray(ConsoleKeyEncoder.MAX_BYTES)

    private fun encode(code: Int, mode: ConsoleKeyboardMode, action: KeyAction = KeyAction.PRESSED): ByteArray =
        output.copyOf(encoder.encode(code, action, mode, output))

    @Test
    fun emitsSet1MakeBreakAndExtendedCodes() {
        val mode = ConsoleKeyboardMode.RAW
        assertContentEquals(byteArrayOf(0x1E), encode(30, mode))
        assertContentEquals(byteArrayOf(0x1E), encode(30, mode, KeyAction.REPEATED))
        assertContentEquals(byteArrayOf(0x9E.toByte()), encode(30, mode, KeyAction.RELEASED))
        assertContentEquals(byteArrayOf(0xE0.toByte(), 0x48), encode(103, mode))
        assertContentEquals(byteArrayOf(0xE0.toByte(), 0xC8.toByte()), encode(103, mode, KeyAction.RELEASED))
        assertContentEquals(byteArrayOf(0xE1.toByte(), 0x1D, 0x45), encode(119, mode))
        assertContentEquals(byteArrayOf(0xE1.toByte(), 0x9D.toByte(), 0xC5.toByte()), encode(119, mode, KeyAction.RELEASED))
    }

    @Test
    fun emitsPrintScreenAndAltSysRq() {
        val mode = ConsoleKeyboardMode.RAW
        assertContentEquals(byteArrayOf(0xE0.toByte(), 0x2A, 0xE0.toByte(), 0x37), encode(99, mode))
        encode(56, mode)
        assertContentEquals(byteArrayOf(0x54), encode(99, mode))
        assertContentEquals(byteArrayOf(0xD4.toByte()), encode(99, mode, KeyAction.RELEASED))
    }

    @Test
    fun mediumRawEncodesKeycodesBeyondTheKeymap() {
        val mode = ConsoleKeyboardMode.MEDIUMRAW
        assertContentEquals(byteArrayOf(30), encode(30, mode))
        assertContentEquals(byteArrayOf(0x9E.toByte()), encode(30, mode, KeyAction.RELEASED))
        assertContentEquals(byteArrayOf(0, 0x81.toByte(), 0x80.toByte()), encode(128, mode))
        assertContentEquals(byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0xFF.toByte()), encode(0x3FFF, mode, KeyAction.RELEASED))
        assertEquals(0, encode(0, mode).size)
        assertEquals(0, encode(0x4000, mode).size)
    }

    @Test
    fun modeChangesPreserveHeldModifiersAndOffSuppressesInput() {
        assertEquals(0, encode(42, ConsoleKeyboardMode.OFF).size)
        assertEquals(0, encode(30, ConsoleKeyboardMode.OFF).size)
        assertContentEquals(byteArrayOf('A'.code.toByte()), encode(30, ConsoleKeyboardMode.UNICODE))
        encode(42, ConsoleKeyboardMode.RAW, KeyAction.RELEASED)
        assertContentEquals(byteArrayOf('a'.code.toByte()), encode(30, ConsoleKeyboardMode.XLATE))
        assertEquals(0, encode(30, ConsoleKeyboardMode.XLATE, KeyAction.RELEASED).size)
    }

    @Test
    fun releasingOneShiftKeepsTheOtherHeldAndCapsLockInvertsShift() {
        val mode = ConsoleKeyboardMode.XLATE
        encode(42, mode)
        encode(54, mode)
        encode(42, mode, KeyAction.RELEASED)
        assertContentEquals(byteArrayOf('A'.code.toByte()), encode(30, mode))
        encode(58, mode)
        assertContentEquals(byteArrayOf('a'.code.toByte()), encode(30, mode))
        encode(54, mode, KeyAction.RELEASED)
        assertContentEquals(byteArrayOf('A'.code.toByte()), encode(30, mode))
    }

    @Test
    fun translatedControlAndAltSequences() {
        val mode = ConsoleKeyboardMode.UNICODE
        encode(29, mode)
        assertContentEquals(byteArrayOf(1), encode(30, mode))
        assertContentEquals(byteArrayOf(0), encode(57, mode))
        encode(29, mode, KeyAction.RELEASED)
        encode(56, mode)
        assertContentEquals(byteArrayOf(0x1B, 'a'.code.toByte()), encode(30, mode))
        assertContentEquals("\u001B\u001B[24~".encodeToByteArray(), encode(88, mode))
    }
}
