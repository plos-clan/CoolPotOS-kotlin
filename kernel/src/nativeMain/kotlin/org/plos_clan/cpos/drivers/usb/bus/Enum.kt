@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.bus

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.readBytes
import org.plos_clan.cpos.drivers.usb.defs.ConfigurationDescriptor
import org.plos_clan.cpos.drivers.usb.defs.DESC_CONFIGURATION
import org.plos_clan.cpos.drivers.usb.defs.DESC_DEVICE
import org.plos_clan.cpos.drivers.usb.defs.DeviceDescriptor
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_IN
import org.plos_clan.cpos.drivers.usb.defs.REQ_DIR_OUT
import org.plos_clan.cpos.drivers.usb.defs.REQ_GET_DESCRIPTOR
import org.plos_clan.cpos.drivers.usb.defs.REQ_SET_CONFIGURATION
import org.plos_clan.cpos.drivers.usb.defs.SPEED_SUPER
import org.plos_clan.cpos.drivers.usb.defs.SetupPacket
import org.plos_clan.cpos.mem.MmioRegion
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.readU16
import org.plos_clan.cpos.utils.readU8

suspend fun UsbDevice.enumerate(): Unit? {
    val header = MmioRegion.allocate() ?: return null
    try {
        suspend fun readDescriptor(type: UByte, length: UShort, buffer: MmioRegion): Boolean {
            val setup =
                SetupPacket(
                    REQ_DIR_IN,
                    REQ_GET_DESCRIPTOR,
                    value = (type.toUInt() shl 8).toUShort(),
                    length = length,
                )
            val request =
                UsbTransfer(
                    0u,
                    listOf(UsbBuffer(buffer.physicalAddress, length.toUInt())),
                    setup = setup,
                )
            val result = transfer(request, 5_000)
            return result.successful && result.actualLength == length.toUInt()
        }

        if (!readDescriptor(DESC_DEVICE, 8u, header)) return null
        val ptr = header.view<UByteVar>()
        val rawMps = ptr.readU8(7).toInt()
        val mps =
            if (speed >= SPEED_SUPER.toUInt()) {
                if (rawMps != 9) return null
                512u
            } else {
                if (rawMps !in listOf(8, 16, 32, 64)) return null
                rawMps.toUInt()
            }
        host.updateEp0Mps(slotId, mps) ?: return null
        if (!readDescriptor(DESC_DEVICE, DeviceDescriptor.SIZE_BYTES.toUShort(), header))
            return null
        if (ptr.readU8(0).toInt() < DeviceDescriptor.SIZE_BYTES || ptr.readU8(1) != DESC_DEVICE)
            return null
        desc =
            DeviceDescriptor(
                ptr.readU16(2),
                ptr.readU8(4),
                ptr.readU8(5),
                ptr.readU8(6),
                ptr.readU8(7),
                ptr.readU16(8),
                ptr.readU16(10),
                ptr.readU16(12),
                ptr.readU8(14),
                ptr.readU8(15),
                ptr.readU8(16),
                ptr.readU8(17),
            )
        if (desc!!.numConfigurations == 0u.toUByte()) return null
        if (
            !readDescriptor(
                DESC_CONFIGURATION,
                ConfigurationDescriptor.SIZE_BYTES.toUShort(),
                header,
            )
        )
            return null
        val length = ptr.readU16(2)
        if (length.toInt() < ConfigurationDescriptor.SIZE_BYTES) return null
        val pages = (length.toULong() + PAGE_SIZE_BYTES - 1uL) / PAGE_SIZE_BYTES
        val buffer = MmioRegion.allocate(pages) ?: return null
        val configuration =
            try {
                if (!readDescriptor(DESC_CONFIGURATION, length, buffer)) return null
                UsbConfiguration.parse(buffer.view<ByteVar>().readBytes(length.toInt()))
                    ?: return null
            } finally {
                buffer.free()
            }
        for (settings in configuration.settings.groupBy { it.desc.interfaceNumber }.values) {
            val default = settings.first { it.desc.alternateSetting == 0u.toUByte() }
            val iface = UsbInterface(this, default.desc)
            iface.settings.clear()
            iface.settings.addAll(settings)
            iface.activeSetting = default
            interfaces.add(iface)
        }
        updateEndpoints()
        host.configureEndpoints(slotId, interfaces.flatMap { it.endpoints }) ?: return null
        val setup =
            SetupPacket(REQ_DIR_OUT, REQ_SET_CONFIGURATION, value = configuration.value.toUShort())
        if (!transfer(UsbTransfer(0u, setup = setup), 5_000).successful) return null
        matchDrivers()
        println("USB: Device enumeration complete (slot $slotId)")
        return Unit
    } finally {
        header.free()
    }
}
