@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers

import bridge.rsdp_request
import kotlinx.cinterop.*
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.drivers.apic.Apic
import org.plos_clan.cpos.utils.*

private const val RSDP_V1_LENGTH = 20
private const val RSDP_V2_MIN_LENGTH = 36
private const val RSDP_REVISION_OFFSET = 15
private const val RSDP_RSDT_ADDRESS_OFFSET = 16
private const val RSDP_LENGTH_OFFSET = 20
private const val RSDP_XSDT_ADDRESS_OFFSET = 24
private const val SDT_HEADER_LENGTH = 36
private const val SDT_LENGTH_OFFSET = 4
private const val MCFG_HEADER_LENGTH = SDT_HEADER_LENGTH + 8
private const val MCFG_ENTRY_LENGTH = 16
private const val MCFG_ENTRY_BASE_ADDRESS_OFFSET = 0
private const val MCFG_ENTRY_SEGMENT_GROUP_OFFSET = 8
private const val MCFG_ENTRY_START_BUS_OFFSET = 10
private const val MCFG_ENTRY_END_BUS_OFFSET = 11
private const val MADT_HEADER_LENGTH = SDT_HEADER_LENGTH + 8
private const val HPET_GAS_SPACE_ID_OFFSET = SDT_HEADER_LENGTH + 4
private const val HPET_GAS_ADDRESS_OFFSET = SDT_HEADER_LENGTH + 8
private const val SPCR_GAS_ADDRESS_OFFSET = SDT_HEADER_LENGTH + 8

private data class AcpiTable(
    val pointer: CPointer<UByteVar>,
    val length: Int,
)

private data class RootSdt(
    val table: AcpiTable,
    val entrySize: Int,
)

private data class MadtInfo(
    val lapicAddress: UInt,
    val ioapicAddress: UInt,
)

private data class McfgInfo(
    val totalRegionCount: Int,
    val regions: List<PcieEcamRegion>,
)

private data class HpetGasAddress(
    val spaceId: UInt,
    val address: ULong,
)

private interface AcpiTableParser<out T> {
    val signature: String
    fun parse(table: AcpiTable): T?
}

private object McfgParser : AcpiTableParser<McfgInfo> {
    override val signature: String = "MCFG"

    override fun parse(table: AcpiTable): McfgInfo? {
        if (table.length < MCFG_HEADER_LENGTH) {
            println("ACPI: invalid MCFG length=${table.length}")
            return null
        }

        val payloadSize = table.length - MCFG_HEADER_LENGTH
        val totalRegionCount = payloadSize / MCFG_ENTRY_LENGTH
        if (payloadSize % MCFG_ENTRY_LENGTH != 0) {
            println("ACPI: malformed MCFG payload size=$payloadSize")
        }

        val regions = buildList {
            var offset = MCFG_HEADER_LENGTH
            repeat(totalRegionCount) { index ->
                val baseAddress = table.pointer.readU64(offset + MCFG_ENTRY_BASE_ADDRESS_OFFSET)
                val segmentGroup = table.pointer.readU16(offset + MCFG_ENTRY_SEGMENT_GROUP_OFFSET).toUInt()
                val startBus = table.pointer.readU8(offset + MCFG_ENTRY_START_BUS_OFFSET).toUInt()
                val endBus = table.pointer.readU8(offset + MCFG_ENTRY_END_BUS_OFFSET).toUInt()

                if (baseAddress == 0uL || endBus < startBus) {
                    println(
                        "ACPI: ignore MCFG region#$index seg=$segmentGroup bus=$startBus-$endBus base=${baseAddress.hex()}",
                    )
                } else {
                    add(
                        PcieEcamRegion(
                            baseAddress = baseAddress,
                            segmentGroup = segmentGroup,
                            startBus = startBus,
                            endBus = endBus,
                        ),
                    )
                }

                offset += MCFG_ENTRY_LENGTH
            }
        }

        return McfgInfo(totalRegionCount = totalRegionCount, regions = regions)
    }
}

private object MadtParser : AcpiTableParser<MadtInfo> {
    override val signature: String = "APIC"

    override fun parse(table: AcpiTable): MadtInfo? {
        if (table.length < MADT_HEADER_LENGTH) {
            println("ACPI: invalid MADT length=${table.length}")
            return null
        }

        val lapicAddress = table.pointer.readU32(SDT_HEADER_LENGTH)
        var ioapicAddress = 0u
        var cursor = MADT_HEADER_LENGTH

        while (cursor + 2 <= table.length) {
            val entryType = table.pointer.readU8(cursor).toUInt()
            val entryLength = table.pointer.readU8(cursor + 1).toInt()
            if (entryLength < 2 || cursor + entryLength > table.length) {
                println("ACPI: malformed MADT entry at offset=$cursor")
                break
            }

            if (entryType == 1u && entryLength >= 12) {
                ioapicAddress = table.pointer.readU32(cursor + 4)
            }
            cursor += entryLength
        }

        return MadtInfo(
            lapicAddress = lapicAddress,
            ioapicAddress = ioapicAddress,
        )
    }
}

private object HpetParser : AcpiTableParser<HpetGasAddress> {
    override val signature: String = "HPET"

    override fun parse(table: AcpiTable): HpetGasAddress? {
        if (table.length < HPET_GAS_ADDRESS_OFFSET + 8) {
            println("ACPI: invalid HPET length=${table.length}")
            return null
        }
        val spaceId = table.pointer.readU8(HPET_GAS_SPACE_ID_OFFSET).toUInt()
        val address = table.pointer.readU64(HPET_GAS_ADDRESS_OFFSET)
        return HpetGasAddress(spaceId = spaceId, address = address)
    }
}

private object SpcrParser : AcpiTableParser<ULong> {
    override val signature: String = "SPCR"

    override fun parse(table: AcpiTable): ULong? {
        if (table.length < SPCR_GAS_ADDRESS_OFFSET + 8) {
            println("ACPI: invalid SPCR length=${table.length}")
            return null
        }
        return table.pointer.readU64(SPCR_GAS_ADDRESS_OFFSET)
    }
}

object Acpi {
    private var root: RootSdt? = null
    private val tableIndex = linkedMapOf<String, ULong>()

    fun initialize() {
        if (!Hhdm.isReady && Hhdm.initialize() == null) {
            println("ACPI: HHDM is unavailable")
            return
        }

        val rsdp = rsdp_request.response?.pointed?.address?.reinterpret<UByteVar>()
        if (rsdp == null) {
            println("ACPI: limine did not provide RSDP")
            return
        }
        if (!rsdp.matchesAscii(0, "RSD PTR ")) {
            println("ACPI: invalid RSDP signature")
            return
        }

        val revision = rsdp.readU8(RSDP_REVISION_OFFSET).toUInt()
        if (!rsdp.checksumOk(RSDP_V1_LENGTH)) {
            println("ACPI: RSDP v1 checksum failed")
        }
        if (revision >= 2u) {
            val rsdpLength = rsdp.readU32(RSDP_LENGTH_OFFSET).toInt()
            if (rsdpLength >= RSDP_V2_MIN_LENGTH && !rsdp.checksumOk(rsdpLength)) {
                println("ACPI: RSDP v2 checksum failed")
            }
        }

        val useXsdt = revision != 0u
        val rootAddress = if (useXsdt) {
            rsdp.readU64(RSDP_XSDT_ADDRESS_OFFSET)
        } else {
            rsdp.readU32(RSDP_RSDT_ADDRESS_OFFSET).toULong()
        }
        if (rootAddress == 0uL) {
            println("ACPI: root SDT address is zero")
            return
        }

        val rootTable = tableAt(rootAddress) ?: run {
            println("ACPI: cannot access root SDT at ${rootAddress.hex()}")
            return
        }
        root = RootSdt(rootTable, if (useXsdt) 8 else 4)
        val rootSignature = rootTable.pointer.readAscii(0, 4)

        println("ACPI revision: $revision")
        println("ACPI root SDT: $rootSignature at ${rootAddress.hex()}")

        rebuildTableIndex()

        parseIfFound(HpetParser) { hpetGasAddress ->
            println("ACPI: HPET address=${hpetGasAddress.address.hex()}")
            Hpet.initialize(
                baseAddress = hpetGasAddress.address,
                spaceId = hpetGasAddress.spaceId,
            )
        }

        parseIfFound(McfgParser) { mcfg ->
            println("ACPI: MCFG region count=${mcfg.totalRegionCount} usable=${mcfg.regions.size}")
            Pcie.initialize(mcfg.regions)
        }

        parseIfFound(MadtParser) { madt ->
            println("ACPI: LAPIC address=${madt.lapicAddress.hex()}")
            println("ACPI: IOAPIC address=${madt.ioapicAddress.hex()}")
            if (madt.lapicAddress == 0u) {
                println("ACPI: LAPIC address is invalid, skip APIC init")
            } else {
                Apic.initialize(
                    lapicPhysicalAddress = madt.lapicAddress,
                    ioapicPhysicalAddress = madt.ioapicAddress,
                )
            }
        }

        parseIfFound(SpcrParser) { uartAddress ->
            println("ACPI: UART base=${uartAddress.hex()}")
        }
    }

    private fun rebuildTableIndex() {
        tableIndex.clear()
        scanRootEntries { signature, address ->
            if (signature !in tableIndex) {
                tableIndex[signature] = address
            }
        }
    }

    private inline fun <T> parseIfFound(
        parser: AcpiTableParser<T>,
        onParsed: (T) -> Unit,
    ) {
        val tableAddress = findSdt(parser.signature) ?: return
        println("ACPI: found ${parser.signature} at ${tableAddress.hex()}")
        val table = tableAt(tableAddress) ?: return
        parser.parse(table)?.let(onParsed)
    }

    private fun findSdt(signature: String): ULong? {
        if (signature.length != 4) {
            return null
        }
        if (tableIndex.isEmpty()) {
            rebuildTableIndex()
        }
        return tableIndex[signature]
    }

    private inline fun scanRootEntries(
        consume: (signature: String, address: ULong) -> Unit,
    ) {
        val currentRoot = root ?: return
        val payloadLength = currentRoot.table.length - SDT_HEADER_LENGTH
        if (payloadLength <= 0) {
            return
        }

        val count = payloadLength / currentRoot.entrySize
        var offset = SDT_HEADER_LENGTH
        repeat(count) {
            val tableAddress = if (currentRoot.entrySize == 8) {
                currentRoot.table.pointer.readU64(offset)
            } else {
                currentRoot.table.pointer.readU32(offset).toULong()
            }
            offset += currentRoot.entrySize

            if (tableAddress == 0uL) {
                return@repeat
            }

            val table = tableAt(tableAddress) ?: return@repeat
            consume(table.pointer.readAscii(0, 4), tableAddress)
        }
    }
}

private fun tableAt(address: ULong): AcpiTable? {
    val pointer = address.toVirtualPointer<UByteVar>() ?: return null

    val length = pointer.readU32(SDT_LENGTH_OFFSET).toInt()
    if (length < SDT_HEADER_LENGTH) {
        return null
    }
    return AcpiTable(pointer = pointer, length = length)
}
