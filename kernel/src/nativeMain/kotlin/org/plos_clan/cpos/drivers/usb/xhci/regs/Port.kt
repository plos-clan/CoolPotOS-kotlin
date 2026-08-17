package org.plos_clan.cpos.drivers.usb.xhci.regs

import org.plos_clan.cpos.mem.MmioAddress

const val PORT_CCS: UInt = 0x0000_0001u
const val PORT_PED: UInt = 0x0000_0002u
const val PORT_PR: UInt = 0x0000_0010u
const val PORT_PLS: UInt = 0x0000_01e0u
const val PORT_PP: UInt = 0x0000_0200u
const val PORT_CSC: UInt = 0x0002_0000u
const val PORT_PRC: UInt = 0x0020_0000u
const val PORT_RW1C_MASK: UInt = 0x00fe_0000u
const val PORT_SPEED_SHIFT = 10
const val PORT_SPEED_MASK: UInt = 0xfu

class Port internal constructor(
    val id: Int,
    baseAddress: MmioAddress,
) : RegisterBlock(baseAddress) {
    val baseAddress: MmioAddress
        get() = address

    constructor(operationalBaseAddress: MmioAddress, index: Int) : this(
        id = index + 1,
        baseAddress = operationalBaseAddress + PORT_REGISTER_BASE_OFFSET +
            index.toULong() * PORT_REGISTER_STRIDE,
    )

    val isConnected: Boolean
        get() = readPortSc() and PORT_CCS != 0u

    val isEnabled: Boolean
        get() = readPortSc() and PORT_PED != 0u

    val hasConnectionChange: Boolean
        get() = readPortSc() and PORT_CSC != 0u

    val hasResetChange: Boolean
        get() = readPortSc() and PORT_PRC != 0u

    val isInReset: Boolean
        get() = readPortSc() and PORT_PR != 0u

    val speedId: UInt
        get() = (readPortSc() shr PORT_SPEED_SHIFT) and PORT_SPEED_MASK

    fun reset(): Boolean {
        if (!isConnected) return false
        updatePortSc(PORT_PR or PORT_PP)
        return true
    }

    fun updatePortSc(mask: UInt) {
        val value = readPortSc()
        val writeValue = (value and (PORT_RW1C_MASK or PORT_PED or PORT_PR).inv()) or mask
        writeU32(0uL, writeValue)
    }

    private fun readPortSc(): UInt = readU32(0uL)
}

private const val PORT_REGISTER_BASE_OFFSET = 0x400uL
private const val PORT_REGISTER_STRIDE = 0x10uL
