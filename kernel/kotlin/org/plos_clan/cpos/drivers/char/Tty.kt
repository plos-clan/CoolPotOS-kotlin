package org.plos_clan.cpos.drivers.char

import org.plos_clan.cpos.drivers.DEV_CHAR
import org.plos_clan.cpos.drivers.DEV_TTY
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.TermiosConstants
import org.plos_clan.cpos.utils.VTModeConstants

private const val NCCS = 19
private const val TERMIOS2_NCCS = NCCS

enum class TtyDeviceType {
    TTY_SERIAL_DEVICE,
    TTY_GRAPHY_DEVICE;

    override fun toString(): String {
        return if (this == TTY_GRAPHY_DEVICE) {
            "Graphics"
        } else {
            "Serial"
        }
    }
}

data class WinSize(
    var wsRow: Short,
    var wsCol: Short,
    var wsXpixel: Short,
    var wsYpixel: Short
)

class Termios(
    var cIflag: Int,    /* input mode flags */
    var cOflag: Int,    /* output mode flags */
    var cCflag: Int,    /* control mode flags */
    var cLflag: Int,    /* local mode flags */
    var cLine: Byte,    /* line discipline */
    var cCc: ByteArray  /* control characters */
) {
    init {
        require(cCc.size == NCCS) { "c_cc length must $NCCS" }
    }
}

class Termios2(
    var cIflag: Int,
    var cOflag: Int,
    var cCflag: Int,
    var cLflag: Int,
    var cLine: Byte,
    var cCc: ByteArray,
    var cIspeed: Int,   /* input speed */
    var cOspeed: Int    /* output speed */
) {
    init {
        require(cCc.size == TERMIOS2_NCCS) { "c_cc length must is $TERMIOS2_NCCS" }
    }
}

data class VtMode(
    val mode: Byte,     // 终端模式
    val waitvval: Byte, // 垂直同步
    val relsig: Short,  // 释放信号
    val acqsig: Short,  // 获取信号
    val frsig: Short    // 强制释放信号
)

class TtySession(
    val termios: Termios,
    val vtMode: VtMode,
    var ttyMode: Int,
    var ttyKbMode: Int,
    val backend: TtySessionBackend,
    val device: TtyDevice,
) : DeviceBackend {
    override fun ioctl(
        device: Device,
        command: Int,
        args: UserMemory
    ): Long = backend.ioctl(this, command, args).toLong()

    override fun poll(device: Device, events: Int): Long =
        backend.poll(this, events).toLong()

    override fun read(
        device: Device,
        buffer: ByteArray,
        offset: ULong,
        size: ULong
    ): Long = backend.read(this, buffer, size).toLong()

    override fun write(
        device: Device,
        buffer: ByteArray,
        offset: ULong,
        size: ULong
    ): Long = backend.write(this, buffer, size).toLong()

}

data class TtyDevice(val name: String, val device: TtyPhysicalDevice, val type: TtyDeviceType)

interface TtySessionBackend {
    fun write(session: TtySession, buffer: ByteArray, count: ULong): ULong
    fun read(session: TtySession, buffer: ByteArray, count: ULong): ULong
    fun flush(session: TtySession)
    fun ioctl(session: TtySession, command: Int, args: UserMemory): Int
    fun poll(session: TtySession, events: Int): Int
}

interface TtyPhysicalDevice {
    fun write(session: TtySession, buffer: ByteArray, count: ULong): ULong
    fun read(session: TtySession, buffer: ByteArray, count: ULong): ULong
    fun flush(session: TtySession)
    fun ioctl(session: TtySession, command: Int, args: UserMemory): Int
}

object TtyManager {
    val devices = mutableListOf<TtyDevice>()

    fun installTtyDevice(device: TtyDevice) {
        devices += device
        println("TTY: install ${device.name} type: ${device.type}")
    }

    private fun createDefaultTermios(): Termios {
        return Termios(
            TermiosConstants.BRKINT or TermiosConstants.ICRNL,
            TermiosConstants.OPOST,
            TermiosConstants.CS8 or TermiosConstants.CREAD or TermiosConstants.CLOCAL,
            TermiosConstants.ECHO or TermiosConstants.ICANON or TermiosConstants.IEXTEN or TermiosConstants.ISIG,
            0,
            ByteArray(19).apply {
                this[TermiosConstants.VINTR] = 3
                this[TermiosConstants.VQUIT] = 28
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

    fun initialize() {
        val devName = Cmdline["console"] ?: "fb0"

        val device = devices.find { it.name == devName } ?: return
        for (index in 0..6) {
            val session =
                TtySession(
                    createDefaultTermios(),
                    createDefaultVtMode(),
                    VTModeConstants.KD_TEXT,
                    VTModeConstants.K_XLATE,
                    TerminalSession(device.device as TtyGraphicsDevice),
                    device
                )
            DeviceManager.installDevice(
                DEV_CHAR,
                DEV_TTY,
                session,
                "tty$index",
                0UL,
                session
            )
        }
    }
}
