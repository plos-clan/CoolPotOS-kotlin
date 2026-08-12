@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
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
}

class MmioRegion private constructor(
    val physicalAddress: ULong,
    val virtualAddress: ULong,
    val byteLength: ULong,
) {
    fun addressAt(offset: ULong, width: Int = 1): MmioAddress? {
        if (width <= 0) return null
        val end = offset + width.toULong()
        if (end < offset || end > byteLength) return null
        val address = virtualAddress + offset
        return address.takeIf { it >= virtualAddress }?.let(::MmioAddress)
    }

    companion object {
        fun map(physicalAddress: ULong, byteLength: ULong): MmioRegion? {
            if (byteLength == 0uL) return null
            if (physicalAddress + byteLength < physicalAddress) return null
            val virtualAddress = KernelPageDirectory.mapMmio(physicalAddress, byteLength) ?: return null
            return MmioRegion(physicalAddress, virtualAddress, byteLength)
        }
    }
}
