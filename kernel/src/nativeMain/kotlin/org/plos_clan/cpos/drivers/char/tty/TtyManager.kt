package org.plos_clan.cpos.drivers.char.tty

import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.drivers.char.TerminalSession
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.TermiosConstants
import org.plos_clan.cpos.utils.VTModeConstants

object TtyManager {
    private const val FRAME_INTERVAL_MILLIS = 1_000L / 60L

    val devices = mutableListOf<TtyDevice>()
    val vts = ArrayList<TtySession>()

    private var activeVT = 0

    fun setActiveVT(index: Int): Boolean {
        if (index !in vts.indices) {
            return false
        }
        activeVT = index
        return true
    }

    fun getActiveVT(): TtySession = vts[activeVT]

    fun attachProcessToVT(index: Int, process: Process): Boolean {
        val session = vts.getOrNull(index) ?: return false
        return session.attach(process)
    }

    fun processTerminal(process: Process): ProcessTerminal? {
        val session = vts.firstOrNull { it.sessionId == process.sessionId } ?: return null
        val device = DeviceManager.findByBackend(session) ?: return null
        return ProcessTerminal(device.number.value, session.foregroundProcessGroup)
    }

    fun installTtyDevice(device: TtyDevice) {
        devices += device
        println("TTY: install ${device.name} type: ${device.type}")
    }

    private fun createDefaultTermios(): Termios {
        return Termios(
            TermiosConstants.BRKINT or TermiosConstants.ICRNL or
                    TermiosConstants.INPCK or TermiosConstants.ISTRIP or TermiosConstants.IXON,
            TermiosConstants.OPOST,
            TermiosConstants.CS8 or TermiosConstants.CREAD or TermiosConstants.CLOCAL,
            TermiosConstants.ECHO or TermiosConstants.ICANON or TermiosConstants.IEXTEN or TermiosConstants.ISIG,
            0,
            ByteArray(19).apply {
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
            }
        )
    }

    private fun createDefaultVtMode(): VtMode {
        return VtMode(
            0, 0, 0, 0, 0
        )
    }

    fun initialize(): Boolean {
        val devName = Cmdline["console"] ?: "fb0"
        val device = devices.find { it.name == devName } ?: return false
        val graphics = device.device as? TtyGraphicsDevice ?: return false
        val flushRequested = KernelCoroutines.dispatcher.createEvent()
        val invalidate = flushRequested::signal
        val registered = mutableListOf<Device>()
        for (index in 0..6) {
            val backend = TerminalSession.create(graphics, invalidate)
                ?: return rollbackInitialization(registered)
            val session = TtySession(
                createDefaultTermios(),
                createDefaultVtMode(),
                VTModeConstants.KD_TEXT,
                VTModeConstants.K_XLATE,
                backend,
                device,
            )
            vts += session
            val tty = DeviceManager.register(
                DeviceRegistration(
                    name = "tty$index",
                    type = DeviceType.CHARACTER,
                    major = LinuxDeviceMajor.VIRTUAL_TERMINAL.number,
                    minor = index.toUInt(),
                    backend = session,
                    sysfs = SysfsDevicePublication.virtual("tty", "tty$index"),
                ),
            )
            if (tty == null) {
                return rollbackInitialization(registered)
            }
            registered += tty
        }

        val console = vts.first()
        val controllingTty = DeviceManager.register(
            DeviceRegistration(
                name = "tty",
                type = DeviceType.CHARACTER,
                major = LinuxDeviceMajor.TTY_AUXILIARY.number,
                minor = 0u,
                backend = ControllingTty,
                sysfs = SysfsDevicePublication.virtual("tty", "tty"),
            ),
        )
        if (controllingTty == null) {
            return rollbackInitialization(registered)
        }
        registered += controllingTty

        val systemConsole = DeviceManager.register(
            DeviceRegistration(
                name = "console",
                type = DeviceType.CHARACTER,
                major = LinuxDeviceMajor.TTY_AUXILIARY.number,
                minor = 1u,
                backend = console,
                sysfs = SysfsDevicePublication.virtual("tty", "console"),
            ),
        )
        if (systemConsole == null) {
            return rollbackInitialization(registered)
        }
        KernelCoroutines.launch("terminal-flush") {
            while (isActive) {
                flushRequested.await()
                delay(FRAME_INTERVAL_MILLIS)
                vts.forEach(TtySession::flushIfDirty)
            }
        }
        return true
    }

    private fun rollbackInitialization(registered: List<Device>): Boolean {
        registered.asReversed().forEach(DeviceManager::unregister)
        vts.asReversed().forEach(TtySession::destroy)
        vts.clear()
        activeVT = 0
        return false
    }
}
