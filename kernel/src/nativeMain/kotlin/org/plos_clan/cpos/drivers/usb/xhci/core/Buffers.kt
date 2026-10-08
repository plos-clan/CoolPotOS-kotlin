@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.xhci.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.drivers.usb.bus.TransferResult
import org.plos_clan.cpos.drivers.usb.bus.UsbBuffer
import org.plos_clan.cpos.drivers.usb.bus.UsbTransfer
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.toPointer
import platform.posix.memcpy

internal class PendingTransfer(
    val request: UsbTransfer,
    memory: DmaMemory,
    packetSize: UInt,
) {
    private class Gather(private val memory: DmaMemory, private val input: Boolean) {
        private data class Copy(
            val source: ULong,
            val target: ULong,
            val length: UInt,
            val offset: UInt,
        )

        private val pages = mutableListOf<MmioRegion>()
        private val mappings = mutableListOf<MmioRegion>()
        private val copies = mutableListOf<Copy>()
        private var used = PAGE_SIZE_BYTES

        fun append(fragments: List<UsbBuffer>, offset: UInt): UsbBuffer {
            val length = fragments.sumOf { it.length.toULong() }
            if (length > PAGE_SIZE_BYTES - used) {
                pages.add(memory.allocate())
                used = 0uL
            }
            val page = pages.last()
            var copied = 0u
            for (fragment in fragments) {
                val source = address(fragment)
                val target = page.virtualAddress + used + copied
                val copy = Copy(source, target, fragment.length, offset + copied)
                copies.add(copy)
                if (!input) {
                    memcpy(target.toPointer<UByteVar>(), source.toPointer<UByteVar>(), fragment.length.toULong())
                }
                copied += fragment.length
            }
            val buffer = UsbBuffer(page.physicalAddress + used, length.toUInt(), page.virtualAddress + used)
            used += length
            return buffer
        }

        private fun address(fragment: UsbBuffer): ULong {
            if (fragment.virtualAddress != 0uL) return fragment.virtualAddress
            val offset = fragment.physicalAddress and (PAGE_SIZE_BYTES - 1uL)
            val mapping = MmioRegion.map(fragment.physicalAddress - offset, offset + fragment.length)
                ?: throw DmaMemory.AllocationFailure()
            mappings.add(mapping)
            return mapping.virtualAddress + offset
        }

        fun complete(actualLength: UInt) {
            if (!input) return
            for (copy in copies) {
                if (copy.offset >= actualLength) break
                val length = minOf(copy.length, actualLength - copy.offset)
                memcpy(copy.source.toPointer<UByteVar>(), copy.target.toPointer<UByteVar>(), length.toULong())
            }
        }

        fun free() {
            mappings.forEach { it.free() }
            pages.forEach { it.free() }
        }
    }

    private var gather: Gather? = null
    val descriptor: TransferDescriptor

    init {
        require(packetSize.toULong() <= PAGE_SIZE_BYTES)
        try {
            val buffers = PacketBuffers(request.buffers, packetSize) { fragments, offset ->
                val input = (request.setup?.requestType ?: request.endpointAddress).toInt() and 0x80 != 0
                val target = gather ?: Gather(memory, input).also { gather = it }
                target.append(fragments, offset)
            }
            descriptor = TransferDescriptor(buffers.buffers, packetSize, request.setup)
        } catch (failure: Throwable) {
            free()
            throw failure
        }
    }

    fun complete(result: TransferResult) {
        if (request.isCompleted) return
        gather?.complete(result.actualLength)
        free()
        request.complete(result)
    }

    fun free() {
        gather?.free()
    }
}
