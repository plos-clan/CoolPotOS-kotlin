package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.sysfs.SysfsBindings
import org.plos_clan.cpos.fs.sysfs.SysfsIndexBinding
import org.plos_clan.cpos.fs.sysfs.SysfsObjectSpec
import org.plos_clan.cpos.fs.sysfs.SysfsParent
import org.plos_clan.cpos.fs.sysfs.SysfsTextAttribute
import org.plos_clan.cpos.fs.vfs.VfsResult

internal class NetworkInterfaceKobject(
    private val interface_: NetworkInterfaceView,
) {
    private val environment = listOf(
        "INTERFACE" to interface_.name,
        "IFINDEX" to interface_.index.toString(),
    )

    val specification = SysfsObjectSpec(
        name = interface_.name,
        parent = SysfsParent.Virtual(SUBSYSTEM),
        attributes = buildList {
            add(attribute("uevent") {
                environment.joinToString("\n") { (key, value) -> "$key=$value" }
            })
            add(attribute("ifindex") { interface_.index.toString() })
            add(attribute("iflink") { interface_.index.toString() })
            add(attribute("type") { interface_.kind.hardwareType.toString() })
            add(attribute("address") { interface_.hardwareAddress.toString() })
            add(attribute("addr_len") { MacAddress.SIZE_BYTES.toString() })
            add(attribute("broadcast") { interface_.kind.broadcastAddress.toString() })
            add(attribute("mtu") { interface_.mtu.toString() })
            add(attribute("flags") { "0x${interface_.configurationFlags.toString(16)}" })
            add(attribute("operstate") { interface_.operationalState.sysfsName })
            add(attribute("carrier") { if (interface_.carrier) "1" else "0" })
            if (interface_.kind == NetworkInterfaceKind.ETHERNET) {
                add(attribute("speed") {
                    (interface_.linkSpeedBitsPerSecond / BITS_PER_MEGABIT).toString()
                })
            }
        },
        bindings = SysfsBindings(deviceClass = SysfsIndexBinding(SUBSYSTEM)),
    )

    fun event(action: KobjectAction): KobjectUevent = KobjectUevent(
        action,
        "/devices/virtual/$SUBSYSTEM/${interface_.name}",
        SUBSYSTEM,
        environment,
    )

    private fun attribute(name: String, value: () -> String): SysfsTextAttribute =
        SysfsTextAttribute(name, reader = {
            VfsResult.Ok("${value()}\n".encodeToByteArray())
        })

    companion object {
        private const val SUBSYSTEM = "net"
        private const val BITS_PER_MEGABIT = 1_000_000uL
    }
}
