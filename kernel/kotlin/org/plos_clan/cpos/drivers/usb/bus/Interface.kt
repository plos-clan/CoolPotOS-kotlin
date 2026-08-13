package org.plos_clan.cpos.drivers.usb.bus

import org.plos_clan.cpos.drivers.usb.defs.EndpointDescriptor
import org.plos_clan.cpos.drivers.usb.defs.InterfaceDescriptor
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_IN
import org.plos_clan.cpos.drivers.usb.defs.SsEndpointCompanionDescriptor

data class UsbEndpoint(
    val desc: EndpointDescriptor,
    var ssDesc: SsEndpointCompanionDescriptor? = null,
)

class UsbExtraData {
    var hidReportDescriptorLength: UShort = 0u
}

class UsbInterface(
    val device: UsbDevice,
    val desc: InterfaceDescriptor,
) {
    var driver: UsbDriver? = null
    val endpoints = mutableListOf<UsbEndpoint>()
    val extraData = UsbExtraData()

    fun matches(classCode: UByte, subClass: UByte, protocol: UByte): Boolean {
        return (classCode == 0xffu.toUByte() || desc.interfaceClass == classCode) &&
            (subClass == 0xffu.toUByte() || desc.interfaceSubclass == subClass) &&
            (protocol == 0xffu.toUByte() || desc.interfaceProtocol == protocol)
    }

    fun findEndpoint(endpointType: UByte, isIn: Boolean): UsbEndpoint? {
        val endpointDirection = if (isIn) REQ_DIR_IN else 0u.toUByte()

        for (endpoint in endpoints) {
            val currentDirection = endpoint.desc.endpointAddress and REQ_DIR_IN
            val currentType = endpoint.desc.attributes and 0x03u.toUByte()

            if (currentDirection == endpointDirection && currentType == endpointType) {
                return endpoint
            }
        }

        return null
    }

    fun findSibling(classCode: UByte, subClass: UByte, protocol: UByte): UsbInterface? {
        for (iface in device.interfaces) {
            if (iface.desc.interfaceNumber != desc.interfaceNumber &&
                iface.matches(classCode, subClass, protocol)
            ) {
                return iface
            }
        }

        return null
    }
}
