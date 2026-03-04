@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers

import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.get
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.utils.hasBit
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.toPointer

private const val PCI_INVALID_VENDOR_ID = 0xFFFFu
private const val PCI_VENDOR_DEVICE_OFFSET = 0x00
private const val PCI_CLASS_REVISION_OFFSET = 0x08
private const val PCI_HEADER_TYPE_OFFSET = 0x0C
private const val PCI_INTERRUPT_OFFSET = 0x3C
private const val PCI_BUS_SHIFT = 20
private const val PCI_DEVICE_SHIFT = 15
private const val PCI_FUNCTION_SHIFT = 12
private const val PCI_CLASS_SUBCLASS_MASK = 0xFFFF00u

private val PCI_CLASS_NAMES = linkedMapOf(
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
)

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

object Pcie {
    private data class MappedRegion(
        val descriptor: PcieEcamRegion,
        val virtualBase: ULong,
        val busStart: Int,
        val busEnd: Int,
    )

    private val devices = mutableListOf<PciDeviceInfo>()
    private val enumeratedLocations = hashSetOf<ULong>()

    val enumeratedDevices: List<PciDeviceInfo>
        get() = devices.toList()

    private fun classNameOf(classCode: UInt): String =
        PCI_CLASS_NAMES[classCode]
            ?: PCI_CLASS_NAMES[classCode and PCI_CLASS_SUBCLASS_MASK]
            ?: "Unknown device"

    fun initialize(regions: List<PcieEcamRegion>) {
        devices.clear()
        enumeratedLocations.clear()

        if (regions.isEmpty()) {
            println("PCIe: no usable ECAM region, skip enumeration")
            return
        }

        val mappedRegions = regions.mapNotNull(::mapRegion)
        if (mappedRegions.isEmpty()) {
            println("PCIe: failed to map any ECAM region")
            return
        }

        mappedRegions.forEach(::scanRegion)
        println("PCIe: enumeration complete devices=${devices.size}")
    }

    private fun mapRegion(region: PcieEcamRegion): MappedRegion? {
        val busStart = region.startBus.toInt()
        val busEnd = region.endBus.toInt()
        if (busStart !in 0..255 || busEnd !in 0..255 || busEnd < busStart) {
            println("PCIe: ignore invalid region seg=${region.segmentGroup} bus=$busStart-$busEnd")
            return null
        }

        val busCount = busEnd - busStart + 1
        val sizeBytes = busCount.toULong() shl PCI_BUS_SHIFT
        val virtualBase = KernelPageDirectory.mapMmio(region.baseAddress, sizeBytes) ?: run {
            println("PCIe: failed to map ECAM seg=${region.segmentGroup} base=${region.baseAddress.hex()}")
            return null
        }

        println(
            "PCIe: ECAM seg=${region.segmentGroup} bus=$busStart-$busEnd base=${region.baseAddress.hex()} mapped=${virtualBase.hex()}",
        )
        return MappedRegion(
            descriptor = region,
            virtualBase = virtualBase,
            busStart = busStart,
            busEnd = busEnd,
        )
    }

    private fun scanRegion(region: MappedRegion) {
        for (bus in region.busStart..region.busEnd) {
            scanBus(region, bus)
        }
    }

    private fun scanBus(region: MappedRegion, bus: Int) {
        for (device in 0 until 32) {
            if (readVendorId(region, bus, device, 0) == PCI_INVALID_VENDOR_ID) {
                continue
            }

            scanFunction(region, bus, device, 0)

            val headerType = readHeaderType(region, bus, device, 0)
            if (headerType.toULong().hasBit(7)) {
                for (function in 1 until 8) {
                    if (readVendorId(region, bus, device, function) == PCI_INVALID_VENDOR_ID) {
                        continue
                    }
                    scanFunction(region, bus, device, function)
                }
            }
        }
    }

    private fun scanFunction(region: MappedRegion, bus: Int, device: Int, function: Int) {
        val locationKey = makeLocationKey(region.descriptor.segmentGroup, bus, device, function)
        if (!enumeratedLocations.add(locationKey)) {
            return
        }

        val idRegister = readConfig32(region, bus, device, function, PCI_VENDOR_DEVICE_OFFSET)
        val vendorId = idRegister and 0xFFFFu
        if (vendorId == PCI_INVALID_VENDOR_ID) {
            return
        }

        val classRegister = readConfig32(region, bus, device, function, PCI_CLASS_REVISION_OFFSET)
        val headerRegister = readConfig32(region, bus, device, function, PCI_HEADER_TYPE_OFFSET)
        val interruptRegister = readConfig32(region, bus, device, function, PCI_INTERRUPT_OFFSET)
        val classCode = (classRegister shr 8) and 0x00FF_FFFFu

        val deviceInfo = PciDeviceInfo(
            segmentGroup = region.descriptor.segmentGroup,
            bus = bus.toUInt(),
            device = device.toUInt(),
            function = function.toUInt(),
            vendorId = vendorId,
            deviceId = (idRegister shr 16) and 0xFFFFu,
            classCode = classCode,
            className = classNameOf(classCode),
            revisionId = classRegister and 0xFFu,
            headerType = (headerRegister shr 16) and 0x7Fu,
            interruptLine = interruptRegister and 0xFFu,
            interruptPin = (interruptRegister shr 8) and 0xFFu,
        )
        devices += deviceInfo

        println(
            "PCIe: dev seg=${deviceInfo.segmentGroup} BSF=${deviceInfo.bus}:${deviceInfo.device}:${deviceInfo.function} " +
                "vid=${deviceInfo.vendorId.hex()} did=${deviceInfo.deviceId.hex()} class=${deviceInfo.classCode.hex()} name=${deviceInfo.className}",
        )
    }

    private fun readVendorId(region: MappedRegion, bus: Int, device: Int, function: Int): UInt =
        readConfig32(region, bus, device, function, PCI_VENDOR_DEVICE_OFFSET) and 0xFFFFu

    private fun readHeaderType(region: MappedRegion, bus: Int, device: Int, function: Int): UInt =
        (readConfig32(region, bus, device, function, PCI_HEADER_TYPE_OFFSET) shr 16) and 0xFFu

    private fun readConfig32(
        region: MappedRegion,
        bus: Int,
        device: Int,
        function: Int,
        offset: Int,
    ): UInt {
        if (offset !in 0..0xFFC || offset and 0x3 != 0) {
            return UInt.MAX_VALUE
        }
        if (bus !in region.busStart..region.busEnd || device !in 0..31 || function !in 0..7) {
            return UInt.MAX_VALUE
        }

        val registerAddress = region.virtualBase + functionOffset(region, bus, device, function, offset)
        return registerAddress.toPointer<UIntVar>()?.get(0) ?: UInt.MAX_VALUE
    }

    private fun functionOffset(
        region: MappedRegion,
        bus: Int,
        device: Int,
        function: Int,
        offset: Int,
    ): ULong {
        val busOffset = (bus - region.busStart).toULong() shl PCI_BUS_SHIFT
        val deviceOffset = device.toULong() shl PCI_DEVICE_SHIFT
        val functionOffset = function.toULong() shl PCI_FUNCTION_SHIFT
        return busOffset + deviceOffset + functionOffset + offset.toULong()
    }

    private fun makeLocationKey(segmentGroup: UInt, bus: Int, device: Int, function: Int): ULong =
        (segmentGroup.toULong() shl 24) or
            (bus.toULong() shl 16) or
            (device.toULong() shl 8) or
            function.toULong()
}
