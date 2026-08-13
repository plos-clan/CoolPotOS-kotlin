@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.bus

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.plus
import org.plos_clan.cpos.drivers.usb.defs.ConfigurationDescriptor
import org.plos_clan.cpos.drivers.usb.defs.DESC_CONFIGURATION
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
import org.plos_clan.cpos.utils.readU8
import org.plos_clan.cpos.utils.readU16

suspend fun UsbDevice.enumerate(): Boolean {
    val buffers = mutableListOf<MmioRegion>()
    try {
        val descriptorBuffer = MmioRegion.allocate() ?: return false
        buffers.add(descriptorBuffer)

        if (!submitControl(
                ControlTransferArgs(
                    setup = SetupPacket(
                        requestType = REQ_DIR_IN,
                        request = REQ_GET_DESCRIPTOR,
                        value = (DESC_DEVICE.toUInt() shl 8).toUShort(),
                        length = 8u.toUShort(),
                    ),
                    bufferPhysicalAddress = descriptorBuffer.physicalAddress,
                ),
            )
        ) {
            return false
        }

        val descriptorPtr = descriptorBuffer.view<UByteVar>()
        var mps0 = descriptorPtr.readU8(7).toUInt()

        if (mps0 != 0u) {
            if (speed >= SPEED_SUPER.toUInt()) {
                mps0 = 1u shl mps0.toInt()
            }
            if (!host.updateEp0Mps(slotId, mps0)) {
                println("Failed to update EP0 MPS for slot $slotId")
                return false
            }
        }

        if (!submitControl(
                ControlTransferArgs(
                    setup = SetupPacket(
                        requestType = REQ_DIR_IN,
                        request = REQ_GET_DESCRIPTOR,
                        value = (DESC_DEVICE.toUInt() shl 8).toUShort(),
                        length = DeviceDescriptor.SIZE_BYTES.toUShort(),
                    ),
                    bufferPhysicalAddress = descriptorBuffer.physicalAddress,
                ),
            )
        ) {
            return false
        }

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
        println(
            "USB Device: ${deviceDescriptor.idVendor.toInt().toString(16).padStart(4, '0')}:" +
                "${deviceDescriptor.idProduct.toInt().toString(16).padStart(4, '0')}",
        )

        val headerBuffer = MmioRegion.allocate() ?: return false
        buffers.add(headerBuffer)

        if (!submitControl(
                ControlTransferArgs(
                    setup = SetupPacket(
                        requestType = REQ_DIR_IN,
                        request = REQ_GET_DESCRIPTOR,
                        value = (DESC_CONFIGURATION.toUInt() shl 8).toUShort(),
                        length = ConfigurationDescriptor.SIZE_BYTES.toUShort(),
                    ),
                    bufferPhysicalAddress = headerBuffer.physicalAddress,
                ),
            )
        ) {
            return false
        }

        val headerPtr = headerBuffer.view<UByteVar>()
        val totalLength = headerPtr.readU16(2)
        val configValue = headerPtr.readU8(5)

        val pagesNeeded = (totalLength.toULong() + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES
        val configBuffer = MmioRegion.allocate(pagesNeeded) ?: return false
        buffers.add(configBuffer)

        if (!submitControl(
                ControlTransferArgs(
                    setup = SetupPacket(
                        requestType = REQ_DIR_IN,
                        request = REQ_GET_DESCRIPTOR,
                        value = (DESC_CONFIGURATION.toUInt() shl 8).toUShort(),
                        length = totalLength,
                    ),
                    bufferPhysicalAddress = configBuffer.physicalAddress,
                ),
            )
        ) {
            return false
        }

        println("Parsing config tree (len: $totalLength)")
        parseConfigTree(configBuffer.view<UByteVar>(), totalLength)

        val endpoints = mutableListOf<UsbEndpoint>()
        for (iface in interfaces) {
            if (iface.desc.alternateSetting != 0u.toUByte()) {
                continue
            }
            endpoints.addAll(iface.endpoints)
        }

        println("Configuring endpoints in hardware...")
        if (!host.configureEndpoints(slotId, endpoints)) {
            return false
        }

        if (!submitControl(
                ControlTransferArgs(
                    setup = SetupPacket(
                        requestType = REQ_DIR_OUT,
                        request = REQ_SET_CONFIGURATION,
                        value = configValue.toUShort(),
                    ),
                ),
            )
        ) {
            return false
        }

        matchDrivers()
        println("Device enumeration complete (slot $slotId)")
    } finally {
        buffers.forEach { it.free() }
    }
    return true
}

private fun UsbDevice.parseConfigTree(configRaw: CPointer<UByteVar>, totalLength: UShort) {
    var offset = 0u.toUShort()

    while (offset < totalLength) {
        val ptr = requireNotNull(configRaw + offset.toInt())
        val descLength = ptr.readU8(0)
        val descType = ptr.readU8(1)

        if (offset.toUInt() + descLength.toUInt() > totalLength.toUInt()) {
            break
        }

        when (descType) {
            DESC_INTERFACE -> parseInterfaceDescriptor(ptr)
            DESC_HID -> parseHidDescriptor(ptr)
            DESC_ENDPOINT -> parseEndpointDescriptor(ptr)
            DESC_SS_EP_COMPANION -> parseSsCompanion(ptr)
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

private fun UsbDevice.parseHidDescriptor(ptr: CPointer<UByteVar>) {
    val iface = interfaces.lastOrNull() ?: return
    val numDescriptors = ptr.readU8(6)
    var pos = HidDescriptorHeader.SIZE_BYTES

    repeat(numDescriptors.toInt()) {
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
