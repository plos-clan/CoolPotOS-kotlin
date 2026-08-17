@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.adapt.hid

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import org.plos_clan.cpos.drivers.usb.bus.CompletionEvent
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbDriver
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.defs.CLASS_HID
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_INT

class MouseLayout {
    var axisX: HidField? = null
    var axisY: HidField? = null
    var axisWheel: HidField? = null
    val buttons = mutableListOf<HidField>()
}

class Mouse(
    val hid: HidDevice,
    val layout: MouseLayout,
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
                if (field.isConst() || !field.isVariable()) {
                    continue
                }
                if (field.usagePage == 0x01u.toUShort()) {
                    when (field.usageMin and 0xffffu) {
                        0x30u -> layout.axisX = field
                        0x31u -> layout.axisY = field
                        0x38u -> layout.axisWheel = field
                        else -> {}
                    }
                } else if (field.usagePage == 0x09u.toUShort()) {
                    layout.buttons.add(field)
                }
            }
        }

        if (layout.axisX == null && layout.buttons.isEmpty()) {
            println("Mouse: No mouse fields found")
        }
    }

    override fun disconnect() {
        println("Mouse: disconnected")
        hid.free()
    }

    override fun handleCompletion(event: CompletionEvent) {
        if (event.endpointAddress != hid.endpointAddress) {
            return
        }

        if (event.status != TransferStatus.COMPLETED && event.status != TransferStatus.SHORT_PACKET) {
            println("Mouse: transfer failed (${event.status.ordinal})")
            return
        }

        val data = hid.buffer!!.view<UByteVar>()

        layout.axisX?.let { field ->
            val dx = field.valueSigned(data, 0u)
            println("Mouse (X): $dx")
        }

        layout.axisY?.let { field ->
            val dy = field.valueSigned(data, 0u)
            println("Mouse (Y): $dy")
        }

        layout.axisWheel?.let { field ->
            val wheel = field.valueSigned(data, 0u)
            println("Mouse (Wheel): $wheel")
        }

        for (field in layout.buttons) {
            for (i in 0 until field.reportCount.toInt()) {
                if (field.value(data, i.toUInt()) != 0u) {
                    val buttonId = (field.usageMin + i.toUInt()) and 0xffffu
                    println("Mouse (Btn): $buttonId")
                }
            }
        }

        hid.transferCompletion.release()
    }

    companion object {
        suspend fun create(iface: UsbInterface, endpointAddress: UByte): Mouse? {
            val hid = HidDevice.create(iface, endpointAddress) ?: return null
            val mouse = Mouse(hid = hid, layout = MouseLayout())
            mouse.scanLayout()
            return mouse
        }
    }
}

suspend fun probeMouse(iface: UsbInterface): UsbDriver? {
    if (!iface.matches(CLASS_HID, 1u.toUByte(), 2u.toUByte())) {
        return null
    }

    val endpoint = iface.findEndpoint(EP_TYPE_INT, true) ?: run {
        println("Mouse: No Interrupt IN endpoint")
        return null
    }

    val mouse = Mouse.create(iface, endpoint.desc.endpointAddress) ?: return null
    println("HID Mouse (Slot ${iface.device.slotId})")

    return mouse
}
