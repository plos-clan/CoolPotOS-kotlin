@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import bridge.hhdm_request
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer

object Hhdm {
    var offset = 0uL
        private set

    val isReady: Boolean
        get() = offset != 0uL

    fun initialize(): ULong? =
        hhdm_request.response?.pointed?.offset?.also { discoveredOffset ->
            offset = discoveredOffset
            println("HHDM offset: ${discoveredOffset.hex()}")
        }

    fun toVirtual(physicalAddress: ULong): ULong = physicalAddress + offset

    fun <T : CPointed> toVirtualPointer(physicalAddress: ULong): CPointer<T>? =
        toVirtual(physicalAddress).toPointer()
}
