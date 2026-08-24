package org.plos_clan.cpos.drivers.usb.xhci.regs

import org.plos_clan.cpos.mem.MmioAddress

abstract class RegisterBlock(
    protected val address: MmioAddress,
) {
    protected fun readU8(offset: ULong): UByte = (address + offset).readU8()

    protected fun readU16(offset: ULong): UShort = (address + offset).readU16()

    protected fun readU32(offset: ULong): UInt = (address + offset).readU32()

    protected fun writeU8(offset: ULong, value: UByte) {
        (address + offset).writeU8(value)
    }

    protected fun writeU32(offset: ULong, value: UInt) {
        (address + offset).writeU32(value)
    }
}
