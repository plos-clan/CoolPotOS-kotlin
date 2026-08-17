package org.plos_clan.cpos.drivers.pcie

internal class PciScanner(
    private val segment: UShort,
    private val onDevice: (PciDevice) -> Unit,
) {
    private val scannedBuses = BooleanArray(PCI_BUS_COUNT)

    fun scanRegion(startBus: Int, endBus: Int) {
        for (bus in startBus..endBus) scanBus(bus)
    }

    private fun scanBus(bus: Int) {
        if (bus !in scannedBuses.indices || scannedBuses[bus]) return
        scannedBuses[bus] = true

        for (device in 0 until PCI_DEVICE_COUNT) {
            val firstFunction = PciAddress.of(segment, bus.toUByte(), device.toUByte(), 0u)
            val firstConfig = Pcie.configurationSpace(firstFunction) ?: continue
            val firstVendorId = firstConfig.readU16(PCI_VENDOR_DEVICE_OFFSET)
            if (firstVendorId == UShort.MAX_VALUE) continue

            scanFunction(firstFunction)
            if (firstFunction.hasMultipleFunctions()) {
                for (function in 1 until PCI_FUNCTION_COUNT) {
                    val address = PciAddress.of(
                        segment,
                        bus.toUByte(),
                        device.toUByte(),
                        function.toUByte(),
                    )
                    val config = Pcie.configurationSpace(address) ?: continue
                    if (config.readU16(PCI_VENDOR_DEVICE_OFFSET) != UShort.MAX_VALUE) {
                        scanFunction(address)
                    }
                }
            }
        }
    }

    private fun scanFunction(address: PciAddress) {
        val config = Pcie.configurationSpace(address) ?: return
        val header = PciHeader(config)
        if (header.vendorId == UShort.MAX_VALUE) return

        when (header.headerType) {
            PciHeaderType.ENDPOINT -> {
                val endpoint = EndpointHeader(header)
                val baseFlags = (
                    PCI_COMMAND_MEMORY_SPACE.toUInt() or
                        PCI_COMMAND_IO_SPACE.toUInt() or
                        PCI_COMMAND_BUS_MASTER.toUInt()
                    ).toUShort()
                header.updateCommand(baseFlags, true)
                header.updateCommand(PCI_COMMAND_INTX_DISABLE, true)

                val device = PciDevice(
                    address = address,
                    vendorId = header.vendorId,
                    deviceId = header.deviceId,
                    classCode = header.classCode,
                    subClass = header.subClass,
                    progIf = header.progIf,
                    revision = header.revision,
                    bars = endpoint.bars(),
                    interrupt = PciInterrupt.resolve(endpoint),
                    deviceType = PciDeviceType.parse(header.classCode, header.subClass),
                )
                device.printInfo()
                onDevice(device)
            }

            PciHeaderType.PCI_PCI_BRIDGE -> {
                val bridge = BridgeHeader(header)
                val secondaryBus = bridge.secondaryBus.toInt()
                if (secondaryBus > bridge.primaryBus.toInt() &&
                    secondaryBus <= bridge.subordinateBus.toInt()
                ) {
                    scanBus(secondaryBus)
                }
            }

            else -> Unit
        }
    }
}

private const val PCI_VENDOR_DEVICE_OFFSET = 0x00
private const val PCI_BUS_COUNT = 256
private const val PCI_DEVICE_COUNT = 32
private const val PCI_FUNCTION_COUNT = 8
