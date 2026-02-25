@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.driver

import kotlinx.cinterop.*
import natives.rsdp_request
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.utils.checksumOk
import org.plos_clan.cpos.utils.hex32
import org.plos_clan.cpos.utils.hex64
import org.plos_clan.cpos.utils.matchesAscii
import org.plos_clan.cpos.utils.readAscii
import org.plos_clan.cpos.utils.readU32
import org.plos_clan.cpos.utils.readU64
import org.plos_clan.cpos.utils.readU8
import org.plos_clan.cpos.utils.toVirtualPointer

private const val RSDP_V1_LENGTH = 20
private const val RSDP_V2_MIN_LENGTH = 36
private const val SDT_HEADER_LENGTH = 36
private const val MCFG_HEADER_LENGTH = SDT_HEADER_LENGTH + 8
private const val MCFG_ENTRY_LENGTH = 16
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

private data class HpetGasAddress(
    val spaceId: UInt,
    val address: ULong,
)

private interface AcpiTableParser<out T> {
    val signature: String
    fun parse(table: AcpiTable): T?
}

private object McfgParser : AcpiTableParser<Int> {
    override val signature: String = "MCFG"

    override fun parse(table: AcpiTable): Int? {
        if (table.length < MCFG_HEADER_LENGTH) {
            println("ACPI: invalid MCFG length=${table.length}")
            return null
        }
        val payloadSize = table.length - MCFG_HEADER_LENGTH
        return payloadSize / MCFG_ENTRY_LENGTH
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

    fun init() {
        reset()

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

        val revision = rsdp.readU8(15).toUInt()
        if (!rsdp.checksumOk(RSDP_V1_LENGTH)) {
            println("ACPI: RSDP v1 checksum failed")
        }
        if (revision >= 2u) {
            val rsdpLength = rsdp.readU32(20).toInt()
            if (rsdpLength >= RSDP_V2_MIN_LENGTH && !rsdp.checksumOk(rsdpLength)) {
                println("ACPI: RSDP v2 checksum failed")
            }
        }

        val useXsdt = revision != 0u
        val rootAddress = if (useXsdt) rsdp.readU64(24) else rsdp.readU32(16).toULong()
        if (rootAddress == 0uL) {
            println("ACPI: root SDT address is zero")
            return
        }

        val rootTable = tableAt(rootAddress)
        if (rootTable == null) {
            println("ACPI: cannot access root SDT at 0x${rootAddress.hex64()}")
            return
        }

        root = RootSdt(
            table = rootTable,
            entrySize = if (useXsdt) 8 else 4,
        )

        println("ACPI revision: $revision")
        println(
            "ACPI root SDT: ${rootTable.pointer.readAscii(0, 4)} at 0x${rootAddress.hex64()} length=${rootTable.length}",
        )

        rebuildTableIndex()

        parseIfFound(HpetParser) { hpetGasAddress ->
            println(
                "ACPI: HPET GAS space_id=${hpetGasAddress.spaceId} address=0x${hpetGasAddress.address.hex64()}",
            )
            Hpet.initialize(
                baseAddress = hpetGasAddress.address,
                spaceId = hpetGasAddress.spaceId,
            )
        }

        parseIfFound(McfgParser) { regionCount ->
            println("ACPI: MCFG region count=$regionCount")
        }
        parseIfFound(MadtParser) { madt ->
            println("ACPI: LAPIC address=0x${madt.lapicAddress.hex32()}")
            println("ACPI: IOAPIC address=0x${madt.ioapicAddress.hex32()}")
        }

        parseIfFound(SpcrParser) { uartAddress ->
            println("ACPI: UART base=0x${uartAddress.hex64()}")
        }
    }

    private fun reset() {
        root = null
        tableIndex.clear()
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
        println("ACPI: found ${parser.signature} at 0x${tableAddress.hex64()}")
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

    val length = pointer.readU32(4).toInt()
    if (length < SDT_HEADER_LENGTH) {
        return null
    }
    return AcpiTable(pointer = pointer, length = length)
}
