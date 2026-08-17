package org.plos_clan.cpos.drivers.usb.xhci.regs

import org.plos_clan.cpos.mem.MmioAddress

class LegacySupport(
    val baseAddress: MmioAddress,
) : RegisterBlock(baseAddress) {
    val isBiosOwned: Boolean
        get() = readU8(USBLEGSUP_BIOS_OWNED_OFFSET) != 0.toUByte()

    fun requestOsOwnership() {
        writeU8(USBLEGSUP_OS_OWNED_OFFSET, 1u)
    }

    fun sanitizeSmi() {
        val value = readU32(USBLEGCTLSTS_OFFSET)
        val sanitized = (value and USBLEGCTLSTS_DISABLE_SMI_MASK.inv()) or
            USBLEGCTLSTS_CLEAR_STATUS_MASK
        writeU32(USBLEGCTLSTS_OFFSET, sanitized)
    }
}

private const val USBLEGSUP_BIOS_OWNED_OFFSET = 0x02uL
private const val USBLEGSUP_OS_OWNED_OFFSET = 0x03uL
private const val USBLEGCTLSTS_OFFSET = 0x04uL
private const val USBLEGCTLSTS_DISABLE_SMI_MASK = 0xe011u
private const val USBLEGCTLSTS_CLEAR_STATUS_MASK = 0xe000_0000u
