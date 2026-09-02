package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sysfs.Sysfs
import org.plos_clan.cpos.fs.sysfs.SysfsObjectHandle
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.IrqSpinLock

internal object NetworkInterfaceKobjects : NetworkConfigurationListener {
    private data class Publication(
        val kobject: NetworkInterfaceKobject,
        val handle: SysfsObjectHandle,
    )

    private val lock = IrqSpinLock()
    private val publications = mutableMapOf<NetworkInterface, Publication>()

    override fun linkChanged(interface_: NetworkInterface, removed: Boolean) = lock.withLock {
        if (removed) {
            val publication = publications.remove(interface_) ?: return@withLock
            KobjectUeventNetlinkProtocol.publish(publication.kobject.event(KobjectAction.REMOVE))
            val result = Sysfs.unregisterObject(publication.handle)
            if (result is VfsResult.Err && result.error != VfsError.NOT_FOUND) {
                println("net: failed to remove sysfs object ${interface_.name}: ${result.error}")
            }
            return@withLock
        }

        if (publications.containsKey(interface_)) return@withLock
        val kobject = NetworkInterfaceKobject(interface_)
        when (val result = Sysfs.registerObject(kobject.specification)) {
            is VfsResult.Ok -> {
                publications[interface_] = Publication(kobject, result.value)
                KobjectUeventNetlinkProtocol.publish(kobject.event(KobjectAction.ADD))
            }
            is VfsResult.Err -> println(
                "net: failed to publish sysfs object ${interface_.name}: ${result.error}",
            )
        }
    }
}
