package org.plos_clan.cpos.drivers.usb.xhci.core

import org.plos_clan.cpos.drivers.usb.bus.UsbBuffer
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket

class TransferDescriptor(
    buffers: List<UsbBuffer>,
    packetSize: UInt,
    val setup: SetupPacket? = null,
) {
    val trbs: List<Trb>
    val length: UInt

    init {
        require(packetSize != 0u)
        val total = buffers.sumOf { it.length.toULong() }
        require(total <= UInt.MAX_VALUE.toULong())
        require(setup == null || total == setup.length.toULong())
        length = total.toUInt()
        val input = setup != null && setup.requestType.toInt() and 0x80 != 0
        trbs = buildList {
            if (setup != null) {
                val low =
                    setup.requestType.toUInt() or
                        (setup.request.toUInt() shl 8) or
                        (setup.value.toUInt() shl 16)
                val high = setup.index.toUInt() or (setup.length.toUInt() shl 16)
                add(Trb.newSetupStage(low, high, if (length == 0u) 0u else if (input) 3u else 2u))
            }
            var transferred = 0u
            for (buffer in buffers) {
                require(buffer.physicalAddress <= ULong.MAX_VALUE - buffer.length)
                var address = buffer.physicalAddress
                var remaining = buffer.length
                while (remaining != 0u) {
                    val chunk = minOf(remaining, (0x10000uL - (address and 0xffffuL)).toUInt())
                    val first = transferred == 0u
                    transferred += chunk
                    val more = transferred < length
                    val packets = (length.toULong() + packetSize - 1u) / packetSize
                    val left = minOf(31uL, packets - transferred.toULong() / packetSize).toUInt()
                    val type = if (setup != null && first) TRB_DATA_STAGE else TRB_NORMAL
                    val direction = if (type == TRB_DATA_STAGE && input) 1u shl 16 else 0u
                    val flags =
                        TRB_ISP or
                            direction or
                            when {
                                more -> TRB_CHAIN
                                setup == null -> TRB_IOC
                                else -> 0u
                            }
                    add(
                        Trb(
                            address.toUInt(),
                            (address shr 32).toUInt(),
                            chunk or ((if (more) left else 0u) shl 17),
                            (type shl 10) or flags,
                        )
                    )
                    remaining -= chunk
                    address += chunk
                }
            }
            if (setup != null) add(Trb.newStatusStage(length == 0u || !input))
            else if (isEmpty()) add(Trb.newNormal(0uL, 0u))
        }
    }

    fun actualLength(index: Int, residual: UInt): UInt {
        require(index in trbs.indices)
        var actual = 0u
        for (position in 0..index) {
            val trb = trbs[position]
            if (trb.type != TRB_NORMAL && trb.type != TRB_DATA_STAGE) continue
            actual +=
                if (position == index) trb.bufferLength - minOf(residual, trb.bufferLength)
                else trb.bufferLength
        }
        return actual
    }
}
