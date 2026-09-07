package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.sysfs.SysfsBindings
import org.plos_clan.cpos.fs.sysfs.SysfsIndexBinding
import org.plos_clan.cpos.fs.sysfs.SysfsObjectSpec
import org.plos_clan.cpos.fs.sysfs.SysfsParent
import org.plos_clan.cpos.fs.sysfs.SysfsTextAttribute
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult

internal class NetworkInterfaceKobject(
    private val intfc: NetworkInterfaceView,
    private val ueventPublisher: KobjectUeventPublisher,
) {
    private val deviceEnvironment = listOf(
        "INTERFACE" to intfc.name,
        "IFINDEX" to intfc.index.toString(),
    )

    val specification = SysfsObjectSpec(
        name = intfc.name,
        parent = SysfsParent.Virtual(SUBSYSTEM),
        attributes = buildList {
            add(attribute("uevent", UEVENT_MODE, ::storeUevent) {
                deviceEnvironment.joinToString("\n") { (key, value) -> "$key=$value" }
            })
            add(attribute("ifindex") { intfc.index.toString() })
            add(attribute("iflink") { intfc.index.toString() })
            add(attribute("type") { intfc.kind.hardwareType.toString() })
            add(attribute("address") { intfc.hardwareAddress.toString() })
            add(attribute("addr_len") { MacAddress.SIZE_BYTES.toString() })
            add(attribute("broadcast") { intfc.kind.broadcastAddress.toString() })
            add(attribute("mtu") { intfc.mtu.toString() })
            add(attribute("flags") { "0x${intfc.configurationFlags.toString(16)}" })
            add(attribute("operstate") { intfc.operationalState.sysfsName })
            add(attribute("carrier") { if (intfc.carrier) "1" else "0" })
            if (intfc.kind == NetworkInterfaceKind.ETHERNET) {
                add(attribute("speed") {
                    (intfc.linkSpeedBitsPerSecond / BITS_PER_MEGABIT).toString()
                })
            }
        },
        bindings = SysfsBindings(deviceClass = SysfsIndexBinding(SUBSYSTEM)),
    )

    fun publish(action: KobjectAction) = ueventPublisher.publish(event(action))

    private fun storeUevent(input: ByteArray): VfsResult<Unit> {
        val request = KobjectUeventRequest.parse(input)
            ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        ueventPublisher.publish(event(request.action, request.environment))
        return VfsResult.Ok(Unit)
    }

    private fun event(
        action: KobjectAction,
        syntheticEnvironment: List<Pair<String, String>> = emptyList(),
    ) = KobjectUevent(
        action,
        "/devices/virtual/$SUBSYSTEM/${intfc.name}",
        SUBSYSTEM,
        if (syntheticEnvironment.isEmpty()) deviceEnvironment
        else syntheticEnvironment + deviceEnvironment,
    )

    private fun attribute(
        name: String,
        mode: UInt = SysfsTextAttribute.TEXT_MODE,
        writer: ((ByteArray) -> VfsResult<Unit>)? = null,
        value: () -> String,
    ): SysfsTextAttribute = SysfsTextAttribute(
        name,
        mode,
        {
            VfsResult.Ok("${value()}\n".encodeToByteArray())
        },
        writer,
    )

    companion object {
        private const val SUBSYSTEM = "net"
        private const val BITS_PER_MEGABIT = 1_000_000uL
        private const val UEVENT_MODE = 0x1a4u
    }
}
