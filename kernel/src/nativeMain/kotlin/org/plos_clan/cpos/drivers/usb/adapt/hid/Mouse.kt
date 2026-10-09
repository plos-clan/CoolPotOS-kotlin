@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.adapt.hid

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.input.AbsoluteAxis
import org.plos_clan.cpos.drivers.input.AbsoluteAxisInfo
import org.plos_clan.cpos.drivers.input.EvdevDevice
import org.plos_clan.cpos.drivers.input.InputEvent
import org.plos_clan.cpos.drivers.input.InputEventType
import org.plos_clan.cpos.drivers.input.InputId
import org.plos_clan.cpos.drivers.input.RelativeAxis
import org.plos_clan.cpos.drivers.usb.bus.CompletionEvent
import org.plos_clan.cpos.drivers.usb.bus.TransferStatus
import org.plos_clan.cpos.drivers.usb.bus.UsbDriver
import org.plos_clan.cpos.drivers.usb.bus.UsbInterface
import org.plos_clan.cpos.drivers.usb.defs.CLASS_HID
import org.plos_clan.cpos.drivers.usb.defs.EP_TYPE_INT

private class MouseField private constructor(
    val field: HidField,
    val type: InputEventType,
    val code: UShort,
) {
    fun read(data: CPointer<UByteVar>, timestamp: ULong): InputEvent {
        val value = when (type) {
            InputEventType.KEY -> if (field.value(data, 0u) == 0u) 0 else 1
            else -> if (field.logicalMin < 0) field.valueSigned(data, 0u) else field.value(data, 0u).toInt()
        }
        return InputEvent(timestamp, type, code, value)
    }

    companion object {
        fun from(field: HidField): MouseField? {
            if (field.applicationUsage != 0x10002u) return null
            if (field.kind != HidKind.INPUT || field.isConst() || !field.isVariable()) return null
            if (field.bitSize !in 1u..32u || field.reportCount != 1u) return null
            val usage = field.usageMin and 0xffffu
            if (field.usagePage == 9u.toUShort() && usage in 1u..16u) {
                return MouseField(field, InputEventType.KEY, (0x10fu + usage).toUShort())
            }
            if (field.flags and 4u == 0u) {
                if (field.usagePage != 1u.toUShort() || field.logicalMin >= field.logicalMax) return null
                val axis = when (usage) {
                    0x30u -> AbsoluteAxis.X
                    0x31u -> AbsoluteAxis.Y
                    else -> return null
                }
                return MouseField(field, InputEventType.ABSOLUTE, axis.code)
            }
            val axis = when (field.usagePage.toInt()) {
                1 -> when (usage) {
                    0x30u -> RelativeAxis.X
                    0x31u -> RelativeAxis.Y
                    0x38u -> RelativeAxis.WHEEL
                    else -> null
                }
                12 -> if (usage == 0x238u) RelativeAxis.HORIZONTAL_WHEEL else null
                else -> null
            } ?: return null
            return MouseField(field, InputEventType.RELATIVE, axis.code)
        }
    }
}

private class Mouse(
    private val hid: HidDevice,
    private val reports: Map<UByte, List<MouseField>>,
    private val input: EvdevDevice,
) : UsbDriver {
    override suspend fun disconnect() {
        input.uninstall()
        hid.free()
    }

    override fun handleCompletion(event: CompletionEvent) {
        if (event.endpointAddress != hid.endpointAddress) return
        try {
            val successful = event.status == TransferStatus.COMPLETED ||
                event.status == TransferStatus.SHORT_PACKET
            if (!successful) return
            val buffer = hid.buffer ?: return
            val capacity = hid.maxReportSize.toUInt()
            val length = capacity - minOf(event.residualLength, capacity)
            if (length == 0u) return
            val data = buffer.view<UByteVar>()
            val numbered = hid.descriptor.reports.keys.any { it != 0u.toUByte() }
            val reportId = if (numbered) data[0] else 0u.toUByte()
            val report = hid.descriptor.reports[reportId] ?: return
            if (length < report.sizeBytes(HidKind.INPUT)) return
            val fields = reports[reportId] ?: return
            val timestamp = TscClock.nanoTime()
            for (field in fields) {
                val inputEvent = field.read(data, timestamp)
                if (inputEvent.type != InputEventType.RELATIVE || inputEvent.value != 0) {
                    input.receive(inputEvent)
                }
            }
            val sync = InputEvent(timestamp, InputEventType.SYNCHRONIZATION, InputEvent.SYN_REPORT, 0)
            input.receive(sync)
        } finally {
            hid.transferCompletion.release()
        }
    }

    companion object {
        suspend fun create(iface: UsbInterface, endpointAddress: UByte): Mouse? {
            val hid = HidDevice.create(iface, endpointAddress) ?: return null
            val fields = hid.descriptor.reports.values
                .flatMap { it.fields }
                .mapNotNull(MouseField::from)
            val keys = fields.filter { it.type == InputEventType.KEY }.map { it.code }.distinct()
            val axes = RelativeAxis.entries.filter { axis ->
                fields.any { it.type == InputEventType.RELATIVE && it.code == axis.code }
            }
            val absoluteAxes = fields.filter { it.type == InputEventType.ABSOLUTE }.associate { field ->
                val axis = AbsoluteAxis.entries.first { it.code == field.code }
                val info = AbsoluteAxisInfo(minimum = field.field.logicalMin, maximum = field.field.logicalMax)
                axis to info
            }
            val descriptor = iface.device.desc
            val id = InputId(
                bus = InputId.BUS_USB,
                vendor = descriptor?.idVendor ?: 0u,
                product = descriptor?.idProduct ?: 0u,
                version = descriptor?.bcdDevice ?: 0u,
            )
            val input = EvdevDevice(
                "USB HID mouse", "usb-${iface.device.slotId}/input${iface.desc.interfaceNumber}",
                id, keys, axes, null, absoluteAxes,
            )
            if (fields.isEmpty() || !input.install()) {
                hid.free()
                return null
            }
            val reports = fields.groupBy { it.field.reportId }
            val mouse = Mouse(hid, reports, input)
            hid.bind(mouse)
            return mouse
        }
    }
}

suspend fun probeMouse(iface: UsbInterface): UsbDriver? {
    if (!iface.matches(CLASS_HID)) return null
    val endpoint = iface.findEndpoint(EP_TYPE_INT, true) ?: return null
    return Mouse.create(iface, endpoint.desc.endpointAddress)
}
