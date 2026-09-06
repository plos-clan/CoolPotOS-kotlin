package org.plos_clan.cpos.drivers.char.tty

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.VTModeConstants

object TtyManager {
    private const val FRAME_INTERVAL_MILLIS = 1_000L / 60L

    private val drivers = mutableListOf<TtyDriver>()
    internal val sessions = ArrayList<TtySession>()
    private val consoleLock = KernelMutex()
    private val virtualTerminals = linkedMapOf<Int, TtySession>()
    private var activeVirtualTerminal: TtySession? = null
    private var systemConsole: TtySession? = null

    var terminalType: String = "dumb"
        private set

    internal fun install(driver: TtyDriver): Boolean {
        if (drivers.any { it.consoleName == driver.consoleName }) return false
        drivers += driver
        println("TTY: discovered ${driver.consoleName}")
        return true
    }

    fun setActiveVT(number: Int): Boolean = consoleLock.withLock {
        val target = virtualTerminals[number] ?: return@withLock false
        if (!target.start()) return@withLock false
        activeVirtualTerminal = target
        target.withBackend { it.redraw() }
        true
    }

    internal fun openActiveVirtualTerminal(device: Device): VfsResult<DeviceBackend> = consoleLock.withLock {
        activeVirtualTerminal?.open(device) ?: VfsResult.Err(VfsError.NO_SUCH_DEVICE_OR_ADDRESS)
    }

    internal fun withActiveVirtualTerminal(action: (TtySession, TtySessionBackend) -> Unit) =
        consoleLock.withLock {
            val session = activeVirtualTerminal ?: return@withLock
            session.withBackend { action(session, it) }
        }

    internal fun disallocateVirtualTerminals(number: ULong): Int = consoleLock.withLock {
        if (number > VTModeConstants.MAX_NR_CONSOLES.toULong()) return@withLock -Errno.ENXIO
        if (number == 0uL) {
            for ((index, session) in virtualTerminals) {
                if (index != 1 && session !== activeVirtualTerminal) session.deallocate()
            }
            return@withLock Errno.EOK
        }
        val target = virtualTerminals[number.toInt()] ?: return@withLock Errno.EOK
        if (target === activeVirtualTerminal) return@withLock -Errno.EBUSY
        if (number == 1uL) return@withLock if (target.isInUse) -Errno.EBUSY else Errno.EOK
        if (target.deallocate()) Errno.EOK else -Errno.EBUSY
    }

    fun attachProcessToConsole(process: Process): Boolean =
        systemConsole?.attach(process) == true

    fun processTerminal(process: Process): ProcessTerminal? {
        val session = sessions.firstOrNull { it.sessionId == process.sessionId } ?: return null
        val device = DeviceManager.findByBackend(session) ?: return null
        return ProcessTerminal(device.number.value, session.foregroundProcessGroup)
    }

    fun initialize(): Boolean {
        val requestedConsole = Cmdline["console"]?.substringBefore(',')?.takeIf(String::isNotEmpty)
        val driver = if (requestedConsole == null) {
            drivers.firstOrNull()
        } else {
            drivers.firstOrNull { it.consoleName == requestedConsole }
        } ?: run {
            println("TTY: console ${requestedConsole ?: "<default>"} is unavailable")
            return false
        }

        val flushRequested = if (driver.bufferedOutput) {
            KernelCoroutines.dispatcher.createEvent()
        } else {
            null
        }
        val endpoints = driver.createEndpoints(flushRequested?.let { event -> event::signal } ?: {})
        if (!validEndpoints(endpoints)) {
            println("TTY: invalid endpoint layout for ${driver.consoleName}")
            return false
        }

        val endpointSessions = endpoints.map { endpoint ->
            endpoint to TtySession(
                createBackend = endpoint.createBackend,
                inputSpeed = endpoint.inputSpeed,
                outputSpeed = endpoint.outputSpeed,
            )
        }
        sessions += endpointSessions.map { it.second }
        for ((endpoint, session) in endpointSessions.sortedBy { it.first.virtualTerminalNumber }) {
            endpoint.virtualTerminalNumber?.let { virtualTerminals[it] = session }
        }
        activeVirtualTerminal = virtualTerminals.values.firstOrNull()
        systemConsole = endpointSessions.first().second
        terminalType = driver.terminalType

        val registered = ArrayList<Device>(endpoints.size + 3)
        for ((endpoint, session) in endpointSessions) {
            val device = DeviceManager.register(
                DeviceRegistration(
                    name = endpoint.name,
                    type = DeviceType.CHARACTER,
                    major = endpoint.major,
                    minor = endpoint.minor,
                    backend = session,
                    sysfs = SysfsDevicePublication.virtual("tty", endpoint.name),
                ),
            ) ?: return rollbackInitialization(registered)
            registered += device
        }

        val console = checkNotNull(systemConsole)
        val aliases = buildList {
            if (virtualTerminals.isNotEmpty()) {
                add(DeviceRegistration(
                    name = "tty0",
                    type = DeviceType.CHARACTER,
                    major = LinuxDeviceMajor.TTY.number,
                    minor = 0u,
                    backend = ActiveTty,
                    sysfs = SysfsDevicePublication.virtual("tty", "tty0"),
                ))
            }
            add(DeviceRegistration(
                name = "tty",
                type = DeviceType.CHARACTER,
                major = LinuxDeviceMajor.TTY_AUXILIARY.number,
                minor = 0u,
                backend = ControllingTty,
                sysfs = SysfsDevicePublication.virtual("tty", "tty"),
            ))
            add(DeviceRegistration(
                name = "console",
                type = DeviceType.CHARACTER,
                major = LinuxDeviceMajor.TTY_AUXILIARY.number,
                minor = 1u,
                backend = console,
                sysfs = SysfsDevicePublication.virtual("tty", "console"),
            ))
        }
        for (registration in aliases) {
            val device = DeviceManager.register(registration)
                ?: return rollbackInitialization(registered)
            registered += device
        }

        for ((endpoint, session) in endpointSessions) {
            if (endpoint.virtualTerminalNumber != null && session !== activeVirtualTerminal) continue
            if (!session.start()) {
                println("TTY: failed to start ${driver.consoleName}")
                return rollbackInitialization(registered)
            }
        }

        if (flushRequested != null) {
            KernelCoroutines.launch("terminal-flush") {
                while (isActive) {
                    flushRequested.await()
                    delay(FRAME_INTERVAL_MILLIS) // 该语句如果优化成 milliseconds 会导致协程报错
                    consoleLock.withLock {
                        activeVirtualTerminal?.flushIfDirty()
                    }
                }
            }
        }
        println("TTY: console=${driver.consoleName} term=$terminalType")
        return true
    }

    private fun validEndpoints(endpoints: List<TtyEndpoint>): Boolean {
        if (endpoints.isEmpty() || endpoints.map(TtyEndpoint::name).toSet().size != endpoints.size) {
            return false
        }
        val numbers = endpoints.map { it.major to it.minor }
        if (numbers.toSet().size != numbers.size) return false
        val virtualIndices = endpoints.mapNotNull(TtyEndpoint::virtualTerminalNumber)
        return virtualIndices.all { it in 1..VTModeConstants.MAX_NR_CONSOLES } &&
            virtualIndices.toSet().size == virtualIndices.size
    }

    private fun rollbackInitialization(registered: List<Device>): Boolean {
        registered.asReversed().forEach(DeviceManager::unregister)
        sessions.asReversed().forEach(TtySession::destroy)
        sessions.clear()
        virtualTerminals.clear()
        activeVirtualTerminal = null
        systemConsole = null
        terminalType = "dumb"
        return false
    }
}
