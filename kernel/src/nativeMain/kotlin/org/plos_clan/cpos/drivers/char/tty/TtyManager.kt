package org.plos_clan.cpos.drivers.char.tty

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.TermiosConstants
import org.plos_clan.cpos.utils.VTModeConstants

object TtyManager {
    private const val FRAME_INTERVAL_MILLIS = 1_000L / 60L

    private val drivers = mutableListOf<TtyDriver>()
    internal val sessions = ArrayList<TtySession>()
    private val virtualTerminals = ArrayList<TtySession>()
    private var activeVirtualTerminalIndex = 0
    private var systemConsole: TtySession? = null

    var terminalType: String = "dumb"
        private set

    internal fun install(driver: TtyDriver): Boolean {
        if (drivers.any { it.consoleName == driver.consoleName }) return false
        drivers += driver
        println("TTY: discovered ${driver.consoleName}")
        return true
    }

    fun setActiveVT(index: Int): Boolean {
        if (index !in virtualTerminals.indices) return false
        activeVirtualTerminalIndex = index
        return true
    }

    fun activeVirtualTerminal(): TtySession? =
        virtualTerminals.getOrNull(activeVirtualTerminalIndex)

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
            ?: run {
                println("TTY: failed to create ${driver.consoleName}")
                return false
            }
        if (!validEndpoints(endpoints)) {
            endpoints.asReversed().forEach { it.backend.destroy() }
            println("TTY: invalid endpoint layout for ${driver.consoleName}")
            return false
        }

        val endpointSessions = endpoints.map { endpoint ->
            endpoint to TtySession(
                termios = createDefaultTermios(),
                vtMode = VtMode(0, 0, 0, 0, 0),
                ttyMode = VTModeConstants.KD_TEXT,
                ttyKbMode = VTModeConstants.K_XLATE,
                backend = endpoint.backend,
                inputSpeed = endpoint.inputSpeed,
                outputSpeed = endpoint.outputSpeed,
            )
        }
        sessions += endpointSessions.map { it.second }
        virtualTerminals += endpointSessions
            .mapNotNull { (endpoint, session) ->
                endpoint.virtualTerminalIndex?.let { index -> index to session }
            }
            .sortedBy { it.first }
            .map { it.second }
        systemConsole = endpointSessions.first().second
        terminalType = driver.terminalType

        val registered = ArrayList<Device>(endpoints.size + 2)
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
        val aliases = listOf(
            DeviceRegistration(
                name = "tty",
                type = DeviceType.CHARACTER,
                major = LinuxDeviceMajor.TTY_AUXILIARY.number,
                minor = 0u,
                backend = ControllingTty,
                sysfs = SysfsDevicePublication.virtual("tty", "tty"),
            ),
            DeviceRegistration(
                name = "console",
                type = DeviceType.CHARACTER,
                major = LinuxDeviceMajor.TTY_AUXILIARY.number,
                minor = 1u,
                backend = console,
                sysfs = SysfsDevicePublication.virtual("tty", "console"),
            ),
        )
        for (registration in aliases) {
            val device = DeviceManager.register(registration)
                ?: return rollbackInitialization(registered)
            registered += device
        }

        for (session in sessions) {
            if (!session.backend.start(session)) {
                println("TTY: failed to start ${driver.consoleName}")
                return rollbackInitialization(registered)
            }
        }

        if (flushRequested != null) {
            KernelCoroutines.launch("terminal-flush") {
                while (isActive) {
                    flushRequested.await()
                    delay(FRAME_INTERVAL_MILLIS)
                    sessions.forEach(TtySession::flushIfDirty)
                }
            }
        }
        println("TTY: console=${driver.consoleName} term=$terminalType")
        return true
    }

    private fun createDefaultTermios() = Termios(
        cIflag = TermiosConstants.BRKINT or TermiosConstants.ICRNL or TermiosConstants.IXON,
        cOflag = TermiosConstants.OPOST or TermiosConstants.ONLCR,
        cCflag = TermiosConstants.CS8 or TermiosConstants.CREAD or TermiosConstants.CLOCAL,
        cLflag = TermiosConstants.ECHO or TermiosConstants.ECHOE or TermiosConstants.ECHOK or
            TermiosConstants.ICANON or TermiosConstants.IEXTEN or TermiosConstants.ISIG,
        cLine = 0,
        cCc = ByteArray(19).apply {
            this[TermiosConstants.VINTR] = 3
            this[TermiosConstants.VQUIT] = 28
            this[TermiosConstants.VERASE] = 127
            this[TermiosConstants.VKILL] = 21
            this[TermiosConstants.VEOF] = 4
            this[TermiosConstants.VTIME] = 0
            this[TermiosConstants.VMIN] = 1
            this[TermiosConstants.VSTART] = 17
            this[TermiosConstants.VSTOP] = 19
            this[TermiosConstants.VSUSP] = 26
            this[TermiosConstants.VREPRINT] = 18
            this[TermiosConstants.VDISCARD] = 15
            this[TermiosConstants.VWERASE] = 23
            this[TermiosConstants.VLNEXT] = 22
        },
    )

    private fun validEndpoints(endpoints: List<TtyEndpoint>): Boolean {
        if (endpoints.isEmpty() || endpoints.map(TtyEndpoint::name).toSet().size != endpoints.size) {
            return false
        }
        val numbers = endpoints.map { it.major to it.minor }
        if (numbers.toSet().size != numbers.size) return false
        val virtualIndices = endpoints.mapNotNull(TtyEndpoint::virtualTerminalIndex)
        return virtualIndices.all { it >= 0 } &&
            virtualIndices.toSet().size == virtualIndices.size
    }

    private fun rollbackInitialization(registered: List<Device>): Boolean {
        registered.asReversed().forEach(DeviceManager::unregister)
        sessions.asReversed().forEach(TtySession::destroy)
        sessions.clear()
        virtualTerminals.clear()
        activeVirtualTerminalIndex = 0
        systemConsole = null
        terminalType = "dumb"
        return false
    }
}
