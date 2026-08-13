@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer
import platform.posix.memset

class DmaBuffer private constructor(
    val physicalAddress: ULong,
    val virtualAddress: ULong,
    val pageCount: ULong,
) {
    private var freed = false

    fun <T : CPointed> view(): CPointer<T> =
        requireNotNull(Hhdm.toVirtualPointer<T>(physicalAddress)) {
            "DmaBuffer: physical address ${physicalAddress.hex()} is not mapped"
        }

    fun free() {
        if (freed) {
            return
        }
        freed = true

        val directory = KernelPageDirectory.getDirectory()
        repeat(pageCount.toInt()) { index ->
            directory.unmapPage(virtualAddress + index.toULong() * PAGE_SIZE_BYTES)
        }
        BuddyFrameAllocator.freeFrames(physicalAddress, pageCount)
    }

    companion object {
        fun allocate(pageCount: ULong = 1uL): DmaBuffer? {
            require(pageCount > 0uL) { "pageCount must be positive" }

            val physical = BuddyFrameAllocator.allocateFrames(pageCount) ?: return null
            val byteLength = pageCount * PAGE_SIZE_BYTES
            val virtual = KernelPageDirectory.mapMmio(physical, byteLength) ?: run {
                BuddyFrameAllocator.freeFrames(physical, pageCount)
                return null
            }
            memset(virtual.toPointer<UByteVar>(), 0, byteLength)
            return DmaBuffer(physical, virtual, pageCount)
        }
    }
}
