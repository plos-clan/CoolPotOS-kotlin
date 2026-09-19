package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamsTest {
    @Test
    fun reservesStreamZeroAndHonorsIndependentLimits() {
        assertEquals(15, StreamAllocation(64, 64, 16).count)
        assertEquals(16, StreamAllocation(64, 16, 64).count)
        assertEquals(32, StreamAllocation(64, 16, 64).contextEntries)
        assertEquals(65535, StreamAllocation(Int.MAX_VALUE, 65536, 65536).count)
        assertEquals(65536, StreamAllocation(Int.MAX_VALUE, 65536, 65536).contextEntries)
    }

    @Test
    fun usesMinimumFourContextsEvenForOneStream() {
        assertEquals(1, StreamAllocation(1, 2, 4).count)
        assertEquals(4, StreamAllocation(1, 2, 4).contextEntries)
        assertEquals(0, StreamAllocation(1, 0, 4).count)
        assertEquals(0, StreamAllocation(1, 2, 0).count)
        assertEquals(0, StreamAllocation(0, 2, 4).contextEntries)
    }
}
