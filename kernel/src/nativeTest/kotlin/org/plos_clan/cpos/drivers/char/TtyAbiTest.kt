package org.plos_clan.cpos.drivers.char

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.plos_clan.cpos.drivers.char.tty.Termios
import org.plos_clan.cpos.drivers.char.tty.Termios2
import org.plos_clan.cpos.drivers.char.tty.WinSize
import org.plos_clan.cpos.utils.LittleEndianBuffer

class TtyAbiTest {
    @Test
    fun roundTripsWindowSize() {
        val original = WinSize(24, 80, 640, 480)
        val bytes = original.toNativeBytes()

        assertContentEquals(
            byteArrayOf(0x18, 0, 0x50, 0, 0x80.toByte(), 0x02, 0xE0.toByte(), 0x01),
            bytes,
        )

        val decoded = WinSize(0, 0, 0, 0)
        assertTrue(decoded.updateFromNativeBytes(bytes))
        assertEquals(original, decoded)
        assertFalse(decoded.updateFromNativeBytes(ByteArray(bytes.size - 1)))
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripsTermiosLayout() {
        val controls = ByteArray(19) { index -> (index + 1).toByte() }
        val original = Termios(
            cIflag = 0x0102_0304,
            cOflag = 0x1122_3344,
            cCflag = 0x2233_4455,
            cLflag = 0x3344_5566,
            cLine = 5,
            cCc = controls,
        )
        val bytes = original.toNativeBytes()
        val input = LittleEndianBuffer(bytes)

        assertEquals(Termios.NATIVE_SIZE, bytes.size)
        assertEquals(0x0102_0304u, input.readU32(0))
        assertEquals(0x1122_3344u, input.readU32(4))
        assertEquals(0x2233_4455u, input.readU32(8))
        assertEquals(0x3344_5566u, input.readU32(12))
        assertEquals(5, bytes[16].toInt())
        assertContentEquals(controls, bytes.copyOfRange(17, bytes.size))

        val decoded = Termios(0, 0, 0, 0, 0, ByteArray(19))
        assertTrue(decoded.updateFromNativeBytes(bytes))
        assertContentEquals(bytes, decoded.toNativeBytes())
        assertFalse(decoded.updateFromNativeBytes(ByteArray(Termios.NATIVE_SIZE - 1)))
        assertContentEquals(bytes, decoded.toNativeBytes())
    }

    @Test
    fun roundTripsTermios2Speeds() {
        val controls = ByteArray(19) { index -> (0x20 + index).toByte() }
        val original = Termios2(
            cIflag = 1,
            cOflag = 2,
            cCflag = 3,
            cLflag = 4,
            cLine = 5,
            cCc = controls,
            cIspeed = 115_200,
            cOspeed = 230_400,
        )
        val bytes = original.toNativeBytes()
        val input = LittleEndianBuffer(bytes)

        assertEquals(Termios2.NATIVE_SIZE, bytes.size)
        assertContentEquals(controls, bytes.copyOfRange(17, 36))
        assertEquals(115_200u, input.readU32(36))
        assertEquals(230_400u, input.readU32(40))

        val decoded = Termios2(0, 0, 0, 0, 0, ByteArray(19), 0, 0)
        assertTrue(decoded.updateFromNativeBytes(bytes))
        assertContentEquals(bytes, decoded.toNativeBytes())
        assertFalse(decoded.updateFromNativeBytes(ByteArray(Termios2.NATIVE_SIZE + 1)))
        assertContentEquals(bytes, decoded.toNativeBytes())
    }

    @Test
    fun requiresExactControlCharacterCounts() {
        assertFailsWith<IllegalArgumentException> {
            Termios(0, 0, 0, 0, 0, ByteArray(18))
        }
        assertFailsWith<IllegalArgumentException> {
            Termios2(0, 0, 0, 0, 0, ByteArray(20), 0, 0)
        }
    }
}
