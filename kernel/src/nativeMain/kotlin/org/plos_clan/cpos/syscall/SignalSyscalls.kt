@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.SignalFd
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.tasks.DefaultSignalAction
import org.plos_clan.cpos.tasks.PidHandle
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalAction
import org.plos_clan.cpos.tasks.SignalActionFlag
import org.plos_clan.cpos.tasks.SignalGatewayContext
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalMaskOperation
import org.plos_clan.cpos.tasks.SignalPayload
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.tasks.SignalSendResult
import org.plos_clan.cpos.tasks.SignalStack
import org.plos_clan.cpos.tasks.Thread
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PtraceRegisters
import org.plos_clan.cpos.utils.alignDown

private const val SIGNAL_SET_SIZE = ULong.SIZE_BYTES
private const val SIGNAL_INFO_SIZE = 128
private const val SIGNAL_ACTION_SIZE = 32
private const val SIGNAL_STACK_SIZE = 24
private const val MINIMUM_SIGNAL_STACK_SIZE = 2_048uL

private const val STACK_ON_STACK = 1u
private const val STACK_DISABLED = 2u
private const val STACK_AUTO_DISABLE = 0x8000_0000u
private const val PIDFD_SELF_THREAD = -10000
private const val PIDFD_SELF_THREAD_GROUP = -10001

internal data class QueuedSignalInfo(
    val number: Int,
    val error: Int,
    val code: Int,
    val payload: ByteArray,
) {
    val requiresSelf: Boolean
        get() = code >= 0 || code == SignalInfo.THREAD

    fun signalInfo(signal: Signal) = SignalInfo(
        signal = signal,
        code = code,
        error = error,
        payload = SignalPayload.Raw(payload),
    )
}

internal enum class PidFdSignalScope {
    DEFAULT,
    THREAD,
    THREAD_GROUP,
    PROCESS_GROUP,
    ;

    fun resolve(scope: PidHandle.Scope): PidFdSignalScope = when {
        this != DEFAULT -> this
        scope == PidHandle.Scope.THREAD -> THREAD
        else -> THREAD_GROUP
    }

    companion object {
        fun from(flags: ULong): PidFdSignalScope? = when (flags.toUInt()) {
            0u -> DEFAULT
            1u -> THREAD
            2u -> THREAD_GROUP
            4u -> PROCESS_GROUP
            else -> null
        }
    }
}

internal object SignalAbi {
    const val INFO_SIZE = SIGNAL_INFO_SIZE

    private const val ACTION_HANDLER_OFFSET = 0
    private const val ACTION_FLAGS_OFFSET = 8
    private const val ACTION_RESTORER_OFFSET = 16
    private const val ACTION_MASK_OFFSET = 24

    private const val INFO_SIGNAL_OFFSET = 0
    private const val INFO_ERROR_OFFSET = 4
    private const val INFO_CODE_OFFSET = 8
    private const val INFO_PAYLOAD_OFFSET = 16
    private const val INFO_UID_OFFSET = 20
    private const val INFO_VALUE_OFFSET = 24
    private const val INFO_CHILD_USER_TIME_OFFSET = 32
    private const val INFO_CHILD_SYSTEM_TIME_OFFSET = 40

    private const val STACK_POINTER_OFFSET = 0
    private const val STACK_FLAGS_OFFSET = 8
    private const val STACK_SIZE_OFFSET = 16

    fun readAction(bytes: ByteArray): SignalAction {
        val input = LittleEndianBuffer(bytes)
        val flags = input.readU64(ACTION_FLAGS_OFFSET) and supportedActionFlags
        return SignalAction(
            handler = input.readU64(ACTION_HANDLER_OFFSET),
            flags = flags,
            restorer = input.readU64(ACTION_RESTORER_OFFSET),
            mask = input.readU64(ACTION_MASK_OFFSET) and Signal.BLOCKABLE_MASK,
        )
    }

    fun actionBytes(action: SignalAction): ByteArray = ByteArray(SIGNAL_ACTION_SIZE).also { bytes ->
        LittleEndianBuffer(bytes).apply {
            writeU64(ACTION_HANDLER_OFFSET, action.handler)
            writeU64(ACTION_FLAGS_OFFSET, action.flags)
            writeU64(ACTION_RESTORER_OFFSET, action.restorer)
            writeU64(ACTION_MASK_OFFSET, action.mask)
        }
    }

    fun readQueuedInfo(bytes: ByteArray): QueuedSignalInfo {
        val input = LittleEndianBuffer(bytes)
        return QueuedSignalInfo(
            number = input.readU32(INFO_SIGNAL_OFFSET).toInt(),
            error = input.readU32(INFO_ERROR_OFFSET).toInt(),
            code = input.readU32(INFO_CODE_OFFSET).toInt(),
            payload = bytes.copyOfRange(INFO_PAYLOAD_OFFSET, SIGNAL_INFO_SIZE),
        )
    }

    fun infoBytes(info: SignalInfo): ByteArray =
        ByteArray(SIGNAL_INFO_SIZE).also { writeInfo(it, 0, info) }

    fun writeInfo(destination: ByteArray, offset: Int, info: SignalInfo) {
        LittleEndianBuffer(destination).apply {
            writeU32(offset + INFO_SIGNAL_OFFSET, info.signal.number.toUInt())
            writeU32(offset + INFO_ERROR_OFFSET, info.error.toUInt())
            writeU32(offset + INFO_CODE_OFFSET, info.code.toUInt())
            when (val payload = info.payload) {
                is SignalPayload.Sender -> {
                    writeU32(offset + INFO_PAYLOAD_OFFSET, payload.pid.toUInt())
                    writeU32(offset + INFO_UID_OFFSET, payload.uid.toUInt())
                    writeU64(offset + INFO_VALUE_OFFSET, payload.value)
                }

                is SignalPayload.Child -> {
                    writeU32(offset + INFO_PAYLOAD_OFFSET, payload.pid.toUInt())
                    writeU32(offset + INFO_UID_OFFSET, payload.uid.toUInt())
                    writeU32(offset + INFO_VALUE_OFFSET, payload.status.toUInt())
                    writeU64(offset + INFO_CHILD_USER_TIME_OFFSET, payload.userTime.toULong())
                    writeU64(offset + INFO_CHILD_SYSTEM_TIME_OFFSET, payload.systemTime.toULong())
                }

                is SignalPayload.Fault ->
                    writeU64(offset + INFO_PAYLOAD_OFFSET, payload.address)
                is SignalPayload.Raw -> payload.bytes.copyInto(
                    destination = destination,
                    destinationOffset = offset + INFO_PAYLOAD_OFFSET,
                    endIndex = minOf(payload.bytes.size, SIGNAL_INFO_SIZE - INFO_PAYLOAD_OFFSET),
                )
                SignalPayload.None -> Unit
            }
        }
    }

    fun readStack(
        bytes: ByteArray,
        offset: Int = 0,
        allowStatusFlag: Boolean = false,
    ): SignalStack? {
        if (offset < 0 || offset > bytes.size - SIGNAL_STACK_SIZE) return null
        val input = LittleEndianBuffer(bytes)
        val flags = input.readU32(offset + STACK_FLAGS_OFFSET)
        val configuration = if (allowStatusFlag) flags and STACK_ON_STACK.inv() else flags
        if (configuration != 0u && configuration != STACK_DISABLED &&
            configuration != STACK_AUTO_DISABLE
        ) {
            return null
        }
        if (configuration == STACK_DISABLED) return SignalStack.DISABLED

        val base = input.readU64(offset + STACK_POINTER_OFFSET)
        val size = input.readU64(offset + STACK_SIZE_OFFSET)
        if (size < MINIMUM_SIGNAL_STACK_SIZE || base >= USER_VIRTUAL_ADDRESS_LIMIT ||
            size > USER_VIRTUAL_ADDRESS_LIMIT - base
        ) {
            return null
        }
        return SignalStack(base, size, configuration == STACK_AUTO_DISABLE)
    }

    fun stackBytes(stack: SignalStack, stackPointer: ULong): ByteArray =
        ByteArray(SIGNAL_STACK_SIZE).also { writeStack(it, 0, stack, stackPointer) }

    fun writeStack(
        destination: ByteArray,
        offset: Int,
        stack: SignalStack,
        stackPointer: ULong,
    ) {
        val flags = when {
            !stack.enabled -> STACK_DISABLED
            stack.contains(stackPointer) -> STACK_ON_STACK
            else -> 0u
        } or if (stack.autoDisable) STACK_AUTO_DISABLE else 0u
        LittleEndianBuffer(destination).apply {
            writeU64(offset + STACK_POINTER_OFFSET, stack.base)
            writeU32(offset + STACK_FLAGS_OFFSET, flags)
            writeU64(offset + STACK_SIZE_OFFSET, stack.size)
        }
    }

    private val supportedActionFlags = SignalActionFlag.entries.fold(0uL) { flags, flag ->
        flags or flag.mask
    }
}

internal object SignalSyscalls {
    fun rtSigaction(regs: PtraceRegisters, process: Process): Long {
        val signal = Signal.from(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EINVAL)
        if (regs[PtraceRegisters.IDX_R10] != SIGNAL_SET_SIZE.toULong()) {
            return errno(Errno.EINVAL)
        }

        val actionAddress = regs[PtraceRegisters.IDX_RSI]
        if (actionAddress != 0uL && (signal == Signal.KILL || signal == Signal.STOP)) {
            return errno(Errno.EINVAL)
        }
        val action = if (actionAddress == 0uL) {
            null
        } else {
            val bytes = UserMemory(process.addressSpace, actionAddress)
                .copyFromUser(SIGNAL_ACTION_SIZE) ?: return errno(Errno.EFAULT)
            SignalAbi.readAction(bytes)
        }

        val previous = action?.let { SignalRouter.installAction(process, signal, it) }
            ?: process.signals.action(signal)
        val previousAddress = regs[PtraceRegisters.IDX_RDX]
        if (previousAddress != 0uL &&
            !UserMemory(process.addressSpace, previousAddress)
                .copyToUser(SignalAbi.actionBytes(previous))
        ) {
            return errno(Errno.EFAULT)
        }
        return 0L
    }

    fun rtSigprocmask(regs: PtraceRegisters, process: Process): Long {
        if (regs[PtraceRegisters.IDX_R10] != SIGNAL_SET_SIZE.toULong()) {
            return errno(Errno.EINVAL)
        }
        val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        val setAddress = regs[PtraceRegisters.IDX_RSI]
        val previous = if (setAddress == 0uL) {
            thread.signals.mask
        } else {
            val operation = when (regs[PtraceRegisters.IDX_RDI]) {
                0uL -> SignalMaskOperation.BLOCK
                1uL -> SignalMaskOperation.UNBLOCK
                2uL -> SignalMaskOperation.SET
                else -> return errno(Errno.EINVAL)
            }
            val requested = readMask(process, setAddress) ?: return errno(Errno.EFAULT)
            thread.signals.updateMask(operation, requested)
        }

        val previousAddress = regs[PtraceRegisters.IDX_RDX]
        return if (previousAddress == 0uL || writeMask(process, previousAddress, previous)) {
            0L
        } else {
            errno(Errno.EFAULT)
        }
    }

    fun signalfd4(regs: PtraceRegisters, process: Process): Long {
        if (regs[PtraceRegisters.IDX_RDX] != SIGNAL_SET_SIZE.toULong()) {
            return errno(Errno.EINVAL)
        }
        val mask = readMask(process, regs[PtraceRegisters.IDX_RSI])
            ?.and(Signal.BLOCKABLE_MASK) ?: return errno(Errno.EFAULT)
        val flags = SignalFdFlags.from(regs[PtraceRegisters.IDX_R10])
            ?: return errno(Errno.EINVAL)
        val descriptor = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
        if (descriptor != -1) {
            val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
            return try {
                val signalFd = file.backend as? SignalFd ?: return errno(Errno.EINVAL)
                signalFd.updateMask(mask)
                process.signals.notifySignalFdWaiters()
                descriptor.toLong()
            } finally {
                file.release()
            }
        }

        val context = process.context ?: return errno(Errno.ENOENT)
        val file = when (val result = FileSystemManager.vfs.createSignalFd(
            process.vfsOperationContext,
            context,
            mask,
            flags.nonBlocking,
        )) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return errno(result.error.errno)
        }
        val descriptorFlags = if (flags.closeOnExec) {
            FileDescriptorFlags.FD_CLOEXEC
        } else {
            0uL
        }
        return process.fdTable.install(file, descriptorFlags)?.toLong() ?: run {
            file.release()
            errno(Errno.EMFILE)
        }
    }

    fun rtSigpending(regs: PtraceRegisters, process: Process): Long {
        if (regs[PtraceRegisters.IDX_RSI] != SIGNAL_SET_SIZE.toULong()) {
            return errno(Errno.EINVAL)
        }
        val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        val pending = thread.pendingSignalMask and thread.signals.mask
        return if (writeMask(process, regs[PtraceRegisters.IDX_RDI], pending)) 0L
        else errno(Errno.EFAULT)
    }

    fun rtSigtimedwait(regs: PtraceRegisters, process: Process): Long {
        if (regs[PtraceRegisters.IDX_R10] != SIGNAL_SET_SIZE.toULong()) {
            return errno(Errno.EINVAL)
        }
        val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        val accepted = readMask(process, regs[PtraceRegisters.IDX_RDI])
            ?.and(Signal.BLOCKABLE_MASK) ?: return errno(Errno.EFAULT)
        val infoAddress = regs[PtraceRegisters.IDX_RSI]
        if (infoAddress != 0uL &&
            !UserMemory(process.addressSpace, infoAddress).isWritable(SIGNAL_INFO_SIZE)
        ) {
            return errno(Errno.EFAULT)
        }
        val timeout = readTimeout(process, regs[PtraceRegisters.IDX_RDX])
        if (timeout is SignalTimeout.Invalid) return errno(timeout.error)
        val finiteTimeout = (timeout as? SignalTimeout.Finite)?.value
        val immediate = finiteTimeout?.isZeroDuration == true
        val deadline = finiteTimeout?.takeUnless { immediate }
            ?.deadlineFrom(TscClock.nanoTime())

        thread.signals.beginWait(accepted)
        try {
            while (true) {
                val info = thread.takePendingSignal(accepted)
                if (info != null) {
                    if (infoAddress != 0uL &&
                        !UserMemory(process.addressSpace, infoAddress)
                            .copyToUser(SignalAbi.infoBytes(info))
                    ) {
                        return errno(Errno.EFAULT)
                    }
                    return info.signal.number.toLong()
                }
                if (immediate || deadline != null && TscClock.nanoTime() >= deadline
                ) {
                    return errno(Errno.EAGAIN)
                }
                if (thread.hasPendingSignal()) return errno(Errno.EINTR)
                val parked = if (deadline == null) Scheduler.parkCurrent()
                else Scheduler.parkCurrentUntil(deadline)
                if (!parked) Scheduler.yieldCurrent()
            }
        } finally {
            thread.signals.endWait()
        }
    }

    fun rtSigsuspend(regs: PtraceRegisters, process: Process): Long {
        if (regs[PtraceRegisters.IDX_RSI] != SIGNAL_SET_SIZE.toULong()) {
            return errno(Errno.EINVAL)
        }
        val requested = readMask(process, regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EFAULT)
        val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        return suspendUntilSignal(regs, thread, requested)
    }

    fun pause(regs: PtraceRegisters, process: Process): Long {
        val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        return suspendUntilSignal(regs, thread, thread.signals.mask)
    }

    fun sigaltstack(regs: PtraceRegisters, process: Process): Long {
        val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        val replacementAddress = regs[PtraceRegisters.IDX_RDI]
        val previousAddress = regs[PtraceRegisters.IDX_RSI]
        val previous = thread.signals.stack

        if (replacementAddress != 0uL) {
            if (previous.contains(regs[PtraceRegisters.IDX_RSP])) return errno(Errno.EPERM)
            val bytes = UserMemory(process.addressSpace, replacementAddress)
                .copyFromUser(SIGNAL_STACK_SIZE) ?: return errno(Errno.EFAULT)
            val replacement = SignalAbi.readStack(bytes) ?: return errno(Errno.EINVAL)
            thread.signals.replaceStack(replacement)
        }
        if (previousAddress != 0uL &&
            !UserMemory(process.addressSpace, previousAddress)
                .copyToUser(SignalAbi.stackBytes(previous, regs[PtraceRegisters.IDX_RSP]))
        ) {
            return errno(Errno.EFAULT)
        }
        return 0L
    }

    fun kill(regs: PtraceRegisters, process: Process): Long {
        val requested = signalOrZero(regs[PtraceRegisters.IDX_RSI])
            ?: return errno(Errno.EINVAL)
        val pid = regs[PtraceRegisters.IDX_RDI].toUInt().toInt()
        val targets = when {
            pid > 0 -> listOfNotNull(ProcessManager.findProcess(pid))
            pid == 0 -> ProcessManager.processesInGroup(process.processGroupId)
            pid == -1 -> ProcessManager.snapshotProcesses()
                .filter { it !== process && it.id != 1 }
            pid == Int.MIN_VALUE -> emptyList()
            else -> ProcessManager.processesInGroup(-pid)
        }
        val info = requested.value?.let { SignalInfo.fromSender(it, process) }
        return sendResult(SignalRouter.sendProcesses(process, targets, info))
    }

    fun pidfdSendSignal(regs: PtraceRegisters, process: Process): Long {
        val flags = PidFdSignalScope.from(regs[PtraceRegisters.IDX_R10])
            ?: return errno(Errno.EINVAL)
        val descriptor = regs[PtraceRegisters.IDX_RDI].toInt()
        val target = when (descriptor) {
            PIDFD_SELF_THREAD -> {
                val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
                PidHandle(thread, PidHandle.Scope.THREAD)
            }
            PIDFD_SELF_THREAD_GROUP -> {
                val leader = process.threads.firstOrNull { it.id == process.id }
                    ?: return errno(Errno.ESRCH)
                PidHandle(leader, PidHandle.Scope.PROCESS)
            }
            else -> {
                val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
                try {
                    if (file.access == AccessMode.PATH) return errno(Errno.EBADF)
                    (file.inode.backend as? PidHandle.Provider)?.target
                        ?: return errno(Errno.EBADF)
                } finally {
                    file.release()
                }
            }
        }
        val scope = flags.resolve(target.scope)
        val number = regs[PtraceRegisters.IDX_RSI].toInt()
        val infoAddress = regs[PtraceRegisters.IDX_RDX]
        val supplied = if (infoAddress == 0uL) null else {
            val info = readQueuedInfo(process, infoAddress) ?: return errno(Errno.EFAULT)
            if (info.number != number) return errno(Errno.EINVAL)
            val self = scope != PidFdSignalScope.PROCESS_GROUP &&
                target.thread === ProcessManager.currentThread()
            if (info.requiresSelf && !self) return errno(Errno.EPERM)
            info
        }
        val group = if (scope == PidFdSignalScope.PROCESS_GROUP) {
            ProcessManager.processesInGroup(target.thread.id).also {
                if (it.isEmpty()) return errno(Errno.ESRCH)
            }
        } else {
            if (target.state == PidHandle.State.DEAD) return errno(Errno.ESRCH)
            null
        }
        val signal = Signal.from(number)
        if (number != 0 && signal == null) return errno(Errno.EINVAL)
        val code = if (scope == PidFdSignalScope.THREAD) SignalInfo.THREAD else SignalInfo.USER
        val info = signal?.let { supplied?.signalInfo(it) ?: SignalInfo.fromSender(it, process, code) }
        val result = when {
            group != null -> SignalRouter.sendProcesses(process, group, info)
            scope == PidFdSignalScope.THREAD -> SignalRouter.sendThread(process, target.thread, info)
            else -> SignalRouter.sendProcess(process, target.thread.process, info)
        }
        return sendResult(result)
    }

    fun tkill(regs: PtraceRegisters, process: Process): Long {
        val tid = positiveId(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EINVAL)
        val requested = signalOrZero(regs[PtraceRegisters.IDX_RSI])
            ?: return errno(Errno.EINVAL)
        val target = ProcessManager.findThread(tid) ?: return errno(Errno.ESRCH)
        return sendThread(process, target, requested.value, SignalInfo.THREAD)
    }

    fun tgkill(regs: PtraceRegisters, process: Process): Long {
        val tgid = positiveId(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EINVAL)
        val tid = positiveId(regs[PtraceRegisters.IDX_RSI]) ?: return errno(Errno.EINVAL)
        val requested = signalOrZero(regs[PtraceRegisters.IDX_RDX])
            ?: return errno(Errno.EINVAL)
        val target = ProcessManager.findThread(tid)
            ?.takeIf { it.process.id == tgid } ?: return errno(Errno.ESRCH)
        return sendThread(process, target, requested.value, SignalInfo.THREAD)
    }

    fun rtSigqueueinfo(regs: PtraceRegisters, process: Process): Long {
        val pid = positiveId(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.ESRCH)
        val requested = signalOrZero(regs[PtraceRegisters.IDX_RSI])
            ?: return errno(Errno.EINVAL)
        val target = ProcessManager.findProcess(pid) ?: return errno(Errno.ESRCH)
        val supplied = readQueuedInfo(process, regs[PtraceRegisters.IDX_RDX])
            ?: return errno(Errno.EFAULT)
        val current = ProcessManager.currentThread()
        if (supplied.requiresSelf && current?.id != target.id) {
            return errno(Errno.EPERM)
        }
        val info = requested.value?.let(supplied::signalInfo)
        return sendResult(SignalRouter.sendProcess(process, target, info))
    }

    fun rtTgsigqueueinfo(regs: PtraceRegisters, process: Process): Long {
        val tgid = positiveId(regs[PtraceRegisters.IDX_RDI]) ?: return errno(Errno.EINVAL)
        val tid = positiveId(regs[PtraceRegisters.IDX_RSI]) ?: return errno(Errno.EINVAL)
        val requested = signalOrZero(regs[PtraceRegisters.IDX_RDX])
            ?: return errno(Errno.EINVAL)
        val target = ProcessManager.findThread(tid)
            ?.takeIf { it.process.id == tgid } ?: return errno(Errno.ESRCH)
        val supplied = readQueuedInfo(process, regs[PtraceRegisters.IDX_R10])
            ?: return errno(Errno.EFAULT)
        if (supplied.requiresSelf && ProcessManager.currentThread() !== target) {
            return errno(Errno.EPERM)
        }
        val info = requested.value?.let(supplied::signalInfo)
        return sendResult(SignalRouter.sendThread(process, target, info))
    }

    fun rtSigreturn(regs: PtraceRegisters, process: Process): Long {
        val thread = ProcessManager.currentThread() ?: return errno(Errno.ESRCH)
        val restored = SignalFrame.restore(regs, thread)
        if (restored != null) return restored.toLong()
        ProcessExit.bySignal(thread, Signal.SEGV, coreDump = true)
    }

    private fun suspendUntilSignal(
        regs: PtraceRegisters,
        thread: Thread,
        requestedMask: ULong,
    ): Long {
        val previous = thread.signals.replaceMask(requestedMask)
        try {
            while (true) {
                regs[PtraceRegisters.IDX_RAX] = errno(Errno.EINTR).toULong()
                if (SignalDelivery.deliverPending(regs, thread, previous)) {
                    return errno(Errno.EINTR)
                }
                Scheduler.parkCurrent()
            }
        } finally {
            if (!regs.signalFrameInstalled) thread.signals.mask = previous
        }
    }

    private fun sendThread(sender: Process, target: Thread, signal: Signal?, code: Int): Long {
        val info = signal?.let { SignalInfo.fromSender(it, sender, code) }
        return sendResult(SignalRouter.sendThread(sender, target, info))
    }

    private fun sendResult(result: SignalSendResult): Long = when (result) {
        SignalSendResult.SUCCESS -> 0L
        SignalSendResult.NO_SUCH_PROCESS -> errno(Errno.ESRCH)
        SignalSendResult.NOT_PERMITTED -> errno(Errno.EPERM)
        SignalSendResult.LIMIT_REACHED -> errno(Errno.EAGAIN)
    }

    private fun readQueuedInfo(process: Process, address: ULong): QueuedSignalInfo? =
        UserMemory(process.addressSpace, address).copyFromUser(SIGNAL_INFO_SIZE)
            ?.let(SignalAbi::readQueuedInfo)

    private fun signalOrZero(raw: ULong): RequestedSignal? = when {
        raw == 0uL -> RequestedSignal(null)
        else -> Signal.from(raw)?.let(::RequestedSignal)
    }

    private fun positiveId(raw: ULong): Int? =
        raw.takeIf { it in 1uL..Int.MAX_VALUE.toULong() }?.toInt()

    private fun readMask(process: Process, address: ULong): ULong? =
        UserMemory(process.addressSpace, address).copyFromUser(SIGNAL_SET_SIZE)
            ?.let { LittleEndianBuffer(it).readU64(0) }

    private fun writeMask(process: Process, address: ULong, mask: ULong): Boolean {
        if (address == 0uL) return false
        val bytes = ByteArray(SIGNAL_SET_SIZE).also { LittleEndianBuffer(it).writeU64(0, mask) }
        return UserMemory(process.addressSpace, address).copyToUser(bytes)
    }

    private fun readTimeout(process: Process, address: ULong): SignalTimeout {
        if (address == 0uL) return SignalTimeout.Infinite
        val bytes = UserMemory(process.addressSpace, address).copyFromUser(TimeSpec.NATIVE_SIZE)
            ?: return SignalTimeout.Invalid(Errno.EFAULT)
        val timeout = TimeSpec(0, 0)
        return if (timeout.updateFromNativeBytes(bytes) && timeout.isValidDuration) {
            SignalTimeout.Finite(timeout)
        } else {
            SignalTimeout.Invalid(Errno.EINVAL)
        }
    }

    private value class SignalFdFlags private constructor(private val bits: Int) {
        val nonBlocking: Boolean
            get() = bits and OpenFlags.O_NONBLOCK != 0

        val closeOnExec: Boolean
            get() = bits and OpenFlags.O_CLOEXEC != 0

        companion object {
            private const val SUPPORTED = OpenFlags.O_NONBLOCK or OpenFlags.O_CLOEXEC

            fun from(raw: ULong): SignalFdFlags? = raw.toInt()
                .takeIf { it and SUPPORTED.inv() == 0 }
                ?.let(::SignalFdFlags)
        }
    }

    private data class RequestedSignal(val value: Signal?)

    private sealed interface SignalTimeout {
        data object Infinite : SignalTimeout
        data class Finite(val value: TimeSpec) : SignalTimeout
        data class Invalid(val error: Int) : SignalTimeout
    }
}

internal object SignalDelivery {
    private const val CAPTURED_REGISTER_SIZE = ULong.SIZE_BYTES * 3

    fun deliverPending(
        registers: PtraceRegisters,
        thread: Thread,
        returnMask: ULong? = null,
    ): Boolean {
        while (true) {
            val accepted = thread.signals.mask.inv()
            val info = thread.takePendingSignal(accepted) ?: return false
            val action = thread.process.signals.actionForDelivery(info.signal)
            when {
                action.isIgnored -> continue
                action.isDefault -> when (info.signal.defaultAction) {
                    DefaultSignalAction.IGNORE -> continue
                    DefaultSignalAction.CONTINUE -> {
                        thread.process.signals.resume(thread.process)
                        continue
                    }
                    DefaultSignalAction.STOP -> {
                        thread.process.signals.stop(thread.process, thread, info.signal)
                        continue
                    }
                    DefaultSignalAction.TERMINATE ->
                        ProcessExit.bySignal(thread, info.signal, coreDump = false)
                    DefaultSignalAction.CORE_DUMP ->
                        ProcessExit.bySignal(thread, info.signal, coreDump = true)
                }
                else -> {
                    val savedMask = returnMask ?: thread.signals.mask
                    if (!SignalFrame.install(registers, thread, info, action, savedMask)) {
                        ProcessExit.bySignal(thread, Signal.SEGV, coreDump = true)
                    }
                    return true
                }
            }
        }
    }

    fun deliverGateway(registers: PtraceRegisters, thread: Thread): Boolean {
        val context = thread.signals.takeGateway() ?: return false
        if (context is SignalGatewayContext.Pending) {
            if (!restoreGatewayRegisters(registers, thread, context)) {
                ProcessExit.bySignal(thread, Signal.SEGV, coreDump = true)
            }
            deliverPending(registers, thread)
            return true
        }

        val synchronous = context as SignalGatewayContext.Synchronous
        val signal = synchronous.info.signal
        val action = thread.process.signals.actionForDelivery(signal)
        if (!action.isCaught || thread.signals.mask and signal.bit != 0uL) {
            ProcessExit.bySignal(
                thread,
                signal,
                coreDump = signal.defaultAction == DefaultSignalAction.CORE_DUMP,
            )
        }
        if (!restoreGatewayRegisters(registers, thread, synchronous)) {
            ProcessExit.bySignal(thread, Signal.SEGV, coreDump = true)
        }
        if (!SignalFrame.install(
                registers = registers,
                thread = thread,
                info = synchronous.info,
                action = action,
                returnMask = thread.signals.mask,
                synchronous = synchronous,
            )
        ) {
            ProcessExit.bySignal(thread, Signal.SEGV, coreDump = true)
        }
        return true
    }

    private fun restoreGatewayRegisters(
        registers: PtraceRegisters,
        thread: Thread,
        context: SignalGatewayContext,
    ): Boolean {
        val captureAddress = context.captureAddress ?: return false
        if (registers[PtraceRegisters.IDX_RSP] != captureAddress) return false
        val bytes = UserMemory(thread.process.addressSpace, captureAddress)
            .copyFromUser(CAPTURED_REGISTER_SIZE) ?: return false
        val captured = LittleEndianBuffer(bytes)
        registers[PtraceRegisters.IDX_R11] = captured.readU64(0)
        registers[PtraceRegisters.IDX_RCX] = captured.readU64(ULong.SIZE_BYTES)
        registers[PtraceRegisters.IDX_RAX] = captured.readU64(ULong.SIZE_BYTES * 2)
        registers[PtraceRegisters.IDX_RIP] = context.instructionPointer
        registers[PtraceRegisters.IDX_RSP] = context.stackPointer
        registers[PtraceRegisters.IDX_RFLAGS] = context.flags
        registers[PtraceRegisters.IDX_ERRCODE] =
            (context as? SignalGatewayContext.Synchronous)?.errorCode ?: 0uL
        registers[PtraceRegisters.IDX_FUNC] = PtraceRegisters.NO_SYSCALL
        return true
    }
}

private object SignalFrame {
    private const val RETURN_ADDRESS_OFFSET = 0
    private const val CONTEXT_OFFSET = 8
    private const val CONTEXT_SIZE = 304
    private const val INFO_OFFSET = CONTEXT_OFFSET + CONTEXT_SIZE
    private const val FIXED_SIZE = INFO_OFFSET + SIGNAL_INFO_SIZE

    private const val CONTEXT_FLAGS_OFFSET = CONTEXT_OFFSET
    private const val CONTEXT_STACK_OFFSET = CONTEXT_OFFSET + 16
    private const val CONTEXT_REGISTERS_OFFSET = CONTEXT_OFFSET + 40
    private const val CONTEXT_FPSTATE_POINTER_OFFSET = CONTEXT_OFFSET + 224
    private const val CONTEXT_SIGNAL_MASK_OFFSET = CONTEXT_OFFSET + 296

    private const val GENERAL_R8 = 0
    private const val GENERAL_R9 = 1
    private const val GENERAL_R10 = 2
    private const val GENERAL_R11 = 3
    private const val GENERAL_R12 = 4
    private const val GENERAL_R13 = 5
    private const val GENERAL_R14 = 6
    private const val GENERAL_R15 = 7
    private const val GENERAL_RDI = 8
    private const val GENERAL_RSI = 9
    private const val GENERAL_RBP = 10
    private const val GENERAL_RBX = 11
    private const val GENERAL_RDX = 12
    private const val GENERAL_RAX = 13
    private const val GENERAL_RCX = 14
    private const val GENERAL_RSP = 15
    private const val GENERAL_RIP = 16
    private const val GENERAL_FLAGS = 17
    private const val GENERAL_SEGMENTS = 18
    private const val GENERAL_ERROR = 19
    private const val GENERAL_TRAP_NUMBER = 20
    private const val GENERAL_OLD_MASK = 21
    private const val GENERAL_FAULT_ADDRESS = 22

    private const val USER_CONTEXT_FLAGS = 0x7uL
    private const val USER_CODE_SELECTOR = 0x23uL
    private const val USER_DATA_SELECTOR = 0x1buL
    private const val RED_ZONE_SIZE = 128uL
    private const val STACK_ALIGNMENT = 16uL
    private const val XSTATE_ALIGNMENT = 64uL
    private const val XSTATE_MAGIC1 = 0x4650_5853u
    private const val XSTATE_MAGIC2 = 0x4650_5845u
    private const val XSTATE_MAGIC_OFFSET = 464
    private const val XSTATE_HEADER_OFFSET = 512
    private const val XSTATE_SIGNAL_SIZE = PtraceRegisters.EXTENDED_STATE_SIZE + UInt.SIZE_BYTES
    private const val SUPPORTED_XFEATURES = 0x7uL
    private const val VALID_MXCSR_BITS = 0x0000_ffbfu
    private const val RESTORABLE_FLAGS = 0x0005_0dd5uL

    fun install(
        registers: PtraceRegisters,
        thread: Thread,
        info: SignalInfo,
        action: SignalAction,
        returnMask: ULong,
        synchronous: SignalGatewayContext.Synchronous? = null,
    ): Boolean {
        if (!action.has(SignalActionFlag.RESTORER) ||
            action.handler >= USER_VIRTUAL_ADDRESS_LIMIT ||
            action.restorer >= USER_VIRTUAL_ADDRESS_LIMIT
        ) {
            return false
        }
        val oldStackPointer = registers[PtraceRegisters.IDX_RSP]
        val alternativeStack = thread.signals.stack
        val alreadyOnAlternativeStack = alternativeStack.contains(oldStackPointer)
        val useAlternativeStack = action.has(SignalActionFlag.ON_STACK) &&
            alternativeStack.enabled && !alreadyOnAlternativeStack
        val top = if (useAlternativeStack) {
            alternativeStack.top
        } else {
            if (oldStackPointer < RED_ZONE_SIZE) return false
            oldStackPointer - RED_ZONE_SIZE
        }
        if (top <= XSTATE_SIGNAL_SIZE.toULong()) return false
        val xstateAddress = (top - XSTATE_SIGNAL_SIZE.toULong()).alignDown(XSTATE_ALIGNMENT)
        if (xstateAddress <= FIXED_SIZE.toULong() + ULong.SIZE_BYTES.toULong()) return false
        val alignedFrame = (xstateAddress - FIXED_SIZE.toULong()).alignDown(STACK_ALIGNMENT)
        if (alignedFrame < ULong.SIZE_BYTES.toULong()) return false
        val frameAddress = alignedFrame - ULong.SIZE_BYTES.toULong()
        val frameSize = xstateAddress + XSTATE_SIGNAL_SIZE.toULong() - frameAddress
        if (frameSize > Int.MAX_VALUE.toULong() ||
            xstateAddress >= USER_VIRTUAL_ADDRESS_LIMIT ||
            frameSize > USER_VIRTUAL_ADDRESS_LIMIT - frameAddress ||
            (useAlternativeStack || alreadyOnAlternativeStack) &&
                frameAddress < alternativeStack.base
        ) {
            return false
        }

        val frame = ByteArray(frameSize.toInt())
        val output = LittleEndianBuffer(frame)
        output.writeU64(RETURN_ADDRESS_OFFSET, action.restorer)
        output.writeU64(CONTEXT_FLAGS_OFFSET, USER_CONTEXT_FLAGS)
        SignalAbi.writeStack(frame, CONTEXT_STACK_OFFSET, alternativeStack, oldStackPointer)
        writeRegisters(output, registers, action, returnMask, synchronous)
        output.writeU64(CONTEXT_FPSTATE_POINTER_OFFSET, xstateAddress)
        output.writeU64(CONTEXT_SIGNAL_MASK_OFFSET, returnMask)
        if (action.has(SignalActionFlag.SIGNAL_INFO)) {
            SignalAbi.writeInfo(frame, INFO_OFFSET, info)
        }

        val xstateOffset = (xstateAddress - frameAddress).toInt()
        registers.copyExtendedStateTo(frame, xstateOffset)
        frame.fill(
            0,
            xstateOffset + XSTATE_MAGIC_OFFSET,
            xstateOffset + XSTATE_HEADER_OFFSET,
        )
        output.writeU32(xstateOffset + XSTATE_MAGIC_OFFSET, XSTATE_MAGIC1)
        output.writeU32(
            xstateOffset + XSTATE_MAGIC_OFFSET + 4,
            XSTATE_SIGNAL_SIZE.toUInt(),
        )
        output.writeU64(
            xstateOffset + XSTATE_MAGIC_OFFSET + 8,
            SUPPORTED_XFEATURES,
        )
        output.writeU32(
            xstateOffset + XSTATE_MAGIC_OFFSET + 16,
            PtraceRegisters.EXTENDED_STATE_SIZE.toUInt(),
        )
        output.writeU32(xstateOffset + PtraceRegisters.EXTENDED_STATE_SIZE, XSTATE_MAGIC2)

        if (!UserMemory(thread.process.addressSpace, frameAddress).copyToUser(frame)) return false

        var handlerMask = thread.signals.mask or action.mask
        if (!action.has(SignalActionFlag.NODEFER)) handlerMask = handlerMask or info.signal.bit
        thread.signals.mask = handlerMask
        if (useAlternativeStack && alternativeStack.autoDisable) {
            thread.signals.replaceStack(SignalStack.DISABLED)
        }

        registers[PtraceRegisters.IDX_RIP] = action.handler
        registers[PtraceRegisters.IDX_RSP] = frameAddress
        registers[PtraceRegisters.IDX_RDI] = info.signal.number.toULong()
        registers[PtraceRegisters.IDX_RSI] = frameAddress + INFO_OFFSET.toULong()
        registers[PtraceRegisters.IDX_RDX] = frameAddress + CONTEXT_OFFSET.toULong()
        registers[PtraceRegisters.IDX_RAX] = 0uL
        registers[PtraceRegisters.IDX_CS] = USER_CODE_SELECTOR
        registers[PtraceRegisters.IDX_SS] = USER_DATA_SELECTOR
        registers.markSignalFrameInstalled()
        SignalRouter.requestDelivery(thread.process)
        return true
    }

    fun restore(registers: PtraceRegisters, thread: Thread): ULong? {
        val contextAddress = registers[PtraceRegisters.IDX_RSP]
        if (contextAddress >= USER_VIRTUAL_ADDRESS_LIMIT) return null
        val bytes = UserMemory(thread.process.addressSpace, contextAddress)
            .copyFromUser(CONTEXT_SIZE) ?: return null
        val input = LittleEndianBuffer(bytes)
        val restoredStack = SignalAbi.readStack(
            bytes,
            offset = CONTEXT_STACK_OFFSET - CONTEXT_OFFSET,
            allowStatusFlag = true,
        ) ?: return null
        val restoredMask = input.readU64(296) and Signal.BLOCKABLE_MASK
        val restoredRip = general(input, GENERAL_RIP)
        val restoredRsp = general(input, GENERAL_RSP)
        val segments = general(input, GENERAL_SEGMENTS)
        if (restoredRip >= USER_VIRTUAL_ADDRESS_LIMIT ||
            restoredRsp >= USER_VIRTUAL_ADDRESS_LIMIT ||
            segments and 0xffffuL != USER_CODE_SELECTOR ||
            segments shr 48 and 0xffffuL != USER_DATA_SELECTOR
        ) {
            return null
        }

        val fpstateAddress = input.readU64(224)
        val xstate = if (fpstateAddress == 0uL) {
            null
        } else {
            if (fpstateAddress and (XSTATE_ALIGNMENT - 1uL) != 0uL) return null
            val image = UserMemory(thread.process.addressSpace, fpstateAddress)
                .copyFromUser(XSTATE_SIGNAL_SIZE) ?: return null
            val magic2 = LittleEndianBuffer(image)
                .readU32(PtraceRegisters.EXTENDED_STATE_SIZE)
            if (!sanitizeExtendedState(image, magic2)) return null
            image
        }

        registers[PtraceRegisters.IDX_R8] = general(input, GENERAL_R8)
        registers[PtraceRegisters.IDX_R9] = general(input, GENERAL_R9)
        registers[PtraceRegisters.IDX_R10] = general(input, GENERAL_R10)
        registers[PtraceRegisters.IDX_R11] = general(input, GENERAL_R11)
        registers[PtraceRegisters.IDX_R12] = general(input, GENERAL_R12)
        registers[PtraceRegisters.IDX_R13] = general(input, GENERAL_R13)
        registers[PtraceRegisters.IDX_R14] = general(input, GENERAL_R14)
        registers[PtraceRegisters.IDX_R15] = general(input, GENERAL_R15)
        registers[PtraceRegisters.IDX_RDI] = general(input, GENERAL_RDI)
        registers[PtraceRegisters.IDX_RSI] = general(input, GENERAL_RSI)
        registers[PtraceRegisters.IDX_RBP] = general(input, GENERAL_RBP)
        registers[PtraceRegisters.IDX_RBX] = general(input, GENERAL_RBX)
        registers[PtraceRegisters.IDX_RDX] = general(input, GENERAL_RDX)
        registers[PtraceRegisters.IDX_RAX] = general(input, GENERAL_RAX)
        registers[PtraceRegisters.IDX_RCX] = general(input, GENERAL_RCX)
        registers[PtraceRegisters.IDX_RSP] = restoredRsp
        registers[PtraceRegisters.IDX_RIP] = restoredRip
        val requestedFlags = general(input, GENERAL_FLAGS)
        val preservedFlags = registers[PtraceRegisters.IDX_RFLAGS] and RESTORABLE_FLAGS.inv()
        registers[PtraceRegisters.IDX_RFLAGS] =
            preservedFlags or (requestedFlags and RESTORABLE_FLAGS) or 2uL
        registers[PtraceRegisters.IDX_CS] = USER_CODE_SELECTOR
        registers[PtraceRegisters.IDX_SS] = USER_DATA_SELECTOR
        registers[PtraceRegisters.IDX_ERRCODE] = general(input, GENERAL_ERROR)
        registers[PtraceRegisters.IDX_FUNC] = PtraceRegisters.SIGNAL_RETURN
        if (xstate == null) {
            bridge.fast_handoff_reset_user_xstate()
        } else if (!registers.restoreExtendedState(xstate)) {
            return null
        }
        thread.signals.mask = restoredMask
        thread.signals.replaceStack(restoredStack)
        return registers[PtraceRegisters.IDX_RAX]
    }

    private fun writeRegisters(
        output: LittleEndianBuffer,
        registers: PtraceRegisters,
        action: SignalAction,
        returnMask: ULong,
        synchronous: SignalGatewayContext.Synchronous?,
    ) {
        fun write(index: Int, value: ULong) {
            output.writeU64(CONTEXT_REGISTERS_OFFSET + index * ULong.SIZE_BYTES, value)
        }
        write(GENERAL_R8, registers[PtraceRegisters.IDX_R8])
        write(GENERAL_R9, registers[PtraceRegisters.IDX_R9])
        write(GENERAL_R10, registers[PtraceRegisters.IDX_R10])
        write(GENERAL_R11, registers[PtraceRegisters.IDX_R11])
        write(GENERAL_R12, registers[PtraceRegisters.IDX_R12])
        write(GENERAL_R13, registers[PtraceRegisters.IDX_R13])
        write(GENERAL_R14, registers[PtraceRegisters.IDX_R14])
        write(GENERAL_R15, registers[PtraceRegisters.IDX_R15])
        write(GENERAL_RDI, registers[PtraceRegisters.IDX_RDI])
        write(GENERAL_RSI, registers[PtraceRegisters.IDX_RSI])
        write(GENERAL_RBP, registers[PtraceRegisters.IDX_RBP])
        write(GENERAL_RBX, registers[PtraceRegisters.IDX_RBX])
        write(GENERAL_RDX, registers[PtraceRegisters.IDX_RDX])
        val syscall = registers[PtraceRegisters.IDX_FUNC]
        val restart = synchronous == null &&
            registers[PtraceRegisters.IDX_RAX].toLong() == -Errno.EINTR.toLong() &&
            action.has(SignalActionFlag.RESTART) && Syscall.isRestartable(syscall)
        write(
            GENERAL_RAX,
            if (restart) syscall else registers[PtraceRegisters.IDX_RAX],
        )
        write(GENERAL_RCX, registers[PtraceRegisters.IDX_RCX])
        write(GENERAL_RSP, registers[PtraceRegisters.IDX_RSP])
        val instructionPointer = registers[PtraceRegisters.IDX_RIP]
        write(GENERAL_RIP, if (restart && instructionPointer >= 2uL) instructionPointer - 2uL else instructionPointer)
        write(GENERAL_FLAGS, registers[PtraceRegisters.IDX_RFLAGS])
        write(
            GENERAL_SEGMENTS,
            USER_CODE_SELECTOR or (USER_DATA_SELECTOR shl 48),
        )
        write(GENERAL_ERROR, synchronous?.errorCode ?: registers[PtraceRegisters.IDX_ERRCODE])
        write(GENERAL_TRAP_NUMBER, synchronous?.trapNumber ?: 0uL)
        write(GENERAL_OLD_MASK, returnMask)
        val faultAddress = (synchronous?.info?.payload as? SignalPayload.Fault)?.address ?: 0uL
        write(GENERAL_FAULT_ADDRESS, faultAddress)
    }

    private fun general(input: LittleEndianBuffer, index: Int): ULong =
        input.readU64(40 + index * ULong.SIZE_BYTES)

    private fun sanitizeExtendedState(bytes: ByteArray, magic2: UInt): Boolean {
        val state = LittleEndianBuffer(bytes)
        if (state.readU32(24) and VALID_MXCSR_BITS.inv() != 0u) return false
        val magic1 = state.readU32(XSTATE_MAGIC_OFFSET)
        if (magic1 == 0u) {
            bytes.fill(0, XSTATE_MAGIC_OFFSET)
            state.writeU64(XSTATE_HEADER_OFFSET, 0x3uL)
            return true
        }
        val frameFeatures = state.readU64(XSTATE_MAGIC_OFFSET + 8)
        if (magic1 != XSTATE_MAGIC1 || magic2 != XSTATE_MAGIC2 ||
            state.readU32(XSTATE_MAGIC_OFFSET + 4) != XSTATE_SIGNAL_SIZE.toUInt() ||
            frameFeatures and SUPPORTED_XFEATURES.inv() != 0uL ||
            state.readU32(XSTATE_MAGIC_OFFSET + 16) !=
                PtraceRegisters.EXTENDED_STATE_SIZE.toUInt()
        ) {
            return false
        }
        if (state.readU64(XSTATE_HEADER_OFFSET) and frameFeatures.inv() != 0uL ||
            state.readU64(XSTATE_HEADER_OFFSET + 8) != 0uL
        ) {
            return false
        }
        for (offset in XSTATE_HEADER_OFFSET + 16 until XSTATE_HEADER_OFFSET + 64) {
            if (bytes[offset] != 0.toByte()) return false
        }
        bytes.fill(0, XSTATE_MAGIC_OFFSET, XSTATE_HEADER_OFFSET)
        return true
    }
}
