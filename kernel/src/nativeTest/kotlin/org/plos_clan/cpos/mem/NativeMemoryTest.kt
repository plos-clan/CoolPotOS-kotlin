@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeMemoryTest {
    @Test
    fun transfersArraysAndOverlappingNativeRanges() = memScoped {
        val storage = allocArray<UByteVar>(8)
        val memory = object : NativeMemory() {
            override val pointer: CPointer<UByteVar> = storage
            override val size = 8
        }
        val input = ByteArrayBuffer(byteArrayOf(1, 2, 3, 4))
        val source = assertNotNull(input.prepareRead(0, 4))
        assertEquals(4, source.copyTo(0, memory, 0, 4))
        assertEquals(4, memory.copyFrom(2, memory, 0, 4))
        val output = ByteArray(6)
        val destination = assertNotNull(ByteArrayBuffer(output).prepareWrite(0, 6))
        assertEquals(6, destination.copyFrom(0, memory, 0, 6))
        assertContentEquals(byteArrayOf(1, 2, 1, 2, 3, 4), output)
        assertEquals(0, memory.copyTo(8, ByteArray(0), 0, 0))
        assertEquals(0, memory.copyFrom(8, input, 0, 0))
    }
}
