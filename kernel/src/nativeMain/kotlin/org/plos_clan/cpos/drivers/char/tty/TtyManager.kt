package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.drivers.char.TerminalSession
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.TermiosConstants
import org.plos_clan.cpos.utils.VTModeConstants

object TtyManager {
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

    fun setActiveTV(index: Int): Boolean = setActiveVT(index)

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
        val registered = mutableListOf<Device>()
        for (index in 0..6) {
            val session = TtySession(
                createDefaultTermios(),
                createDefaultVtMode(),
                VTModeConstants.KD_TEXT,
                VTModeConstants.K_XLATE,
                TerminalSession(device.device as TtyGraphicsDevice),
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
                ),
            )
            if (tty == null) {
                registered.forEach(DeviceManager::unregister)
                vts.clear()
                return false
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
            ),
        )
        if (controllingTty == null) {
            registered.forEach(DeviceManager::unregister)
            vts.clear()
            return false
        }
        registered += controllingTty

        val systemConsole = DeviceManager.register(
            DeviceRegistration(
                name = "console",
                type = DeviceType.CHARACTER,
                major = LinuxDeviceMajor.TTY_AUXILIARY.number,
                minor = 1u,
                backend = console,
            ),
        )
        if (systemConsole == null) {
            registered.forEach(DeviceManager::unregister)
            vts.clear()
            return false
        }
        return true
    }
}
