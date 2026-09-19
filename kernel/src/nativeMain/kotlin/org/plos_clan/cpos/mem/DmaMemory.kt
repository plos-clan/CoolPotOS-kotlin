@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

class DmaMemory private constructor(private val region: MmioRegion, override val size: Int) :
    NativeMemory(), DmaBuffer {
    override val physicalAddress: ULong
        get() = region.physicalAddress

    override val virtualAddress: ULong
        get() = region.virtualAddress

    override val pointer: CPointer<UByteVar>
        get() = region.view()

    override fun close() = region.free()

    companion object {
        fun allocate(size: Int): DmaMemory? {
            require(size > 0)
            val pages = (size.toULong() + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES
            return MmioRegion.allocate(pages)?.let { DmaMemory(it, size) }
        }
    }
}
