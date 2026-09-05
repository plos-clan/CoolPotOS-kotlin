package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.NativeStruct

const val LINUX_CAPABILITY_VERSION_1 = 0x19980330U
const val LINUX_CAPABILITY_VERSION_2 = 0x20071026U
const val LINUX_CAPABILITY_VERSION_3 = 0x20080522U

const val TASK_CAP_LAST_CAP = 40
const val TASK_CAP_FULL_MASK = 0x1ff_ffff_ffffuL

enum class CapEnum(val id: Int) {
    KILL(5),
    SETGID(6),
    SETUID(7),
    SETPCAP(8),
    NET_BIND_SERVICE(10),
    NET_ADMIN(12),
    NET_RAW(13),
    SYS_PTRACE(19),
    SYS_ADMIN(21),
    AUDIT_WRITE(29),
}

class CapabilityState(
    var effective: ULong = TASK_CAP_FULL_MASK,
    var permitted: ULong = TASK_CAP_FULL_MASK,
    var inheritable: ULong = 0uL,
    var bounding: ULong = TASK_CAP_FULL_MASK,
    var ambient: ULong = 0uL,
    var keepAcrossUserIdChange: Boolean = false,
    var noNewPrivileges: Boolean = false,
) {
    fun inherit(parent: CapabilityState) {
        effective = parent.effective
        permitted = parent.permitted
        inheritable = parent.inheritable
        bounding = parent.bounding
        ambient = parent.ambient
        keepAcrossUserIdChange = parent.keepAcrossUserIdChange
        noNewPrivileges = parent.noNewPrivileges
    }

    fun hasEffective(capability: CapEnum): Boolean = has(effective, capability.id)

    fun containsBounding(capability: Int): Boolean = has(bounding, capability)

    fun dropBounding(capability: Int) {
        bounding = bounding and bit(capability).inv()
    }

    fun containsAmbient(capability: Int): Boolean = has(ambient, capability)

    fun raiseAmbient(capability: Int): Boolean {
        val mask = bit(capability)
        if ((permitted and inheritable and mask) == 0uL) return false
        ambient = ambient or mask
        return true
    }

    fun lowerAmbient(capability: Int) {
        ambient = ambient and bit(capability).inv()
    }

    fun clearAmbient() {
        ambient = 0uL
    }

    fun apply(effective: ULong, permitted: ULong, inheritable: ULong): Boolean {
        val requestedEffective = effective and TASK_CAP_FULL_MASK
        val requestedPermitted = permitted and TASK_CAP_FULL_MASK
        val requestedInheritable = inheritable and TASK_CAP_FULL_MASK
        if (requestedEffective and requestedPermitted.inv() != 0uL ||
            requestedPermitted and this.permitted.inv() != 0uL
        ) {
            return false
        }

        val addedInheritable = requestedInheritable and this.inheritable.inv()
        val inheritableLimit = this.permitted or
            if (hasEffective(CapEnum.SETPCAP)) bounding else 0uL
        if (addedInheritable and inheritableLimit.inv() != 0uL) return false

        this.effective = requestedEffective
        this.permitted = requestedPermitted
        this.inheritable = requestedInheritable
        ambient = ambient and requestedPermitted and requestedInheritable
        return true
    }

    fun applyUserIdChange(change: Credentials.UserIdChange) {
        val previous = change.previous
        val current = change.current
        val hadRootIdentity = previous.real == 0 || previous.effective == 0 || previous.saved == 0
        val hasRootIdentity = current.real == 0 || current.effective == 0 || current.saved == 0
        when {
            hadRootIdentity && !hasRootIdentity -> {
                if (!keepAcrossUserIdChange) permitted = 0uL
                effective = 0uL
                ambient = 0uL
            }

            previous.effective == 0 && current.effective != 0 -> effective = 0uL
            previous.effective != 0 && current.effective == 0 -> effective = permitted
        }
    }

    fun applyExec(execution: Credentials.Execution) {
        val inheritedAmbient = if (execution.privileged) 0uL else ambient
        val root = execution.userIds.real == 0 || execution.userIds.effective == 0
        permitted = (if (root) bounding else 0uL) or inheritedAmbient
        effective = if (execution.userIds.effective == 0) permitted else inheritedAmbient
        ambient = inheritedAmbient
        keepAcrossUserIdChange = false
    }

    companion object {
        fun isValid(capability: Int): Boolean = capability in 0..TASK_CAP_LAST_CAP

        private fun bit(capability: Int): ULong = 1uL shl capability

        private fun has(set: ULong, capability: Int): Boolean =
            isValid(capability) && set and bit(capability) != 0uL
    }
}

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
