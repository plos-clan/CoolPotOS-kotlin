package org.plos_clan.cpos.drivers.pcie

enum class PciDeviceType(val displayName: String) {
    IDE_CONTROLLER("IDE Controller"),
    SATA_CONTROLLER("SATA Controller"),
    NVME_CONTROLLER("NVMe Controller"),
    ETHERNET_CONTROLLER("Ethernet Controller"),
    NETWORK_CONTROLLER("Network Controller"),
    VGA_COMPATIBLE("VGA Compatible Display"),
    AUDIO_DEVICE("Audio Device"),
    HOST_BRIDGE("Host Bridge"),
    ISA_BRIDGE("ISA Bridge"),
    PCI_PCI_BRIDGE("PCI-PCI Bridge"),
    USB_CONTROLLER("USB Controller"),
    SMBUS_CONTROLLER("SMBus Controller"),
    BLUETOOTH_CONTROLLER("Bluetooth Controller"),
    SERIAL_CONTROLLER("Serial Port Controller"),
    MEMORY_CONTROLLER("Memory Controller"),
    IOMMU("IOMMU"),
    SYSTEM_PERIPHERAL("System Peripheral"),
    UNKNOWN("Unknown Device");

    companion object {
        fun parse(classCode: UByte, subClass: UByte): PciDeviceType =
            when ((classCode.toUInt() shl 8) or subClass.toUInt()) {
                0x0101u -> IDE_CONTROLLER
                0x0106u -> SATA_CONTROLLER
                0x0108u -> NVME_CONTROLLER
                0x0200u -> ETHERNET_CONTROLLER
                0x0280u -> NETWORK_CONTROLLER
                0x0300u -> VGA_COMPATIBLE
                0x0403u -> AUDIO_DEVICE
                0x0500u -> MEMORY_CONTROLLER
                0x0600u -> HOST_BRIDGE
                0x0601u -> ISA_BRIDGE
                0x0604u, 0x0609u -> PCI_PCI_BRIDGE
                0x0700u -> SERIAL_CONTROLLER
                0x0806u -> IOMMU
                0x0880u -> SYSTEM_PERIPHERAL
                0x0C03u -> USB_CONTROLLER
                0x0C05u -> SMBUS_CONTROLLER
                0x0D11u -> BLUETOOTH_CONTROLLER
                else -> UNKNOWN
            }
    }
}
