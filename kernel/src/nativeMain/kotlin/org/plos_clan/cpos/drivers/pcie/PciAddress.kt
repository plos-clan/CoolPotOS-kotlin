package org.plos_clan.cpos.drivers.pcie

import org.plos_clan.cpos.mem.MmioAddress

value class PciAddress(val raw: UInt) {
    val segment: UShort
        get() = ((raw shr 16) and 0xFFFFu).toUShort()

    val bus: UByte
        get() = ((raw shr 8) and 0xFFu).toUByte()

    val device: UByte
        get() = ((raw shr 3) and 0x1Fu).toUByte()

    val function: UByte
        get() = (raw and 0x7u).toUByte()

    val sysfsName: String
        get() = segment.toInt().toString(16).padStart(4, '0') + ":" +
            bus.toInt().toString(16).padStart(2, '0') + ":" +
            device.toInt().toString(16).padStart(2, '0') + "." +
            function.toInt().toString(16)

    fun mmioAddress(): MmioAddress? = Pcie.configurationSpace(this)?.baseAddress

    fun hasMultipleFunctions(): Boolean =
        PciHeader(Pcie.configurationSpace(this) ?: return false).isMultiFunction

    internal fun offsetFrom(busStart: Int): ULong =
        ((bus.toInt() - busStart).toULong() shl PCI_BUS_SHIFT) or
            (device.toULong() shl PCI_DEVICE_SHIFT) or
            (function.toULong() shl PCI_FUNCTION_SHIFT)

    override fun toString(): String =
        "${bus.toInt().toString(16).padStart(2, '0')}:" +
            "${device.toInt().toString(16).padStart(2, '0')}." +
            function.toInt().toString(16)

    companion object {
        fun of(segment: UShort, bus: UByte, device: UByte, function: UByte): PciAddress =
            PciAddress(
                (segment.toUInt() shl 16) or
                    (bus.toUInt() shl 8) or
                    (device.toUInt() shl 3) or
                    function.toUInt(),
            )
    }
}

private const val PCI_BUS_SHIFT = 20
private const val PCI_DEVICE_SHIFT = 15
private const val PCI_FUNCTION_SHIFT = 12
