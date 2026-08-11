@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.mem.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.module.ElfLoader
import org.plos_clan.cpos.syscall.Syscall.copyWordToUser
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.TaskState
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.PtraceRegisters

private const val ARCH_SET_GS = 0x1001uL
private const val ARCH_SET_FS = 0x1002uL
private const val ARCH_GET_FS = 0x1003uL
private const val ARCH_GET_GS = 0x1004uL
private const val MSR_KERNEL_GS_BASE = 0xC0000102U
private const val SIGCHLD = 17uL
private const val CLONE_SETTLS = 0x0008_0000uL
private const val CLONE_PARENT_SETTID = 0x0010_0000uL
private const val CLONE_CHILD_CLEARTID = 0x0020_0000uL
private const val CLONE_CHILD_SETTID = 0x0100_0000uL
private const val CLONE_SUPPORTED = 0x0138_0000uL
private const val WAIT_WNOHANG = 1uL
private const val WAIT_SUPPORTED = 0x0buL // WNOHANG | WUNTRACED | WCONTINUED

private class IdTriplet(
    private val real: Int,
    private val effective: Int,
    private val saved: Int,
) : NativeStruct() {
    override fun toNativeBytes(): ByteArray = ByteArray(NATIVE_SIZE).also { buffer ->
        putU32LE(buffer, 0, real)
        putU32LE(buffer, Int.SIZE_BYTES, effective)
        putU32LE(buffer, Int.SIZE_BYTES * 2, saved)
    }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean = false

    companion object {
        const val NATIVE_SIZE = Int.SIZE_BYTES * 3
    }
}

fun sysArchPrctl(regs: PtraceRegisters, process: Process): Long {
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

fun sysGetPID(regs: PtraceRegisters, process: Process): Long {
    return process.id.toLong()
}

fun sysGetUID(regs: PtraceRegisters, process: Process): Long {
    return process.ruid.toLong()
}

fun sysGetGID(regs: PtraceRegisters, process: Process): Long {
    return process.rgid.toLong()
}

fun sysGetEUID(regs: PtraceRegisters, process: Process): Long = process.euid.toLong()

fun sysGetEGID(regs: PtraceRegisters, process: Process): Long = process.egid.toLong()

fun sysGetPPID(regs: PtraceRegisters, process: Process): Long = process.parentId.toLong()

fun sysGetPGRP(regs: PtraceRegisters, process: Process): Long = process.processGroupId.toLong()

fun sysSetPGID(regs: PtraceRegisters, process: Process): Long {
    val pid = regs[PtraceRegisters.IDX_RDI]
    val requestedGroup = regs[PtraceRegisters.IDX_RSI]
    if (pid > Int.MAX_VALUE.toULong() || requestedGroup > Int.MAX_VALUE.toULong()) {
        return errno(Errno.EINVAL)
    }
    val target = if (pid == 0uL) {
        process
    } else {
        ProcessManager.findProcess(pid.toInt()) ?: return errno(Errno.ESRCH)
    }
    val group = if (requestedGroup == 0uL) target.id else requestedGroup.toInt()
    if (group <= 0) return errno(Errno.EINVAL)
    target.processGroupId = group
    return 0L
}

fun sysSetFSUID(regs: PtraceRegisters, process: Process): Long {
    val requested = regs[PtraceRegisters.IDX_RDI]
        .takeIf { it <= Int.MAX_VALUE.toULong() }
        ?.toInt()
    return process.setFilesystemUid(requested).toLong()
}

fun sysSetFSGID(regs: PtraceRegisters, process: Process): Long {
    val requested = regs[PtraceRegisters.IDX_RDI]
        .takeIf { it <= Int.MAX_VALUE.toULong() }
        ?.toInt()
    return process.setFilesystemGid(requested).toLong()
}

fun sysGetSID(regs: PtraceRegisters, process: Process): Long {
    val pid = regs[PtraceRegisters.IDX_RDI].toInt()
    val process =
        if (pid == 0) process else ProcessManager.findProcess(pid) ?: return errno(Errno.ESRCH)
    return process.sessionId.toLong()
}

fun sysClone(regs: PtraceRegisters, process: Process): Long {
    val flags = regs[PtraceRegisters.IDX_RDI]
    val unsupported = flags and 0xffff_ffff_ffff_ff00uL and CLONE_SUPPORTED.inv()
    if (flags and 0xffuL != SIGCHLD || unsupported != 0uL) {
        return errno(Errno.ENOSYS)
    }
    val parentTid = regs[PtraceRegisters.IDX_RDX]
    val childTid = regs[PtraceRegisters.IDX_R10]
    if (flags and CLONE_PARENT_SETTID != 0uL &&
        !UserMemory(process.addressSpace, parentTid).isWritable(Int.SIZE_BYTES)
    ) return errno(Errno.EFAULT)

    val stack = regs[PtraceRegisters.IDX_RSI].takeUnless { it == 0uL } ?: regs[PtraceRegisters.IDX_RSP]
    if (stack == 0uL || stack >= USER_VIRTUAL_ADDRESS_LIMIT) return errno(Errno.EFAULT)

    val child = ProcessManager.createUserProcess(
        name = process.name,
        parent = process,
    )
    val registers = ULongArray(PtraceRegisters.REGISTER_COUNT).also(regs::copyInto)
    val fsBase = if (flags and CLONE_SETTLS != 0uL) {
        regs[PtraceRegisters.IDX_R8]
    } else {
        regs[PtraceRegisters.IDX_FS_BASE]
    }
    val childThread = ProcessManager.createUserThread(
        process = child,
        entryPoint = regs[PtraceRegisters.IDX_RIP],
        stackPointer = stack,
        fsBase = fsBase,
        registers = registers,
    ) ?: return errno(Errno.ENOMEM)
    if (flags and CLONE_PARENT_SETTID != 0uL &&
        Syscall.copyWordToUser(process, parentTid, child.id.toULong()) != 0L
    ) return errno(Errno.EFAULT)
    if (flags and CLONE_CHILD_SETTID != 0uL &&
        Syscall.copyWordToUser(child, childTid, child.id.toULong()) != 0L
    ) return errno(Errno.EFAULT)
    if (flags and CLONE_CHILD_CLEARTID != 0uL) childThread.clearChildTid = childTid
    return child.id.toLong()
}

fun sysGetResUID(regs: PtraceRegisters, process: Process): Long = copyIds(
    process,
    regs[PtraceRegisters.IDX_RDI],
    IdTriplet(process.ruid, process.euid, process.suid),
)

fun sysGetResGID(regs: PtraceRegisters, process: Process): Long = copyIds(
    process,
    regs[PtraceRegisters.IDX_RDI],
    IdTriplet(process.rgid, process.egid, process.sgid),
)

private fun copyIds(process: Process, address: ULong, ids: IdTriplet): Long =
    if (UserMemory(process.addressSpace, address).copyToUser(ids.toNativeBytes())) {
        0L
    } else {
        errno(Errno.EFAULT)
    }

fun sysGetTID(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    return thread.id.toLong()
}

fun sysExit(regs: PtraceRegisters, process: Process): Long = terminate(
    process = process,
    group = false,
    status = regs[PtraceRegisters.IDX_RDI].toInt(),
)

fun sysExitGroup(regs: PtraceRegisters, process: Process): Long = terminate(
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
    ProcessManager.markExited(process, status)
    current.clearChildTid = 0uL
    Scheduler.yieldCurrent()
    while (true) {
        bridge.wait_for_interrupt()
    }
}

fun sysSetTidAddress(regs: PtraceRegisters, process: Process): Long {
    val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
    thread.clearChildTid = regs[PtraceRegisters.IDX_RDI]
    return thread.id.toLong()
}

fun sysExecve(regs: PtraceRegisters, process: Process): Long {
    val path = Syscall.copyPath(process, regs[PtraceRegisters.IDX_RDI])
        ?: return errno(Errno.EFAULT)
    val arguments = readStringVector(process, regs[PtraceRegisters.IDX_RSI])
        ?: return errno(Errno.EFAULT)
    val environment = readStringVector(process, regs[PtraceRegisters.IDX_RDX])
        ?: return errno(Errno.EFAULT)
    val executablePath = path.decodeToString()
    val image = ElfLoader.loadProcess(
        path = executablePath,
        process = process,
        arguments = arguments,
        environment = environment,
    ) ?: return errno(Errno.ENOEXEC)
    process.installExecutable(executablePath, arguments.ifEmpty { listOf(executablePath) })
    process.fdTable.closeOnExec()
    regs[PtraceRegisters.IDX_RIP] = image.entryPoint
    regs[PtraceRegisters.IDX_RSP] = image.stackPointer
    regs[PtraceRegisters.IDX_RAX] = 0uL
    regs[PtraceRegisters.IDX_FS_BASE] = 0uL
    bridge.fast_handoff_reset_user_xstate()
    return 0L
}

fun sysWait4(regs: PtraceRegisters, process: Process): Long {
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
        val exited = children.firstOrNull { it.state == TaskState.ZOMBIE }
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
        val pointer = pointerBytes.readU64LE(0)
        if (pointer == 0uL) return values
        val value = UserMemory(process.addressSpace, pointer).copyCStringFromUser(4096)
            ?: return null
        values += value.decodeToString()
    }
    return null
}

private fun ByteArray.readU64LE(offset: Int): ULong {
    var value = 0uL
    repeat(ULong.SIZE_BYTES) { index ->
        value = value or (this[offset + index].toUByte().toULong() shl (index * Byte.SIZE_BITS))
    }
    return value
}
