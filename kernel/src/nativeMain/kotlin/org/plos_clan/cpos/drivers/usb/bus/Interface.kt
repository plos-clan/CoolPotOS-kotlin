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
    val associatedInterfaceNumbers = mutableListOf<UByte>()
}

class UsbInterface(
    val device: UsbDevice,
    val desc: InterfaceDescriptor,
) {
    var driver: UsbDriver? = null
    val endpoints = mutableListOf<UsbEndpoint>()
    val extraData = UsbExtraData()

    fun matches(
        classCode: UByte,
        subClass: UByte? = null,
        protocol: UByte? = null,
    ): Boolean = desc.interfaceClass == classCode &&
        (subClass == null || desc.interfaceSubclass == subClass) &&
        (protocol == null || desc.interfaceProtocol == protocol)

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

    fun findAssociatedInterface(
        classCode: UByte,
        subClass: UByte? = null,
        protocol: UByte? = null,
    ): UsbInterface? = device.interfaces.firstOrNull { iface ->
        iface.desc.interfaceNumber in extraData.associatedInterfaceNumbers &&
            iface.matches(classCode, subClass, protocol)
    }
}
