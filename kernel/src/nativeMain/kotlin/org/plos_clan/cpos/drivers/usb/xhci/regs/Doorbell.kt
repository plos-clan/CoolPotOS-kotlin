package org.plos_clan.cpos.drivers.usb.xhci.regs

import org.plos_clan.cpos.mem.MmioAddress

class Doorbell(baseAddress: MmioAddress) : RegisterBlock(baseAddress) {
    fun ring(slotId: UByte, dci: UInt, streamId: Int = 0) {
        writeU32(slotId.toULong() * DOORBELL_STRIDE, dci or (streamId.toUInt() shl 16))
    }
}

private const val DOORBELL_STRIDE = 4uL
