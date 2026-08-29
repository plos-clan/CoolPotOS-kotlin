@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.module.elf.ElfLoader
import org.plos_clan.cpos.syscall.Syscall.copyWordToUser
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.CapHeader
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.CapManager
import org.plos_clan.cpos.tasks.CapabilityState
import org.plos_clan.cpos.tasks.Capabilities
import org.plos_clan.cpos.tasks.ChildEventKind
import org.plos_clan.cpos.tasks.LINUX_CAPABILITY_VERSION_3
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessGroupResult
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.tasks.ResourceLimit
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.SignalStack
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.PtraceRegisters
import org.plos_clan.cpos.utils.toByteArray

private const val ARCH_SET_GS = 0x1001uL
private const val ARCH_SET_FS = 0x1002uL
private const val ARCH_GET_FS = 0x1003uL
private const val ARCH_GET_GS = 0x1004uL
private const val MSR_KERNEL_GS_BASE = 0xC0000102U
private const val WAIT_WNOHANG = 1uL
private const val WAIT_WUNTRACED = 2uL
private const val WAIT_WCONTINUED = 8uL
private const val WAIT_SUPPORTED = 0x0buL
private const val ROBUST_LIST_SIZE = 24uL
private const val PR_GET_DUMPABLE = 3UL
private const val PR_SET_DUMPABLE = 4UL
private const val PR_GET_KEEPCAPS = 7UL
private const val PR_SET_KEEPCAPS = 8UL
private const val PR_SET_NAME = 15UL
private const val PR_GET_NAME = 16UL
private const val PR_CAPBSET_READ = 23UL
private const val PR_CAPBSET_DROP = 24UL
private const val PR_SET_NO_NEW_PRIVS = 38UL
private const val PR_GET_NO_NEW_PRIVS = 39UL
private const val PR_CAP_AMBIENT = 47UL
private const val PR_CAP_AMBIENT_IS_SET = 1uL
private const val PR_CAP_AMBIENT_RAISE = 2uL
private const val PR_CAP_AMBIENT_LOWER = 3uL
private const val PR_CAP_AMBIENT_CLEAR_ALL = 4uL
private const val PR_NAME_SIZE = 16
private const val NGROUPS_MAX = 65_536

private sealed interface LinuxIdArgument {
    data object Unchanged : LinuxIdArgument
    data class Value(val id: Int) : LinuxIdArgument

    companion object {
        fun decode(raw: ULong): LinuxIdArgument? = when {
            raw == UInt.MAX_VALUE.toULong() -> Unchanged
            raw <= Int.MAX_VALUE.toULong() -> Value(raw.toInt())
            else -> null
        }
    }
}

private class LinuxRLimit(val limit: ResourceLimit) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(NATIVE_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU64(0, limit.soft)
            writeU64(ULong.SIZE_BYTES, limit.hard)
        }
    }

    companion object {
        const val NATIVE_SIZE = ULong.SIZE_BYTES * 2
    }
}

internal fun archPrctl(regs: PtraceRegisters, process: Process): Long {
    val command = regs[PtraceRegisters.IDX_RDI]
    val argument = regs[PtraceRegisters.IDX_RSI]
    return when (command) {
        ARCH_SET_FS -> {
            if (argument >= USER_VIRTUAL_ADDRESS_LIMIT) {
                errno(Errno.EPERM)
            } else {
                regs[PtraceRegisters.IDX_FS_BASE] = argument
                0L
            }
        }

        ARCH_SET_GS -> {
            if (argument >= USER_VIRTUAL_ADDRESS_LIMIT) {
                errno(Errno.EPERM)
            } else {
                bridge.wrmsr(MSR_KERNEL_GS_BASE, argument)
                0L
            }
        }

        ARCH_GET_FS -> copyWordToUser(
            process,
            argument,
            regs[PtraceRegisters.IDX_FS_BASE],
        )

        ARCH_GET_GS -> copyWordToUser(
            process,
            argument,
            bridge.rdmsr(MSR_KERNEL_GS_BASE),
        )

        else -> errno(Errno.EINVAL)
    }
}

internal fun getPid(regs: PtraceRegisters, process: Process): Long = process.id.toLong()

internal fun getUid(regs: PtraceRegisters, process: Process): Long =
    process.credentials.userIds.real.toLong()

internal fun getGid(regs: PtraceRegisters, process: Process): Long =
    process.credentials.groupIds.real.toLong()

internal fun getEuid(regs: PtraceRegisters, process: Process): Long =
    process.credentials.userIds.effective.toLong()

internal fun getEgid(regs: PtraceRegisters, process: Process): Long =
    process.credentials.groupIds.effective.toLong()

internal fun setUid(regs: PtraceRegisters, process: Process): Long {
    val requested = regs[PtraceRegisters.IDX_RDI]
        .takeIf { it <= Int.MAX_VALUE.toULong() }
        ?.toInt() ?: return errno(Errno.EINVAL)
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val change = process.credentials.setUserId(
        requested,
        thread.capabilities.hasEffective(CapEnum.SETUID),
    ) ?: return errno(Errno.EPERM)
    thread.capabilities.applyUserIdChange(change)
    return 0L
}

internal fun setGid(regs: PtraceRegisters, process: Process): Long {
    val requested = regs[PtraceRegisters.IDX_RDI]
        .takeIf { it <= Int.MAX_VALUE.toULong() }
        ?.toInt() ?: return errno(Errno.EINVAL)
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    return if (process.credentials.setGroupId(
            requested,
            thread.capabilities.hasEffective(CapEnum.SETGID),
        )
    ) {
        0L
    } else {
        errno(Errno.EPERM)
    }
}

internal fun getPpid(regs: PtraceRegisters, process: Process): Long = process.parentId.toLong()

internal fun getPgrp(regs: PtraceRegisters, process: Process): Long =
    process.processGroupId.toLong()

internal fun setPgid(regs: PtraceRegisters, process: Process): Long {
    val pid = regs[PtraceRegisters.IDX_RDI].toInt()
    val group = regs[PtraceRegisters.IDX_RSI].toInt()
    if (pid < 0) return errno(Errno.ESRCH)
    if (group < 0) return errno(Errno.EINVAL)
    return when (ProcessManager.setProcessGroup(process, pid, group)) {
        ProcessGroupResult.SUCCESS -> 0L
        ProcessGroupResult.NO_SUCH_PROCESS -> errno(Errno.ESRCH)
        ProcessGroupResult.NOT_PERMITTED -> errno(Errno.EPERM)
    }
}

internal fun setSid(regs: PtraceRegisters, process: Process): Long =
    if (ProcessManager.createSession(process)) process.id.toLong() else errno(Errno.EPERM)

internal fun setFsUid(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val requested = regs[PtraceRegisters.IDX_RDI]
        .takeIf { it <= Int.MAX_VALUE.toULong() }
        ?.toInt()
    return process.credentials.setFilesystemUserId(
        requested,
        thread.capabilities.hasEffective(CapEnum.SETUID),
    ).toLong()
}

internal fun setFsGid(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val requested = regs[PtraceRegisters.IDX_RDI]
        .takeIf { it <= Int.MAX_VALUE.toULong() }
        ?.toInt()
    return process.credentials.setFilesystemGroupId(
        requested,
        thread.capabilities.hasEffective(CapEnum.SETGID),
    ).toLong()
}

internal fun getSid(regs: PtraceRegisters, process: Process): Long {
    val pid = regs[PtraceRegisters.IDX_RDI].toInt()
    val process =
        if (pid == 0) process else ProcessManager.findProcess(pid) ?: return errno(Errno.ESRCH)
    return process.sessionId.toLong()
}

internal fun getResUid(regs: PtraceRegisters, process: Process): Long = copyIds(
    process,
    regs[PtraceRegisters.IDX_RDI],
    regs[PtraceRegisters.IDX_RSI],
    regs[PtraceRegisters.IDX_RDX],
    process.credentials.userIds.real,
    process.credentials.userIds.effective,
    process.credentials.userIds.saved,
)

internal fun getResGid(regs: PtraceRegisters, process: Process): Long = copyIds(
    process,
    regs[PtraceRegisters.IDX_RDI],
    regs[PtraceRegisters.IDX_RSI],
    regs[PtraceRegisters.IDX_RDX],
    process.credentials.groupIds.real,
    process.credentials.groupIds.effective,
    process.credentials.groupIds.saved,
)

internal fun setReUid(regs: PtraceRegisters, process: Process): Long =
    changeUserIds(process, regs, res = false)

internal fun setReGid(regs: PtraceRegisters, process: Process): Long =
    changeGroupIds(process, regs, res = false)

internal fun setResUid(regs: PtraceRegisters, process: Process): Long =
    changeUserIds(process, regs, res = true)

internal fun setResGid(regs: PtraceRegisters, process: Process): Long =
    changeGroupIds(process, regs, res = true)

internal fun getGroups(regs: PtraceRegisters, process: Process): Long {
    val size = regs[PtraceRegisters.IDX_RDI]
    if (size > Int.MAX_VALUE.toULong()) return errno(Errno.EINVAL)
    val groups = process.credentials.supplementaryGroups
    if (size == 0uL) return groups.size.toLong()
    if (size < groups.size.toULong()) return errno(Errno.EINVAL)

    val output = ByteArray(groups.size * UInt.SIZE_BYTES).also { bytes ->
        val buffer = LittleEndianBuffer(bytes)
        groups.forEachIndexed { index, group ->
            buffer.writeU32(index * UInt.SIZE_BYTES, group.toUInt())
        }
    }
    return if (UserMemory(
            process.addressSpace,
            regs[PtraceRegisters.IDX_RSI],
        ).copyToUser(output)
    ) {
        groups.size.toLong()
    } else {
        errno(Errno.EFAULT)
    }
}

internal fun setGroups(regs: PtraceRegisters, process: Process): Long {
    val count = regs[PtraceRegisters.IDX_RDI]
    if (count > NGROUPS_MAX.toULong()) return errno(Errno.EINVAL)
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    if (!thread.capabilities.hasEffective(CapEnum.SETGID)) return errno(Errno.EPERM)
    if (count == 0uL) {
        process.credentials.replaceSupplementaryGroups(emptyList())
        return 0L
    }

    val input = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
        .copyFromUser(count.toInt() * UInt.SIZE_BYTES)
        ?: return errno(Errno.EFAULT)
    val buffer = LittleEndianBuffer(input)
    val groups = ArrayList<Int>(count.toInt())
    repeat(count.toInt()) { index ->
        val value = buffer.readU32(index * UInt.SIZE_BYTES)
        if (value > Int.MAX_VALUE.toUInt()) return errno(Errno.EINVAL)
        groups += value.toInt()
    }
    process.credentials.replaceSupplementaryGroups(groups)
    return 0L
}

private fun changeUserIds(
    process: Process,
    regs: PtraceRegisters,
    res: Boolean,
): Long {
    val real = LinuxIdArgument.decode(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EINVAL)
    val effective = LinuxIdArgument.decode(regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EINVAL)
    val saved = if (res) {
        LinuxIdArgument.decode(regs[PtraceRegisters.IDX_RDX]) ?: return errno(Errno.EINVAL)
    } else {
        LinuxIdArgument.Unchanged
    }
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val privileged = thread.capabilities.hasEffective(CapEnum.SETUID)
    val change = if (res) {
        process.credentials.setResUserIds(
            real.value,
            effective.value,
            saved.value,
            privileged,
        )
    } else {
        process.credentials.setReUserIds(real.value, effective.value, privileged)
    } ?: return errno(Errno.EPERM)
    thread.capabilities.applyUserIdChange(change)
    return 0L
}

private fun changeGroupIds(
    process: Process,
    regs: PtraceRegisters,
    res: Boolean,
): Long {
    val real = LinuxIdArgument.decode(regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EINVAL)
    val effective = LinuxIdArgument.decode(regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EINVAL)
    val saved = if (res) {
        LinuxIdArgument.decode(regs[PtraceRegisters.IDX_RDX]) ?: return errno(Errno.EINVAL)
    } else {
        LinuxIdArgument.Unchanged
    }
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val privileged = thread.capabilities.hasEffective(CapEnum.SETGID)
    val changed = if (res) {
        process.credentials.setResGroupIds(real.value, effective.value, saved.value, privileged)
    } else {
        process.credentials.setReGroupIds(real.value, effective.value, privileged)
    }
    return if (changed) 0L else errno(Errno.EPERM)
}

private val LinuxIdArgument.value: Int?
    get() = (this as? LinuxIdArgument.Value)?.id

internal fun prctl(regs: PtraceRegisters, process: Process): Long {
    val option = regs[PtraceRegisters.IDX_RDI]
    val argument = regs[PtraceRegisters.IDX_RSI]
    val third = regs[PtraceRegisters.IDX_RDX]
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val capabilities = thread.capabilities
    fun capability(value: ULong): Int? = value
        .takeIf { it <= Int.MAX_VALUE.toULong() }
        ?.toInt()
        ?.takeIf(CapabilityState::isValid)

    return when (option) {
        PR_GET_DUMPABLE -> if (process.dumpable) 1L else 0L

        PR_SET_DUMPABLE -> when {
            argument == 0uL -> {
                process.dumpable = false
                0L
            }
            argument == 1uL -> {
                process.dumpable = true
                0L
            }
            else -> errno(Errno.EINVAL)
        }

        PR_GET_KEEPCAPS -> if (capabilities.keepAcrossUserIdChange) 1L else 0L

        PR_SET_KEEPCAPS -> when {
            argument == 0uL -> {
                capabilities.keepAcrossUserIdChange = false
                0L
            }
            argument == 1uL -> {
                capabilities.keepAcrossUserIdChange = true
                0L
            }
            else -> errno(Errno.EINVAL)
        }

        PR_CAPBSET_READ -> {
            val requested = capability(argument) ?: return errno(Errno.EINVAL)
            if (capabilities.containsBounding(requested)) 1L else 0L
        }

        PR_CAPBSET_DROP -> {
            val requested = capability(argument) ?: return errno(Errno.EINVAL)
            if (!capabilities.hasEffective(CapEnum.SETPCAP)) return errno(Errno.EPERM)
            capabilities.dropBounding(requested)
            0L
        }

        PR_GET_NO_NEW_PRIVS -> if (capabilities.noNewPrivileges) 1L else 0L

        PR_SET_NO_NEW_PRIVS -> if (argument == 1uL) {
            capabilities.noNewPrivileges = true
            0L
        } else {
            errno(Errno.EINVAL)
        }

        PR_CAP_AMBIENT -> {
            when (argument) {
                PR_CAP_AMBIENT_CLEAR_ALL -> {
                    if (third != 0uL) return errno(Errno.EINVAL)
                    capabilities.clearAmbient()
                    0L
                }

                PR_CAP_AMBIENT_IS_SET,
                PR_CAP_AMBIENT_RAISE,
                PR_CAP_AMBIENT_LOWER -> {
                    val requested = capability(third) ?: return errno(Errno.EINVAL)
                    when (argument) {
                        PR_CAP_AMBIENT_IS_SET ->
                            if (capabilities.containsAmbient(requested)) 1L else 0L
                        PR_CAP_AMBIENT_RAISE ->
                            if (capabilities.raiseAmbient(requested)) 0L else errno(Errno.EPERM)
                        else -> {
                            capabilities.lowerAmbient(requested)
                            0L
                        }
                    }
                }

                else -> errno(Errno.EINVAL)
            }
        }

        PR_GET_NAME -> {
            val encoded = thread.name.encodeToByteArray()
            val output = ByteArray(PR_NAME_SIZE)
            encoded.copyInto(output, endIndex = minOf(encoded.size, PR_NAME_SIZE - 1))
            if (UserMemory(process.addressSpace, argument).copyToUser(output)) 0L
            else errno(Errno.EFAULT)
        }

        PR_SET_NAME -> {
            val raw = UserMemory(process.addressSpace, argument).copyFromUser(PR_NAME_SIZE)
                ?: return errno(Errno.EFAULT)
            val length = raw.indexOf(0).let { index ->
                if (index < 0) PR_NAME_SIZE - 1 else minOf(index, PR_NAME_SIZE - 1)
            }
            thread.name = raw.copyOf(length).decodeToString()
            0L
        }

        else -> errno(Errno.EINVAL)
    }
}

internal fun prlimit64(regs: PtraceRegisters, process: Process): Long {
    val pid = regs[PtraceRegisters.IDX_RDI].toLong()
    val target = if (pid == 0L) {
        process
    } else {
        if (pid !in 1..Int.MAX_VALUE.toLong()) return errno(Errno.ESRCH)
        ProcessManager.findProcess(pid.toInt()) ?: return errno(Errno.ESRCH)
    }
    val resource = ProcessResource.from(regs[PtraceRegisters.IDX_RSI].toInt())
        ?: return errno(Errno.EINVAL)
    val replacementAddress = regs[PtraceRegisters.IDX_RDX]
    val replacement = if (replacementAddress == 0uL) {
        null
    } else {
        val bytes = UserMemory(process.addressSpace, replacementAddress)
            .copyFromUser(LinuxRLimit.NATIVE_SIZE)
            ?: return errno(Errno.EFAULT)
        val input = LittleEndianBuffer(bytes)
        val soft = input.readU64(0)
        val hard = input.readU64(ULong.SIZE_BYTES)
        if (soft > hard) return errno(Errno.EINVAL)
        ResourceLimit(soft, hard)
    }

    val previous = replacement?.let { target.resourceLimits.replace(resource, it) }
        ?: target.resourceLimits.get(resource)
    val previousAddress = regs[PtraceRegisters.IDX_R10]
    if (previousAddress != 0uL &&
        !UserMemory(process.addressSpace, previousAddress).copyToUser(
            LinuxRLimit(previous).toNativeBytes(),
        )
    ) {
        return errno(Errno.EFAULT)
    }
    return 0L
}

internal fun schedGetAffinity(regs: PtraceRegisters, process: Process): Long {
    val pid = regs[PtraceRegisters.IDX_RDI].toInt()
    val cpusetsize = regs[PtraceRegisters.IDX_RSI]
    val maskAddress = regs[PtraceRegisters.IDX_RDX]
    if (maskAddress == 0uL) return errno(Errno.EFAULT)
    if (cpusetsize == 0uL || cpusetsize > Int.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }

    val thread: Thread? =
        if (pid == 0) ProcessManager.currentThread() else ProcessManager.snapshotProcesses()
            .firstNotNullOfOrNull { process ->
                if (process.id == pid) return@firstNotNullOfOrNull process.threads.first()
                for (thread in process.threads)
                    if (thread.id == pid) return@firstNotNullOfOrNull thread
                null
            }
    if (thread == null) return errno(Errno.ESRCH)
    val affinity = if (thread.affinityMask == 0UL) defaultAffinityMask() else thread.affinityMask
    val mask = UserMemory(process.addressSpace, maskAddress)
    val size = cpusetsize.toInt()
    if (mask.fill(0, size, 0) != size) return errno(Errno.EFAULT)

    val copySize = minOf(size, ULong.SIZE_BYTES)
    val affinityBytes = ByteArray(ULong.SIZE_BYTES) { index ->
        (affinity shr (index * Byte.SIZE_BITS)).toByte()
    }
    if (!mask.copyToUser(affinityBytes, size = copySize)) return errno(Errno.EFAULT)
    return copySize.toLong()
}

private fun copyIds(
    process: Process,
    realAddress: ULong,
    effectiveAddress: ULong,
    savedAddress: ULong,
    real: Int,
    effective: Int,
    saved: Int,
): Long {
    val bytes = ByteArray(Int.SIZE_BYTES)
    fun copy(address: ULong, value: Int): Boolean {
        LittleEndianBuffer(bytes).writeU32(0, value.toUInt())
        return UserMemory(process.addressSpace, address).copyToUser(bytes)
    }
    return if (copy(realAddress, real) && copy(effectiveAddress, effective) && copy(savedAddress, saved)) {
        0L
    } else {
        errno(Errno.EFAULT)
    }
}

private fun defaultAffinityMask(): ULong {
    val count = SMProcessor.cpu_count
    if (count == 0UL) return 1UL
    if (count >= 64UL) return ULong.MAX_VALUE
    return (1UL shl count.toInt()) - 1UL
}

internal fun getTid(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    return thread.id.toLong()
}

internal fun exit(regs: PtraceRegisters, process: Process): Long =
    ProcessExit.current(process, regs[PtraceRegisters.IDX_RDI].toInt(), group = false)

internal fun exitGroup(regs: PtraceRegisters, process: Process): Long =
    ProcessExit.current(process, regs[PtraceRegisters.IDX_RDI].toInt(), group = true)

internal fun setTidAddress(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    thread.clearChildTid = regs[PtraceRegisters.IDX_RDI]
    return thread.id.toLong()
}

internal fun setRobustList(regs: PtraceRegisters, process: Process): Long {
    if (regs[PtraceRegisters.IDX_RSI] != ROBUST_LIST_SIZE) return errno(Errno.EINVAL)
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    thread.robustListHead = regs[PtraceRegisters.IDX_RDI]
    return 0L
}

internal fun rseq(regs: PtraceRegisters, process: Process): Long = errno(Errno.ENOSYS)

internal fun execve(regs: PtraceRegisters, process: Process): Long {
    val path = Syscall.copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    val arguments = readStringVector(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val environment = readStringVector(process, regs[PtraceRegisters.IDX_RDX])
        ?: return errno(Errno.EFAULT)
    val executablePath = path.decodeToString()
    val image = when (val result = ElfLoader.loadProcess(
        path = executablePath,
        process = process,
        arguments = arguments,
        environment = environment,
    )) {
        is VfsResult.Ok -> result.value
        is VfsResult.Err -> return errno(result.error.errno)
    }
    process.installExecutable(image.executablePath, image.arguments)
    process.fdTable.closeOnExec(process.vfsOperationContext)
    process.signals.resetForExec()
    ProcessManager.currentThread()?.let { thread ->
        thread.signals.replaceStack(SignalStack.DISABLED)
        thread.capabilities.keepAcrossUserIdChange = false
    }

    regs[PtraceRegisters.IDX_RIP] = image.entryPoint
    regs[PtraceRegisters.IDX_RSP] = image.stackPointer
    regs[PtraceRegisters.IDX_RAX] = 0uL
    regs[PtraceRegisters.IDX_FS_BASE] = 0uL
    bridge.fast_handoff_reset_user_xstate()
    return 0L
}

internal fun wait4(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val requestedPid = regs[PtraceRegisters.IDX_RDI].toUInt().toInt().toLong()
    val options = regs[PtraceRegisters.IDX_RDX]
    if (options and WAIT_SUPPORTED.inv() != 0uL) return errno(Errno.EINVAL)

    while (true) {
        val observedSequence = process.childEvents.sequence()
        val children = ProcessManager.childrenOf(process.id).filter { child ->
            when {
                requestedPid > 0 -> child.id.toLong() == requestedPid
                requestedPid == 0L -> child.processGroupId == process.processGroupId
                requestedPid == -1L -> true
                else -> child.processGroupId.toLong() == -requestedPid
            }
        }
        if (children.isEmpty()) return errno(Errno.ECHILD)
        val event = process.childEvents.take(
            childIds = children.mapTo(mutableSetOf(), Process::id),
            stopped = options and WAIT_WUNTRACED != 0uL,
            continued = options and WAIT_WCONTINUED != 0uL,
        )
        if (event != null) {
            val status = regs[PtraceRegisters.IDX_RSI]
            if (status != 0uL &&
                !UserMemory(process.addressSpace, status).copyToUser(
                    byteArrayOf(
                        event.status.toByte(),
                        (event.status ushr 8).toByte(),
                        (event.status ushr 16).toByte(),
                        (event.status ushr 24).toByte(),
                    ),
                )
            ) {
                process.childEvents.restore(event)
                return errno(Errno.EFAULT)
            }
            if (event.kind == ChildEventKind.EXITED &&
                !ProcessManager.reapChild(process.id, event.child)
            ) {
                continue
            }
            return event.child.id.toLong()
        }
        if (options and WAIT_WNOHANG != 0uL) return 0L
        if (thread.hasPendingSignal()) return errno(Errno.EINTR)
        if (!process.childEvents.awaitChange(thread, observedSequence)) {
            if (thread.hasPendingSignal()) return errno(Errno.EINTR)
            Scheduler.yieldCurrent()
        }
    }
}

internal fun getCPU(regs: PtraceRegisters, process: Process): Long {
    val cpup = regs[PtraceRegisters.IDX_RDI]
    val nodep = regs[PtraceRegisters.IDX_RSI]

    if (cpup != 0UL) {
        val userCpup = UserMemory(process.addressSpace, cpup)
        val local = SMProcessor.currentLocal()
        if (!userCpup.copyToUser(local.cpuid.toByteArray())) return errno(Errno.EFAULT)
    }

    if (nodep != 0UL) {
        val userNodep = UserMemory(process.addressSpace, nodep)
        if (!userNodep.copyToUser(0L.toByteArray())) return errno(Errno.EFAULT)
    }

    return errno(Errno.EOK)
}

internal fun capGet(regs: PtraceRegisters, process: Process): Long {
    val headMem = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
    val headByte = headMem.copyFromUser(
        CapHeader.NATIVE_SIZE
    ) ?: return errno(Errno.EFAULT)
    val dataMem = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])

    val header = CapHeader(0u, 0).apply { updateFromNativeBytes(headByte) }
    val task = if (header.pid == 0) {
        ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    } else {
        ProcessManager.findThread(header.pid) ?: return errno(Errno.ESRCH)
    }
    val count = CapManager.capabilityCount(header.version)
    if (count == errno(Errno.EINVAL).toInt()) {
        header.version = LINUX_CAPABILITY_VERSION_3
        if (!headMem.copyToUser(header.toNativeBytes())) return errno(Errno.EFAULT)
        return count.toLong()
    }

    val array = Array(count) {
        Capabilities(0u, 0u, 0u)
    }

    val capabilities = task.capabilities
    array[0].effective = (capabilities.effective and UInt.MAX_VALUE.toULong()).toUInt()
    array[0].permitted = (capabilities.permitted and UInt.MAX_VALUE.toULong()).toUInt()
    array[0].inheritable = (capabilities.inheritable and UInt.MAX_VALUE.toULong()).toUInt()

    if (count > 1) {
        array[1].effective = (capabilities.effective shr 32).toUInt()
        array[1].permitted = (capabilities.permitted shr 32).toUInt()
        array[1].inheritable = (capabilities.inheritable shr 32).toUInt()
    }

    if (!dataMem.copyNativeStructArrayToUser(array)) return errno(Errno.EFAULT)

    return errno(Errno.EOK)
}

internal fun capSet(regs: PtraceRegisters, process: Process): Long {
    val headMem = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RDI])
    val headByte = headMem.copyFromUser(
        CapHeader.NATIVE_SIZE
    ) ?: return errno(Errno.EFAULT)
    val dataMem = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])

    val header = CapHeader(0u, 0).apply { updateFromNativeBytes(headByte) }
    val task = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    if (header.pid != 0 && header.pid != task.id) return errno(Errno.EPERM)
    val count = CapManager.capabilityCount(header.version)
    if (count == errno(Errno.EINVAL).toInt()) {
        header.version = LINUX_CAPABILITY_VERSION_3
        if (!headMem.copyToUser(header.toNativeBytes())) return errno(Errno.EFAULT)
        return count.toLong()
    }

    val byteCount = count * Capabilities.NATIVE_SIZE
    val input = dataMem.copyFromUser(byteCount)
        ?: return errno(Errno.EFAULT)

    val array = Array(count) {
        Capabilities(0u, 0u, 0u)
    }

    for (index in array.indices) {
        val offset = index * Capabilities.NATIVE_SIZE
        val elementBytes = input.copyOfRange(
            offset,
            offset + Capabilities.NATIVE_SIZE,
        )
        if (!array[index].updateFromNativeBytes(elementBytes)) return errno(Errno.EINVAL)
    }

    return CapManager.capabilityApply(array, task)
}

private fun readStringVector(process: Process, address: ULong): List<String>? {
    if (address == 0uL || address >= USER_VIRTUAL_ADDRESS_LIMIT) return null
    val values = mutableListOf<String>()
    repeat(256) { index ->
        val pointerBytes = UserMemory(
            process.addressSpace,
            address + index.toULong() * ULong.SIZE_BYTES.toULong(),
        )
            .copyFromUser(ULong.SIZE_BYTES) ?: return null
        val pointer = LittleEndianBuffer(pointerBytes).readU64(0)
        if (pointer == 0uL) return values
        val value = UserMemory(process.addressSpace, pointer).copyCStringFromUser(4096)
            ?: return null
        values += value.decodeToString()
    }
    return null
}
