@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml

import org.plos_clan.cpos.coroutines.KernelEvent

import org.plos_clan.cpos.drivers.acpi.Acpi
import org.plos_clan.cpos.drivers.acpi.AcpiTable
import org.plos_clan.cpos.drivers.acpi.Fadt
import org.plos_clan.cpos.drivers.char.Ps2Keyboard
import org.plos_clan.cpos.utils.checksumOk

private const val ACPI_SDT_HEADER_LENGTH = 36

data class AmlDeviceInfo(
    val path: AmlName,
    val hardwareId: String?,
    val compatibleIds: List<String>,
    val status: ULong,
    val resources: List<AcpiResource>,
) {
    val present: Boolean
        get() = (status and 0x01uL) != 0uL

    val enabled: Boolean
        get() = (status and 0x02uL) != 0uL

    fun matchesId(id: String): Boolean =
        hardwareId == id || id in compatibleIds
}

object Aml {
    val namespace = AmlNamespace()

    private val regions = AmlRegionManager(namespace)
    private val evaluator = AmlEvaluator(namespace, regions, AmlEvents::notify)
    private var initialized = false
    private var enumerated = false
    private var devices = emptyList<AmlDeviceInfo>()

    val isInitialized: Boolean
        get() = initialized

    val enumeratedDevices: List<AmlDeviceInfo>
        get() = devices

    fun initialize(): Boolean {
        if (initialized) {
            return true
        }
        namespace.clear()

        val dsdtAddress = Fadt.info?.dsdtAddress ?: run {
            println("AML: FADT did not provide DSDT")
            return false
        }
        val dsdt = Acpi.tableAtPhysical(dsdtAddress)
            ?.takeIf { it.signature == "DSDT" && it.isValidAmlTable() }
            ?: run {
                println("AML: DSDT is unavailable or invalid")
                return false
            }
        val ssdts = Acpi.findTables("SSDT").filter { it.isValidAmlTable() }

        val loader = AmlLoader(namespace)
        if (!loader.load(dsdt)) {
            return false
        }
        ssdts.forEach(loader::load)

        initialized = true
        println(
            "AML: namespace loaded definitions=${loader.definitionCount} " +
                "tables=${1 + ssdts.size}",
        )
        return true
    }

    fun enumerateDevices(): List<AmlDeviceInfo> {
        if (!initialized && !initialize()) {
            return emptyList()
        }
        if (enumerated) {
            return devices
        }

        evaluate("\\_PIC", listOf(AmlInteger(1uL)))

        devices = namespace.allNodes()
            .asSequence()
            .filter { it.value === AmlDevice }
            .mapNotNull(::describeDevice)
            .toList()
        enumerated = true

        devices.filter { it.present && it.enabled }.forEach { device ->
            evaluate(device.path.child("_INI"))
        }

        if (Fadt.info?.hasI8042Controller != false) {
            devices.firstOrNull { device ->
                device.present && device.enabled &&
                    (device.matchesId("PNP0303") || device.matchesId("PNP030B"))
            }?.let(Ps2Keyboard::initialize)
        }

        devices.filter { device ->
            device.present && device.enabled && device.matchesId("PNP0C0C")
        }.forEach { device ->
            val node = namespace.find(device.path) ?: return@forEach
            val gpe = evaluateChild(node, "_PRW").wakeGpeNumber() ?: return@forEach
            AmlEvents.registerPowerButton(device.path, gpe)
        }

        AmlEvents.installSciRoute()
        println("AML: enumerated ${devices.size} namespace devices")
        return devices
    }

    fun evaluate(
        name: AmlName,
        arguments: List<AmlObject> = emptyList(),
    ): AmlObject? = if (initialized) evaluator.evaluate(name, arguments) else null

    fun evaluate(
        absolutePath: String,
        arguments: List<AmlObject> = emptyList(),
    ): AmlObject? = parseAbsoluteName(absolutePath)?.let { evaluate(it, arguments) }

    fun findDevicesById(id: String): List<AmlDeviceInfo> =
        enumerateDevices().filter { it.matchesId(id) }

    fun processPendingEvents(maxEvents: Int = 64): Int =
        AmlEvents.processPending(maxEvents)

    internal fun installEventWakeup(wakeup: KernelEvent) {
        AmlEvents.installWorkerWakeup(wakeup)
    }

    internal fun evaluateGpe(number: UInt, edgeTriggered: Boolean) {
        if (number > 0xFFu) {
            return
        }
        val prefix = if (edgeTriggered) "_E" else "_L"
        val suffix = number.toString(16).uppercase().padStart(2, '0')
        evaluate(AmlName(listOf("_GPE", "$prefix$suffix")))
    }

    internal fun evaluateGpe(number: UInt) {
        if (number > 0xFFu) {
            return
        }
        val suffix = number.toString(16).uppercase().padStart(2, '0')
        val level = AmlName(listOf("_GPE", "_L$suffix"))
        if (namespace.find(level) != null) {
            evaluate(level)
            return
        }
        val edge = AmlName(listOf("_GPE", "_E$suffix"))
        if (namespace.find(edge) != null) {
            evaluate(edge)
        }
    }

    private fun describeDevice(node: AmlNamespaceNode): AmlDeviceInfo? {
        val status = evaluateChild(node, "_STA")?.integerValue() ?: 0x0FuL
        if ((status and 0x01uL) == 0uL) {
            return AmlDeviceInfo(node.name, null, emptyList(), status, emptyList())
        }

        val hardwareId = evaluateChild(node, "_HID")?.toAcpiId()
        val compatibleIds = evaluateChild(node, "_CID").toAcpiIds()
        val resources = (evaluateChild(node, "_CRS")?.dereference() as? AmlBuffer)
            ?.let(AmlResourceTemplateParser::parse)
            .orEmpty()
        return AmlDeviceInfo(
            path = node.name,
            hardwareId = hardwareId,
            compatibleIds = compatibleIds,
            status = status,
            resources = resources,
        )
    }

    private fun evaluateChild(node: AmlNamespaceNode, segment: String): AmlObject? =
        node.child(segment)?.let(evaluator::evaluate)

    private fun AcpiTable.isValidAmlTable(): Boolean =
        length >= ACPI_SDT_HEADER_LENGTH && pointer.checksumOk(length)
}

private fun AmlObject?.wakeGpeNumber(): UInt? {
    val packageValue = this?.dereference() as? AmlPackage ?: return null
    val descriptor = packageValue.elements.firstOrNull()?.dereference() ?: return null
    val number = when (descriptor) {
        is AmlInteger -> descriptor.value
        is AmlPackage -> descriptor.elements.getOrNull(1)?.integerValue()
        else -> null
    } ?: return null
    return number.takeIf { it <= UInt.MAX_VALUE.toULong() }?.toUInt()
}

private fun AmlObject?.toAcpiIds(): List<String> = when (val value = this?.dereference()) {
    is AmlPackage -> value.elements.mapNotNull(AmlObject::toAcpiId)
    null -> emptyList()
    else -> listOfNotNull(value.toAcpiId())
}

private fun AmlObject.toAcpiId(): String? = when (val value = dereference()) {
    is AmlString -> value.value
    is AmlInteger -> decodeEisaId(value.value.toUInt())
    else -> null
}

private fun decodeEisaId(encoded: UInt): String {
    val value = ((encoded and 0x000000FFu) shl 24) or
        ((encoded and 0x0000FF00u) shl 8) or
        ((encoded and 0x00FF0000u) shr 8) or
        ((encoded and 0xFF000000u) shr 24)
    val first = (((value shr 26) and 0x1Fu) + 0x40u).toInt().toChar()
    val second = (((value shr 21) and 0x1Fu) + 0x40u).toInt().toChar()
    val third = (((value shr 16) and 0x1Fu) + 0x40u).toInt().toChar()
    val product = (value and 0xFFFFu).toString(16).uppercase().padStart(4, '0')
    return "$first$second$third$product"
}

private fun parseAbsoluteName(path: String): AmlName? {
    if (!path.startsWith('\\')) {
        return null
    }
    if (path == "\\") {
        return AmlName.ROOT
    }
    return path.removePrefix("\\")
        .split('.')
        .takeIf { segments -> segments.all { it.length == 4 } }
        ?.let(::AmlName)
}
