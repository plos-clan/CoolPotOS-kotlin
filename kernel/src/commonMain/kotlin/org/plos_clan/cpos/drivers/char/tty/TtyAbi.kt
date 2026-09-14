package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.NativeStruct
import org.plos_clan.cpos.utils.TermiosConstants

private const val NCCS = 19

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
    const val TIOCGPTPEER = 0x5441
}

data class WinSize(
    var wsRow: Short,
    var wsCol: Short,
    var wsXpixel: Short,
    var wsYpixel: Short
) : NativeStruct {
    override fun toNativeBytes(): ByteArray =
        ByteArray(8).also { buffer ->
            LittleEndianBuffer(buffer).apply {
                writeU16(0, wsRow.toUShort())
                writeU16(2, wsCol.toUShort())
                writeU16(4, wsXpixel.toUShort())
                writeU16(6, wsYpixel.toUShort())
            }
        }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        if (buffer.size != 8) {
            return false
        }
        val input = LittleEndianBuffer(buffer)
        wsRow = input.readU16(0).toShort()
        wsCol = input.readU16(2).toShort()
        wsXpixel = input.readU16(4).toShort()
        wsYpixel = input.readU16(6).toShort()
        return true
    }
}

open class Termios(
    var cIflag: Int,    /* input mode flags */
    var cOflag: Int,    /* output mode flags */
    var cCflag: Int,    /* control mode flags */
    var cLflag: Int,    /* local mode flags */
    var cLine: Byte,    /* line discipline */
    var cCc: ByteArray  /* control characters */
) : NativeStruct {
    init {
        require(cCc.size == NCCS) { "c_cc length must $NCCS" }
    }

    override fun toNativeBytes(): ByteArray {
        require(cCc.size == NCCS) { "c_cc length must $NCCS" }

        return ByteArray(NATIVE_SIZE).also { buffer ->
            LittleEndianBuffer(buffer).apply {
                writeU32(0, cIflag.toUInt())
                writeU32(4, cOflag.toUInt())
                writeU32(8, cCflag.toUInt())
                writeU32(12, cLflag.toUInt())
            }
            buffer[LINE_OFFSET] = cLine
            cCc.copyInto(buffer, destinationOffset = CONTROL_CHARACTERS_OFFSET)
        }
    }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        if (buffer.size != NATIVE_SIZE) {
            return false
        }

        val input = LittleEndianBuffer(buffer)
        val inputFlags = input.readU32(0).toInt()
        val outputFlags = input.readU32(4).toInt()
        val controlFlags = input.readU32(8).toInt()
        val localFlags = input.readU32(12).toInt()
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

    fun character(index: Int): Int = cCc[index].toInt() and 0xFF

    fun matches(index: Int, value: Int): Boolean = value != 0 && character(index) == value

    companion object {
        fun defaults() = Termios(
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

        private const val LINE_OFFSET = 16
        private const val CONTROL_CHARACTERS_OFFSET = 17
        const val NATIVE_SIZE = CONTROL_CHARACTERS_OFFSET + NCCS
    }
}

class Termios2(
    cIflag: Int,
    cOflag: Int,
    cCflag: Int,
    cLflag: Int,
    cLine: Byte,
    cCc: ByteArray,
    var cIspeed: Int,
    var cOspeed: Int,
) : Termios(cIflag, cOflag, cCflag, cLflag, cLine, cCc) {
    constructor(termios: Termios, inputSpeed: Int = 0, outputSpeed: Int = inputSpeed) : this(
        termios.cIflag, termios.cOflag, termios.cCflag, termios.cLflag,
        termios.cLine, termios.cCc.copyOf(), inputSpeed, outputSpeed,
    )

    override fun toNativeBytes(): ByteArray = super.toNativeBytes().copyOf(NATIVE_SIZE).also {
        LittleEndianBuffer(it).apply {
            writeU32(Termios.NATIVE_SIZE, cIspeed.toUInt())
            writeU32(Termios.NATIVE_SIZE + Int.SIZE_BYTES, cOspeed.toUInt())
        }
    }

    override fun updateFromNativeBytes(buffer: ByteArray): Boolean {
        if (buffer.size != NATIVE_SIZE) return false
        super.updateFromNativeBytes(buffer.copyOf(Termios.NATIVE_SIZE))
        val input = LittleEndianBuffer(buffer)
        cIspeed = input.readU32(Termios.NATIVE_SIZE).toInt()
        cOspeed = input.readU32(Termios.NATIVE_SIZE + Int.SIZE_BYTES).toInt()
        return true
    }

    companion object {
        const val NATIVE_SIZE = Termios.NATIVE_SIZE + 2 * Int.SIZE_BYTES
    }
}
