@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set

class ByteArrayBufferTest {
    @Test
    fun preparesOnlyValidRanges() {
        val buffer = ByteArrayBuffer(ByteArray(4))

        assertNotNull(buffer.prepareRead(0, 4))
        assertNotNull(buffer.prepareWrite(4, 0))
        assertNull(buffer.prepareRead(-1, 1))
        assertNull(buffer.prepareRead(0, -1))
        assertNull(buffer.prepareWrite(3, 2))
        assertNull(buffer.prepareWrite(5, 0))
    }

    @Test
    fun copiesAndFillsByteArrayRanges() {
        val source = byteArrayOf(1, 2, 3, 4, 5)
        val destination = ByteArray(6)
        val destinationBuffer = ByteArrayBuffer(destination)

        val preparedSource = assertNotNull(ByteArrayBuffer(source).prepareRead(1, 3))
        assertEquals(3, preparedSource.copyTo(1, destination, 2, 3))
        assertContentEquals(byteArrayOf(0, 0, 2, 3, 4, 0), destination)

        val preparedDestination = assertNotNull(destinationBuffer.prepareWrite(1, 3))
        assertEquals(3, preparedDestination.copyFrom(1, byteArrayOf(9, 8, 7, 6), 1, 3))
        assertEquals(
            2,
            assertNotNull(destinationBuffer.prepareWrite(4, 2)).fill(4, 2, 0x7F),
        )
        assertContentEquals(byteArrayOf(0, 8, 7, 6, 0x7F, 0x7F), destination)
    }

    @Test
    fun copiesFromNativeMemory() = memScoped {
        val source = allocArray<UByteVar>(3)
        source[0] = 0xAAu
        source[1] = 0xBBu
        source[2] = 0xCCu
        val destination = ByteArray(5)

        assertEquals(3, ByteArrayBuffer(destination).copyFrom(1, source, 3))
        assertContentEquals(
            byteArrayOf(0, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0),
            destination,
        )
    }

    @Test
    fun rejectsInvalidCopyRanges() {
        val buffer = ByteArrayBuffer(ByteArray(4))

        assertFailsWith<IllegalArgumentException> {
            buffer.copyTo(0, ByteArray(2), 0, 3)
        }
        assertFailsWith<IllegalArgumentException> {
            buffer.copyFrom(0, ByteArray(2), 0, 3)
        }
        assertFailsWith<IllegalArgumentException> { buffer.fill(3, 2) }
    }
}
