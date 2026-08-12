package org.plos_clan.cpos.drivers.usb.xhci.regs

import org.plos_clan.cpos.mem.MmioAddress

class Operational(
    val baseAddress: MmioAddress,
) : RegisterBlock(baseAddress) {
    fun readUsbCommand(): UInt = readU32(OP_USBCMD_OFFSET)

    fun readUsbStatus(): UInt = readU32(OP_USBSTS_OFFSET)

    private fun writeUsbCommand(value: UInt) {
        writeU32(OP_USBCMD_OFFSET, value)
    }

    fun writeUsbStatus(value: UInt) {
        writeU32(OP_USBSTS_OFFSET, value)
    }

    fun start() {
        writeUsbCommand(readUsbCommand() or 1u or (1u shl 2))
    }

    fun stop() {
        writeUsbCommand(readUsbCommand() and 1u.inv())
    }

    fun reset() {
        writeUsbCommand(readUsbCommand() or 2u)
    }

    val isRunning: Boolean
        get() = readUsbCommand() and 1u != 0u

    val isHalted: Boolean
        get() = readUsbStatus() and 1u != 0u

    val notReady: Boolean
        get() = readUsbStatus() and (1u shl 11) != 0u

    fun setMaxSlotsEnabled(number: UByte) {
        writeConfig((readConfig() and 0xffu.inv()) or number.toUInt())
    }

    fun setDcbaa(physicalAddress: ULong) {
        (address + OP_DCBAAP_OFFSET).writeSplitU64(physicalAddress)
    }

    fun setCrcr(physicalAddress: ULong) {
        (address + OP_CRCR_OFFSET).writeSplitU64(physicalAddress, lowMask = 1u)
    }

    private fun readConfig(): UInt = readU32(OP_CONFIG_OFFSET)

    private fun writeConfig(value: UInt) {
        writeU32(OP_CONFIG_OFFSET, value)
    }
}

private const val OP_USBCMD_OFFSET = 0x00uL
private const val OP_USBSTS_OFFSET = 0x04uL
private const val OP_CRCR_OFFSET = 0x18uL
private const val OP_DCBAAP_OFFSET = 0x30uL
private const val OP_CONFIG_OFFSET = 0x38uL
