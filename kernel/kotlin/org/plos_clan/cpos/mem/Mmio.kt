@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer
import platform.posix.memset

value class MmioAddress(val value: ULong) {
    operator fun plus(offset: ULong): MmioAddress = MmioAddress(value + offset)

    fun readU8(): UByte = value.toPointer<UByteVar>()?.get(0) ?: 0u

    fun readU16(): UShort = value.toPointer<UShortVar>()?.get(0) ?: 0u

    fun readU32(): UInt = value.toPointer<UIntVar>()?.get(0) ?: 0u

    fun readU64(): ULong = value.toPointer<ULongVar>()?.get(0) ?: 0uL

    fun writeU8(value: UByte) {
        this.value.toPointer<UByteVar>()?.set(0, value)
    }

    fun writeU16(value: UShort) {
        this.value.toPointer<UShortVar>()?.set(0, value)
    }

    fun writeU32(value: UInt) {
        this.value.toPointer<UIntVar>()?.set(0, value)
    }

    fun writeU64(value: ULong) {
        this.value.toPointer<ULongVar>()?.set(0, value)
    }

    fun writeSplitU64(value: ULong, lowMask: UInt = 0u) {
        writeU32(value.toUInt() or lowMask)
        (this + UInt.SIZE_BYTES.toULong()).writeU32((value shr UInt.SIZE_BITS).toUInt())
    }
}

class MmioRegion private constructor(
    val physicalAddress: ULong,
    val virtualAddress: ULong,
    val byteLength: ULong,
    private var ownedFrames: ULong,
) {
    fun addressAt(offset: ULong, width: Int = 1): MmioAddress? {
        if (width <= 0) return null
        val accessWidth = width.toULong()
        if (offset > ULong.MAX_VALUE - accessWidth || offset + accessWidth > byteLength) {
            return null
        }
        if (offset > ULong.MAX_VALUE - virtualAddress) return null
        return MmioAddress(virtualAddress + offset)
    }

    fun <T : CPointed> view(): CPointer<T> =
        requireNotNull(virtualAddress.toPointer<T>()) {
            "MmioRegion: virtual address ${virtualAddress.hex()} is not valid"
        }

    fun free() {
        KernelPageDirectory.addressSpace.unmap(virtualAddress, byteLength)
        val frames = ownedFrames
        ownedFrames = 0uL
        if (frames > 0uL) {
            BuddyFrameAllocator.free(physicalAddress, frames)
        }
    }

    companion object {
        fun map(physicalAddress: ULong, byteLength: ULong): MmioRegion? {
            if (byteLength == 0uL) return null
            if (physicalAddress > ULong.MAX_VALUE - byteLength) return null

            val virtualAddress = mapPhysical(physicalAddress, byteLength, populate = false)
                ?: return null
            return MmioRegion(physicalAddress, virtualAddress, byteLength, 0uL)
        }

        fun allocate(pageCount: ULong = 1uL): MmioRegion? {
            require(pageCount > 0uL) { "pageCount must be positive" }

            val physical = BuddyFrameAllocator.allocate(pageCount)
            if (physical == INVALID_FRAME) return null
            val byteLength = pageCount * PAGE_SIZE_BYTES
            val virtual = mapPhysical(physical, byteLength, populate = true) ?: run {
                BuddyFrameAllocator.free(physical, pageCount)
                return null
            }
            memset(virtual.toPointer<UByteVar>(), 0, byteLength)
            return MmioRegion(physical, virtual, byteLength, pageCount)
        }

        private fun mapPhysical(
            physicalAddress: ULong,
            byteLength: ULong,
            populate: Boolean,
        ): ULong? {
            val result = KernelPageDirectory.addressSpace.map(
                MemoryMapRequest(
                    hint = 0uL,
                    length = byteLength,
                    access = MEMORY_REGION_READABLE or MEMORY_REGION_WRITABLE,
                    fixed = false,
                    noReplace = false,
                    shared = false,
                    type = MemoryRegionType.MMIO,
                    offset = physicalAddress,
                    populate = populate,
                ),
            )
            return when (result) {
                is MemoryMapResult.Ok -> result.value
                is MemoryMapResult.Err -> null
            }
        }
    }
}

class CachedMmioRegion {
    private val lock = IrqSpinLock()
    private var mapped: MmioRegion? = null

    fun addressAt(physicalAddress: ULong, width: Int = 1): MmioAddress? {
        if (width <= 0) return null
        val accessWidth = width.toULong()
        if (physicalAddress > ULong.MAX_VALUE - accessWidth) return null
        return lock.withLock {
            mapped?.addressFor(physicalAddress, width)?.let { return@withLock it }

            val pageBase = physicalAddress.alignDown(PAGE_SIZE_BYTES)
            val pageOffset = physicalAddress - pageBase
            val requiredLength = pageOffset + accessWidth
            if (requiredLength < pageOffset) return@withLock null

            val region = MmioRegion.map(pageBase, maxOf(PAGE_SIZE_BYTES, requiredLength))
                ?: return@withLock null
            mapped = region
            region.addressFor(physicalAddress, width)
        }
    }

    private fun MmioRegion.addressFor(physicalAddress: ULong, width: Int): MmioAddress? {
        if (physicalAddress < this.physicalAddress) return null
        return addressAt(physicalAddress - this.physicalAddress, width)
    }
}
