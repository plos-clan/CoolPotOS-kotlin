package org.plos_clan.cpos.drivers.usb.bus

import org.plos_clan.cpos.drivers.usb.defs.CDC_UNION_FUNCTIONAL_DESCRIPTOR
import org.plos_clan.cpos.drivers.usb.defs.ConfigurationDescriptor
import org.plos_clan.cpos.drivers.usb.defs.DESC_CONFIGURATION
import org.plos_clan.cpos.drivers.usb.defs.DESC_CS_INTERFACE
import org.plos_clan.cpos.drivers.usb.defs.DESC_ENDPOINT
import org.plos_clan.cpos.drivers.usb.defs.DESC_HID
import org.plos_clan.cpos.drivers.usb.defs.DESC_INTERFACE
import org.plos_clan.cpos.drivers.usb.defs.DESC_REPORT
import org.plos_clan.cpos.drivers.usb.defs.DESC_SS_EP_COMPANION
import org.plos_clan.cpos.drivers.usb.defs.EndpointDescriptor
import org.plos_clan.cpos.drivers.usb.defs.InterfaceDescriptor
import org.plos_clan.cpos.drivers.usb.defs.SsEndpointCompanionDescriptor

data class UsbEndpoint(
    val desc: EndpointDescriptor,
    var ssDesc: SsEndpointCompanionDescriptor? = null,
) {
    val extraDescriptors = mutableListOf<ByteArray>()
    var streamCount: Int = 0
        internal set
}

class UsbExtraData(private val interfaceNumber: UByte) {
    val descriptors = mutableListOf<ByteArray>()

    val hidReportDescriptorLength: UShort
        get() {
            val hid =
                descriptors.firstOrNull { it.size >= 6 && it[1].toUByte() == DESC_HID } ?: return 0u
            val count = hid[5].toUByte().toInt()
            for (index in 0 until count) {
                val offset = 6 + index * 3
                if (offset + 3 > hid.size) return 0u
                if (hid[offset].toUByte() == DESC_REPORT)
                    return (hid[offset + 1].toUByte().toUInt() or
                            (hid[offset + 2].toUByte().toUInt() shl 8))
                        .toUShort()
            }
            return 0u
        }

    val associatedInterfaceNumbers: List<UByte>
        get() =
            descriptors
                .filter {
                    it.size >= 5 &&
                        it[1].toUByte() == DESC_CS_INTERFACE &&
                        it[2].toUByte() == CDC_UNION_FUNCTIONAL_DESCRIPTOR &&
                        it[3].toUByte() == interfaceNumber
                }
                .flatMap { descriptor -> descriptor.drop(4).map { it.toUByte() } }
                .distinct()
}

class UsbAlternateSetting(val desc: InterfaceDescriptor) {
    val endpoints = mutableListOf<UsbEndpoint>()
    val extraData = UsbExtraData(desc.interfaceNumber)
}

class UsbConfiguration
private constructor(
    val value: UByte,
    val settings: List<UsbAlternateSetting>,
) {
    companion object {
        fun parse(bytes: ByteArray): UsbConfiguration? {
            if (bytes.size < ConfigurationDescriptor.SIZE_BYTES) return null
            fun u8(offset: Int): UByte = bytes[offset].toUByte()
            fun u16(offset: Int): UShort =
                (u8(offset).toUInt() or (u8(offset + 1).toUInt() shl 8)).toUShort()
            val length = u16(2).toInt()
            if (
                u8(0).toInt() != ConfigurationDescriptor.SIZE_BYTES ||
                    u8(1) != DESC_CONFIGURATION ||
                    length != bytes.size ||
                    u8(5) == 0u.toUByte()
            )
                return null
            val settings = mutableListOf<UsbAlternateSetting>()
            var setting: UsbAlternateSetting? = null
            var endpoint: UsbEndpoint? = null
            var offset = ConfigurationDescriptor.SIZE_BYTES
            while (offset < length) {
                if (length - offset < 2) return null
                val size = u8(offset).toInt()
                if (size < 2 || size > length - offset) return null
                val type = u8(offset + 1)
                when (type) {
                    DESC_INTERFACE -> {
                        if (size < 9) return null
                        val descriptor =
                            InterfaceDescriptor(
                                u8(offset + 2),
                                u8(offset + 3),
                                u8(offset + 4),
                                u8(offset + 5),
                                u8(offset + 6),
                                u8(offset + 7),
                                u8(offset + 8),
                            )
                        if (
                            settings.any {
                                it.desc.interfaceNumber == descriptor.interfaceNumber &&
                                    it.desc.alternateSetting == descriptor.alternateSetting
                            }
                        )
                            return null
                        setting = UsbAlternateSetting(descriptor)
                        settings.add(setting)
                        endpoint = null
                    }
                    DESC_ENDPOINT -> {
                        val current = setting ?: return null
                        if (size < 7) return null
                        val descriptor =
                            EndpointDescriptor(
                                u8(offset + 2),
                                u8(offset + 3),
                                u16(offset + 4),
                                u8(offset + 6),
                            )
                        if (
                            descriptor.number == 0 ||
                                descriptor.endpointAddress.toInt() and 0x70 != 0 ||
                                descriptor.maxPacketSize.toInt() and 0x7ff == 0 ||
                                current.endpoints.any {
                                    it.desc.endpointAddress == descriptor.endpointAddress
                                }
                        )
                            return null
                        endpoint = UsbEndpoint(descriptor)
                        current.endpoints.add(endpoint)
                    }
                    DESC_SS_EP_COMPANION -> {
                        val current = endpoint ?: return null
                        if (size < 6 || current.ssDesc != null) return null
                        val companion =
                            SsEndpointCompanionDescriptor(
                                u8(offset + 2),
                                u8(offset + 3),
                                u16(offset + 4),
                            )
                        if (
                            companion.maxBurst > 15u ||
                                current.desc.transferType == 2 &&
                                    companion.attributes.toInt() and 0x1f > 16
                        )
                            return null
                        current.ssDesc = companion
                    }
                    else -> {
                        val data = bytes.copyOfRange(offset, offset + size)
                        if (endpoint != null) endpoint.extraDescriptors.add(data)
                        else setting?.extraData?.descriptors?.add(data)
                    }
                }
                offset += size
            }
            val interfaces = settings.groupBy { it.desc.interfaceNumber }
            if (
                interfaces.size != u8(4).toInt() ||
                    interfaces.values.any { alternatives ->
                        alternatives.none { it.desc.alternateSetting == 0u.toUByte() }
                    } ||
                    settings.any { it.endpoints.size != it.desc.numEndpoints.toInt() }
            )
                return null
            val activeEndpoints =
                settings
                    .filter { it.desc.alternateSetting == 0u.toUByte() }
                    .flatMap { it.endpoints }
                    .map { it.desc.endpointAddress }
            if (activeEndpoints.distinct().size != activeEndpoints.size) return null
            return UsbConfiguration(u8(5), settings)
        }
    }
}
