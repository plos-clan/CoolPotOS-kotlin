package org.plos_clan.cpos.drivers.pcie

const val PCI_CAPABILITY_MSI: UByte = 0x05u
const val PCI_CAPABILITY_MSIX: UByte = 0x11u

class PciCapability internal constructor(
    val id: UByte,
    internal val config: PciConfigSpace,
    val offset: UByte,
)

class CapabilityIterator internal constructor(
    private val config: PciConfigSpace,
    nextPointer: UByte,
) : Iterator<PciCapability> {
    private var nextOffset = nextPointer

    override fun hasNext(): Boolean = (nextOffset.toInt() and 0xFC) != 0

    override fun next(): PciCapability {
        if (!hasNext()) throw NoSuchElementException()
        val currentOffset = (nextOffset.toInt() and 0xFC).toUByte()
        val capability = config.readU16(currentOffset.toInt())
        nextOffset = (capability.toInt() shr 8).toUByte()
        return PciCapability(
            id = (capability.toInt() and 0xFF).toUByte(),
            config = config,
            offset = currentOffset,
        )
    }
}

class MsiCapability internal constructor(
    private val config: PciConfigSpace,
    val offset: UByte,
) {
    companion object {
        fun from(capability: PciCapability): MsiCapability? =
            capability.takeIf { it.id == PCI_CAPABILITY_MSI }
                ?.let { MsiCapability(it.config, it.offset) }
    }

    fun setEnabled(enabled: Boolean) {
        val controlOffset = offset.toInt() + 2
        val control = config.readU16(controlOffset)
        val updated = if (enabled) {
            (control.toUInt() or 1u).toUShort()
        } else {
            (control.toUInt() and 1u.inv()).toUShort()
        }
        config.writeU16(controlOffset, updated)
    }

    fun setAddressData(address: ULong, data: UShort) {
        val controlOffset = offset.toInt() + 2
        val control = config.readU16(controlOffset)
        val is64Bit = control.toInt() and (1 shl 7) != 0
        val base = offset.toInt()
        config.writeU32(base + 4, address.toUInt())
        if (is64Bit) {
            config.writeU32(base + 8, (address shr 32).toUInt())
            config.writeU16(base + 12, data)
        } else {
            config.writeU16(base + 8, data)
        }
    }
}

class MsixCapability internal constructor(
    private val config: PciConfigSpace,
    val offset: UByte,
) {
    companion object {
        fun from(capability: PciCapability): MsixCapability? =
            capability.takeIf { it.id == PCI_CAPABILITY_MSIX }
                ?.let { MsixCapability(it.config, it.offset) }
    }

    fun setEnabled(enabled: Boolean) {
        val controlOffset = offset.toInt() + 2
        var control = config.readU16(controlOffset)
        control = if (enabled) {
            (control.toUInt() or (1u shl 15))
                .and((1u shl 14).inv())
                .toUShort()
        } else {
            (control.toUInt() and (1u shl 15).inv()).toUShort()
        }
        config.writeU16(controlOffset, control)
    }

    val tableSize: UInt
        get() = (config.readU16(offset.toInt() + 2).toUInt() and 0x7FFu) + 1u

    val tableBar: UByte
        get() = (config.readU32(offset.toInt() + 4) and 0x7u).toUByte()

    val tableOffset: UInt
        get() = config.readU32(offset.toInt() + 4) and 0xFFFF_FFF8u
}
