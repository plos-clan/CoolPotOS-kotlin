@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DmaMemoryTest {
    @Test
    fun coherentBufferUsesDirectMappingAndReleasesRoundedAllocation() {
        val addressSpace = KernelPageDirectory.addressSpace
        val mappings = addressSpace.used
        val size = PAGE_SIZE_BYTES.toInt() + 17
        val memory = assertNotNull(DmaMemory.allocate(size))
        val allocated = BuddyFrameAllocator.statistics().freeBytes
        try {
            assertEquals(mappings, addressSpace.used)
            assertEquals(Hhdm.toVirtual(memory.physicalAddress), memory.virtualAddress)
            val actual = ByteArray(size)
            assertEquals(size, memory.copyTo(0, actual, 0, size))
            assertContentEquals(ByteArray(size), actual)
            val physical = assertNotNull(Hhdm.toVirtualPointer<UByteVar>(memory.physicalAddress))
            physical[size - 1] = 0x5au
            assertEquals(1, memory.copyTo(size - 1, actual, 0, 1))
            assertEquals(0x5a.toByte(), actual[0])
            assertEquals(size, memory.fill(0, size, 0x3c))
            assertEquals(0x3cu.toUByte(), physical[size - 1])
        } finally {
            memory.close()
        }
        assertEquals(allocated + 2uL * PAGE_SIZE_BYTES, BuddyFrameAllocator.statistics().freeBytes)
        assertEquals(mappings, addressSpace.used)
    }
}
