package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.utils.Errno

object CapManager {
    fun hasAllCapability(thread: Thread, vararg caps: CapEnum): Boolean =
        caps.all(thread.capabilities::hasEffective)

    fun capabilityCount(version: UInt): Int = when (version) {
        LINUX_CAPABILITY_VERSION_1 -> 1
        LINUX_CAPABILITY_VERSION_2, LINUX_CAPABILITY_VERSION_3 -> 2
        else -> errno(Errno.EINVAL).toInt()
    }

    fun capabilityApply(array: Array<Capabilities>, task: Thread): Long {
        var effective = array[0].effective.toULong()
        var permitted = array[0].permitted.toULong()
        var inheritable = array[0].inheritable.toULong()

        if (array.size > 1) {
            effective = effective or (array[1].effective.toULong() shl 32)
            permitted = permitted or (array[1].permitted.toULong() shl 32)
            inheritable = inheritable or (array[1].inheritable.toULong() shl 32)
        }

        return if (task.capabilities.apply(effective, permitted, inheritable)) {
            errno(Errno.EOK)
        } else {
            errno(Errno.EPERM)
        }
    }
}
