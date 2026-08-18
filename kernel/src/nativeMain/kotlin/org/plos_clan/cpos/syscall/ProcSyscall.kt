@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.mem.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.module.elf.ElfLoader
import org.plos_clan.cpos.syscall.Syscall.copyWordToUser
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessGroupResult
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.tasks.ProcessState
import org.plos_clan.cpos.tasks.ResourceLimit
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.TaskState
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.PtraceRegisters

private const val ARCH_SET_GS = 0x1001uL
private const val ARCH_SET_FS = 0x1002uL
private const val ARCH_GET_FS = 0x1003uL
private const val ARCH_GET_GS = 0x1004uL
private const val MSR_KERNEL_GS_BASE = 0xC0000102U
private const val WAIT_WNOHANG = 1uL
private const val WAIT_SUPPORTED = 0x0buL // WNOHANG | WUNTRACED | WCONTINUED
private const val SIGNAL_COUNT = 64
private const val SIGNAL_SET_SIZE = ULong.SIZE_BYTES
private const val SIGACTION_SIZE = 32
private const val SIGKILL = 9
private const val SIGSTOP = 19
private const val ROBUST_LIST_SIZE = 24uL
private const val BLOCKABLE_SIGNAL_MASK = 0xffff_ffff_fffb_feffuL
private const val PR_SET_PDEATHSIG = 1UL
private const val PR_GET_PDEATHSIG = 2UL
private const val PR_GET_DUMPABLE = 3UL
private const val PR_SET_DUMPABLE = 4UL
private const val PR_GET_UNALIGN = 5UL
private const val PR_SET_UNALIGN = 6UL
private const val PR_GET_KEEPCAPS = 7UL
private const val PR_SET_KEEPCAPS = 8UL
private const val PR_GET_FPEMU = 9UL
private const val PR_SET_FPEMU = 10UL
private const val PR_GET_FPEXC = 11UL
private const val PR_SET_FPEXC = 12UL
private const val PR_GET_TIMING = 13UL
private const val PR_SET_TIMING = 14UL
private const val PR_SET_NAME = 15UL
private const val PR_GET_NAME = 16UL
private const val PR_GET_ENDIAN = 19UL
private const val PR_SET_ENDIAN = 20UL
private const val PR_GET_SECCOMP = 21UL
private const val PR_SET_SECCOMP = 22UL
private const val PR_CAPBSET_READ = 23UL
private const val PR_CAPBSET_DROP = 24UL
private const val PR_SET_NO_NEW_PRIVS = 38UL
private const val PR_GET_NO_NEW_PRIVS = 39UL
private const val PR_MCE_KILL = 33UL
private const val PR_MCE_KILL_GET = 34UL
private const val PR_SET_MM = 35UL
private const val PR_SET_PTRACER = 0x59616d61UL // 'Yama' magic value
private const val PR_SET_THP_DISABLE = 41UL
private const val PR_GET_THP_DISABLE = 42UL
private const val PR_TASK_PERF_EVENTS_DISABLE = 31UL
private const val PR_TASK_PERF_EVENTS_ENABLE = 32UL
private const val PR_GET_SPECULATION_CTRL = 52UL
private const val PR_SET_SPECULATION_CTRL = 53UL

private enum class SignalMaskOperation(val value: ULong) {
    BLOCK(0uL),
    UNBLOCK(1uL),
    SET(2uL),
    ;

    companion object {
        fun from(value: ULong): SignalMaskOperation? = when (value) {
            BLOCK.value -> BLOCK
            UNBLOCK.value -> UNBLOCK
            SET.value -> SET
            else -> null
        }
    }
}

private class IdTriplet(
    private val real: Int,
    private val effective: Int,
    private val saved: Int,
) : NativeStruct {
    override fun toNativeBytes(): ByteArray = ByteArray(NATIVE_SIZE).also { buffer ->
        LittleEndianBuffer(buffer).apply {
            writeU32(0, real.toUInt())
            writeU32(Int.SIZE_BYTES, effective.toUInt())
            writeU32(Int.SIZE_BYTES * 2, saved.toUInt())
        }
    }

    companion object {
        const val NATIVE_SIZE = Int.SIZE_BYTES * 3
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

internal fun getUid(regs: PtraceRegisters, process: Process): Long = process.ruid.toLong()

internal fun getGid(regs: PtraceRegisters, process: Process): Long = process.rgid.toLong()

internal fun getEuid(regs: PtraceRegisters, process: Process): Long = process.euid.toLong()

internal fun getEgid(regs: PtraceRegisters, process: Process): Long = process.egid.toLong()

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
    val requested = regs[PtraceRegisters.IDX_RDI]
        .takeIf { it <= Int.MAX_VALUE.toULong() }
        ?.toInt()
    return process.setFilesystemUid(requested).toLong()
}

internal fun setFsGid(regs: PtraceRegisters, process: Process): Long {
    val requested = regs[PtraceRegisters.IDX_RDI]
        .takeIf { it <= Int.MAX_VALUE.toULong() }
        ?.toInt()
    return process.setFilesystemGid(requested).toLong()
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
    IdTriplet(process.ruid, process.euid, process.suid),
)

internal fun getResGid(regs: PtraceRegisters, process: Process): Long = copyIds(
    process,
    regs[PtraceRegisters.IDX_RDI],
    IdTriplet(process.rgid, process.egid, process.sgid),
)

internal fun prctl(regs: PtraceRegisters, process: Process): Long {
    val option = regs[PtraceRegisters.IDX_RDI]
    val thread = ProcessManager.currentThread() ?: run {
        println("ERROR: sys_prctl cannot get current thread")
        return errno(Errno.EINVAL)
    }
    return when(option) {
        PR_GET_NAME -> {
            val userName = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
            val raw = thread.name.encodeToByteArray()
            if(!userName.copyToUser(raw)) return errno(Errno.EFAULT)
            errno(Errno.EOK)
        }
        PR_SET_NAME -> {
            val userName = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
            val raw = userName.copyCStringFromUser(4096) ?: return errno(Errno.EFAULT)
            val str = raw.decodeToString()
            thread.name = str
            errno(Errno.EOK)
        }
        else -> errno(Errno.ENOSYS)
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

internal fun rtSigaction(regs: PtraceRegisters, process: Process): Long {
    val signal = regs[PtraceRegisters.IDX_RDI]
    if (signal !in 1uL..SIGNAL_COUNT.toULong() ||
        regs[PtraceRegisters.IDX_R10] != SIGNAL_SET_SIZE.toULong()
    ) {
        return errno(Errno.EINVAL)
    }

    val actionAddress = regs[PtraceRegisters.IDX_RSI]
    if (actionAddress != 0uL && (signal == SIGKILL.toULong() || signal == SIGSTOP.toULong())) {
        return errno(Errno.EINVAL)
    }
    val action = if (actionAddress == 0uL) {
        null
    } else {
        UserMemory(process.addressSpace, actionAddress).copyFromUser(SIGACTION_SIZE)
            ?: return errno(Errno.EFAULT)
    }

    val index = signal.toInt() - 1
    val previous = process.signalActions[index] ?: ByteArray(SIGACTION_SIZE)
    if (action != null) process.signalActions[index] = action
    val previousAddress = regs[PtraceRegisters.IDX_RDX]
    if (previousAddress != 0uL &&
        !UserMemory(process.addressSpace, previousAddress).copyToUser(previous)
    ) {
        return errno(Errno.EFAULT)
    }
    return 0L
}

internal fun rtSigprocmask(regs: PtraceRegisters, process: Process): Long {
    if (regs[PtraceRegisters.IDX_R10] != SIGNAL_SET_SIZE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    val setAddress = regs[PtraceRegisters.IDX_RSI]
    val previous = thread.signalMask
    if (setAddress != 0uL) {
        val operation = SignalMaskOperation.from(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EINVAL)
        val bytes = UserMemory(process.addressSpace, setAddress).copyFromUser(SIGNAL_SET_SIZE)
            ?: return errno(Errno.EFAULT)
        val mask = LittleEndianBuffer(bytes).readU64(0) and BLOCKABLE_SIGNAL_MASK
        thread.signalMask = when (operation) {
            SignalMaskOperation.BLOCK -> previous or mask
            SignalMaskOperation.UNBLOCK -> previous and mask.inv()
            SignalMaskOperation.SET -> mask
        }
    }
    val previousAddress = regs[PtraceRegisters.IDX_RDX]
    if (previousAddress != 0uL && copyWordToUser(process, previousAddress, previous) != 0L) {
        return errno(Errno.EFAULT)
    }
    return 0L
}

private fun copyIds(process: Process, address: ULong, ids: IdTriplet): Long =
    if (UserMemory(process.addressSpace, address).copyToUser(ids.toNativeBytes())) {
        0L
    } else {
        errno(Errno.EFAULT)
    }

internal fun getTid(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    return thread.id.toLong()
}

internal fun exit(regs: PtraceRegisters, process: Process): Long = terminate(
    process = process,
    group = false,
    status = regs[PtraceRegisters.IDX_RDI].toInt(),
)

internal fun exitGroup(regs: PtraceRegisters, process: Process): Long = terminate(
    process = process,
    group = true,
    status = regs[PtraceRegisters.IDX_RDI].toInt(),
)

private fun terminate(process: Process, group: Boolean, status: Int): Nothing {
    val current = ProcessManager.currentThread() ?: error("exit without a current thread")
    if (group) {
        process.threads.forEach { it.state = TaskState.ZOMBIE }
    } else {
        current.state = TaskState.ZOMBIE
    }

    val clearChildTid = current.clearChildTid
    current.clearChildTid = 0uL
    if (clearChildTid != 0uL &&
        UserMemory(process.addressSpace, clearChildTid).copyToUser(ByteArray(Int.SIZE_BYTES))
    ) {
        Futex.wakePrivate(process, clearChildTid)
    }
    if (group || process.threads.none { it.state != TaskState.ZOMBIE }) {
        ProcessManager.markExited(process, status)
    }
    Scheduler.yieldCurrent()
    while (true) {
        bridge.wait_for_interrupt()
    }
}

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
    process.installExecutable(executablePath, arguments.ifEmpty { listOf(executablePath) })
    process.fdTable.closeOnExec()
    regs[PtraceRegisters.IDX_RIP] = image.entryPoint
    regs[PtraceRegisters.IDX_RSP] = image.stackPointer
    regs[PtraceRegisters.IDX_RAX] = 0uL
    regs[PtraceRegisters.IDX_FS_BASE] = 0uL
    bridge.fast_handoff_reset_user_xstate()
    return 0L
}

internal fun wait4(regs: PtraceRegisters, process: Process): Long {
    val requestedPid = regs[PtraceRegisters.IDX_RDI].toUInt().toInt().toLong()
    val options = regs[PtraceRegisters.IDX_RDX]
    if (options and WAIT_SUPPORTED.inv() != 0uL) return errno(Errno.EINVAL)

    while (true) {
        val children = ProcessManager.childrenOf(process.id).filter { child ->
            when {
                requestedPid > 0 -> child.id.toLong() == requestedPid
                requestedPid == 0L -> child.processGroupId == process.processGroupId
                requestedPid == -1L -> true
                else -> child.processGroupId.toLong() == -requestedPid
            }
        }
        if (children.isEmpty()) return errno(Errno.ECHILD)
        val exited = children.firstOrNull { it.state == ProcessState.ZOMBIE }
        if (exited != null) {
            val status = regs[PtraceRegisters.IDX_RSI]
            if (status != 0uL &&
                !UserMemory(process.addressSpace, status).copyToUser(
                    byteArrayOf(
                        0,
                        (exited.exitCode and 0xff).toByte(),
                        0,
                        0,
                    ),
                )
            ) return errno(Errno.EFAULT)
            if (!ProcessManager.reapChild(process.id, exited)) continue
            return exited.id.toLong()
        }
        if (options and WAIT_WNOHANG != 0uL) return 0L
        Scheduler.yieldCurrent()
        bridge.wait_for_interrupt()
    }
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
