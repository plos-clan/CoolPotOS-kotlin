package org.plos_clan.cpos.drivers.char

import org.plos_clan.cpos.drivers.char.tty.TerminalBuffer
import org.plos_clan.cpos.mem.ByteArrayBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalBufferTest {
    @Test
    fun preservesRecordsAcrossPartialReadsAndWraparound() {
        val buffer = TerminalBuffer(8)
        val destination = ByteArray(8)
        val prepared = ByteArrayBuffer(destination).prepareWrite(0, destination.size)!!
        buffer.write("abc".encodeToByteArray(), 0, 3)
        buffer.offer(10, TerminalBuffer.LINE)
        buffer.write("de".encodeToByteArray(), 0, 2)
        buffer.offer(10, TerminalBuffer.LINE)
        assertEquals(2, buffer.read(prepared, 0, 2, true))
        assertEquals("ab", destination.decodeToString(0, 2))
        assertEquals(2, buffer.read(prepared, 0, 8, true))
        assertEquals("c\n", destination.decodeToString(0, 2))
        buffer.write("fg".encodeToByteArray(), 0, 2)
        buffer.offer(10, TerminalBuffer.LINE)
        assertEquals(3, buffer.read(prepared, 0, 8, true))
        assertEquals("de\n", destination.decodeToString(0, 3))
        assertEquals(3, buffer.read(prepared, 0, 8, true))
        assertEquals("fg\n", destination.decodeToString(0, 3))
    }

    @Test
    fun eofConsumesNoByteAndDoesNotLeakIntoTheNextRead() {
        val buffer = TerminalBuffer(8)
        val destination = ByteArrayBuffer(ByteArray(8)).prepareWrite(0, 8)!!
        buffer.offer(97)
        buffer.offer(4, TerminalBuffer.EOF)
        buffer.offer(4, TerminalBuffer.EOF)
        assertEquals(1, buffer.available)
        assertEquals(1, buffer.read(destination, 0, 1, true))
        assertEquals(1, buffer.committed)
        assertEquals(0, buffer.read(destination, 0, 8, true))
        assertEquals(0, buffer.size)
    }

    @Test
    fun fullBufferKeepsExistingDataAndSupportsModeChanges() {
        val buffer = TerminalBuffer(8)
        val destination = ByteArray(8)
        val prepared = ByteArrayBuffer(destination).prepareWrite(0, 8)!!
        assertEquals(8, buffer.write("abcdefghij".encodeToByteArray(), 0, 10))
        assertFalse(buffer.offer(10, TerminalBuffer.LINE))
        assertEquals(0, buffer.read(prepared, 0, 8, true))
        buffer.setCanonical(true)
        assertEquals(8, buffer.read(prepared, 0, 8, true))
        assertEquals("abcdefgh", destination.decodeToString())
        buffer.offer(4, TerminalBuffer.EOF)
        buffer.setCanonical(false)
        assertEquals(1, buffer.read(prepared, 0, 8, false))
        assertEquals(4, destination[0].toInt())
    }

    @Test
    fun utf8ErasureCannotCrossACommittedRecord() {
        val buffer = TerminalBuffer(16)
        buffer.offer(10, TerminalBuffer.LINE)
        val bytes = "中".encodeToByteArray()
        buffer.write(bytes, 0, bytes.size)
        assertEquals(3, buffer.erase(true))
        assertEquals(0, buffer.erase(true))
        assertEquals(1, buffer.committed)
        assertTrue(buffer.offer(10, TerminalBuffer.LINE))
    }
}
