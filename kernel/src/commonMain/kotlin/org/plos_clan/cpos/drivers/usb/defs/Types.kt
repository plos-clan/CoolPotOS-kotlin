package org.plos_clan.cpos.drivers.usb.defs

data class SetupPacket(
    val requestType: UByte,
    val request: UByte,
    val value: UShort = 0u,
    val index: UShort = 0u,
    val length: UShort = 0u,
) {
    companion object {
        const val SIZE_BYTES = 8
    }
}

data class DeviceDescriptor(
    val bcdUsb: UShort,
    val deviceClass: UByte,
    val deviceSubclass: UByte,
    val deviceProtocol: UByte,
    val maxPacketSize0: UByte,
    val idVendor: UShort,
    val idProduct: UShort,
    val bcdDevice: UShort,
    val iManufacturer: UByte,
    val iProduct: UByte,
    val iSerialNumber: UByte,
    val numConfigurations: UByte,
) {
    companion object {
        const val SIZE_BYTES = 18
    }
}

data class ConfigurationDescriptor(
    val totalLength: UShort,
    val numInterfaces: UByte,
    val configurationValue: UByte,
    val configurationStr: UByte,
    val attributes: UByte,
    val maxPower: UByte,
) {
    companion object {
        const val SIZE_BYTES = 9
    }
}

data class InterfaceDescriptor(
    val interfaceNumber: UByte,
    val alternateSetting: UByte,
    val numEndpoints: UByte,
    val interfaceClass: UByte,
    val interfaceSubclass: UByte,
    val interfaceProtocol: UByte,
    val interfaceStr: UByte,
)

data class HidDescriptorHeader(
    val bcdHid: UShort,
    val countryCode: UByte,
    val numDescriptors: UByte,
) {
    companion object {
        const val SIZE_BYTES = 6
    }
}

data class EndpointDescriptor(
    val endpointAddress: UByte,
    val attributes: UByte,
    val maxPacketSize: UShort,
    val interval: UByte,
) {
    val number: Int
        get() = endpointAddress.toInt() and 0x0f

    val isIn: Boolean
        get() = endpointAddress.toInt() and 0x80 != 0

    val transferType: Int
        get() = attributes.toInt() and 3
}

data class SsEndpointCompanionDescriptor(
    val maxBurst: UByte,
    val attributes: UByte,
    val bytesPerInterval: UShort,
) {
    val maxStreams: Int
        get() =
            (attributes.toInt() and 0x1f).let {
                if (it in 1..16) 1 shl it else 0
            }
}
