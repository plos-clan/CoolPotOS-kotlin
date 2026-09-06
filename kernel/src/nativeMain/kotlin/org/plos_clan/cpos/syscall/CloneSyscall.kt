@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.fs.cgroupfs.Cgroupfs
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.MemoryCloneMode
import org.plos_clan.cpos.tasks.PidHandle
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalStack
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters

private const val EXIT_SIGNAL_MASK = 0xffuL
private const val SIGNAL_COUNT = 64uL

private enum class Flag(val mask: ULong) {
    VM(0x0000_0100uL),
    FS(0x0000_0200uL),
    FILES(0x0000_0400uL),
    SIGHAND(0x0000_0800uL),
    PIDFD(0x0000_1000uL),
    VFORK(0x0000_4000uL),
    THREAD(0x0001_0000uL),
    SYSVSEM(0x0004_0000uL),
    SETTLS(0x0008_0000uL),
    PARENT_SETTID(0x0010_0000uL),
    CHILD_CLEARTID(0x0020_0000uL),
    CHILD_SETTID(0x0100_0000uL),
    CLEAR_SIGHAND(0x1_0000_0000uL),
    INTO_CGROUP(0x2_0000_0000uL),
}

private val supportedFlags = Flag.entries.fold(0uL) { mask, flag -> mask or flag.mask }
private val processResourceFlags = Flag.FS.mask or Flag.FILES.mask or Flag.SIGHAND.mask
private val threadResourceFlags = processResourceFlags or Flag.VM.mask

internal data class CloneRequest(
    val flags: ULong,
    val exitSignal: ULong,
    val stackPointer: ULong,
    val parentTid: ULong,
    val childTid: ULong,
    val tls: ULong,
    val pidfd: ULong,
    val cgroup: ULong? = null,
) {
    fun execute(registers: PtraceRegisters, parent: Process): Long {
        val validationError = validate()
        if (validationError != null) return errno(validationError)

        val current = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        val childStack = stackPointer.takeUnless { it == 0uL }
            ?: registers[PtraceRegisters.IDX_RSP]
        if (childStack == 0uL || childStack >= USER_VIRTUAL_ADDRESS_LIMIT) {
            return errno(Errno.EFAULT)
        }

        val fsBase = if (has(Flag.SETTLS)) tls else registers[PtraceRegisters.IDX_FS_BASE]
        if (fsBase >= USER_VIRTUAL_ADDRESS_LIMIT) return errno(Errno.EINVAL)

        val parentTidMemory = if (has(Flag.PARENT_SETTID)) {
            UserMemory(parent.addressSpace, parentTid)
        } else null
        val pidfdMemory = if (has(Flag.PIDFD)) {
            UserMemory(parent.addressSpace, pidfd)
        } else null
        val threadClone = has(Flag.THREAD)
        val placement = if (has(Flag.INTO_CGROUP)) {
            val file = parent.fdTable.acquire(checkNotNull(cgroup)) ?: return errno(Errno.EBADF)
            val result = try {
                Cgroupfs.placement(parent.vfsOperationContext, file, threadClone)
            } finally {
                file.release()
            }
            when (result) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return errno(result.error.errno)
            }
        } else null
        val target = if (has(Flag.PIDFD)) {
            PidHandle(if (threadClone) PidHandle.Scope.THREAD else PidHandle.Scope.PROCESS)
        } else null
        val descriptor = if (target != null) {
            when (val result = PidFdSyscalls.prepare(parent, target)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return errno(result.error.errno)
            }
        } else null

        return descriptor.use {
            val child = if (threadClone) parent else ProcessManager.createUserProcess(
                name = parent.name,
                parent = parent,
                memory = if (has(Flag.VM)) MemoryCloneMode.SHARE else MemoryCloneMode.COPY,
                vforkParent = current.takeIf { has(Flag.VFORK) },
                terminationSignal = Signal.from(exitSignal),
            ).also { process ->
                if (has(Flag.CLEAR_SIGHAND)) process.signals.resetAll()
            }
            val childThread = try {
                val childTidMemory = if (has(Flag.CHILD_SETTID)) {
                    UserMemory(child.addressSpace, childTid)
                } else null
                val snapshot = ULongArray(PtraceRegisters.REGISTER_COUNT).also(registers::copyInto)
                val inheritStack = !threadClone && (!has(Flag.VM) || has(Flag.VFORK))
                val threadSignals = if (threadClone) {
                    current.signals.fork(inheritStack = false)
                } else {
                    child.signals.newThread(
                        mask = current.signals.mask,
                        stack = current.signals.stack.takeIf { inheritStack } ?: SignalStack.DISABLED,
                    )
                }
                when (val result = ProcessManager.createUserThread(
                    process = child,
                    entryPoint = registers[PtraceRegisters.IDX_RIP],
                    stackPointer = childStack,
                    parentThread = if (threadClone) current.parentThread else current,
                    fsBase = fsBase,
                    registers = snapshot,
                    signals = threadSignals,
                    placement = placement,
                    prepare = { id ->
                        // Resolve pages after fork so parent writes honor copy-on-write.
                        val bytes = ByteArray(Int.SIZE_BYTES)
                        val output = LittleEndianBuffer(bytes)
                        if (descriptor != null) {
                            output.writeU32(0, descriptor.fd.toUInt())
                            if (pidfdMemory?.copyToUser(bytes) != true) {
                                return@createUserThread VfsResult.Err(VfsError.FAULT)
                            }
                        }
                        output.writeU32(0, id.toUInt())
                        if (parentTidMemory?.copyToUser(bytes) == false ||
                            childTidMemory?.copyToUser(bytes) == false
                        ) VfsResult.Err(VfsError.FAULT) else VfsResult.Ok(Unit)
                    },
                )) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> return errno(result.error.errno)
                }
            } finally {
                if (!threadClone) ProcessManager.discardUserProcess(child)
            }
            childThread.capabilities.inherit(current.capabilities)
            if (has(Flag.CHILD_CLEARTID)) childThread.clearChildTid = childTid
            target?.attach(childThread)
            descriptor?.install()

            Scheduler.enqueueThread(childThread)
            if (has(Flag.VFORK)) child.awaitVfork()
            childThread.id.toLong()
        }
    }

    fun validate(): Int? = when {
        flags and supportedFlags.inv() != 0uL || exitSignal > SIGNAL_COUNT -> Errno.EINVAL
        has(Flag.PIDFD) && has(Flag.PARENT_SETTID) && pidfd == parentTid -> Errno.EINVAL
        has(Flag.INTO_CGROUP) && cgroup == null -> Errno.EINVAL
        has(Flag.CLEAR_SIGHAND) && has(Flag.SIGHAND) -> Errno.EINVAL
        has(Flag.SIGHAND) && !has(Flag.VM) -> Errno.EINVAL
        has(Flag.THREAD) && (!has(Flag.SIGHAND) || exitSignal != 0uL) -> Errno.EINVAL
        has(Flag.THREAD) && has(Flag.VFORK) -> Errno.EOPNOTSUPP
        has(Flag.THREAD) && flags and threadResourceFlags != threadResourceFlags ->
            Errno.EOPNOTSUPP

        !has(Flag.THREAD) && flags and processResourceFlags != 0uL -> Errno.EOPNOTSUPP
        else -> null
    }

    private fun has(flag: Flag): Boolean = flags and flag.mask != 0uL

    companion object {
        const val MIN_SIZE = 64
        const val NATIVE_SIZE = 88

        fun legacy(registers: PtraceRegisters): CloneRequest {
            val encodedFlags = registers[PtraceRegisters.IDX_RDI]
            return CloneRequest(
                flags = encodedFlags and EXIT_SIGNAL_MASK.inv(),
                exitSignal = encodedFlags and EXIT_SIGNAL_MASK,
                stackPointer = registers[PtraceRegisters.IDX_RSI],
                parentTid = registers[PtraceRegisters.IDX_RDX],
                childTid = registers[PtraceRegisters.IDX_R10],
                tls = registers[PtraceRegisters.IDX_R8],
                pidfd = registers[PtraceRegisters.IDX_RDX],
            )
        }

        fun decode(bytes: ByteArray): CloneRequest? {
            if (bytes.size < MIN_SIZE) return null
            val input = LittleEndianBuffer(bytes)
            val flags = input.readU64(0)
            val stack = input.readU64(40)
            val stackSize = input.readU64(48)
            val setTidBytes = minOf(ULong.SIZE_BYTES, maxOf(0, bytes.size - 72))
            if (flags and EXIT_SIGNAL_MASK != 0uL ||
                setTidBytes != 0 && input.readUnsigned(72, setTidBytes) != 0uL ||
                (stack == 0uL) != (stackSize == 0uL) ||
                stackSize > ULong.MAX_VALUE - stack
            ) return null

            return CloneRequest(
                flags = flags,
                pidfd = input.readU64(8),
                childTid = input.readU64(16),
                parentTid = input.readU64(24),
                exitSignal = input.readU64(32),
                stackPointer = stack + stackSize,
                tls = input.readU64(56),
                cgroup = if (bytes.size >= NATIVE_SIZE) input.readU64(80) else null,
            )
        }
    }
}

fun clone(registers: PtraceRegisters, process: Process): Long =
    CloneRequest.legacy(registers).execute(registers, process)

fun clone3(registers: PtraceRegisters, process: Process): Long {
    val size = registers[PtraceRegisters.IDX_RSI]
    if (size < CloneRequest.MIN_SIZE.toULong()) return errno(Errno.EINVAL)
    if (size > PAGE_SIZE_BYTES) return errno(Errno.E2BIG)

    val bytes = UserMemory(process.addressSpace, registers[PtraceRegisters.IDX_RDI])
        .copyFromUser(size.toInt()) ?: return errno(Errno.EFAULT)
    for (index in CloneRequest.NATIVE_SIZE until bytes.size) {
        if (bytes[index] != 0.toByte()) return errno(Errno.E2BIG)
    }
    val request = CloneRequest.decode(bytes) ?: return errno(Errno.EINVAL)
    return request.execute(registers, process)
}
