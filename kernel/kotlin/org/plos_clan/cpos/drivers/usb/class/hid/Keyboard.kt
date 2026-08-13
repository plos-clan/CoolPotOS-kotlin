@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.hid

import kotlinx.cinterop.UByteVar
import kotlinx.coroutines.launch
import org.plos_clan.cpos.coroutines.KernelCoroutines
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
        hid.free()
    }

    override fun handleCompletion(event: CompletionEvent) {
        if (event.endpointAddress != hid.endpointAddress) {
            return
        }

        if (event.status != TransferStatus.COMPLETED && event.status != TransferStatus.SHORT_PACKET) {
            println("KBD: Transfer failed (${event.status.ordinal})")
            return
        }

        val data = hid.buffer!!.view<UByteVar>()

        for (modifier in layout.modifiers) {
            if (modifier.value(data, 0u) == 1u) {
                println("Key (Mod): 0x${(modifier.usageMin and 0xffu).toString(16).padStart(2, '0')}")
            }
        }

        for (arrayField in layout.arrays) {
            for (i in 0..arrayField.reportCount.toInt()) {
                val usage = arrayField.value(data, i.toUInt())
                if (usage > 1u) {
                    println("Key (Std): 0x${usage.toString(16).padStart(2, '0')}")
                }
            }
        }

        for (bitmap in layout.bitmaps) {
            if (bitmap.value(data, 0u) == 1u) {
                println("Key (Bmp): 0x${(bitmap.usageMin and 0xffu).toString(16).padStart(2, '0')}")
            }
        }

        KernelCoroutines.scope.launch {
            hid.submitTransfer()
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

    keyboard.hid.submitTransfer()
    return keyboard
}
