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
    private data class Copy(
        val source: ULong,
        val target: ULong,
        val length: UInt,
        val offset: UInt,
    )

    private val pages = mutableListOf<MmioRegion>()
    private val mappings = mutableListOf<MmioRegion>()
    private val copies = mutableListOf<Copy>()
    private val input =
        (request.setup?.requestType ?: request.endpointAddress).toInt() and 0x80 != 0
    val descriptor: TransferDescriptor

    init {
        require(packetSize.toULong() <= PAGE_SIZE_BYTES)
        var used = PAGE_SIZE_BYTES
        try {
            val buffers =
                PacketBuffers(request.buffers, packetSize) { fragments, offset ->
                    val length = fragments.sumOf { it.length.toULong() }
                    if (length > PAGE_SIZE_BYTES - used) {
                        pages.add(memory.allocate())
                        used = 0uL
                    }
                    val page = pages.last()
                    var copied = 0u
                    for (fragment in fragments) {
                        val source =
                            if (fragment.virtualAddress != 0uL) fragment.virtualAddress
                            else {
                                val pageOffset =
                                    fragment.physicalAddress and (PAGE_SIZE_BYTES - 1uL)
                                val mapping =
                                    MmioRegion.map(
                                        fragment.physicalAddress - pageOffset,
                                        pageOffset + fragment.length,
                                    ) ?: throw DmaMemory.AllocationFailure()
                                mappings.add(mapping)
                                mapping.virtualAddress + pageOffset
                            }
                        val target = page.virtualAddress + used + copied
                        copies.add(Copy(source, target, fragment.length, offset + copied))
                        if (!input)
                            memcpy(
                                target.toPointer<UByteVar>(),
                                source.toPointer<UByteVar>(),
                                fragment.length.toULong(),
                            )
                        copied += fragment.length
                    }
                    val buffer =
                        UsbBuffer(
                            page.physicalAddress + used,
                            length.toUInt(),
                            page.virtualAddress + used,
                        )
                    used += length
                    buffer
                }
            descriptor = TransferDescriptor(buffers.buffers, packetSize, request.setup)
        } catch (failure: Throwable) {
            free()
            throw failure
        }
    }

    fun complete(result: TransferResult) {
        if (request.isCompleted) return
        if (input)
            for (copy in copies) {
                if (copy.offset >= result.actualLength) break
                val length = minOf(copy.length, result.actualLength - copy.offset)
                memcpy(
                    copy.source.toPointer<UByteVar>(),
                    copy.target.toPointer<UByteVar>(),
                    length.toULong(),
                )
            }
        free()
        request.complete(result)
    }

    fun free() {
        mappings.forEach { it.free() }
        pages.forEach { it.free() }
    }
}
