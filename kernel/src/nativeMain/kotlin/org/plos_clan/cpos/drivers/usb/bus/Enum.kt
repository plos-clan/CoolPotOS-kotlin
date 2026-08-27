@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.bus

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.plus
import org.plos_clan.cpos.drivers.usb.defs.ConfigurationDescriptor
import org.plos_clan.cpos.drivers.usb.defs.CDC_UNION_FUNCTIONAL_DESCRIPTOR
import org.plos_clan.cpos.drivers.usb.defs.DESC_CONFIGURATION
import org.plos_clan.cpos.drivers.usb.defs.DESC_CS_INTERFACE
import org.plos_clan.cpos.drivers.usb.defs.DESC_DEVICE
import org.plos_clan.cpos.drivers.usb.defs.DESC_ENDPOINT
import org.plos_clan.cpos.drivers.usb.defs.DESC_HID
import org.plos_clan.cpos.drivers.usb.defs.DESC_INTERFACE
import org.plos_clan.cpos.drivers.usb.defs.DESC_REPORT
import org.plos_clan.cpos.drivers.usb.defs.DESC_SS_EP_COMPANION
import org.plos_clan.cpos.drivers.usb.defs.DeviceDescriptor
import org.plos_clan.cpos.drivers.usb.defs.EndpointDescriptor
import org.plos_clan.cpos.drivers.usb.defs.HidDescriptorHeader
import org.plos_clan.cpos.drivers.usb.defs.InterfaceDescriptor
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_IN
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_OUT
import org.plos_clan.cpos.drivers.usb.defs.REQ_GET_DESCRIPTOR
import org.plos_clan.cpos.drivers.usb.defs.REQ_SET_CONFIGURATION
import org.plos_clan.cpos.drivers.usb.defs.SPEED_SUPER
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket
import org.plos_clan.cpos.drivers.usb.defs.SsEndpointCompanionDescriptor
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.readU16
import org.plos_clan.cpos.utils.readU8

suspend fun UsbDevice.enumerate(): Unit? {
    val buffers = mutableListOf<MmioRegion>()
    try {
        val descriptorBuffer = MmioRegion.allocate() ?: return null
        buffers.add(descriptorBuffer)

        submitControl(
            ControlTransferArgs(
                setup = SetupPacket(
                    requestType = REQ_DIR_IN,
                    request = REQ_GET_DESCRIPTOR,
                    value = (DESC_DEVICE.toUInt() shl 8).toUShort(),
                    length = 8u.toUShort(),
                ),
                bufferPhysicalAddress = descriptorBuffer.physicalAddress,
            ),
        ) ?: return null

        val descriptorPtr = descriptorBuffer.view<UByteVar>()
        var mps0 = descriptorPtr.readU8(7).toUInt()

        if (mps0 != 0u) {
            if (speed >= SPEED_SUPER.toUInt()) {
                mps0 = 1u shl mps0.toInt()
            }
            host.updateEp0Mps(slotId, mps0) ?: run {
                println("Failed to update EP0 MPS for slot $slotId")
                return null
            }
        }

        submitControl(
            ControlTransferArgs(
                setup = SetupPacket(
                    requestType = REQ_DIR_IN,
                    request = REQ_GET_DESCRIPTOR,
                    value = (DESC_DEVICE.toUInt() shl 8).toUShort(),
                    length = DeviceDescriptor.SIZE_BYTES.toUShort(),
                ),
                bufferPhysicalAddress = descriptorBuffer.physicalAddress,
            ),
        ) ?: return null

        val deviceDescriptor = DeviceDescriptor(
            bcdUsb = descriptorPtr.readU16(2),
            deviceClass = descriptorPtr.readU8(4),
            deviceSubclass = descriptorPtr.readU8(5),
            deviceProtocol = descriptorPtr.readU8(6),
            maxPacketSize0 = descriptorPtr.readU8(7),
            idVendor = descriptorPtr.readU16(8),
            idProduct = descriptorPtr.readU16(10),
            bcdDevice = descriptorPtr.readU16(12),
            iManufacturer = descriptorPtr.readU8(14),
            iProduct = descriptorPtr.readU8(15),
            iSerialNumber = descriptorPtr.readU8(16),
            numConfigurations = descriptorPtr.readU8(17),
        )
        desc = deviceDescriptor

        val devicePrefix = deviceDescriptor.idVendor.toInt().toString(16).padStart(4, '0')
        val deviceSuffix =  deviceDescriptor.idProduct.toInt().toString(16).padStart(4, '0')
        println("USB Device: ${devicePrefix}:${deviceSuffix}")

        val headerBuffer = MmioRegion.allocate() ?: return null
        buffers.add(headerBuffer)

        submitControl(
            ControlTransferArgs(
                setup = SetupPacket(
                    requestType = REQ_DIR_IN,
                    request = REQ_GET_DESCRIPTOR,
                    value = (DESC_CONFIGURATION.toUInt() shl 8).toUShort(),
                    length = ConfigurationDescriptor.SIZE_BYTES.toUShort(),
                ),
                bufferPhysicalAddress = headerBuffer.physicalAddress,
            ),
        ) ?: return null

        val headerPtr = headerBuffer.view<UByteVar>()
        val totalLength = headerPtr.readU16(2)
        val configValue = headerPtr.readU8(5)

        val pagesNeeded = (totalLength.toULong() + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES
        val configBuffer = MmioRegion.allocate(pagesNeeded) ?: return null
        buffers.add(configBuffer)

        submitControl(
            ControlTransferArgs(
                setup = SetupPacket(
                    requestType = REQ_DIR_IN,
                    request = REQ_GET_DESCRIPTOR,
                    value = (DESC_CONFIGURATION.toUInt() shl 8).toUShort(),
                    length = totalLength,
                ),
                bufferPhysicalAddress = configBuffer.physicalAddress,
            ),
        ) ?: return null

        println("Parsing config tree (len: $totalLength)")
        parseConfigTree(configBuffer.view(), totalLength)

        val endpoints = mutableListOf<UsbEndpoint>()
        for (iface in interfaces) {
            if (iface.desc.alternateSetting != 0u.toUByte()) {
                continue
            }
            endpoints.addAll(iface.endpoints)
        }

        println("Configuring endpoints in hardware...")
        host.configureEndpoints(slotId, endpoints) ?: return null

        submitControl(
            ControlTransferArgs(
                setup = SetupPacket(
                    requestType = REQ_DIR_OUT,
                    request = REQ_SET_CONFIGURATION,
                    value = configValue.toUShort(),
                ),
            ),
        ) ?: return null

        matchDrivers()
        println("Device enumeration complete (slot $slotId)")
    } finally {
        buffers.forEach { it.free() }
    }
    return Unit
}

private fun UsbDevice.parseConfigTree(configRaw: CPointer<UByteVar>, totalLength: UShort) {
    var offset = 0u.toUShort()

    while (offset < totalLength) {
        val ptr = requireNotNull(configRaw + offset.toInt())
        val descLength = ptr.readU8(0)
        val descType = ptr.readU8(1)

        if (descLength < 2u || offset.toUInt() + descLength.toUInt() > totalLength.toUInt()) {
            break
        }

        when (descType) {
            DESC_INTERFACE -> if (descLength >= 9u) parseInterfaceDescriptor(ptr)
            DESC_HID -> if (descLength >= HidDescriptorHeader.SIZE_BYTES.toUInt()) {
                parseHidDescriptor(ptr, descLength.toInt())
            }
            DESC_ENDPOINT -> if (descLength >= 7u) parseEndpointDescriptor(ptr)
            DESC_SS_EP_COMPANION -> if (descLength >= 6u) parseSsCompanion(ptr)
            DESC_CS_INTERFACE -> parseCdcUnionDescriptor(ptr, descLength.toInt())
            else -> {}
        }

        offset = (offset + descLength.toUShort()).toUShort()
    }
}

private fun UsbDevice.parseInterfaceDescriptor(ptr: CPointer<UByteVar>) {
    val descriptor = InterfaceDescriptor(
        interfaceNumber = ptr.readU8(2),
        alternateSetting = ptr.readU8(3),
        numEndpoints = ptr.readU8(4),
        interfaceClass = ptr.readU8(5),
        interfaceSubclass = ptr.readU8(6),
        interfaceProtocol = ptr.readU8(7),
        interfaceStr = ptr.readU8(8),
    )
    interfaces.add(UsbInterface(device = this, desc = descriptor))
}

private fun UsbDevice.parseHidDescriptor(ptr: CPointer<UByteVar>, length: Int) {
    val iface = interfaces.lastOrNull() ?: return
    val numDescriptors = ptr.readU8(5)
    var pos = HidDescriptorHeader.SIZE_BYTES

    repeat(numDescriptors.toInt()) {
        if (pos > length - 3) return
        val descType = ptr.readU8(pos)
        val descLength =
            (ptr.readU8(pos + 1).toUInt() or (ptr.readU8(pos + 2).toUInt() shl 8)).toUShort()

        if (descType == DESC_REPORT) {
            iface.extraData.hidReportDescriptorLength = descLength
            return
        }
        pos += 3
    }
}

private fun UsbDevice.parseCdcUnionDescriptor(ptr: CPointer<UByteVar>, length: Int) {
    if (length < 5 || ptr.readU8(2) != CDC_UNION_FUNCTIONAL_DESCRIPTOR) return
    val iface = interfaces.lastOrNull() ?: return
    if (ptr.readU8(3) != iface.desc.interfaceNumber) return

    for (offset in 4 until length) {
        val interfaceNumber = ptr.readU8(offset)
        if (interfaceNumber !in iface.extraData.associatedInterfaceNumbers) {
            iface.extraData.associatedInterfaceNumbers.add(interfaceNumber)
        }
    }
}

private fun UsbDevice.parseSsCompanion(ptr: CPointer<UByteVar>) {
    val iface = interfaces.lastOrNull() ?: return
    val endpoint = iface.endpoints.lastOrNull() ?: return

    endpoint.ssDesc = SsEndpointCompanionDescriptor(
        maxBurst = ptr.readU8(2),
        attributes = ptr.readU8(3),
        bytesPerInterval = ptr.readU16(4),
    )
}

private fun UsbDevice.parseEndpointDescriptor(ptr: CPointer<UByteVar>) {
    val iface = interfaces.lastOrNull() ?: return
    val descriptor = EndpointDescriptor(
        endpointAddress = ptr.readU8(2),
        attributes = ptr.readU8(3),
        maxPacketSize = ptr.readU16(4),
        interval = ptr.readU8(6),
    )
    iface.endpoints.add(UsbEndpoint(desc = descriptor))
    endpointMap.set(descriptor.endpointAddress, (interfaces.size - 1).toUByte())
}
