@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import natives.hhdm_request
import org.plos_clan.cpos.utils.toAddress
import org.plos_clan.cpos.utils.toHex64
import org.plos_clan.cpos.utils.toPointer

object Hhdm {
    var offset: ULong = 0u
        private set

    val isReady: Boolean
        get() = offset != 0uL

    fun initialize(): ULong? {
        val response = hhdm_request.response?.pointed ?: return null
        offset = response.offset
        println("HHDM offset: 0x${offset.toHex64()}")
        return offset
    }

    fun toVirtual(physicalAddress: ULong): ULong = physicalAddress + offset

    fun toPhysical(virtualAddress: ULong): ULong = virtualAddress - offset

    fun <T : CPointed> toVirtualPointer(physicalAddress: ULong): CPointer<T>? =
        toVirtual(physicalAddress).toPointer()

    fun <T : CPointed> toPhysicalPointer(virtualAddress: ULong): CPointer<T>? =
        toPhysical(virtualAddress).toPointer()

    fun <T : CPointed> toVirtualPointer(pointer: CPointer<T>): CPointer<T>? =
        toVirtual(pointer.toAddress()).toPointer()

    fun <T : CPointed> toPhysicalPointer(pointer: CPointer<T>): CPointer<T>? =
        toPhysical(pointer.toAddress()).toPointer()
}
