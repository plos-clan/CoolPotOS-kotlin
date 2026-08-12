package org.plos_clan.cpos.drivers.usb.xhci.regs

import org.plos_clan.cpos.mem.MmioAddress

class Doorbell(
    baseAddress: MmioAddress,
) : RegisterBlock(baseAddress) {
    fun ring(slotId: UByte, dci: UInt) {
        writeU32(slotId.toULong() * DOORBELL_STRIDE, dci)
    }
}

private const val DOORBELL_STRIDE = 4uL
