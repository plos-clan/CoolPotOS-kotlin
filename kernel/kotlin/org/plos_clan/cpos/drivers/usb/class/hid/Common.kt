@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.hid

import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.drivers.usb.bus.ControlTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.GeneralTransferArgs
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.bus.submitControl
import org.plos_clan.cpos.drivers.usb.bus.submitTransfer
import org.plos_clan.cpos.drivers.usb.defs.DESC_REPORT
import org.plos_clan.cpos.drivers.usb.defs.PROTO_REPORT
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_IN
import org.plos_clan.cpos.drivers.usb.defs.REQ_GET_DESCRIPTOR
import org.plos_clan.cpos.drivers.usb.defs.REQ_REC_INTERFACE
import org.plos_clan.cpos.drivers.usb.defs.REQ_SET_IDLE
import org.plos_clan.cpos.drivers.usb.defs.REQ_SET_PROTOCOL
import org.plos_clan.cpos.drivers.usb.defs.REQ_TYPE_CLASS
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

class HidDevice(
    val iface: UsbInterface,
    val endpointAddress: UByte,
) {
    var reportDescriptorBuffer: MmioRegion? = null
    var reportDescriptorLength: UShort = 0u
    var buffer: MmioRegion? = null
    var maxReportSize: UShort = 0u
    var descriptor = HidDescriptor()

    fun free() {
        buffer?.free()
        buffer = null

        reportDescriptorBuffer?.free()
        reportDescriptorBuffer = null
    }

    internal suspend fun submitTransfer() {
        val buffer = buffer ?: return

        if (!iface.device.submitTransfer(
                GeneralTransferArgs(
                    endpointAddress = endpointAddress,
                    bufferPhysicalAddress = buffer.physicalAddress,
                    length = maxReportSize.toUInt(),
                ),
            )
        ) {
            println("HID: Submit transfer failed")
        }
    }

    private suspend fun setProtocol(protocol: UShort) {
        if (!iface.device.submitControl(
                ControlTransferArgs(
                    setup = SetupPacket(
                        requestType = REQ_TYPE_CLASS or REQ_REC_INTERFACE,
                        request = REQ_SET_PROTOCOL,
                        value = protocol,
                        index = iface.desc.interfaceNumber.toUShort(),
                    ),
                ),
            )
        ) {
            println("HID: Set protocol failed (ignored)")
        }
    }

    private suspend fun setIdle(duration: UShort) {
        if (!iface.device.submitControl(
                ControlTransferArgs(
                    setup = SetupPacket(
                        requestType = REQ_TYPE_CLASS or REQ_REC_INTERFACE,
                        request = REQ_SET_IDLE,
                        value = duration,
                        index = iface.desc.interfaceNumber.toUShort(),
                    ),
                ),
            )
        ) {
            println("HID: Set idle failed (ignored)")
        }
    }

    private suspend fun fetchReportDescriptor(): Boolean {
        val buffer = reportDescriptorBuffer ?: return false

        if (!iface.device.submitControl(
                ControlTransferArgs(
                    setup = SetupPacket(
                        requestType = REQ_DIR_IN or REQ_REC_INTERFACE,
                        request = REQ_GET_DESCRIPTOR,
                        value = (DESC_REPORT.toUInt() shl 8).toUShort(),
                        index = iface.desc.interfaceNumber.toUShort(),
                        length = reportDescriptorLength,
                    ),
                    bufferPhysicalAddress = buffer.physicalAddress,
                ),
            )
        ) {
            println("HID: Failed to fetch report descriptor")
            return false
        }
        return true
    }

    companion object {
        suspend fun create(iface: UsbInterface, endpointAddress: UByte): HidDevice? {
            val descLength = iface.extraData.hidReportDescriptorLength
            if (descLength == 0u.toUShort()) {
                println("HID: report descriptor length is 0")
                return null
            }

            val descPages = (descLength.toULong() + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES
            val descBuffer = MmioRegion.allocate(descPages) ?: return null

            val device = HidDevice(iface = iface, endpointAddress = endpointAddress)
            device.reportDescriptorBuffer = descBuffer
            device.reportDescriptorLength = descLength

            if (!device.fetchReportDescriptor()) {
                descBuffer.free()
                return null
            }

            val parser = HidParser(descBuffer.view<UByteVar>(), descLength)
            val parsedDescriptor = parser.parse() ?: run {
                println("HID: Failed to parse descriptor")
                descBuffer.free()
                return null
            }
            device.descriptor = parsedDescriptor

            var maxReportSize = 0u
            for (report in parsedDescriptor.reports.values) {
                val bytes = report.sizeBytes(HidKind.INPUT)
                if (bytes > maxReportSize) {
                    maxReportSize = bytes
                }
            }

            val pagesNeeded = (maxReportSize.toULong() + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES
            val reportBuffer = MmioRegion.allocate(pagesNeeded) ?: run {
                descBuffer.free()
                return null
            }
            device.buffer = reportBuffer
            device.maxReportSize = maxReportSize.toUShort()

            device.setProtocol(PROTO_REPORT.toUShort())
            device.setIdle(0u.toUShort())

            return device
        }
    }
}
