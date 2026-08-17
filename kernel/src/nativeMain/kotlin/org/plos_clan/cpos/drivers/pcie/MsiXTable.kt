package org.plos_clan.cpos.drivers.pcie

import org.plos_clan.cpos.mem.MmioAddress
import org.plos_clan.cpos.mem.MmioRegion

class MsiXTable private constructor(
    private val region: MmioRegion,
    val entryCount: UInt,
) {
    fun entry(index: UInt): MsiXTableEntry? {
        if (index >= entryCount) return null
        val offset = index.toULong() * MSIX_TABLE_ENTRY_SIZE
        val entryWidth = MSIX_TABLE_ENTRY_SIZE.toInt()
        val address = region.addressAt(offset, entryWidth) ?: return null
        return MsiXTableEntry(address)
    }

    companion object {
        fun create(bar: PciBar, offset: UInt, count: UInt): MsiXTable? {
            if (bar.address == 0uL || count == 0u) return null
            val byteLength = count.toULong() * MSIX_TABLE_ENTRY_SIZE
            if (byteLength / MSIX_TABLE_ENTRY_SIZE != count.toULong()) return null
            val physicalAddress = bar.address + offset.toULong()
            if (physicalAddress < bar.address) return null
            val region = MmioRegion.map(physicalAddress, byteLength) ?: return null
            return MsiXTable(region, count)
        }
    }
}

class MsiXTableEntry internal constructor(
    private val address: MmioAddress,
) {
    fun write(messageAddress: ULong, data: UInt, masked: Boolean) {
        address.writeU32(messageAddress.toUInt())
        (address + 4uL).writeU32((messageAddress shr 32).toUInt())
        (address + 8uL).writeU32(data)
        (address + 12uL).writeU32(if (masked) 1u else 0u)
    }
}

private const val MSIX_TABLE_ENTRY_SIZE = 16uL
