@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.PositionlessDeviceBackend
import org.plos_clan.cpos.fs.vfs.VfsError
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
import kotlin.concurrent.atomics.AtomicLong

class TtySession(
    private val createBackend: () -> TtySessionBackend?,
    inputSpeed: Int = 0,
    outputSpeed: Int = inputSpeed,
) : TtyDevice() {
    var termios = Termios.defaults()
        private set
    var termios2 = Termios2(termios, inputSpeed, outputSpeed)
        private set

    private val lifecycleLock = KernelMutex()
    private val generation = AtomicLong(0)
    private var backend: TtySessionBackend? = null
    private var openCount = 0
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
        termios = Termios.defaults()
        termios2 = Termios2(termios)
    }

    private fun hangup(file: OpenFile): Int {
        if (file.isHungUp) return -Errno.EIO
        if (ProcessManager.currentThread()?.capabilities?.hasEffective(CapEnum.SYS_ADMIN) != true) {
            return -Errno.EPERM
        }
        val detached = stateLock.withLock {
            val previous = controllingSessionId
            controllingSessionId = 0
            foregroundProcessGroupId = 0
            generation.fetchAndAdd(1)
            previous
        }
        file.backend.hangup(this)
        val leader = if (detached == 0) null else ProcessManager.findProcess(detached)
        if (leader != null) {
            SignalRouter.sendProcess(null, leader, SignalInfo(Signal.HANGUP, SignalInfo.KERNEL))
            SignalRouter.sendProcess(null, leader, SignalInfo(Signal.CONTINUE, SignalInfo.KERNEL))
        }
        return Errno.EOK
    }

    class OpenFile internal constructor(
        val session: TtySession,
        internal val backend: TtySessionBackend,
        private val generation: Long,
    ) : PositionlessDeviceBackend {
        val isHungUp: Boolean
            get() = generation != session.generation.load()

        override fun close(device: Device) = session.lifecycleLock.withLock {
            check(session.openCount > 0)
            session.openCount -= 1
        }

        override fun ioctl(device: Device, command: Int, args: UserMemory): Long =
            if (command == IoctlConstants.TIOCVHANGUP) {
                control(command) { session.hangup(this) }.toLong()
            } else {
                backend.ioctl(this, command, args).toLong()
            }

        internal fun control(command: Int, action: () -> Int): Int = session.lifecycleLock.withLock {
            if (!isHungUp) action()
            else if (command == IoctlConstants.TIOCSPGRP) -Errno.ENOTTY else -Errno.EIO
        }

        override fun poll(device: Device, events: Int): Long = if (isHungUp) {
            (PollEvents.DEFAULT_FILE_EVENTS or PollEvents.POLLERR or PollEvents.POLLHUP).toLong()
        } else {
            backend.poll(session, events).toLong()
        }

        override fun read(
            device: Device,
            buffer: PreparedBufferDestination,
            bufferOffset: Int,
            size: ULong,
        ): Long = if (isHungUp) 0 else backend.read(this, buffer, bufferOffset, size)

        override fun write(
            device: Device,
            buffer: PreparedBufferSource,
            bufferOffset: Int,
            size: ULong,
        ): Long = session.lifecycleLock.withLock {
            if (isHungUp) -Errno.EIO.toLong()
            else backend.write(session, buffer, bufferOffset, size)
        }
    }

    internal fun signalForeground(signal: Signal) {
        val processGroup = foregroundProcessGroup
        if (processGroup == 0) return
        val info = SignalInfo(signal, SignalInfo.KERNEL)
        for (process in ProcessManager.snapshotProcesses()) {
            if (process.processGroupId == processGroup) {
                SignalRouter.sendProcess(null, process, info)
            }
        }
    }

    fun attach(process: Process): Boolean = stateLock.withLock {
        if (process.sessionId != process.id ||
            controllingSessionId != 0 && controllingSessionId != process.sessionId
        ) {
            return@withLock false
        }
        controllingSessionId = process.sessionId
        if (foregroundProcessGroupId == 0) {
            foregroundProcessGroupId = process.processGroupId
        }
        true
    }

    fun attachCurrentProcess(): Boolean {
        val process = ProcessManager.currentProcess() ?: return false
        return attach(process)
    }

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
        return stateLock.withLock {
            if (controllingSessionId != process.sessionId) {
                return@withLock false
            }
            controllingSessionId = 0
            foregroundProcessGroupId = 0
            true
        }
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
        val sessionId = ProcessManager.currentProcess()?.sessionId
        val session = TtyManager.sessions.firstOrNull { sessionId != 0 && it.sessionId == sessionId }
        return session?.open(device) ?: VfsResult.Err(VfsError.NO_SUCH_DEVICE_OR_ADDRESS)
    }
}

internal object ActiveTty : TtyDevice() {
    override fun open(device: Device): VfsResult<DeviceBackend> = TtyManager.openActiveVirtualTerminal(device)
}
