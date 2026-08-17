package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.ByteArrayBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PipeBufferTest {
    @Test
    fun preservesDataAcrossCapacityAndWrapAround() {
        val input = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val output = ByteArray(input.size)
        val source = assertNotNull(ByteArrayBuffer(input).prepareRead(0, input.size))
        val destination = assertNotNull(ByteArrayBuffer(output).prepareWrite(0, output.size))
        val buffer = PipeBuffer(5)

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
}
