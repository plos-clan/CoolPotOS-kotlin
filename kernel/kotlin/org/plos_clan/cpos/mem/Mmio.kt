@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

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
import org.plos_clan.cpos.utils.toPointer

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

    /** Writes a 64-bit register represented by two adjacent 32-bit MMIO words. */
    fun writeSplitU64(value: ULong, lowMask: UInt = 0u) {
        writeU32(value.toUInt() or lowMask)
        (this + UInt.SIZE_BYTES.toULong()).writeU32((value shr UInt.SIZE_BITS).toUInt())
    }
}

class MmioRegion private constructor(
    val physicalAddress: ULong,
    val virtualAddress: ULong,
    val byteLength: ULong,
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

    companion object {
        fun map(physicalAddress: ULong, byteLength: ULong): MmioRegion? {
            if (byteLength == 0uL) return null
            if (physicalAddress > ULong.MAX_VALUE - byteLength) return null
            val virtualAddress = KernelPageDirectory.mapMmio(physicalAddress, byteLength) ?: return null
            return MmioRegion(physicalAddress, virtualAddress, byteLength)
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
