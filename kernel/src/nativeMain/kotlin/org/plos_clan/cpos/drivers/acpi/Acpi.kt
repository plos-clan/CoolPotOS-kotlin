@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi

import bridge.rsdp_request
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.drivers.acpi.aml.Aml
import org.plos_clan.cpos.drivers.acpi.apic.Apic
import org.plos_clan.cpos.drivers.acpi.fadt.FadtParser
import org.plos_clan.cpos.drivers.pcie.Pcie
import org.plos_clan.cpos.drivers.pcie.PcieEcamRegion
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.utils.checksumOk
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.matchesAscii
import org.plos_clan.cpos.utils.readAscii
import org.plos_clan.cpos.utils.readU16
import org.plos_clan.cpos.utils.readU32
import org.plos_clan.cpos.utils.readU64
import org.plos_clan.cpos.utils.readU8
import org.plos_clan.cpos.utils.toVirtualPointer

private const val RSDP_V1_LENGTH = 20
private const val RSDP_V2_MIN_LENGTH = 36
private const val RSDP_REVISION_OFFSET = 15
private const val RSDP_RSDT_ADDRESS_OFFSET = 16
private const val RSDP_LENGTH_OFFSET = 20
private const val RSDP_XSDT_ADDRESS_OFFSET = 24
private const val SDT_HEADER_LENGTH = 36
private const val SDT_LENGTH_OFFSET = 4
private const val MAX_ACPI_TABLE_LENGTH = 16 * 1024 * 1024
private const val MAX_RSDP_LENGTH = 4096
private const val MCFG_HEADER_LENGTH = SDT_HEADER_LENGTH + 8
private const val MCFG_ENTRY_LENGTH = 16
private const val MCFG_ENTRY_BASE_ADDRESS_OFFSET = 0
private const val MCFG_ENTRY_SEGMENT_GROUP_OFFSET = 8
private const val MCFG_ENTRY_START_BUS_OFFSET = 10
private const val MCFG_ENTRY_END_BUS_OFFSET = 11
private const val MADT_HEADER_LENGTH = SDT_HEADER_LENGTH + 8
private const val SPCR_GAS_ADDRESS_OFFSET = SDT_HEADER_LENGTH + 8

data class AcpiTable(
    val physicalAddress: ULong,
    val pointer: CPointer<UByteVar>,
    val length: Int,
) {
    val signature: String
        get() = pointer.readAscii(0, 4)

    fun hasLength(requiredLength: Int): Boolean =
        (length >= requiredLength).also { valid ->
            if (!valid) {
                println("ACPI: invalid $signature length=$length required=$requiredLength")
            }
        }
}

private enum class RootSdtKind(val entrySize: Int) {
    RSDT(UInt.SIZE_BYTES),
    XSDT(ULong.SIZE_BYTES);

    fun entryAddressAt(table: AcpiTable, offset: Int): ULong =
        when (this) {
            RSDT -> table.pointer.readU32(offset).toULong()
            XSDT -> table.pointer.readU64(offset)
        }

    fun rootAddressAt(rsdp: CPointer<UByteVar>): ULong =
        when (this) {
            RSDT -> rsdp.readU32(RSDP_RSDT_ADDRESS_OFFSET).toULong()
            XSDT -> rsdp.readU64(RSDP_XSDT_ADDRESS_OFFSET)
        }

    companion object {
        fun forRevision(revision: UInt): RootSdtKind =
            if (revision == 0u) RSDT else XSDT
    }
}

private data class RootSdt(
    val table: AcpiTable,
    val kind: RootSdtKind,
) {
    fun entryAddressAt(offset: Int): ULong =
        kind.entryAddressAt(table, offset)

    val entrySize: Int
        get() = kind.entrySize
}

private data class MadtInfo(
    val lapicAddress: UInt,
    val ioapicAddress: UInt,
)

private data class McfgInfo(
    val totalRegionCount: Int,
    val regions: List<PcieEcamRegion>,
)

interface AcpiTableParser<out T> {
    val signature: String
    fun parse(table: AcpiTable): T?
}

private object McfgParser : AcpiTableParser<McfgInfo> {
    override val signature: String = "MCFG"

    override fun parse(table: AcpiTable): McfgInfo? {
        if (!table.hasLength(MCFG_HEADER_LENGTH)) {
            return null
        }

        val payloadSize = table.length - MCFG_HEADER_LENGTH
        val totalRegionCount = payloadSize / MCFG_ENTRY_LENGTH
        if (payloadSize % MCFG_ENTRY_LENGTH != 0) {
            println("ACPI: malformed MCFG payload size=$payloadSize")
        }

        val regions = buildList {
            repeat(totalRegionCount) { index ->
                val offset = MCFG_HEADER_LENGTH + index * MCFG_ENTRY_LENGTH
                val baseAddress = table.pointer.readU64(offset + MCFG_ENTRY_BASE_ADDRESS_OFFSET)
                val segmentGroup = table.pointer.readU16(offset + MCFG_ENTRY_SEGMENT_GROUP_OFFSET).toUInt()
                val startBus = table.pointer.readU8(offset + MCFG_ENTRY_START_BUS_OFFSET).toUInt()
                val endBus = table.pointer.readU8(offset + MCFG_ENTRY_END_BUS_OFFSET).toUInt()
                val region = PcieEcamRegion(
                    baseAddress = baseAddress,
                    segmentGroup = segmentGroup.toUShort(),
                    startBus = startBus.toUByte(),
                    endBus = endBus.toUByte(),
                )

                if (region.isUsable) {
                    add(region)
                } else {
                    val busRange = "${region.startBus}-${region.endBus}"
                    val base = baseAddress.hex()
                    println("ACPI: ignore MCFG region#$index seg=$segmentGroup bus=$busRange base=$base")
                }
            }
        }

        return McfgInfo(totalRegionCount = totalRegionCount, regions = regions)
    }
}

private object MadtParser : AcpiTableParser<MadtInfo> {
    override val signature: String = "APIC"

    override fun parse(table: AcpiTable): MadtInfo? {
        if (!table.hasLength(MADT_HEADER_LENGTH)) {
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

private object SpcrParser : AcpiTableParser<ULong> {
    override val signature: String = "SPCR"

    override fun parse(table: AcpiTable): ULong? {
        if (!table.hasLength(SPCR_GAS_ADDRESS_OFFSET + ULong.SIZE_BYTES)) {
            return null
        }
        return table.pointer.readU64(SPCR_GAS_ADDRESS_OFFSET)
    }
}

object Acpi {
    private var root: RootSdt? = null
    private val tableIndex = mutableMapOf<String, MutableList<ULong>>()

    fun initialize(): Boolean {
        if (!initializeRoot()) {
            return false
        }

        parseIfFound(FadtParser) { fadt ->
            println(
                "ACPI: FADT SCI=${fadt.sciInterrupt}, " +
                    "DSDT=0x${fadt.dsdtAddress?.toString(16)}, " +
                    "i8042=${fadt.hasI8042Controller}",
            )
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

        return true
    }

    fun enumerateDevices() {
        if (root == null && !initializeRoot()) {
            return
        }

        parseIfFound(McfgParser) { mcfg ->
            println("ACPI: MCFG region count=${mcfg.totalRegionCount} usable=${mcfg.regions.size}")
            Pcie.initialize(mcfg.regions)
        }

        Aml.enumerateDevices()
    }

    fun findTable(signature: String): AcpiTable? =
        findTables(signature).firstOrNull()

    fun findTables(signature: String): List<AcpiTable> {
        if (signature.length != 4) {
            return emptyList()
        }
        if (tableIndex.isEmpty()) {
            rebuildTableIndex()
        }
        return tableIndex[signature]
            .orEmpty()
            .mapNotNull(::tableAt)
    }

    fun tableAtPhysical(address: ULong): AcpiTable? = tableAt(address)

    private fun initializeRoot(): Boolean {
        if (root != null) {
            return true
        }
        if (!Hhdm.isReady && Hhdm.initialize() == null) {
            println("ACPI: HHDM is unavailable")
            return false
        }

        val rsdp = rsdp_request.response?.pointed?.address?.reinterpret<UByteVar>() ?: run {
            println("ACPI: limine did not provide RSDP")
            return false
        }
        if (!rsdp.matchesAscii(0, "RSD PTR ")) {
            println("ACPI: invalid RSDP signature")
            return false
        }

        val revision = rsdp.readU8(RSDP_REVISION_OFFSET).toUInt()
        if (!rsdp.checksumOk(RSDP_V1_LENGTH)) {
            println("ACPI: RSDP v1 checksum failed")
            return false
        }
        if (revision >= 2u) {
            val rsdpLength = rsdp.readU32(RSDP_LENGTH_OFFSET)
            if (rsdpLength < RSDP_V2_MIN_LENGTH.toUInt() ||
                rsdpLength > MAX_RSDP_LENGTH.toUInt()
            ) {
                println("ACPI: invalid RSDP v2 length=$rsdpLength")
                return false
            }
            if (!rsdp.checksumOk(rsdpLength.toInt())) {
                println("ACPI: RSDP v2 checksum failed")
                return false
            }
        }

        val rootKind = RootSdtKind.forRevision(revision)
        val rootAddress = rootKind.rootAddressAt(rsdp)
        if (rootAddress == 0uL) {
            println("ACPI: root SDT address is zero")
            return false
        }

        val rootTable = tableAt(rootAddress) ?: run {
            println("ACPI: cannot access root SDT at ${rootAddress.hex()}")
            return false
        }
        if (rootTable.signature != rootKind.name || !rootTable.pointer.checksumOk(rootTable.length)) {
            println("ACPI: invalid ${rootKind.name} table")
            return false
        }
        root = RootSdt(rootTable, rootKind)
        println("ACPI revision: $revision")
        println("ACPI root SDT: ${rootTable.signature} at ${rootAddress.hex()}")

        rebuildTableIndex()
        return true
    }

    private fun rebuildTableIndex() {
        tableIndex.clear()
        scanRootEntries { signature, address ->
            tableIndex.getOrPut(signature) { mutableListOf() } += address
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
        return tableIndex[signature]?.firstOrNull()
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
            val tableAddress = currentRoot.entryAddressAt(offset)
            offset += currentRoot.entrySize

            if (tableAddress == 0uL) {
                return@repeat
            }

            val table = tableAt(tableAddress) ?: return@repeat
            if (!table.pointer.checksumOk(table.length)) {
                println("ACPI: ignore ${table.signature} with invalid checksum")
                return@repeat
            }
            consume(table.signature, tableAddress)
        }
    }
}

private fun tableAt(address: ULong): AcpiTable? {
    val pointer = address.toVirtualPointer<UByteVar>() ?: return null

    val lengthValue = pointer.readU32(SDT_LENGTH_OFFSET)
    if (lengthValue < SDT_HEADER_LENGTH.toUInt() ||
        lengthValue > MAX_ACPI_TABLE_LENGTH.toUInt()
    ) {
        return null
    }
    val length = lengthValue.toInt()
    return AcpiTable(
        physicalAddress = address,
        pointer = pointer,
        length = length,
    )
}
