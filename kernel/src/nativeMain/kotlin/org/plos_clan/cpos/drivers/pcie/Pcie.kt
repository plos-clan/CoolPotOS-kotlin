package org.plos_clan.cpos.drivers.pcie

import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.hex

data class PcieEcamRegion(
    val baseAddress: ULong,
    val segmentGroup: UShort,
    val startBus: UByte,
    val endBus: UByte,
) {
    val busRange: IntRange
        get() = startBus.toInt()..endBus.toInt()

    val isUsable: Boolean
        get() = baseAddress != 0uL &&
            startBus <= endBus
}

object Pcie {
    private val devices = mutableListOf<PciDevice>()
    private val activeRegions = mutableListOf<MappedEcamRegion>()

    val enumeratedDevices: List<PciDevice>
        get() = devices.toList()

    fun initialize(regions: List<PcieEcamRegion>) {
        devices.clear()
        activeRegions.clear()

        if (regions.isEmpty()) {
            println("PCIe: no usable ECAM region, skip enumeration")
            return
        }

        activeRegions += regions.mapNotNull(::mapRegion)
        if (activeRegions.isEmpty()) {
            println("PCIe: failed to map any ECAM region")
            return
        }

        activeRegions.forEach { region ->
            PciScanner(region.descriptor.segmentGroup, devices::add)
                .scanRegion(region.descriptor.startBus.toInt(), region.descriptor.endBus.toInt())
        }
        println("PCIe: enumeration complete devices=${devices.size}")
    }

    fun readConfig(
        segment: UInt,
        bus: UInt,
        device: UInt,
        function: UInt,
        offset: UInt,
        byteCount: Int,
    ): ULong? {
        if (!validConfigRequest(segment, bus, device, function, offset, byteCount)) return null
        val address = PciAddress.of(
            segment.toUShort(),
            bus.toUByte(),
            device.toUByte(),
            function.toUByte(),
        )
        val config = configurationSpace(address) ?: return null
        return config.read(offset.toInt(), byteCount)
    }

    fun writeConfig(
        segment: UInt,
        bus: UInt,
        device: UInt,
        function: UInt,
        offset: UInt,
        byteCount: Int,
        value: ULong,
    ): Boolean {
        if (!validConfigRequest(segment, bus, device, function, offset, byteCount)) return false
        val address = PciAddress.of(
            segment.toUShort(),
            bus.toUByte(),
            device.toUByte(),
            function.toUByte(),
        )
        val config = configurationSpace(address) ?: return false
        config.write(offset.toInt(), byteCount, value)
        return true
    }

    internal fun configurationSpace(address: PciAddress): PciConfigSpace? =
        activeRegions.firstNotNullOfOrNull { it.configurationSpace(address) }

    private fun validConfigRequest(
        segment: UInt,
        bus: UInt,
        device: UInt,
        function: UInt,
        offset: UInt,
        byteCount: Int,
    ): Boolean {
        if (segment > UShort.MAX_VALUE.toUInt() ||
            bus > UByte.MAX_VALUE.toUInt() ||
            device > 31u ||
            function > 7u ||
            byteCount !in 1..8
        ) {
            return false
        }
        val end = offset.toULong() + byteCount.toULong()
        return end >= offset.toULong() && end <= PCI_FUNCTION_CONFIG_SIZE
    }

    private fun mapRegion(region: PcieEcamRegion): MappedEcamRegion? {
        if (!region.isUsable) {
            println(
                "PCIe: ignore invalid region seg=${region.segmentGroup} " +
                    "bus=${region.startBus}-${region.endBus}",
            )
            return null
        }

        val busCount = (region.endBus.toInt() - region.startBus.toInt() + 1).toULong()
        val byteLength = busCount shl 20
        println(
            "PCIe: ECAM seg=${region.segmentGroup} " +
                "bus=${region.startBus}-${region.endBus} base=${region.baseAddress.hex()}",
        )
        val mapping = MmioRegion.map(region.baseAddress, byteLength) ?: return null
        return MappedEcamRegion(region, mapping)
    }

    private data class MappedEcamRegion(
        val descriptor: PcieEcamRegion,
        val mapping: MmioRegion,
    ) {
        fun contains(address: PciAddress): Boolean =
            address.segment == descriptor.segmentGroup &&
                address.bus.toInt() in descriptor.busRange &&
                address.device.toInt() in 0..31 &&
                address.function.toInt() in 0..7

        fun configurationSpace(address: PciAddress): PciConfigSpace? {
            if (!contains(address)) return null
            val offset = address.offsetFrom(descriptor.startBus.toInt())
            val base = mapping.addressAt(offset, PCI_FUNCTION_CONFIG_SIZE.toInt()) ?: return null
            return PciConfigSpace(base)
        }
    }
}

internal const val PCI_FUNCTION_CONFIG_SIZE = 0x1000uL
