@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.drivers.char.tty

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.ModeAwareDeviceBackend
import org.plos_clan.cpos.drivers.PositionlessDeviceBackend
import org.plos_clan.cpos.fs.vfs.IoMode
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.TermiosConstants

class TtySession(
    private val createBackend: () -> TtySessionBackend?,
    val deviceNumber: ULong = 0uL,
    inputSpeed: Int = 0,
    outputSpeed: Int = inputSpeed,
) : TtyDevice() {
    private val attributes = AtomicReference(Termios2(Termios.defaults(), inputSpeed, outputSpeed))
    val termios: Termios
        get() = attributes.load()
    val termios2: Termios2
        get() = attributes.load()

    private val lifecycleLock = KernelMutex()
    private val generation = AtomicLong(0)
    private var backend: TtySessionBackend? = null
    private var openCount = 0
    internal var exclusive = false
    private val stateLock = IrqSpinLock()
    private var controllingSessionId = 0
    private var foregroundProcessGroupId = 0

    val sessionId: Int
        get() = stateLock.withLock { controllingSessionId }

    val foregroundProcessGroup: Int
        get() = stateLock.withLock { foregroundProcessGroupId }

    override fun open(device: Device): VfsResult<DeviceBackend> = lifecycleLock.withLock {
        when (val result = allocate()) {
            is VfsResult.Err -> result
            is VfsResult.Ok -> {
                if (exclusive && ProcessManager.currentThread()?.capabilities?.hasEffective(CapEnum.SYS_ADMIN) != true) {
                    return@withLock VfsResult.Err(VfsError.BUSY)
                }
                val error = result.value.open(this)
                if (error != 0) return@withLock VfsResult.Err(VfsError.fromErrno(-error))
                openCount++
                VfsResult.Ok(OpenFile(this, result.value, generation.load()))
            }
        }
    }

    internal fun start(): Boolean = lifecycleLock.withLock { allocate() is VfsResult.Ok }

    private fun allocate(): VfsResult<TtySessionBackend> {
        backend?.let { return VfsResult.Ok(it) }
        val created = createBackend() ?: return VfsResult.Err(VfsError.NO_MEMORY)
        if (!created.start(this)) {
            created.destroy()
            return VfsResult.Err(VfsError.IO)
        }
        backend = created
        return VfsResult.Ok(created)
    }

    fun receiveInput(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
        if (offset < 0 || count < 0 || offset > data.size - count) return
        lifecycleLock.withLock { backend?.receiveInput(this, data, offset, count) }
    }

    internal fun withBackend(action: (TtySessionBackend) -> Unit) = lifecycleLock.withLock {
        backend?.let(action)
        Unit
    }

    internal fun flushIfDirty() = withBackend(TtySessionBackend::flushIfDirty)

    internal val isInUse: Boolean
        get() = lifecycleLock.withLock { openCount != 0 || sessionId != 0 }

    internal fun deallocate(): Boolean = lifecycleLock.withLock {
        if (openCount != 0 || sessionId != 0) return@withLock false
        backend?.destroy()
        backend = null
        true
    }

    internal fun destroy() = lifecycleLock.withLock {
        backend?.destroy()
        backend = null
    }

    internal fun resetTermios() {
        attributes.store(Termios2(Termios.defaults()))
    }

    internal fun updateTermios(value: Termios2) = attributes.store(value)

    fun hangup() {
        lifecycleLock.withLock {
            val group = stateLock.withLock {
                val previous = foregroundProcessGroupId
                controllingSessionId = 0
                foregroundProcessGroupId = 0
                generation.fetchAndAdd(1)
                previous
            }
            for (process in ProcessManager.snapshotProcesses()) {
                if (process.controllingTerminal === this) process.controllingTerminal = null
                if (group != 0 && process.processGroupId == group) {
                    SignalRouter.sendProcess(null, process, SignalInfo(Signal.HANGUP, SignalInfo.KERNEL))
                    SignalRouter.sendProcess(null, process, SignalInfo(Signal.CONTINUE, SignalInfo.KERNEL))
                }
            }
            backend?.hangup(this)
        }
    }

    override fun open(device: Device, caller: VfsOperationContext, options: OpenOptions): VfsResult<DeviceBackend> {
        val result = open(device)
        if (result is VfsResult.Ok && !options.noControllingTerminal && sessionId == 0) attachCurrentProcess()
        return result
    }

    internal fun masterFile(): OpenFile = lifecycleLock.withLock {
        OpenFile(this, checkNotNull(backend), generation.load(), true)
    }

    class OpenFile internal constructor(
        val session: TtySession,
        internal val backend: TtySessionBackend,
        private val generation: Long,
        internal val isMaster: Boolean = false,
    ) : ModeAwareDeviceBackend {
        override val readinessVersion: Int
            get() = backend.readinessVersion + session.generation.load().toInt()

        val isHungUp: Boolean
            get() = generation != session.generation.load()

        override fun close(device: Device) = session.lifecycleLock.withLock {
            check(session.openCount > 0)
            session.openCount -= 1
            backend.close(session)
        }

        override fun ioctl(device: Device, command: Int, args: UserMemory): Long {
            if (command != IoctlConstants.TIOCVHANGUP) return backend.ioctl(this, command, args).toLong()
            if (isHungUp) return -Errno.EIO.toLong()
            if (ProcessManager.currentThread()?.capabilities?.hasEffective(CapEnum.SYS_ADMIN) != true) {
                return -Errno.EPERM.toLong()
            }
            session.hangup()
            return 0
        }

        internal fun control(command: Int, action: () -> Int): Int = session.lifecycleLock.withLock {
            if (!isHungUp) action()
            else if (command == IoctlConstants.TIOCSPGRP) -Errno.ENOTTY else -Errno.EIO
        }

        override fun poll(device: Device, events: Int): Long = if (isHungUp) {
            ((events and PollEvents.DEFAULT_FILE_EVENTS) or PollEvents.POLLERR or PollEvents.POLLHUP).toLong()
        } else {
            backend.poll(session, events).toLong()
        }

        override fun read(
            device: Device,
            buffer: PreparedBufferDestination,
            bufferOffset: Int,
            size: ULong,
            mode: IoMode,
        ): Long {
            if (isHungUp || size == 0uL) return 0
            val error = checkBackground(Signal.TERMINAL_INPUT_STOP)
            return if (error != 0) error.toLong() else backend.read(this, buffer, bufferOffset, size, mode)
        }

        override fun write(
            device: Device,
            buffer: PreparedBufferSource,
            bufferOffset: Int,
            size: ULong,
            mode: IoMode,
        ): Long {
            if (isHungUp) return -Errno.EIO.toLong()
            val error = if (session.termios.cLflag and TermiosConstants.TOSTOP != 0) {
                checkBackground(Signal.TERMINAL_OUTPUT_STOP)
            } else 0
            return if (error != 0) error.toLong() else backend.write(this, buffer, bufferOffset, size, mode)
        }

        internal fun checkBackground(signal: Signal): Int {
            val thread = ProcessManager.currentThread() ?: return 0
            val process = thread.process
            if (isMaster || process.controllingTerminal !== session || process.processGroupId == session.foregroundProcessGroup) return 0
            val ignored = thread.signals.mask and signal.bit != 0uL || process.signals.action(signal).isIgnored
            if (ignored) return if (signal == Signal.TERMINAL_INPUT_STOP) -Errno.EIO else 0
            val group = ProcessManager.processesInGroup(process.processGroupId)
            val orphaned = group.none { member ->
                val parent = ProcessManager.findProcess(member.parentId)
                parent != null && parent.sessionId == process.sessionId && parent.processGroupId != process.processGroupId
            }
            if (orphaned) return -Errno.EIO
            for (member in group) SignalRouter.sendProcess(null, member, SignalInfo(signal, SignalInfo.KERNEL))
            return -Errno.EINTR
        }
    }

    internal fun signalForeground(signal: Signal) {
        val processGroup = foregroundProcessGroup
        if (processGroup == 0) return
        val info = SignalInfo(signal, SignalInfo.KERNEL)
        for (process in ProcessManager.processesInGroup(processGroup)) SignalRouter.sendProcess(null, process, info)
    }

    fun attach(process: Process, force: Boolean = false): Boolean = stateLock.withLock {
        if (process.sessionId != process.id || process.controllingTerminal != null && process.controllingTerminal !== this) {
            return@withLock false
        }
        if (controllingSessionId != 0 && controllingSessionId != process.sessionId) {
            if (!force || ProcessManager.currentThread()?.capabilities?.hasEffective(CapEnum.SYS_ADMIN) != true) return@withLock false
            for (member in ProcessManager.snapshotProcesses()) {
                if (member.controllingTerminal === this) member.controllingTerminal = null
            }
            foregroundProcessGroupId = 0
        }
        controllingSessionId = process.sessionId
        if (foregroundProcessGroupId == 0) foregroundProcessGroupId = process.processGroupId
        for (member in ProcessManager.snapshotProcesses()) {
            if (member.sessionId == process.sessionId) member.controllingTerminal = this
        }
        true
    }

    fun attachCurrentProcess(force: Boolean = false): Boolean =
        ProcessManager.currentProcess()?.let { attach(it, force) } == true

    fun setForegroundProcessGroup(process: Process, processGroup: Int): Boolean {
        if (processGroup <= 0) {
            return false
        }
        if (ProcessManager.snapshotProcesses().none {
                it.sessionId == process.sessionId && it.processGroupId == processGroup
            }
        ) {
            return false
        }
        return stateLock.withLock {
            if (controllingSessionId != process.sessionId) {
                return@withLock false
            }
            foregroundProcessGroupId = processGroup
            true
        }
    }

    fun detachCurrentProcess(): Boolean {
        val process = ProcessManager.currentProcess() ?: return false
        if (process.controllingTerminal !== this) return false
        if (process.id != process.sessionId) {
            process.controllingTerminal = null
            return true
        }
        signalForeground(Signal.HANGUP)
        signalForeground(Signal.CONTINUE)
        stateLock.withLock {
            for (member in ProcessManager.snapshotProcesses()) {
                if (member.controllingTerminal === this) member.controllingTerminal = null
            }
            controllingSessionId = 0
            foregroundProcessGroupId = 0
        }
        return true
    }

}

abstract class TtyDevice : PositionlessDeviceBackend {
    abstract override fun open(device: Device): VfsResult<DeviceBackend>

    final override fun ioctl(device: Device, command: Int, args: UserMemory): Long = -Errno.EIO.toLong()
    final override fun poll(device: Device, events: Int): Long = PollEvents.POLLERR.toLong()
    final override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong,
    ): Long = -Errno.EIO.toLong()

    final override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        size: ULong,
    ): Long = -Errno.EIO.toLong()
}

internal object ControllingTty : TtyDevice() {
    override fun open(device: Device): VfsResult<DeviceBackend> {
        val session = ProcessManager.currentProcess()?.controllingTerminal
        return session?.open(device) ?: VfsResult.Err(VfsError.NO_SUCH_DEVICE_OR_ADDRESS)
    }
}

internal object ActiveTty : TtyDevice() {
    override fun open(device: Device): VfsResult<DeviceBackend> = TtyManager.openActiveVirtualTerminal(device)
}
