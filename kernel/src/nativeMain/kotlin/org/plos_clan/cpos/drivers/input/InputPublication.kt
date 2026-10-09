package org.plos_clan.cpos.drivers.input

import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.fs.sysfs.SysfsBindings
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.fs.sysfs.SysfsIndexBinding
import org.plos_clan.cpos.fs.sysfs.SysfsObjectHandle
import org.plos_clan.cpos.fs.sysfs.SysfsObjectSpec
import org.plos_clan.cpos.fs.sysfs.SysfsParent
import org.plos_clan.cpos.fs.sysfs.SysfsUevent
import org.plos_clan.cpos.network.KobjectAction
import org.plos_clan.cpos.network.KobjectUevent
import org.plos_clan.cpos.network.KobjectUeventNetlinkProtocol
import org.plos_clan.cpos.fs.sysfs.SysfsTextAttribute
import org.plos_clan.cpos.fs.vfs.VfsResult

internal class InputPublication private constructor(
    private val objects: List<SysfsObjectHandle>,
    private val backend: DeviceBackend,
    private val uevent: SysfsUevent,
) {
    fun close() {
        DeviceManager.unregisterAll(backend)
        uevent.publish(KobjectAction.REMOVE)
        objects.asReversed().forEach { Sysfs.unregisterObject(it) }
    }

    companion object {
        fun create(
            index: Int,
            backend: DeviceBackend,
            name: String,
            physicalPath: String,
            id: InputId,
            capabilities: Map<String, ByteArray>,
        ): InputPublication? {
            val deviceClass = SysfsIndexBinding("input")
            val bindings = SysfsBindings(deviceClass = deviceClass)
            val productIds = listOf(id.bus, id.vendor, id.product, id.version)
            val product = productIds.joinToString("/") { it.toString(16) }
            val environment = listOf(
                "PRODUCT" to product, "NAME" to "\"$name\"", "PHYS" to "\"$physicalPath\"", "PROP" to "0",
            ) + capabilities.map { (key, value) -> key.uppercase() to bitmapText(value).trimEnd() }
            val event = KobjectUevent(
                KobjectAction.ADD, "/devices/virtual/input/input$index", "input", environment,
            )
            val uevent = SysfsUevent(event, KobjectUeventNetlinkProtocol)
            val attributes = listOf(
                uevent.attribute,
                SysfsTextAttribute.constant("name", "$name\n"),
                SysfsTextAttribute.constant("phys", "$physicalPath\n"),
                SysfsTextAttribute.constant("uniq", "\n"),
                SysfsTextAttribute.constant("properties", "0\n"),
            )
            val parentRoot = SysfsParent.Virtual("input")
            val parentSpec = SysfsObjectSpec(
                "input$index", parentRoot, attributes = attributes, bindings = bindings,
            )
            val parent = (Sysfs.registerObject(parentSpec) as? VfsResult.Ok)?.value ?: return null
            val parentObject = SysfsParent.Object(parent)
            val objects = mutableListOf(parent)
            val publication = InputPublication(objects, backend, uevent)
            val identity = mapOf(
                "bustype" to id.bus, "vendor" to id.vendor,
                "product" to id.product, "version" to id.version,
            ).map { (key, value) ->
                SysfsTextAttribute.constant(key, value.toString(16).padStart(4, '0') + "\n")
            }
            val bitmaps = capabilities.map { (key, value) ->
                SysfsTextAttribute.constant(key, bitmapText(value))
            }
            val children = listOf("id" to identity, "capabilities" to bitmaps)
            for ((child, values) in children) {
                val spec = SysfsObjectSpec(child, parentObject, attributes = values)
                val handle = (Sysfs.registerObject(spec) as? VfsResult.Ok)?.value
                if (handle == null) {
                    publication.close()
                    return null
                }
                objects.add(handle)
            }
            uevent.publish(KobjectAction.ADD)
            val eventSpec = SysfsObjectSpec(
                "event$index", parentObject, bindings = bindings,
            )
            val eventPublication = SysfsDevicePublication.NewObject(eventSpec)
            val registration = DeviceRegistration(
                name = "input/event$index",
                type = DeviceType.CHARACTER,
                major = LinuxDeviceMajor.INPUT.number,
                minor = (64 + index).toUInt(),
                backend = backend,
                sysfs = eventPublication,
            )
            if (DeviceManager.register(registration) != null) return publication
            publication.close()
            return null
        }

        private fun bitmapText(bytes: ByteArray): String {
            val words = MutableList((bytes.size + 7) / 8) { 0uL }
            bytes.forEachIndexed { index, byte ->
                val shift = index % 8 * 8
                words[index / 8] = words[index / 8] or (byte.toUByte().toULong() shl shift)
            }
            val text = words.asReversed().dropWhile { it == 0uL }.joinToString(" ") { it.toString(16) }
            return text.ifEmpty { "0" } + "\n"
        }
    }
}
