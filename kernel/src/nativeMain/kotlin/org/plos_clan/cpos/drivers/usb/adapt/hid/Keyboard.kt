@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.adapt.hid

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import org.plos_clan.cpos.drivers.input.InputId
import org.plos_clan.cpos.drivers.input.InputManager
import org.plos_clan.cpos.drivers.usb.bus.CompletionEvent
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbDriver
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.defs.CLASS_HID
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_INT

class KeyLayout {
    val modifiers = mutableListOf<HidField>()
    val arrays = mutableListOf<HidField>()
    val bitmaps = mutableListOf<HidField>()
}

class Keyboard(
    val hid: HidDevice,
    val layout: KeyLayout,
) : UsbDriver {
    private fun scanLayout() {
        for (report in hid.descriptor.reports.values) {
            if (report.sizeBytes(HidKind.INPUT) == 0u) {
                continue
            }
            for (field in report.fields) {
                if (field.kind != HidKind.INPUT) {
                    continue
                }
                if (field.isConst() || field.usagePage != 0x07u.toUShort()) {
                    continue
                }
                if (!field.isVariable()) {
                    layout.arrays.add(field)
                    continue
                }
                val usage = field.usageMin and 0xffffu
                if (usage in 0xe0u..0xe7u) {
                    layout.modifiers.add(field)
                } else {
                    layout.bitmaps.add(field)
                }
            }
        }

        if (layout.modifiers.isEmpty() && layout.arrays.isEmpty()) {
            println("KBD: No keyboard fields found")
        }
    }

    override fun disconnect() {
        println("KBD: disconnected")
        InputManager.unregisterKeyboard(hid)
        hid.free()
    }

    override fun handleCompletion(event: CompletionEvent) {
        if (event.endpointAddress != hid.endpointAddress) {
            return
        }
        try {
            if (event.status != TransferStatus.COMPLETED &&
                event.status != TransferStatus.SHORT_PACKET
            ) {
                println("KBD: Transfer failed (${event.status.ordinal})")
                return
            }
            val buffer = hid.buffer ?: return
            val length = (
                hid.maxReportSize.toUInt() -
                    minOf(event.residualLength, hid.maxReportSize.toUInt())
                ).toInt()
            if (length == 0) return

            val data = buffer.view<UByteVar>()
            val numbered = hid.descriptor.reports.keys.any { it != 0u.toUByte() }
            val reportId = if (numbered) data[0] else 0u.toUByte()
            val input = InputManager.findKeyboard(hid) ?: run {
                val descriptor = hid.iface.device.desc
                InputManager.registerKeyboard(
                    source = hid,
                    name = "USB HID keyboard",
                    physicalPath =
                        "usb-${hid.iface.device.slotId}/input${hid.iface.desc.interfaceNumber}",
                    id = InputId(
                        bus = InputId.BUS_USB,
                        vendor = descriptor?.idVendor ?: 0u,
                        product = descriptor?.idProduct ?: 0u,
                        version = descriptor?.bcdDevice ?: 0u,
                    ),
                )
            } ?: return
            val report = input.beginReport()
            val availableBits = length.toULong() * Byte.SIZE_BITS.toULong()
            var hasKeyboardFields = false

            for (field in layout.modifiers) {
                if (field.reportId != reportId) continue
                hasKeyboardFields = true
                val endBit = field.bitOffset.toULong() +
                    field.bitSize.toULong() * field.reportCount.toULong()
                if (endBit > availableBits) {
                    report.invalidate()
                    continue
                }
                report.setHidUsage(
                    (field.usageMin and 0xffffu).toInt(),
                    field.value(data, 0u) != 0u,
                )
            }
            for (field in layout.arrays) {
                if (field.reportId != reportId) continue
                hasKeyboardFields = true
                val endBit = field.bitOffset.toULong() +
                    field.bitSize.toULong() * field.reportCount.toULong()
                if (endBit > availableBits) {
                    report.invalidate()
                    continue
                }
                report.coverHidRange(field.usageMin, field.usageMax)
                repeat(field.reportCount.toInt()) { index ->
                    report.setHidUsage(field.value(data, index.toUInt()).toInt())
                }
            }
            for (field in layout.bitmaps) {
                if (field.reportId != reportId) continue
                hasKeyboardFields = true
                val endBit = field.bitOffset.toULong() +
                    field.bitSize.toULong() * field.reportCount.toULong()
                if (endBit > availableBits) {
                    report.invalidate()
                    continue
                }
                report.setHidUsage(
                    (field.usageMin and 0xffffu).toInt(),
                    field.value(data, 0u) != 0u,
                )
            }
            if (hasKeyboardFields) report.commit()
        } finally {
            hid.transferCompletion.release()
        }
    }

    companion object {
        suspend fun create(iface: UsbInterface, endpointAddress: UByte): Keyboard? {
            val hid = HidDevice.create(iface, endpointAddress) ?: return null
            val keyboard = Keyboard(hid = hid, layout = KeyLayout())
            keyboard.scanLayout()
            return keyboard
        }
    }
}

suspend fun probeKbd(iface: UsbInterface): UsbDriver? {
    if (!iface.matches(CLASS_HID, 1u.toUByte(), 1u.toUByte())) {
        return null
    }

    val endpoint = iface.findEndpoint(EP_TYPE_INT, true) ?: run {
        println("KBD: No Interrupt IN endpoint")
        return null
    }

    val keyboard = Keyboard.create(iface, endpoint.desc.endpointAddress) ?: return null
    println("HID Keyboard (Slot ${iface.device.slotId})")

    return keyboard
}
