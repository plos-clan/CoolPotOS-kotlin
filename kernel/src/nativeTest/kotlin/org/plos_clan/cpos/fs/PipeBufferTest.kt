package org.plos_clan.cpos.fs

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.plos_clan.cpos.fs.vfs.ByteCircularBuffer
import org.plos_clan.cpos.mem.ByteArrayBuffer

class PipeBufferTest {
    @Test
    fun preservesDataAcrossCapacityAndWrapAround() {
        val input = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val output = ByteArray(input.size)
        val source = assertNotNull(ByteArrayBuffer(input).prepareRead(0, input.size))
        val destination = assertNotNull(ByteArrayBuffer(output).prepareWrite(0, output.size))
        val buffer = ByteCircularBuffer(5)

        assertEquals(5, buffer.write(source, 0, input.size))
        assertEquals(5, buffer.size)
        assertEquals(0, buffer.remaining)
        assertEquals(3, buffer.read(destination, 0, 3))
        assertEquals(3, buffer.write(source, 5, 3))
        assertEquals(5, buffer.read(destination, 3, 5))
        assertEquals(0, buffer.size)
        assertEquals(5, buffer.remaining)
        assertContentEquals(input, output)
    }

    @Test
    fun peekDoesNotConsumeBytes() {
        val input = byteArrayOf(1, 2, 3, 4)
        val first = ByteArray(input.size)
        val second = ByteArray(input.size)
        val source = assertNotNull(ByteArrayBuffer(input).prepareRead(0, input.size))
        val firstDestination = assertNotNull(ByteArrayBuffer(first).prepareWrite(0, first.size))
        val secondDestination = assertNotNull(ByteArrayBuffer(second).prepareWrite(0, second.size))
        val buffer = ByteCircularBuffer(input.size)

        assertEquals(input.size, buffer.write(source, 0, input.size))
        assertEquals(input.size, buffer.read(firstDestination, 0, first.size, peek = true))
        assertEquals(input.size, buffer.size)
        assertEquals(input.size, buffer.read(secondDestination, 0, second.size))
        assertContentEquals(input, first)
        assertContentEquals(input, second)
    }

    @Test
    fun growthPreservesWrappedContents() {
        val initial = byteArrayOf(1, 2)
        val appended = byteArrayOf(3, 4, 5)
        val discarded = ByteArray(1)
        val output = ByteArray(4)
        val buffer = ByteCircularBuffer(2)

        assertEquals(2, buffer.write(assertNotNull(ByteArrayBuffer(initial).prepareRead(0, 2)), 0, 2))
        assertEquals(
            1,
            buffer.read(assertNotNull(ByteArrayBuffer(discarded).prepareWrite(0, 1)), 0, 1),
        )
        buffer.ensureCapacity(5)
        assertEquals(3, buffer.write(assertNotNull(ByteArrayBuffer(appended).prepareRead(0, 3)), 0, 3))
        assertEquals(4, buffer.read(assertNotNull(ByteArrayBuffer(output).prepareWrite(0, 4)), 0, 4))
        assertContentEquals(byteArrayOf(2, 3, 4, 5), output)
    }
}
