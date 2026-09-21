@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

class DmaMemory private constructor(override val physicalAddress: ULong, override val size: Int) :
    NativeMemory(), DmaBuffer {
    override val virtualAddress: ULong
        get() = Hhdm.toVirtual(physicalAddress)

    override val pointer: CPointer<UByteVar>
        get() = checkNotNull(Hhdm.toVirtualPointer(physicalAddress))

    override fun close() {
        val pages = (size.toULong() + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES
        check(BuddyFrameAllocator.free(physicalAddress, pages))
    }

    companion object {
        fun allocate(size: Int): DmaMemory? {
            require(size > 0)
            val pages = (size.toULong() + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES
            val physical = BuddyFrameAllocator.allocate(pages)
            if (physical == INVALID_FRAME) return null
            return DmaMemory(physical, size).also { it.fill(0, size) }
        }
    }
}
