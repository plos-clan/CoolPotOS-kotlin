package org.plos_clan.cpos.drivers.usb.xhci.regs

import org.plos_clan.cpos.mem.MmioAddress

class Interrupter(
    runtimeBaseAddress: MmioAddress,
    index: Int,
) : RegisterBlock(
    runtimeBaseAddress + INTERRUPTER_BASE_OFFSET + index.toULong() * INTERRUPTER_STRIDE,
) {
    val baseAddress: MmioAddress
        get() = address

    val eventRingDequeuePointerAddress: MmioAddress
        get() = address + IR_ERDP_OFFSET

    fun setErstsz(size: UInt) {
        writeU32(IR_ERSTSZ_OFFSET, size)
    }

    fun setErstba(physicalAddress: ULong) {
        (address + IR_ERSTBA_OFFSET).writeSplitU64(physicalAddress)
    }

    fun setErdp(physicalAddress: ULong) {
        (address + IR_ERDP_OFFSET).writeSplitU64(physicalAddress)
    }

    fun enable() {
        writeU32(IR_IMAN_OFFSET, readU32(IR_IMAN_OFFSET) or 0x3u)
    }
}

private const val INTERRUPTER_BASE_OFFSET = 0x20uL
private const val INTERRUPTER_STRIDE = 0x20uL
private const val IR_IMAN_OFFSET = 0x00uL
private const val IR_ERSTSZ_OFFSET = 0x08uL
private const val IR_ERSTBA_OFFSET = 0x10uL
private const val IR_ERDP_OFFSET = 0x18uL
