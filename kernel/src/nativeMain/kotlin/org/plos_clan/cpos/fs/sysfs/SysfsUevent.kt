package org.plos_clan.cpos.fs.sysfs

import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.network.KobjectAction
import org.plos_clan.cpos.network.KobjectUevent
import org.plos_clan.cpos.network.KobjectUeventPublisher
import org.plos_clan.cpos.network.KobjectUeventRequest

internal class SysfsUevent(
    private val event: KobjectUevent,
    private val publisher: KobjectUeventPublisher,
) {
    val attribute = SysfsTextAttribute(
        name = "uevent",
        mode = 0x1a4u,
        reader = {
            VfsResult.Ok(buildString {
                event.environment.forEach { (key, value) -> append(key).append('=').append(value).append('\n') }
            }.encodeToByteArray())
        },
        writer = { input ->
            val request = KobjectUeventRequest.parse(input)
            if (request == null) {
                VfsResult.Err(VfsError.INVALID_ARGUMENT)
            } else {
                publisher.publish(event.copy(
                    action = request.action,
                    environment = request.environment + event.environment,
                ))
                VfsResult.Ok(Unit)
            }
        },
    )

    fun publish(action: KobjectAction) = publisher.publish(event.copy(action = action))
}
