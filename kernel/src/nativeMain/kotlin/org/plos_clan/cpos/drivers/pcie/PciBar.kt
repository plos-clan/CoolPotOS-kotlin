package org.plos_clan.cpos.drivers.pcie

enum class PciBarType {
    IO,
    MEMORY32,
    MEMORY64,
}

data class PciBar(
    val type: PciBarType,
    val address: ULong,
    val size: ULong,
    val prefetchable: Boolean,
) {
    companion object {
        internal fun read(config: PciConfigSpace, index: Int): PciBar? {
            if (index !in 0 until PCI_BAR_COUNT) return null

            val offset = PCI_BAR_OFFSET + index * UInt.SIZE_BYTES
            val lowValue = config.readU32(offset)
            if (lowValue == 0u) return null

            config.writeU32(offset, UInt.MAX_VALUE)
            val lowMask = config.readU32(offset)
            config.writeU32(offset, lowValue)

            val isIo = lowValue and 1u != 0u
            val is64Bit = !isIo && (lowValue and 0x07u) == 0x04u
            val isPrefetchable = !isIo && (lowValue and 0x08u) != 0u

            return when {
                is64Bit -> {
                    val highOffset = offset + UInt.SIZE_BYTES
                    val highValue = config.readU32(highOffset)
                    config.writeU32(highOffset, UInt.MAX_VALUE)
                    val highMask = config.readU32(highOffset)
                    config.writeU32(highOffset, highValue)

                    val memoryMask = 0xFFFF_FFF0u
                    val address = (highValue.toULong() shl 32) or
                        (lowValue and memoryMask).toULong()
                    val encodedMask = (highMask.toULong() shl 32) or
                        (lowMask and memoryMask).toULong()
                    PciBar(PciBarType.MEMORY64, address, encodedMask.inv() + 1uL, isPrefetchable)
                }

                isIo -> {
                    val ioMask = 0xFFFF_FFFCu
                    PciBar(
                        type = PciBarType.IO,
                        address = (lowValue and ioMask).toULong(),
                        size = (lowMask and ioMask).inv().toULong() + 1uL,
                        prefetchable = false,
                    )
                }

                else -> {
                    val memoryMask = 0xFFFF_FFF0u
                    PciBar(
                        type = PciBarType.MEMORY32,
                        address = (lowValue and memoryMask).toULong(),
                        size = (lowMask and memoryMask).inv().toULong() + 1uL,
                        prefetchable = isPrefetchable,
                    )
                }
            }
        }
    }
}

private const val PCI_BAR_OFFSET = 0x10
