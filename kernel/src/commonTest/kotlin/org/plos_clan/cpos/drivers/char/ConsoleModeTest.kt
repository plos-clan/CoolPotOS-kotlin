package org.plos_clan.cpos.drivers.char

import org.plos_clan.cpos.drivers.char.tty.ConsoleDisplayMode
import org.plos_clan.cpos.drivers.char.tty.ConsoleKeyboardMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConsoleModeTest {
    @Test
    fun displayModeValidatesTheEntireUnsignedLong() {
        assertEquals(ConsoleDisplayMode.TEXT, ConsoleDisplayMode.from(0uL))
        assertEquals(ConsoleDisplayMode.GRAPHICS, ConsoleDisplayMode.from(1uL))
        assertEquals(ConsoleDisplayMode.TEXT, ConsoleDisplayMode.from(2uL))
        assertEquals(ConsoleDisplayMode.TEXT, ConsoleDisplayMode.from(3uL))
        assertNull(ConsoleDisplayMode.from(4uL))
        assertNull(ConsoleDisplayMode.from(0x1_0000_0001uL))
        assertNull(ConsoleDisplayMode.from(ULong.MAX_VALUE))
    }

    @Test
    fun keyboardModesUseLinuxAbiValuesIncludingOff() {
        assertEquals(ConsoleKeyboardMode.RAW, ConsoleKeyboardMode.from(0u))
        assertEquals(ConsoleKeyboardMode.XLATE, ConsoleKeyboardMode.from(1u))
        assertEquals(ConsoleKeyboardMode.MEDIUMRAW, ConsoleKeyboardMode.from(2u))
        assertEquals(ConsoleKeyboardMode.UNICODE, ConsoleKeyboardMode.from(3u))
        assertEquals(ConsoleKeyboardMode.OFF, ConsoleKeyboardMode.from(4u))
        assertNull(ConsoleKeyboardMode.from(5u))
        assertNull(ConsoleKeyboardMode.from(UInt.MAX_VALUE))
    }
}
