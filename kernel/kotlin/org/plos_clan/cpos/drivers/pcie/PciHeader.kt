package org.plos_clan.cpos.drivers.pcie

import org.plos_clan.cpos.mem.MmioAddress

internal class PciConfigSpace(
    val baseAddress: MmioAddress,
) {
    fun readU8(offset: Int): UByte = (baseAddress + offset.toULong()).readU8()

    fun readU16(offset: Int): UShort = (baseAddress + offset.toULong()).readU16()

    fun readU32(offset: Int): UInt = (baseAddress + offset.toULong()).readU32()

    fun writeU8(offset: Int, value: UByte) {
        (baseAddress + offset.toULong()).writeU8(value)
    }

    fun writeU16(offset: Int, value: UShort) {
        (baseAddress + offset.toULong()).writeU16(value)
    }

    fun writeU32(offset: Int, value: UInt) {
        (baseAddress + offset.toULong()).writeU32(value)
    }

    fun read(offset: Int, byteCount: Int): ULong = when (byteCount) {
        UByte.SIZE_BYTES -> readU8(offset).toULong()
        UShort.SIZE_BYTES -> readU16(offset).toULong()
        UInt.SIZE_BYTES -> readU32(offset).toULong()
        ULong.SIZE_BYTES -> {
            readU32(offset).toULong() or
                (readU32(offset + UInt.SIZE_BYTES).toULong() shl UInt.SIZE_BITS)
        }
        else -> (0 until byteCount).fold(0uL) { value, index ->
            value or (readU8(offset + index).toULong() shl (index * Byte.SIZE_BITS))
        }
    }

    fun write(offset: Int, byteCount: Int, value: ULong) {
        when (byteCount) {
            UByte.SIZE_BYTES -> writeU8(offset, value.toUByte())
            UShort.SIZE_BYTES -> writeU16(offset, value.toUShort())
            UInt.SIZE_BYTES -> writeU32(offset, value.toUInt())
            ULong.SIZE_BYTES -> {
                writeU32(offset, value.toUInt())
                writeU32(offset + UInt.SIZE_BYTES, (value shr UInt.SIZE_BITS).toUInt())
            }
            else -> repeat(byteCount) { index ->
                writeU8(offset + index, (value shr (index * Byte.SIZE_BITS)).toUByte())
            }
        }
    }
}

const val PCI_COMMAND_IO_SPACE: UShort = 0x0001u
const val PCI_COMMAND_MEMORY_SPACE: UShort = 0x0002u
const val PCI_COMMAND_BUS_MASTER: UShort = 0x0004u
const val PCI_COMMAND_INTX_DISABLE: UShort = 0x0400u

enum class PciHeaderType {
    ENDPOINT,
    PCI_PCI_BRIDGE,
    CARDBUS_BRIDGE,
    UNKNOWN;

    companion object {
        fun parse(value: UByte): PciHeaderType = when (value.toInt() and 0x7F) {
            0x00 -> ENDPOINT
            0x01 -> PCI_PCI_BRIDGE
            0x02 -> CARDBUS_BRIDGE
            else -> UNKNOWN
        }
    }
}

class PciHeader internal constructor(
    internal val config: PciConfigSpace,
) {
    val vendorId: UShort
        get() = config.readU16(0x00)

    val deviceId: UShort
        get() = config.readU16(0x02)

    val command: UShort
        get() = config.readU16(0x04)

    val status: UShort
        get() = config.readU16(0x06)

    val revision: UByte
        get() = config.readU8(0x08)

    val progIf: UByte
        get() = config.readU8(0x09)

    val subClass: UByte
        get() = config.readU8(0x0A)

    val classCode: UByte
        get() = config.readU8(0x0B)

    val headerType: PciHeaderType
        get() = PciHeaderType.parse(config.readU8(0x0E))

    val isMultiFunction: Boolean
        get() = config.readU8(0x0E).toInt() and 0x80 != 0

    val interruptLine: UByte
        get() = config.readU8(0x3C)

    val interruptPin: UByte
        get() = config.readU8(0x3D)

    fun hasCapabilities(): Boolean = (status.toInt() and (1 shl 4)) != 0

    fun updateCommand(mask: UShort, enable: Boolean) {
        val updated = if (enable) {
            (command.toUInt() or mask.toUInt()).toUShort()
        } else {
            (command.toUInt() and mask.toUInt().inv()).toUShort()
        }
        config.writeU16(0x04, updated)
    }
}

data class EndpointHeader(
    val header: PciHeader,
) {
    fun bars(): Array<PciBar?> {
        val bars = arrayOfNulls<PciBar>(PCI_BAR_COUNT)
        var skipNext = false
        for (index in bars.indices) {
            if (skipNext) {
                skipNext = false
                continue
            }
            val bar = PciBar.read(header.config, index) ?: continue
            bars[index] = bar
            skipNext = bar.type == PciBarType.MEMORY64
        }
        return bars
    }

    fun capabilities(): CapabilityIterator =
        if (header.hasCapabilities()) {
            CapabilityIterator(header.config, header.config.readU8(PCI_CAPABILITY_POINTER_OFFSET))
        } else {
            CapabilityIterator(header.config, 0u)
        }
}

data class BridgeHeader(
    val header: PciHeader,
) {
    val primaryBus: UByte
        get() = header.config.readU8(0x18)

    val secondaryBus: UByte
        get() = header.config.readU8(0x19)

    val subordinateBus: UByte
        get() = header.config.readU8(0x1A)
}

const val PCI_BAR_COUNT = 6
const val PCI_CAPABILITY_POINTER_OFFSET = 0x34
