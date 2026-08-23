package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.PositionlessDeviceBackend
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.tasks.SignalInfo
import org.plos_clan.cpos.tasks.SignalRouter
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock

class TtySession(
    val termios: Termios,
    val vtMode: VtMode,
    var ttyMode: Int,
    var ttyKbMode: Int,
    val backend: TtySessionBackend,
    val device: TtyDevice,
) : PositionlessDeviceBackend {
    val termios2 = Termios2(
        termios.cIflag,
        termios.cOflag,
        termios.cCflag,
        termios.cLflag,
        termios.cLine,
        termios.cCc.copyOf(),
        0,
        0,
    )
    private val stateLock = IrqSpinLock()
    private var controllingSessionId = 0
    private var foregroundProcessGroupId = 0

    val sessionId: Int
        get() = stateLock.withLock { controllingSessionId }

    val foregroundProcessGroup: Int
        get() = stateLock.withLock { foregroundProcessGroupId }

    override fun ioctl(
        device: Device,
        command: Int,
        args: UserMemory
    ): Long = backend.ioctl(this, command, args).toLong()

    override fun poll(device: Device, events: Int): Long =
        backend.poll(this, events).toLong()

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong
    ): Long = backend.read(this, buffer, bufferOffset, size)

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        size: ULong
    ): Long = backend.write(this, buffer, bufferOffset, size)

    fun keyboardInput(data: CharArray) = backend.keyboardInput(this, data)

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

internal object ControllingTty : PositionlessDeviceBackend {
    override fun open(device: Device): DeviceBackend? = currentSession()

    override fun ioctl(device: Device, command: Int, args: UserMemory): Long =
        currentSession()?.ioctl(device, command, args) ?: -Errno.ENXIO.toLong()

    override fun poll(device: Device, events: Int): Long =
        currentSession()?.poll(device, events) ?: -Errno.ENXIO.toLong()

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong,
    ): Long = currentSession()?.read(device, buffer, bufferOffset, size)
        ?: -Errno.ENXIO.toLong()

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        size: ULong,
    ): Long = currentSession()?.write(device, buffer, bufferOffset, size)
        ?: -Errno.ENXIO.toLong()

    private fun currentSession(): TtySession? {
        val sessionId = ProcessManager.currentProcess()?.sessionId?.takeIf { it != 0 } ?: return null
        return TtyManager.vts.firstOrNull { it.sessionId == sessionId }
    }
}
