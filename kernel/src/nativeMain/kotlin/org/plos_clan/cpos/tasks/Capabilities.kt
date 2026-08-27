package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.NativeStruct

const val LINUX_CAPABILITY_VERSION_1 = 0x19980330U
const val LINUX_CAPABILITY_VERSION_2 = 0x20071026U
const val LINUX_CAPABILITY_VERSION_3 = 0x20080522U

const val TASK_CAP_LAST_CAP = 40U
const val TASK_CAP_FULL_MASK = 0x1FFFFFFFFFFu

data class CapHeader(
    var version: UInt,
    var pid: Int,
) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(NATIVE_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU32(0, version)
            writeU32(4, pid.toUInt())
        }
    }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        if (buffer.size != NATIVE_SIZE) return false
        val input = LittleEndianBuffer(buffer)
        version = input.readU32(0)
        pid = input.readU32(4).toInt()
        return true
    }

    companion object {
        const val NATIVE_SIZE = 8
    }
}

data class Capabilities(
    var effective: UInt,
    var permitted: UInt,
    var inheritable: UInt,
) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(NATIVE_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU32(0, effective)
            writeU32(4, permitted)
            writeU32(8, inheritable)
        }
    }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        if (buffer.size != NATIVE_SIZE) return false
        val input = LittleEndianBuffer(buffer)
        effective = input.readU32(0)
        permitted = input.readU32(4)
        inheritable = input.readU32(8)
        return true
    }

    companion object {
        const val NATIVE_SIZE = 12
    }
}

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

    effective = effective and TASK_CAP_FULL_MASK
    permitted = permitted and TASK_CAP_FULL_MASK
    inheritable = inheritable and TASK_CAP_FULL_MASK

    if ((effective and permitted.inv()) != 0UL) return errno(Errno.EPERM)

    task.effective = effective
    task.permitted = permitted
    task.inheritable = inheritable
    task.ambient = task.ambient and (permitted and inheritable)

    return errno(Errno.EOK)
}
