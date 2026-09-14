package org.plos_clan.cpos.fs.sock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SocketTimestampQueueTest {
    @Test
    fun returnsTimestampOfLastDeliveredSpan() {
        val timestamps = SocketTimestampQueue()
        timestamps.append(4, 10uL)
        timestamps.append(3, 20uL)

        assertEquals(20uL, timestamps.read(5, consume = false))
        assertEquals(10uL, timestamps.read(2, consume = true))
        assertEquals(20uL, timestamps.read(3, consume = true))
        assertEquals(20uL, timestamps.read(2, consume = true))
    }

    @Test
    fun distinguishesUnstampedDataAndResetsAfterDrain() {
        val timestamps = SocketTimestampQueue(bufferedBytes = 2)
        timestamps.append(3, null)
        timestamps.append(2, 10uL)

        assertNull(timestamps.read(5, consume = true))
        assertEquals(10uL, timestamps.read(2, consume = true))

        timestamps.append(1, null)
        timestamps.append(2, 30uL)
        assertNull(timestamps.read(1, consume = true))
        assertEquals(30uL, timestamps.read(2, consume = true))
    }
}
