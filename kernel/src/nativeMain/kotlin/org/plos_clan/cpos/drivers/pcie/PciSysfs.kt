package org.plos_clan.cpos.drivers.pcie

import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.fs.sysfs.SysfsBinaryAttribute
import org.plos_clan.cpos.fs.sysfs.SysfsBindings
import org.plos_clan.cpos.fs.sysfs.SysfsBusHandle
import org.plos_clan.cpos.fs.sysfs.SysfsIndexBinding
import org.plos_clan.cpos.fs.sysfs.SysfsObjectHandle
import org.plos_clan.cpos.fs.sysfs.SysfsObjectSpec
import org.plos_clan.cpos.fs.sysfs.SysfsParent
import org.plos_clan.cpos.fs.sysfs.SysfsTextAttribute
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

internal class PciSysfsPublisher {
    private data class RootBus(val segment: UShort, val bus: UByte)

    private val roots = mutableMapOf<RootBus, SysfsObjectHandle>()
    private val functions = mutableMapOf<PciAddress, SysfsObjectHandle>()
    private val registrationOrder = mutableListOf<SysfsObjectHandle>()
    private var busHandle: SysfsBusHandle? = null

    fun initialize() {
        when (val result = Sysfs.registerBus(PCI_BUS_NAME)) {
            is VfsResult.Ok -> busHandle = result.value
            is VfsResult.Err -> if (result.error != VfsError.ALREADY_EXISTS) {
                println("PCIe: failed to register sysfs bus: ${result.error}")
            }
        }
    }

    fun publish(function: PciFunctionInfo, parentBridge: PciAddress?) {
        if (functions.containsKey(function.address)) return
        val parent = if (parentBridge == null) {
            root(function.address.segment, function.address.bus)
        } else {
            functions[parentBridge] ?: run {
                println(
                    "PCIe: skip sysfs ${function.address.sysfsName}: " +
                        "parent ${parentBridge.sysfsName} is unavailable",
                )
                return
            }
        } ?: return

        when (val result = Sysfs.registerObject(objectSpec(function, parent))) {
            is VfsResult.Ok -> {
                functions[function.address] = result.value
                registrationOrder += result.value
            }
            is VfsResult.Err -> println(
                "PCIe: failed to publish ${function.address.sysfsName}: ${result.error}",
            )
        }
    }

    fun handle(address: PciAddress): SysfsObjectHandle? = functions[address]

    fun reset() {
        for (index in registrationOrder.indices.reversed()) {
            val handle = registrationOrder[index]
            val result = Sysfs.unregisterObject(handle)
            if (result is VfsResult.Err && result.error != VfsError.NOT_FOUND) {
                println("PCIe: failed to remove sysfs object ${handle.id}: ${result.error}")
            }
        }
        busHandle?.let { handle ->
            val result = Sysfs.unregisterBus(handle)
            if (result is VfsResult.Err && result.error != VfsError.NOT_FOUND) {
                println("PCIe: failed to remove sysfs bus: ${result.error}")
            }
        }
        registrationOrder.clear()
        functions.clear()
        roots.clear()
        busHandle = null
    }

    private fun root(segment: UShort, bus: UByte): SysfsObjectHandle? {
        val key = RootBus(segment, bus)
        roots[key]?.let { return it }
        return when (val result = Sysfs.registerObject(
            SysfsObjectSpec(name = rootName(segment, bus)),
        )) {
            is VfsResult.Ok -> result.value.also { handle ->
                roots[key] = handle
                registrationOrder += handle
            }
            is VfsResult.Err -> {
                println("PCIe: failed to publish ${rootName(segment, bus)}: ${result.error}")
                null
            }
        }
    }

    private fun objectSpec(
        function: PciFunctionInfo,
        parent: SysfsObjectHandle,
    ): SysfsObjectSpec = SysfsObjectSpec(
        name = function.address.sysfsName,
        parent = SysfsParent.Object(parent),
        attributes = listOf(
            hexadecimalAttribute("vendor", function.vendorId.toUInt(), 4),
            hexadecimalAttribute("device", function.deviceId.toUInt(), 4),
            hexadecimalAttribute("class", function.classValue, 6),
            hexadecimalAttribute("revision", function.revision.toUInt(), 2),
            SysfsTextAttribute.constant("irq", "${function.interruptLine.toInt()}\n"),
            PciConfigAttribute(function.address),
        ),
        bindings = SysfsBindings(bus = SysfsIndexBinding(PCI_BUS_NAME)),
    )

    private fun hexadecimalAttribute(
        name: String,
        value: UInt,
        width: Int,
    ): SysfsTextAttribute = SysfsTextAttribute.constant(
        name,
        "0x${value.toString(16).padStart(width, '0')}\n",
    )

    companion object {
        const val PCI_BUS_NAME = "pci"

        fun rootName(segment: UShort, bus: UByte): String =
            "pci${segment.toInt().toString(16).padStart(4, '0')}:" +
                bus.toInt().toString(16).padStart(2, '0')
    }
}

private class PciConfigAttribute(
    private val address: PciAddress,
) : SysfsBinaryAttribute("config", PCI_FUNCTION_CONFIG_SIZE) {
    override val writable: Boolean
        get() = true

    override fun read(
        offset: ULong,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
    ): IoResult {
        if (count == 0) return IoResult.success(0)
        val config = liveConfiguration() ?: return IoResult.failure(VfsError.NO_DEVICE)
        if (offset > Int.MAX_VALUE.toULong() || count > Int.MAX_VALUE - offset.toInt()) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        val bytes = ByteArray(count)
        val start = offset.toInt()
        var cursor = 0
        while (cursor < count) {
            val configOffset = start + cursor
            val width = transferWidth(configOffset, count - cursor)
            val value = config.read(configOffset, width)
            repeat(width) { index ->
                bytes[cursor + index] = (value shr (index * Byte.SIZE_BITS)).toByte()
            }
            cursor += width
        }
        val copied = destination.copyFrom(destinationOffset, bytes, 0, count)
        return if (copied != 0) IoResult.success(copied) else IoResult.failure(VfsError.FAULT)
    }

    override fun write(
        offset: ULong,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
    ): IoResult {
        if (count == 0) return IoResult.success(0)
        val config = liveConfiguration() ?: return IoResult.failure(VfsError.NO_DEVICE)
        if (offset > Int.MAX_VALUE.toULong() || count > Int.MAX_VALUE - offset.toInt()) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        val bytes = ByteArray(count)
        val copied = source.copyTo(sourceOffset, bytes, 0, count)
        if (copied == 0) return IoResult.failure(VfsError.FAULT)
        val start = offset.toInt()
        var cursor = 0
        while (cursor < copied) {
            val configOffset = start + cursor
            val width = transferWidth(configOffset, copied - cursor)
            var value = 0uL
            repeat(width) { index ->
                value = value or
                    (bytes[cursor + index].toUByte().toULong() shl (index * Byte.SIZE_BITS))
            }
            config.write(configOffset, width, value)
            cursor += width
        }
        return IoResult.success(copied)
    }

    private fun liveConfiguration(): PciConfigSpace? = Pcie.configurationSpace(address)
        ?.takeUnless { it.readU16(0) == UShort.MAX_VALUE }

    private fun transferWidth(offset: Int, remaining: Int): Int = when {
        (offset and 3) == 0 && remaining >= UInt.SIZE_BYTES -> UInt.SIZE_BYTES
        (offset and 1) == 0 && remaining >= UShort.SIZE_BYTES -> UShort.SIZE_BYTES
        else -> UByte.SIZE_BYTES
    }
}
