package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.vfs.ByteCircularBuffer
import org.plos_clan.cpos.mem.ByteArrayBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PipeBufferTest {
    @Test
    fun largeCapacitySupportsSmallTransfers() {
        val buffer = ByteCircularBuffer(Int.MAX_VALUE)
        val input = byteArrayOf(1, 2, 3)
        val output = ByteArray(input.size)
        val sourceBuffer = ByteArrayBuffer(input)
        val destinationBuffer = ByteArrayBuffer(output)
        val source = assertNotNull(sourceBuffer.prepareRead(0, input.size))
        val destination = assertNotNull(destinationBuffer.prepareWrite(0, output.size))

        repeat(8) {
            assertEquals(input.size, buffer.write(source, 0, input.size))
            assertEquals(input.size, buffer.read(destination, 0, output.size))
            assertContentEquals(input, output)
            assertEquals(Int.MAX_VALUE, buffer.remaining)
        }
    }

    @Test
    fun reservationGrowthPreservesWrappedDataAndPartialCommits() {
        val buffer = ByteCircularBuffer(12)
        val input = byteArrayOf(1, 2, 3, 4, 5)
        val sourceBuffer = ByteArrayBuffer(input)
        val source = assertNotNull(sourceBuffer.prepareRead(0, input.size))
        assertEquals(3, buffer.write(source, 0, 3))
        assertEquals(2, buffer.discard(2))
        assertEquals(2, buffer.write(source, 3, 2))

        val reservation = buffer.reserveWrite(7)
        val appended = byteArrayOf(6, 7, 8, 9, 10, 11, 12)
        assertEquals(7, reservation.destination.copyFrom(0, appended, 0, 7))
        assertEquals(3, buffer.size)
        assertEquals(1, buffer.discard(1))
        reservation.commit(3)

        val output = ByteArray(5)
        val destinationBuffer = ByteArrayBuffer(output)
        val destination = assertNotNull(destinationBuffer.prepareWrite(0, output.size))
        assertEquals(5, buffer.read(destination, 0, output.size))
        assertContentEquals(byteArrayOf(4, 5, 6, 7, 8), output)
        assertEquals(12, buffer.remaining)
    }

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

    @Test
    fun preparedSourceReadsWrappedContentsWithoutConsumingThem() {
        val buffer = ByteCircularBuffer(5)
        val discarded = ByteArray(3)
        val output = ByteArray(4)
        val first = ByteArrayBuffer(byteArrayOf(1, 2, 3, 4, 5))
        val second = ByteArrayBuffer(byteArrayOf(6, 7, 8))

        assertEquals(5, buffer.write(assertNotNull(first.prepareRead(0, 5)), 0, 5))
        assertEquals(
            3,
            buffer.read(
                assertNotNull(ByteArrayBuffer(discarded).prepareWrite(0, 3)),
                0,
                3,
            ),
        )
        assertEquals(3, buffer.write(assertNotNull(second.prepareRead(0, 3)), 0, 3))

        val source = assertNotNull(buffer.prepareRead(0, 4))
        assertEquals(4, source.copyTo(0, output, 0, 4))
        assertContentEquals(byteArrayOf(4, 5, 6, 7), output)
        assertEquals(5, buffer.size)
    }

    @Test
    fun writeReservationPublishesOnlyCommittedBytes() {
        val buffer = ByteCircularBuffer(5)
        val initial = ByteArrayBuffer(byteArrayOf(1, 2, 3))
        val appended = byteArrayOf(4, 5)
        val output = ByteArray(4)

        assertEquals(3, buffer.write(assertNotNull(initial.prepareRead(0, 3)), 0, 3))
        val reservation = buffer.reserveWrite(2)
        assertEquals(2, reservation.destination.copyFrom(0, appended, 0, appended.size))
        assertEquals(3, buffer.size)
        reservation.commit(1)
        assertEquals(4, buffer.size)

        assertEquals(
            4,
            buffer.read(
                assertNotNull(ByteArrayBuffer(output).prepareWrite(0, output.size)),
                0,
                output.size,
            ),
        )
        assertContentEquals(byteArrayOf(1, 2, 3, 4), output)
    }
}
