package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.sysfs.SysfsBindings
import org.plos_clan.cpos.fs.sysfs.SysfsIndexBinding
import org.plos_clan.cpos.fs.sysfs.SysfsObjectSpec
import org.plos_clan.cpos.fs.sysfs.SysfsParent
import org.plos_clan.cpos.fs.sysfs.SysfsUevent
import org.plos_clan.cpos.fs.sysfs.SysfsTextAttribute
import org.plos_clan.cpos.fs.vfs.VfsResult

internal class NetworkInterfaceKobject(
    private val intfc: NetworkInterfaceView,
    ueventPublisher: KobjectUeventPublisher,
) {
    private val uevent = SysfsUevent(
        KobjectUevent(
            KobjectAction.ADD,
            "/devices/virtual/$SUBSYSTEM/${intfc.name}",
            SUBSYSTEM,
            listOf("INTERFACE" to intfc.name, "IFINDEX" to intfc.index.toString()),
        ),
        ueventPublisher,
    )

    val specification = SysfsObjectSpec(
        name = intfc.name,
        parent = SysfsParent.Virtual(SUBSYSTEM),
        attributes = buildList {
            add(uevent.attribute)
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

    fun publish(action: KobjectAction) = uevent.publish(action)

    private fun attribute(
        name: String,
        value: () -> String,
    ): SysfsTextAttribute = SysfsTextAttribute(
        name,
        reader = {
            VfsResult.Ok("${value()}\n".encodeToByteArray())
        },
    )

    companion object {
        private const val SUBSYSTEM = "net"
        private const val BITS_PER_MEGABIT = 1_000_000uL
    }
}
