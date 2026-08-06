package org.plos_clan.cpos.drivers.char

import org.plos_clan.cpos.drivers.DEV_CHAR
import org.plos_clan.cpos.drivers.DEV_TTY
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Cmdline
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.TermiosConstants
import org.plos_clan.cpos.utils.VTModeConstants

private const val NCCS = 19
private const val TERMIOS2_NCCS = NCCS

object IoctlConstants {
    const val TCGETS = 0x5401
    const val TCSETS = 0x5402
    const val TCSETSW = 0x5403
    const val TCSETSF = 0x5404

    const val TCGETA = 0x5405
    const val TCSETA = 0x5406
    const val TCSETAW = 0x5407
    const val TCSETAF = 0x5408

    const val TCSBRK = 0x5409
    const val TCXONC = 0x540A
    const val TCFLSH = 0x540B

    const val TIOCEXCL = 0x540C
    const val TIOCNXCL = 0x540D
    const val TIOCSCTTY = 0x540E

    const val TIOCGPGRP = 0x540F
    const val TIOCSPGRP = 0x5410

    const val TIOCOUTQ = 0x5411
    const val TIOCSTI = 0x5412

    const val TIOCGWINSZ = 0x5413
    const val TIOCSWINSZ = 0x5414

    const val TIOCMGET = 0x5415
    const val TIOCMBIS = 0x5416
    const val TIOCMBIC = 0x5417
    const val TIOCMSET = 0x5418

    const val TIOCGSOFTCAR = 0x5419
    const val TIOCSSOFTCAR = 0x541A

    const val FIONREAD = 0x541B
    const val TIOCINQ = FIONREAD

    const val TIOCLINUX = 0x541C
    const val TIOCCONS = 0x541D

    const val TIOCGSERIAL = 0x541E
    const val TIOCSSERIAL = 0x541F

    const val TIOCPKT = 0x5420
    const val FIONBIO = 0x5421

    const val TIOCNOTTY = 0x5422

    const val TIOCSETD = 0x5423
    const val TIOCGETD = 0x5424

    const val TCSBRKP = 0x5425

    const val TIOCSBRK = 0x5427
    const val TIOCCBRK = 0x5428

    const val TIOCGSID = 0x5429

    const val TCGETS2 = 0x802C542A

    const val TCSETS2 = 0x402C542B

    const val TCSETSW2 = 0x402C542C

    const val TCSETSF2 = 0x402C542D

    const val TIOCGRS485 = 0x542E
    const val TIOCSRS485 = 0x542F

    const val TIOCGPTN = 0x80045430
    const val TIOCSPTLCK = 0x40045431
    const val TIOCGDEV = 0x80045432

    const val TCGETX = 0x5432
    const val TCSETX = 0x5433
    const val TCSETXF = 0x5434
    const val TCSETXW = 0x5435

    const val TIOCSIG = 0x40045436

    const val TIOCVHANGUP = 0x5437

    const val TIOCGPKT = 0x80045438
    const val TIOCGPTLCK = 0x80045439

    const val TIOCGEXCL = 0x80045440
}

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
) : NativeStruct() {
    override fun toNativeBytes(): ByteArray =
        ByteArray(8).also { buffer ->
            putU16LE(buffer, 0, wsRow)
            putU16LE(buffer, 2, wsCol)
            putU16LE(buffer, 4, wsXpixel)
            putU16LE(buffer, 6, wsYpixel)
        }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        TODO("Not yet implemented")
    }
}

class Termios(
    var cIflag: Int,    /* input mode flags */
    var cOflag: Int,    /* output mode flags */
    var cCflag: Int,    /* control mode flags */
    var cLflag: Int,    /* local mode flags */
    var cLine: Byte,    /* line discipline */
    var cCc: ByteArray  /* control characters */
) : NativeStruct() {
    init {
        require(cCc.size == NCCS) { "c_cc length must $NCCS" }
    }

    override fun toNativeBytes(): ByteArray {
        require(cCc.size == NCCS) { "c_cc length must $NCCS" }

        return ByteArray(NATIVE_SIZE).also { buffer ->
            putU32LE(buffer, 0, cIflag)
            putU32LE(buffer, 4, cOflag)
            putU32LE(buffer, 8, cCflag)
            putU32LE(buffer, 12, cLflag)
            buffer[LINE_OFFSET] = cLine
            cCc.copyInto(buffer, destinationOffset = CONTROL_CHARACTERS_OFFSET)
        }
    }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        if (buffer.size != NATIVE_SIZE) {
            return false
        }

        val inputFlags = getU32LE(buffer, 0)
        val outputFlags = getU32LE(buffer, 4)
        val controlFlags = getU32LE(buffer, 8)
        val localFlags = getU32LE(buffer, 12)
        val line = buffer[LINE_OFFSET]
        val controlCharacters = buffer.copyOfRange(
            CONTROL_CHARACTERS_OFFSET,
            NATIVE_SIZE,
        )

        cIflag = inputFlags
        cOflag = outputFlags
        cCflag = controlFlags
        cLflag = localFlags
        cLine = line
        cCc = controlCharacters
        return true
    }

    companion object {
        private const val LINE_OFFSET = 16
        private const val CONTROL_CHARACTERS_OFFSET = 17
        const val NATIVE_SIZE = CONTROL_CHARACTERS_OFFSET + NCCS
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
) : NativeStruct() {
    init {
        require(cCc.size == TERMIOS2_NCCS) { "c_cc length must be $TERMIOS2_NCCS" }
    }

    override fun toNativeBytes(): ByteArray {
        require(cCc.size == TERMIOS2_NCCS) { "c_cc length must be $TERMIOS2_NCCS" }

        return ByteArray(NATIVE_SIZE).also { buffer ->
            putU32LE(buffer, 0, cIflag)
            putU32LE(buffer, 4, cOflag)
            putU32LE(buffer, 8, cCflag)
            putU32LE(buffer, 12, cLflag)
            buffer[LINE_OFFSET] = cLine
            cCc.copyInto(buffer, destinationOffset = CONTROL_CHARACTERS_OFFSET)
            putU32LE(buffer, INPUT_SPEED_OFFSET, cIspeed)
            putU32LE(buffer, OUTPUT_SPEED_OFFSET, cOspeed)
        }
    }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        TODO("Not yet implemented")
    }

    private companion object {
        const val LINE_OFFSET = 16
        const val CONTROL_CHARACTERS_OFFSET = 17
        const val INPUT_SPEED_OFFSET = CONTROL_CHARACTERS_OFFSET + TERMIOS2_NCCS
        const val OUTPUT_SPEED_OFFSET = INPUT_SPEED_OFFSET + Int.SIZE_BYTES
        const val NATIVE_SIZE = OUTPUT_SPEED_OFFSET + Int.SIZE_BYTES
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

    fun keyboardInput(data: CharArray) = backend.keyboardInput(this, data)

    fun attach(process: Process) {
        stateLock.withLock {
            controllingSessionId = process.id
            foregroundProcessGroupId = process.id
        }
    }

    fun attachCurrentProcess(): Boolean {
        val process = ProcessManager.currentProcess() ?: return false
        attach(process)
        return true
    }

    fun setForegroundProcessGroup(processGroup: Int): Boolean {
        if (processGroup <= 0) {
            return false
        }
        stateLock.withLock {
            foregroundProcessGroupId = processGroup
        }
        return true
    }

    fun detachCurrentProcess(): Boolean {
        val process = ProcessManager.currentProcess() ?: return false
        return stateLock.withLock {
            if (controllingSessionId != process.id) {
                return@withLock false
            }
            controllingSessionId = 0
            foregroundProcessGroupId = 0
            true
        }
    }
}

data class TtyDevice(val name: String, val device: TtyPhysicalDevice, val type: TtyDeviceType)

interface TtySessionBackend {
    fun keyboardInput(session: TtySession, data: CharArray)
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
        session.attach(process)
        return true
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
            vts += session
            DeviceManager.installDevice(
                DEV_CHAR,
                DEV_TTY,
                session,
                "tty$index",
                0UL,
                session
            )
        }

        vts.firstOrNull()?.let { console ->
            DeviceManager.installDevice(
                DEV_CHAR,
                DEV_TTY,
                console,
                "tty",
                0uL,
                console,
            )
        }
    }
}
