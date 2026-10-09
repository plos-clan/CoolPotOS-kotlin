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
import org.plos_clan.cpos.fs.sysfs.SysfsTextAttribute
import org.plos_clan.cpos.fs.vfs.DeviceNumber
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.PollWait
import org.plos_clan.cpos.tasks.CapEnum
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.VTModeConstants

object TtyManager {
    private const val FRAME_INTERVAL_MILLIS = 1_000L / 60L

    private val drivers = mutableListOf<TtyDriver>()
    internal val sessions = ArrayList<TtySession>()
    private val consoleLock = KernelMutex()
    private val virtualTerminals = linkedMapOf<Int, VirtualConsole>()
    private var activeVirtualTerminal: VirtualConsole? = null
    private var systemConsole: TtySession? = null

    var terminalType: String = "dumb"
        private set

    internal fun install(driver: TtyDriver): Boolean {
        if (drivers.any { it.consoleName == driver.consoleName }) return false
        drivers += driver
        println("TTY: discovered ${driver.consoleName}")
        return true
    }

    internal fun openActiveVirtualTerminal(device: Device): VfsResult<DeviceBackend> = consoleLock.withLock {
        activeVirtualTerminal?.session?.open(device) ?: VfsResult.Err(VfsError.NO_SUCH_DEVICE_OR_ADDRESS)
    }

    internal fun withActiveVirtualTerminal(action: (TtySession, TtySessionBackend) -> Unit) =
        consoleLock.withLock {
            val session = activeVirtualTerminal?.session ?: return@withLock
            session.withBackend { action(session, it) }
        }

    internal fun disallocateVirtualTerminals(number: ULong): Int = consoleLock.withLock {
        if (number > VTModeConstants.MAX_NR_CONSOLES.toULong()) return@withLock -Errno.ENXIO
        if (number == 0uL) {
            for ((index, console) in virtualTerminals) {
                if (index != 1 && console !== activeVirtualTerminal) console.deallocate()
            }
            return@withLock Errno.EOK
        }
        val target = virtualTerminals[number.toInt()] ?: return@withLock Errno.EOK
        if (target === activeVirtualTerminal) return@withLock -Errno.EBUSY
        if (number == 1uL) return@withLock if (target.session.isInUse) -Errno.EBUSY else Errno.EOK
        if (target.deallocate()) Errno.EOK else -Errno.EBUSY
    }

    private val activeAttribute = SysfsTextAttribute("active", reader = {
        val value = consoleLock.withLock { "tty${activeVirtualTerminal?.number ?: 0}\n" }
        VfsResult.Ok(value.encodeToByteArray())
    })

    internal fun virtualConsoleIoctl(file: TtySession.OpenFile, command: Int, args: UserMemory): Int? {
        if (command !in VTModeConstants.VT_OPENQRY..VTModeConstants.VT_DISALLOCATE) return null
        if (file.isHungUp) return -Errno.EIO
        if (command == VTModeConstants.VT_WAITACTIVE) return waitActive(args.address)
        return consoleLock.withLock {
            val console = virtualTerminals.values.firstOrNull { it.session === file.session }
                ?: return@withLock -Errno.ENOTTY
            val result = when (command) {
                VTModeConstants.VT_OPENQRY -> {
                    val available = virtualTerminals.values.firstOrNull { !it.session.isInUse }
                    val bytes = ByteArray(4)
                    LittleEndianBuffer(bytes).writeU32(0, available?.number?.toUInt() ?: UInt.MAX_VALUE)
                    bytes
                }
                VTModeConstants.VT_GETSTATE -> {
                    val bytes = ByteArray(6)
                    val data = LittleEndianBuffer(bytes)
                    data.writeU16(0, (activeVirtualTerminal?.number ?: 0).toUShort())
                    val state = virtualTerminals.values.fold(1) { bits, vt ->
                        if (vt.number < 16 && vt.session.isAllocated) bits or (1 shl vt.number) else bits
                    }
                    data.writeU16(4, state.toUShort())
                    bytes
                }
                VTModeConstants.VT_GETMODE -> console.modeBytes()
                else -> null
            }
            if (result != null) return@withLock if (args.copyToUser(result)) 0 else -Errno.EFAULT
            val thread = ProcessManager.currentThread() ?: return@withLock -Errno.EPERM
            val ownsTerminal = thread.process.controllingTerminal === file.session
            if (!ownsTerminal && !thread.capabilities.hasEffective(CapEnum.SYS_TTY_CONFIG)) {
                return@withLock -Errno.EPERM
            }
            when (command) {
                VTModeConstants.VT_SETMODE -> console.setMode(args, thread.process)
                VTModeConstants.VT_ACTIVATE -> activate(args.address)
                VTModeConstants.VT_RELDISP -> {
                    val target = console.release(args.address)
                    if (target <= 0) return@withLock target
                    switchTo(virtualTerminals.getValue(target))
                }
                else -> -Errno.ENOTTY
            }
        }
    }

    private fun activate(number: ULong): Int {
        if (number !in 1uL..VTModeConstants.MAX_NR_CONSOLES.toULong()) return -Errno.ENXIO
        val target = virtualTerminals[number.toInt()] ?: return -Errno.ENXIO
        if (!target.session.start()) return -Errno.ENOMEM
        val current = activeVirtualTerminal
        if (current === target) return 0
        if (current?.requestRelease(target.number) == true) return 0
        return switchTo(target)
    }

    private fun switchTo(target: VirtualConsole): Int {
        if (!target.session.start()) return -Errno.ENOMEM
        activeVirtualTerminal = target
        target.acquire()
        target.session.withBackend { it.redraw() }
        activeAttribute.notifyChanged()
        return 0
    }

    private fun waitActive(number: ULong): Int {
        if (number !in 1uL..VTModeConstants.MAX_NR_CONSOLES.toULong()) return -Errno.ENXIO
        val thread = ProcessManager.currentThread() ?: return -Errno.EPERM
        val waiter = PollWait(thread)
        waiter.watch(activeAttribute.changes)
        try {
            while (true) {
                waiter.prepare()
                val active = consoleLock.withLock { activeVirtualTerminal?.number == number.toInt() }
                if (active) return 0
                if (thread.hasPendingSignal()) return -Errno.EINTR
                waiter.await(null)
            }
        } finally {
            waiter.close()
        }
    }

    fun attachProcessToConsole(process: Process): Boolean =
        systemConsole?.attach(process) == true

    fun processTerminal(process: Process): ProcessTerminal? {
        val session = process.controllingTerminal ?: return null
        return ProcessTerminal(session.deviceNumber, session.foregroundProcessGroup)
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

        val flushRequested = if (drivers.any { it.bufferedOutput }) {
            KernelCoroutines.dispatcher.createEvent()
        } else {
            null
        }
        val invalidate = flushRequested?.let { event -> event::signal } ?: {}
        val selected = driver.createEndpoints(invalidate)
        val graphics = if (driver.bufferedOutput) driver else drivers.firstOrNull { it.bufferedOutput }
        val additional = drivers.filter { it !== driver && (!it.bufferedOutput || it === graphics) }
        val endpoints = selected + additional.flatMap { it.createEndpoints(invalidate) }
        if (!validEndpoints(endpoints)) {
            println("TTY: invalid endpoint layout for ${driver.consoleName}")
            return false
        }

        val endpointSessions = endpoints.map { endpoint ->
            endpoint to TtySession(
                createBackend = endpoint.createBackend,
                deviceNumber = checkNotNull(DeviceNumber.create(endpoint.major, endpoint.minor)).value,
                inputSpeed = endpoint.inputSpeed,
                outputSpeed = endpoint.outputSpeed,
            )
        }
        sessions += endpointSessions.map { it.second }
        for ((endpoint, session) in endpointSessions.sortedBy { it.first.virtualTerminalNumber }) {
            val number = endpoint.virtualTerminalNumber ?: continue
            virtualTerminals[number] = VirtualConsole(number, session)
        }
        val (consoleEndpoint, console) = endpointSessions.first()
        activeVirtualTerminal = virtualTerminals.values.firstOrNull()
        systemConsole = console
        terminalType = driver.terminalType

        val registered = ArrayList<Device>(endpoints.size + 3)
        for ((endpoint, session) in endpointSessions) {
            val registration = DeviceRegistration(
                name = endpoint.name,
                type = DeviceType.CHARACTER,
                major = endpoint.major,
                minor = endpoint.minor,
                backend = session,
                sysfs = SysfsDevicePublication.virtual("tty", endpoint.name),
            )
            val device = DeviceManager.register(registration) ?: return rollbackInitialization(registered)
            registered += device
        }

        val aliases = buildList {
            if (virtualTerminals.isNotEmpty()) {
                add(DeviceRegistration(
                    name = "tty0",
                    type = DeviceType.CHARACTER,
                    major = LinuxDeviceMajor.TTY.number,
                    minor = 0u,
                    backend = ActiveTty,
                    sysfs = SysfsDevicePublication.virtual("tty", "tty0", attributes = listOf(activeAttribute)),
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
                backend = ConsoleTty(console),
                sysfs = SysfsDevicePublication.virtual(
                    "tty",
                    "console",
                    attributes = listOf(SysfsTextAttribute.constant("active", "${consoleEndpoint.name}\n")),
                ),
            ))
        }
        for (registration in aliases) {
            val device = DeviceManager.register(registration)
                ?: return rollbackInitialization(registered)
            registered += device
        }

        for ((endpoint, session) in endpointSessions) {
            if (session !== console && session !== activeVirtualTerminal?.session) continue
            if (!session.start()) {
                println("TTY: failed to start ${driver.consoleName}")
                return rollbackInitialization(registered)
            }
        }

        if (flushRequested != null) {
            KernelCoroutines.launch("terminal-flush") {
                while (isActive) {
                    flushRequested.await()
                    delay(FRAME_INTERVAL_MILLIS)
                    consoleLock.withLock {
                        activeVirtualTerminal?.session?.flushIfDirty()
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
