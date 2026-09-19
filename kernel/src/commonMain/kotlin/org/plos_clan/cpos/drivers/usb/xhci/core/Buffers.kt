package org.plos_clan.cpos.drivers.usb.xhci.core

import org.plos_clan.cpos.drivers.usb.bus.UsbBuffer

internal class PacketBuffers(
    buffers: List<UsbBuffer>,
    packetSize: UInt,
    gather: (List<UsbBuffer>, UInt) -> UsbBuffer,
) {
    val buffers: List<UsbBuffer>

    init {
        require(packetSize != 0u)
        val source = buffers.filter { it.length != 0u }
        var index = 0
        var offset = 0u
        var transferred = 0u
        val total = source.sumOf { it.length.toULong() }
        require(total <= UInt.MAX_VALUE.toULong())

        fun take(length: UInt): UsbBuffer {
            val buffer = source[index]
            val result =
                UsbBuffer(
                    buffer.physicalAddress + offset,
                    length,
                    if (buffer.virtualAddress == 0uL) 0uL else buffer.virtualAddress + offset,
                )
            offset += length
            if (offset == buffer.length) {
                index++
                offset = 0u
            }
            return result
        }

        this.buffers = buildList {
            while (index < source.size) {
                val current = source[index]
                val address = current.physicalAddress + offset
                val available =
                    minOf(current.length - offset, (0x10000uL - (address and 0xffffuL)).toUInt())
                val remaining = total.toUInt() - transferred
                val direct =
                    if (available == remaining) available else available - available % packetSize
                if (direct != 0u) {
                    add(take(direct))
                    transferred += direct
                    continue
                }
                val length = minOf(packetSize, remaining)
                var needed = length
                val fragments = buildList {
                    while (needed != 0u) {
                        val count = minOf(needed, source[index].length - offset)
                        add(take(count))
                        needed -= count
                    }
                }
                add(gather(fragments, transferred))
                transferred += length
            }
        }
    }
}
