@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.utils.hasBit
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer

private const val PCI_BYTE_MASK = 0xFFu
private const val PCI_HEADER_TYPE_MASK = 0x7Fu
private const val PCI_WORD_MASK = 0xFFFFu
private const val PCI_CLASS_CODE_MASK = 0x00FF_FFFFu
private const val PCI_INVALID_VENDOR_ID = PCI_WORD_MASK
private const val PCI_VENDOR_DEVICE_OFFSET = 0x00
private const val PCI_CLASS_REVISION_OFFSET = 0x08
private const val PCI_HEADER_TYPE_OFFSET = 0x0C
private const val PCI_BRIDGE_BUS_NUMBERS_OFFSET = 0x18
private const val PCI_INTERRUPT_OFFSET = 0x3C
private const val PCI_BUS_SHIFT = 20
private const val PCI_DEVICE_SHIFT = 15
private const val PCI_FUNCTION_SHIFT = 12
private const val PCI_FUNCTION_CONFIG_SIZE = 0x1000uL
private const val PCI_CONFIG_REGISTER_MAX_OFFSET = 0xFFC
private const val PCI_CONFIG_REGISTER_ALIGNMENT = UInt.SIZE_BYTES
private const val PCI_CLASS_SUBCLASS_MASK = 0xFFFF00u
private const val PCI_CLASS_PCI_BRIDGE = 0x060400u
private const val PCI_CLASS_SUBTRACTIVE_PCI_BRIDGE = 0x060900u
private val PCI_INVALID_CONFIG_VALUE = UInt.MAX_VALUE

private val PCI_CONFIG_REGISTER_RANGE = 0..PCI_CONFIG_REGISTER_MAX_OFFSET
private val PCI_BUS_RANGE = 0..255
private val PCI_DEVICE_RANGE = 0..31
private val PCI_FUNCTION_RANGE = 0..7
private val PCI_SECONDARY_FUNCTION_RANGE = 1..7
private val PCI_BRIDGE_CLASS_CODES = setOf(
    PCI_CLASS_PCI_BRIDGE,
    PCI_CLASS_SUBTRACTIVE_PCI_BRIDGE,
)

private val PCI_CLASS_NAMES = mapOf(
    0x000000u to "Non-VGA-Compatible Unclassified Device",
    0x000100u to "VGA-Compatible Unclassified Device",
    0x010000u to "SCSI Bus Controller",
    0x010100u to "IDE Controller",
    0x010200u to "Floppy Disk Controller",
    0x010300u to "IPI Bus Controller",
    0x010400u to "RAID Controller",
    0x010500u to "ATA Controller",
    0x010600u to "Serial ATA Controller",
    0x010700u to "Serial Attached SCSI Controller",
    0x010802u to "NVM Express Controller",
    0x018000u to "Other Mass Storage Controller",
    0x020000u to "Ethernet Controller",
    0x020100u to "Token Ring Controller",
    0x020200u to "FDDI Controller",
    0x020300u to "ATM Controller",
    0x020400u to "ISDN Controller",
    0x020500u to "WorldFip Controller",
    0x020600u to "PICMG 2.14 Multi Computing Controller",
    0x020700u to "Infiniband Controller",
    0x020800u to "Fabric Controller",
    0x028000u to "Other Network Controller",
    0x030000u to "VGA Compatible Controller",
    0x030100u to "XGA Controller",
    0x030200u to "3D Controller (Not VGA-Compatible)",
    0x038000u to "Other Display Controller",
    0x040000u to "Multimedia Video Controller",
    0x040100u to "Multimedia Audio Controller",
    0x040200u to "Computer Telephony Device",
    0x040300u to "Audio Device",
    0x048000u to "Other Multimedia Controller",
    0x050000u to "RAM Controller",
    0x050100u to "Flash Controller",
    0x058000u to "Other Memory Controller",
    0x060000u to "Host Bridge",
    0x060100u to "ISA Bridge",
    0x060200u to "EISA Bridge",
    0x060300u to "MCA Bridge",
    0x060400u to "PCI-to-PCI Bridge",
    0x060500u to "PCMCIA Bridge",
    0x060600u to "NuBus Bridge",
    0x060700u to "CardBus Bridge",
    0x060800u to "RACEway Bridge",
    0x060900u to "PCI-to-PCI Bridge",
    0x060A00u to "InfiniBand-to-PCI Host Bridge",
    0x068000u to "Other Bridge",
    0x070000u to "Serial Controller",
    0x070100u to "Parallel Controller",
    0x070200u to "Multiport Serial Controller",
    0x070300u to "Modem",
    0x070400u to "IEEE 488.1/2 (GPIB) Controller",
    0x070500u to "Smart Card Controller",
    0x078000u to "Other Simple Communication Controller",
    0x080000u to "PIC",
    0x080100u to "DMA Controller",
    0x080200u to "Timer",
    0x080300u to "RTC Controller",
    0x080400u to "PCI Hot-Plug Controller",
    0x080500u to "SD Host controller",
    0x080600u to "IOMMU",
    0x088000u to "Other Base System Peripheral",
    0x090000u to "Keyboard Controller",
    0x090100u to "Digitizer Pen",
    0x090200u to "Mouse Controller",
    0x090300u to "Scanner Controller",
    0x090400u to "Gameport Controller",
    0x098000u to "Other Input Device Controller",
    0x0A0000u to "Generic",
    0x0A8000u to "Other Docking Station",
    0x0B0000u to "386",
    0x0B0100u to "486",
    0x0B0200u to "Pentium",
    0x0B0300u to "Pentium Pro",
    0x0B1000u to "Alpha",
    0x0B2000u to "PowerPC",
    0x0B3000u to "MIPS",
    0x0B4000u to "Co-Processor",
    0x0B8000u to "Other Processor",
    0x0C0000u to "FireWire (IEEE 1394) Controller",
    0x0C0100u to "ACCESS Bus Controller",
    0x0C0200u to "SSA",
    0x0C0300u to "USB Controller",
    0x0C0400u to "Fibre Channel",
    0x0C0500u to "SMBus Controller",
    0x0C0600u to "InfiniBand Controller",
    0x0C0700u to "IPMI Interface",
    0x0C0800u to "SERCOS Interface (IEC 61491)",
    0x0C0900u to "CANbus Controller",
    0x0C8000u to "Other Serial Bus Controller",
    0x0D0000u to "iRDA Compatible Controlle",
    0x0D0100u to "Consumer IR Controller",
    0x0D1000u to "RF Controller",
    0x0D1100u to "Bluetooth Controller",
    0x0D1200u to "Broadband Controller",
    0x0D2000u to "Ethernet Controller (802.1a)",
    0x0D2100u to "Ethernet Controller (802.1b)",
    0x0D8000u to "Other Wireless Controller",
    0x0E0000u to "I20",
    0x0F0000u to "Satellite TV Controller",
    0x0F0100u to "Satellite Audio Controller",
    0x0F0300u to "Satellite Voice Controller",
    0x0F0400u to "Satellite Data Controller",
    0x100000u to "Network and Computing Encrpytion/Decryption",
    0x101000u to "Entertainment Encryption/Decryption",
    0x108000u to "Other Encryption Controller",
    0x110000u to "DPIO Modules",
    0x110100u to "Performance Counters",
    0x111000u to "Communication Synchronizer",
    0x112000u to "Signal Processing Management",
    0x118000u to "Other Signal Processing Controller",
)

data class PcieEcamRegion(
    val baseAddress: ULong,
    val segmentGroup: UInt,
    val startBus: UInt,
    val endBus: UInt,
) {
    val busStart: Int
        get() = startBus.toInt()

    val busEnd: Int
        get() = endBus.toInt()

    val busRange: IntRange
        get() = busStart..busEnd

    val isUsable: Boolean
        get() = baseAddress != 0uL &&
            busStart in PCI_BUS_RANGE &&
            busEnd in PCI_BUS_RANGE &&
            !busRange.isEmpty()
}

data class PciDeviceInfo(
    val segmentGroup: UInt,
    val bus: UInt,
    val device: UInt,
    val function: UInt,
    val vendorId: UInt,
    val deviceId: UInt,
    val classCode: UInt,
    val className: String,
    val revisionId: UInt,
    val headerType: UInt,
    val interruptLine: UInt,
    val interruptPin: UInt,
)

private data class PciFunctionAddress(
    val segmentGroup: UInt,
    val bus: Int,
    val device: Int,
    val function: Int,
) {
    val bsf: String
        get() = "$bus:$device:$function"

    fun offsetFrom(busStart: Int): ULong =
        ((bus - busStart).toULong() shl PCI_BUS_SHIFT) +
            (device.toULong() shl PCI_DEVICE_SHIFT) +
            (function.toULong() shl PCI_FUNCTION_SHIFT)
}

object Pcie {
    private data class MappedRegion(
        val descriptor: PcieEcamRegion,
        val scannedBuses: MutableSet<Int> = hashSetOf(),
        val mappedFunctions: MutableMap<ULong, ULong> = hashMapOf(),
    ) {
        fun contains(address: PciFunctionAddress): Boolean =
            address.bus in descriptor.busRange &&
                address.device in PCI_DEVICE_RANGE &&
                address.function in PCI_FUNCTION_RANGE
    }

    private val devices = mutableListOf<PciDeviceInfo>()
    private val enumeratedLocations = hashSetOf<PciFunctionAddress>()
    private val activeRegions = mutableListOf<MappedRegion>()

    val enumeratedDevices: List<PciDeviceInfo>
        get() = devices.toList()

    private fun classNameOf(classCode: UInt): String =
        PCI_CLASS_NAMES[classCode]
            ?: PCI_CLASS_NAMES[classCode and PCI_CLASS_SUBCLASS_MASK]
            ?: "Unknown device"

    fun initialize(regions: List<PcieEcamRegion>) {
        devices.clear()
        enumeratedLocations.clear()
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

        activeRegions.forEach { region -> scanBus(region, region.descriptor.busStart) }
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
        if (byteCount !in 1..8 || offset.toULong() + byteCount.toULong() > PCI_FUNCTION_CONFIG_SIZE) {
            return null
        }
        val address = PciFunctionAddress(
            segmentGroup = segment,
            bus = bus.toInt(),
            device = device.toInt(),
            function = function.toInt(),
        )
        val region = activeRegions.firstOrNull { it.contains(address) } ?: return null
        var value = 0uL
        repeat(byteCount) { index ->
            val byte = readConfig8(region, address, offset.toInt() + index) ?: return null
            value = value or (byte.toULong() shl (index * 8))
        }
        return value
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
        if (byteCount !in 1..8 || offset.toULong() + byteCount.toULong() > PCI_FUNCTION_CONFIG_SIZE) {
            return false
        }
        val address = PciFunctionAddress(
            segmentGroup = segment,
            bus = bus.toInt(),
            device = device.toInt(),
            function = function.toInt(),
        )
        val region = activeRegions.firstOrNull { it.contains(address) } ?: return false
        repeat(byteCount) { index ->
            if (!writeConfig8(
                    region,
                    address,
                    offset.toInt() + index,
                    (value shr (index * 8)).toUByte(),
                )
            ) {
                return false
            }
        }
        return true
    }

    private fun mapRegion(region: PcieEcamRegion): MappedRegion? {
        if (!region.isUsable) {
            println("PCIe: ignore invalid region seg=${region.segmentGroup} bus=${region.busStart}-${region.busEnd}")
            return null
        }

        val busRange = "${region.busStart}-${region.busEnd}"
        println("PCIe: ECAM seg=${region.segmentGroup} bus=$busRange base=${region.baseAddress.hex()}")
        return MappedRegion(descriptor = region)
    }

    private fun scanBus(region: MappedRegion, bus: Int) {
        if (!region.scannedBuses.add(bus)) {
            return
        }

        for (device in PCI_DEVICE_RANGE) {
            val firstFunction = PciFunctionAddress(region.descriptor.segmentGroup, bus, device, 0)
            val firstVendorId = readConfig32(region, firstFunction, PCI_VENDOR_DEVICE_OFFSET) and PCI_WORD_MASK
            if (firstVendorId == PCI_INVALID_VENDOR_ID) {
                continue
            }

            scanFunction(region, firstFunction)

            val headerType = (readConfig32(region, firstFunction, PCI_HEADER_TYPE_OFFSET) shr 16) and PCI_BYTE_MASK
            if (headerType.toULong().hasBit(7)) {
                for (function in PCI_SECONDARY_FUNCTION_RANGE) {
                    val address = firstFunction.copy(function = function)
                    val vendorId = readConfig32(region, address, PCI_VENDOR_DEVICE_OFFSET) and PCI_WORD_MASK
                    if (vendorId == PCI_INVALID_VENDOR_ID) {
                        continue
                    }
                    scanFunction(region, address)
                }
            }
        }
    }

    private fun scanFunction(region: MappedRegion, address: PciFunctionAddress) {
        if (!enumeratedLocations.add(address)) {
            return
        }

        val idRegister = readConfig32(region, address, PCI_VENDOR_DEVICE_OFFSET)
        val vendorId = idRegister and PCI_WORD_MASK
        if (vendorId == PCI_INVALID_VENDOR_ID) {
            return
        }

        val classRegister = readConfig32(region, address, PCI_CLASS_REVISION_OFFSET)
        val headerRegister = readConfig32(region, address, PCI_HEADER_TYPE_OFFSET)
        val interruptRegister = readConfig32(region, address, PCI_INTERRUPT_OFFSET)
        val classCode = (classRegister shr 8) and PCI_CLASS_CODE_MASK

        val deviceInfo = PciDeviceInfo(
            segmentGroup = address.segmentGroup,
            bus = address.bus.toUInt(),
            device = address.device.toUInt(),
            function = address.function.toUInt(),
            vendorId = vendorId,
            deviceId = (idRegister shr 16) and PCI_WORD_MASK,
            classCode = classCode,
            className = classNameOf(classCode),
            revisionId = classRegister and PCI_BYTE_MASK,
            headerType = (headerRegister shr 16) and PCI_HEADER_TYPE_MASK,
            interruptLine = interruptRegister and PCI_BYTE_MASK,
            interruptPin = (interruptRegister shr 8) and PCI_BYTE_MASK,
        )
        devices += deviceInfo

        println(
            "PCIe: dev seg=${deviceInfo.segmentGroup} BSF=${address.bsf} " +
                "vid=${deviceInfo.vendorId.hex()} did=${deviceInfo.deviceId.hex()} " +
                "class=${deviceInfo.classCode.hex()} name=${deviceInfo.className}",
        )

        if (classCode in PCI_BRIDGE_CLASS_CODES) {
            val busNumbers = readConfig32(region, address, PCI_BRIDGE_BUS_NUMBERS_OFFSET)
            val secondaryBus = ((busNumbers shr 8) and PCI_BYTE_MASK).toInt()
            if (secondaryBus in region.descriptor.busRange && secondaryBus != address.bus) {
                scanBus(region, secondaryBus)
            }
        }
    }

    private fun readConfig32(
        region: MappedRegion,
        address: PciFunctionAddress,
        offset: Int,
    ): UInt {
        if (offset !in PCI_CONFIG_REGISTER_RANGE || offset % PCI_CONFIG_REGISTER_ALIGNMENT != 0) {
            return PCI_INVALID_CONFIG_VALUE
        }
        if (!region.contains(address)) {
            return PCI_INVALID_CONFIG_VALUE
        }

        val functionBase = region.descriptor.baseAddress + address.offsetFrom(region.descriptor.busStart)
        val virtualBase = mapFunction(region, functionBase) ?: return PCI_INVALID_CONFIG_VALUE
        val registerAddress = virtualBase + offset.toULong()
        return registerAddress.toPointer<UIntVar>()?.get(0) ?: PCI_INVALID_CONFIG_VALUE
    }

    private fun readConfig8(
        region: MappedRegion,
        address: PciFunctionAddress,
        offset: Int,
    ): UByte? {
        if (offset !in 0 until PCI_FUNCTION_CONFIG_SIZE.toInt() || !region.contains(address)) {
            return null
        }
        val functionBase = region.descriptor.baseAddress + address.offsetFrom(region.descriptor.busStart)
        val virtualBase = mapFunction(region, functionBase) ?: return null
        return (virtualBase + offset.toULong()).toPointer<UByteVar>()?.get(0)
    }

    private fun writeConfig8(
        region: MappedRegion,
        address: PciFunctionAddress,
        offset: Int,
        value: UByte,
    ): Boolean {
        if (offset !in 0 until PCI_FUNCTION_CONFIG_SIZE.toInt() || !region.contains(address)) {
            return false
        }
        val functionBase = region.descriptor.baseAddress + address.offsetFrom(region.descriptor.busStart)
        val virtualBase = mapFunction(region, functionBase) ?: return false
        val register = (virtualBase + offset.toULong()).toPointer<UByteVar>() ?: return false
        register[0] = value
        return true
    }

    private fun mapFunction(region: MappedRegion, physicalAddress: ULong): ULong? {
        return region.mappedFunctions.getOrPut(physicalAddress) {
            KernelPageDirectory.mapMmio(physicalAddress, PCI_FUNCTION_CONFIG_SIZE) ?: return null
        }
    }
}
