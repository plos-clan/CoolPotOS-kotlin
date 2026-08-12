package org.plos_clan.cpos.drivers.usb.xhci.regs

import org.plos_clan.cpos.mem.MmioAddress

class Capability(
    val baseAddress: MmioAddress,
) : RegisterBlock(baseAddress) {
    val length: UByte
        get() = readU8(CAP_LENGTH_OFFSET)

    val version: UShort
        get() = readU16(CAP_VERSION_OFFSET)

    val doorbellOffset: UInt
        get() = readU32(CAP_DB_OFF_OFFSET) and DB_OFF_MASK

    val runtimeOffset: UInt
        get() = readU32(CAP_RTS_OFF_OFFSET) and RTS_OFF_MASK

    val maxSlots: UByte
        get() = (hcsParams1 and 0xffu).toUByte()

    val maxPorts: UByte
        get() = (hcsParams1 shr 24).toUByte()

    val maxInterrupters: UInt
        get() = (hcsParams1 shr 8) and 0x7ffu

    val supports64BitAddressing: Boolean
        get() = hccParams1 and 1u != 0u

    val uses64ByteContext: Boolean
        get() = hccParams1 and (1u shl 2) != 0u

    val xecp: UInt
        get() = ((hccParams1 shr 16) and 0xffffu) shl 2

    val maxScratchpadBuffers: UInt
        get() {
            val high = (hcsParams2 shr 21) and 0x1fu
            val low = (hcsParams2 shr 27) and 0x1fu
            return (high shl 5) or low
        }

    fun legacySupport(): LegacySupport? =
        findExtCap(1u.toUByte())?.let(::LegacySupport)

    fun findExtCap(targetId: UByte): MmioAddress? {
        var offset = xecp.toULong()
        repeat(32) {
            if (offset == 0uL) return null

            val capabilityAddress = baseAddress + offset
            val header = capabilityAddress.readU32()
            if ((header and 0xffu).toUByte() == targetId) {
                return capabilityAddress
            }
            if (header and 0xff00u == 0u) return null

            offset += ((header shr 8) and 0xffu).toULong() shl 2
        }
        return null
    }

    private val hcsParams1: UInt
        get() = readU32(CAP_HCSPARAMS1_OFFSET)

    private val hcsParams2: UInt
        get() = readU32(CAP_HCSPARAMS2_OFFSET)

    private val hccParams1: UInt
        get() = readU32(CAP_HCCPARAMS1_OFFSET)
}

private const val CAP_LENGTH_OFFSET = 0x00uL
private const val CAP_VERSION_OFFSET = 0x02uL
private const val CAP_HCSPARAMS1_OFFSET = 0x04uL
private const val CAP_HCSPARAMS2_OFFSET = 0x08uL
private const val CAP_HCCPARAMS1_OFFSET = 0x10uL
private const val CAP_DB_OFF_OFFSET = 0x14uL
private const val CAP_RTS_OFF_OFFSET = 0x18uL
private const val DB_OFF_MASK = 0xffff_fffcu
private const val RTS_OFF_MASK = 0xffff_ffe0u
