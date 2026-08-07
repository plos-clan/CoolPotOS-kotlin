@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.char

import bridge.TerminalDisplay
import bridge.TerminalPalette
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.TermiosConstants

private fun allocateTerminalMemory(size: ULong): COpaquePointer? = bridge.malloc(size)

private fun releaseTerminalMemory(pointer: COpaquePointer?) {
    bridge.free(pointer)
}

private val terminalMallocCallback = staticCFunction(::allocateTerminalMemory)
private val terminalFreeCallback = staticCFunction(::releaseTerminalMemory)

private val terminalAnsiColors = uintArrayOf(
    0x0d0d1au,
    0xe84a5fu,
    0x50fa7bu,
    0xfacc60u,
    0x61aeeeu,
    0xc074ecu,
    0x40e0d0u,
    0xbebec2u,
    0x2f2f38u,
    0xff6f91u,
    0x8affc1u,
    0xffe99bu,
    0x9ddfffu,
    0xd69fffu,
    0xb2ffffu,
    0xffffffu,
)

class TerminalSession(device: TtyGraphicsDevice) : TtySessionBackend {

    private val terminal: COpaquePointer? = memScoped {
        val display = alloc<TerminalDisplay> {
            width = device.width
            height = device.height
            buffer = device.address?.reinterpret()
            pitch = device.pitch
            red_mask_size = device.red_mask_size
            red_mask_shift = device.red_mask_shift
            green_mask_size = device.green_mask_size
            green_mask_shift = device.green_mask_shift
            blue_mask_size = device.blue_mask_size
            blue_mask_shift = device.blue_mask_shift
        }

        bridge.terminal_new(
            display = display.ptr,
            font_size_bits = 10.0f.toRawBits().toUInt(),
            malloc = terminalMallocCallback,
            free = terminalFreeCallback,
        )
    }.also { terminal ->
        if (terminal == null) {
            return@also
        }
        memScoped {
            val palette = alloc<TerminalPalette> {
                background = 0x0d0d1au
                foreground = 0xeaeaeau
                terminalAnsiColors.forEachIndexed { index, color ->
                    ansi_colors[index] = color
                }
            }
            bridge.terminal_set_custom_color_scheme(terminal, palette.ptr)
            bridge.terminal_set_crnl_mapping(terminal, true)
        }
    }

    private val lock = IrqSpinLock()
    private val inputLock = IrqSpinLock()
    private val inputData = ByteArray(INPUT_BUFFER_SIZE)
    private val canonicalData = ByteArray(INPUT_BUFFER_SIZE)
    private val canonicalRecords = IntArray(MAX_CANONICAL_RECORDS)
    private var inputHead = 0
    private var inputTail = 0
    private var inputCount = 0
    private var canonicalCount = 0
    private var canonicalRecordHead = 0
    private var canonicalRecordTail = 0
    private var canonicalRecordCount = 0

    override fun keyboardInput(session: TtySession, data: CharArray) {
        if (data.isEmpty()) {
            return
        }

        inputLock.withLock {
            data.forEach { character ->
                receiveInputLocked(session, character.code and 0xFF)
            }
        }
    }

    override fun write(
        session: TtySession,
        buffer: ByteArray,
        count: ULong
    ): ULong {
        val length = minOf(count, buffer.size.toULong()).toInt()
        lock.withLock {
            bridge.terminal_process(terminal, buffer.decodeToString(0, length))
        }
        return count
    }

    override fun read(
        session: TtySession,
        buffer: ByteArray,
        count: ULong
    ): ULong {
        val limit = minOf(count, buffer.size.toULong()).toInt()
        if (limit == 0) {
            return 0uL
        }

        return if (session.hasLocalFlag(TermiosConstants.ICANON)) {
            readCanonical(buffer, limit).toULong()
        } else {
            readNonCanonical(session, buffer, limit).toULong()
        }
    }

    override fun flush(session: TtySession) {
        lock.withLock {
            bridge.terminal_flush(terminal)
        }
    }

    override fun ioctl(
        session: TtySession,
        command: Int,
        args: UserMemory
    ): Int = when (command) {
        IoctlConstants.TIOCGWINSZ -> {
            val rows = 61UL// bridge.terminal_rows(terminal)
            val columns = 116UL// bridge.terminal_columns(terminal)

            val size = WinSize(
                wsRow = minOf(rows, UShort.MAX_VALUE.toULong()).toShort(),
                wsCol = minOf(columns, UShort.MAX_VALUE.toULong()).toShort(),
                wsXpixel = 0,
                wsYpixel = 0,
            )

            if (args.copyToUser(size.toNativeBytes())) {
                Errno.EOK
            } else {
                -Errno.EFAULT
            }
        }

        IoctlConstants.TIOCSCTTY ->
            if (session.attachCurrentProcess()) Errno.EOK else -Errno.ENOTTY

        IoctlConstants.TIOCGPGRP ->
            copyIntToUser(args, session.foregroundProcessGroup)

        IoctlConstants.TIOCSPGRP -> {
            val processGroup = copyIntFromUser(args)
            when {
                processGroup == null -> -Errno.EFAULT
                !session.attachCurrentProcess() -> -Errno.ENOTTY
                !session.setForegroundProcessGroup(processGroup) -> -Errno.EINVAL
                else -> Errno.EOK
            }
        }

        IoctlConstants.TIOCGSID -> {
            val sessionId = session.sessionId
            if (sessionId == 0) -Errno.ENOTTY else copyIntToUser(args, sessionId)
        }

        IoctlConstants.TIOCNOTTY ->
            if (session.detachCurrentProcess()) Errno.EOK else -Errno.ENOTTY

        IoctlConstants.TCGETS ->
            if (args.copyToUser(session.termios.toNativeBytes())) {
                Errno.EOK
            } else {
                -Errno.EFAULT
            }

        IoctlConstants.TCGETS2.toInt() ->
            if (args.copyToUser(session.termios2.toNativeBytes())) {
                Errno.EOK
            } else {
                -Errno.EFAULT
            }

        IoctlConstants.TCSETS,
        IoctlConstants.TCSETSW -> updateTermiosFromUser(session, args)

        IoctlConstants.TCSETSF -> {
            val result = updateTermiosFromUser(session, args)
            if (result == Errno.EOK) {
                flushInput()
            }
            result
        }

        IoctlConstants.TCSETS2,
        IoctlConstants.TCSETSW2 -> updateTermios2FromUser(session, args)

        IoctlConstants.TCSETSF2 -> {
            val result = updateTermios2FromUser(session, args)
            if (result == Errno.EOK) {
                flushInput()
            }
            result
        }

        IoctlConstants.TCFLSH -> {
            flushInput()
            Errno.EOK
        }

        IoctlConstants.FIONREAD -> copyIntToUser(args, availableInputBytes())

        IoctlConstants.TIOCSWINSZ,
        IoctlConstants.TCSBRK,
        IoctlConstants.TCSBRKP,
        IoctlConstants.TIOCNXCL -> Errno.EOK

        else -> {
            println("warn: no implement tty ioctl 0x${command.toString(16)}")
            -Errno.ENOTTY
        }
    }

    override fun poll(
        session: TtySession,
        events: Int
    ): Int {
        var returned = events and PollEvents.NORMAL_OUTPUT
        val readable = inputLock.withLock {
            if (session.hasLocalFlag(TermiosConstants.ICANON)) {
                canonicalRecordCount != 0
            } else {
                inputCount != 0
            }
        }
        if (readable && events and PollEvents.NORMAL_INPUT != 0) {
            returned = returned or PollEvents.NORMAL_INPUT
        }
        return returned
    }

    private fun receiveInputLocked(session: TtySession, rawValue: Int) {
        var value = rawValue
        if (session.hasInputFlag(TermiosConstants.ISTRIP)) {
            value = value and 0x7F
        }
        if (value == '\r'.code) {
            if (session.hasInputFlag(TermiosConstants.IGNCR)) {
                return
            }
            if (session.hasInputFlag(TermiosConstants.ICRNL)) {
                value = '\n'.code
            }
        } else if (value == '\n'.code && session.hasInputFlag(TermiosConstants.INLCR)) {
            value = '\r'.code
        }

        if (!session.hasLocalFlag(TermiosConstants.ICANON)) {
            enqueueInputLocked(value.toByte())
            echoCharacter(session, value)
            return
        }

        val erase = session.controlCharacter(TermiosConstants.VERASE)
        if (value == 0x7F || erase != 0 && value == erase) {
            if (canonicalCount != 0) {
                canonicalCount--
                echoErase(session)
            }
            return
        }

        val kill = session.controlCharacter(TermiosConstants.VKILL)
        if (kill != 0 && value == kill) {
            while (canonicalCount != 0) {
                canonicalCount--
                echoErase(session)
            }
            if (session.hasLocalFlag(TermiosConstants.ECHO) &&
                session.hasLocalFlag(TermiosConstants.ECHOK)
            ) {
                echoText(NEWLINE_TEXT)
            }
            return
        }

        val eof = session.controlCharacter(TermiosConstants.VEOF)
        if (eof != 0 && value == eof) {
            commitCanonicalLocked(eof = true)
            return
        }

        val lineEnd = session.isCanonicalLineEnd(value)
        if (canonicalCount < canonicalData.lastIndex || lineEnd) {
            canonicalData[canonicalCount++] = value.toByte()
        }
        echoCharacter(session, value)

        if (lineEnd) {
            commitCanonicalLocked(eof = false)
        }
    }

    private fun commitCanonicalLocked(eof: Boolean) {
        if (canonicalCount == 0) {
            if (eof) {
                enqueueCanonicalRecordLocked(0)
            }
            return
        }

        if (canonicalCount <= inputData.size - inputCount &&
            canonicalRecordCount < canonicalRecords.size
        ) {
            repeat(canonicalCount) { index ->
                enqueueInputLocked(canonicalData[index])
            }
            enqueueCanonicalRecordLocked(canonicalCount)
        }
        canonicalCount = 0
    }

    private fun readCanonical(buffer: ByteArray, limit: Int): Int {
        while (true) {
            val result = inputLock.withLock {
                if (canonicalRecordCount == 0) {
                    null
                } else {
                    val recordLength = dequeueCanonicalRecordLocked()
                    if (recordLength == 0) {
                        0
                    } else {
                        val transferred = minOf(limit, recordLength)
                        dequeueInputLocked(buffer, 0, transferred)
                        if (transferred < recordLength) {
                            prependCanonicalRecordLocked(recordLength - transferred)
                        }
                        transferred
                    }
                }
            }
            if (result != null) {
                return result
            }
            waitForInterrupt()
        }
    }

    private fun readNonCanonical(session: TtySession, buffer: ByteArray, limit: Int): Int {
        val minimum = minOf(session.controlCharacter(TermiosConstants.VMIN), limit)
        val timeout = session.controlCharacter(TermiosConstants.VTIME)

        if (minimum == 0 && timeout == 0) {
            return drainAvailable(buffer, 0, limit)
        }

        if (minimum == 0) {
            val deadline = timeoutDeadline(timeout)
            while (!hasInput()) {
                if (deadlineReached(deadline)) {
                    return 0
                }
                waitForInterrupt()
            }
            return drainAvailable(buffer, 0, limit)
        }

        var transferred = 0
        while (transferred == 0) {
            transferred += drainAvailable(buffer, transferred, limit - transferred)
            if (transferred == 0) {
                waitForInterrupt()
            }
        }

        if (timeout == 0) {
            while (transferred < minimum) {
                val current = drainAvailable(buffer, transferred, limit - transferred)
                transferred += current
                if (transferred < minimum) {
                    waitForInterrupt()
                }
            }
            return transferred
        }

        var deadline = timeoutDeadline(timeout)
        while (transferred < minimum) {
            val current = drainAvailable(buffer, transferred, limit - transferred)
            if (current != 0) {
                transferred += current
                deadline = timeoutDeadline(timeout)
            } else {
                if (deadlineReached(deadline)) {
                    break
                }
                waitForInterrupt()
            }
        }
        return transferred
    }

    private fun drainAvailable(buffer: ByteArray, offset: Int, limit: Int): Int =
        inputLock.withLock {
            dequeueInputLocked(buffer, offset, minOf(limit, inputCount))
        }

    private fun enqueueInputLocked(value: Byte): Boolean {
        if (inputCount == inputData.size) {
            return false
        }
        inputData[inputTail] = value
        inputTail = (inputTail + 1) % inputData.size
        inputCount++
        return true
    }

    private fun dequeueInputLocked(destination: ByteArray, offset: Int, length: Int): Int {
        val transferred = minOf(length, inputCount)
        repeat(transferred) { index ->
            destination[offset + index] = inputData[inputHead]
            inputHead = (inputHead + 1) % inputData.size
        }
        inputCount -= transferred
        return transferred
    }

    private fun enqueueCanonicalRecordLocked(length: Int): Boolean {
        if (canonicalRecordCount == canonicalRecords.size) {
            return false
        }
        canonicalRecords[canonicalRecordTail] = length
        canonicalRecordTail = (canonicalRecordTail + 1) % canonicalRecords.size
        canonicalRecordCount++
        return true
    }

    private fun dequeueCanonicalRecordLocked(): Int {
        val length = canonicalRecords[canonicalRecordHead]
        canonicalRecordHead = (canonicalRecordHead + 1) % canonicalRecords.size
        canonicalRecordCount--
        return length
    }

    private fun prependCanonicalRecordLocked(length: Int) {
        canonicalRecordHead =
            (canonicalRecordHead + canonicalRecords.size - 1) % canonicalRecords.size
        canonicalRecords[canonicalRecordHead] = length
        canonicalRecordCount++
    }

    private fun hasInput(): Boolean = inputLock.withLock { inputCount != 0 }

    private fun availableInputBytes(): Int = inputLock.withLock { inputCount }

    private fun flushInput() {
        inputLock.withLock {
            inputHead = 0
            inputTail = 0
            inputCount = 0
            canonicalCount = 0
            canonicalRecordHead = 0
            canonicalRecordTail = 0
            canonicalRecordCount = 0
        }
    }

    private fun updateTermiosFromUser(session: TtySession, args: UserMemory): Int {
        val data = args.copyFromUser(Termios.NATIVE_SIZE)
        return if (data != null && session.termios.updateFromNativeBytes(data)) {
            Errno.EOK
        } else {
            -Errno.EFAULT
        }
    }

    private fun updateTermios2FromUser(session: TtySession, args: UserMemory): Int {
        val data = args.copyFromUser(Termios2.NATIVE_SIZE)
        if (data == null || !session.termios2.updateFromNativeBytes(data)) {
            return -Errno.EFAULT
        }
        session.termios.cIflag = session.termios2.cIflag
        session.termios.cOflag = session.termios2.cOflag
        session.termios.cCflag = session.termios2.cCflag
        session.termios.cLflag = session.termios2.cLflag
        session.termios.cLine = session.termios2.cLine
        session.termios.cCc = session.termios2.cCc.copyOf()
        return Errno.EOK
    }

    private fun copyIntFromUser(args: UserMemory): Int? {
        val data = args.copyFromUser(Int.SIZE_BYTES) ?: return null
        return data[0].toUByte().toInt() or
            (data[1].toUByte().toInt() shl 8) or
            (data[2].toUByte().toInt() shl 16) or
            (data[3].toUByte().toInt() shl 24)
    }

    private fun copyIntToUser(args: UserMemory, value: Int): Int {
        val data = ByteArray(Int.SIZE_BYTES) { index ->
            (value ushr (index * Byte.SIZE_BITS)).toByte()
        }
        return if (args.copyToUser(data)) Errno.EOK else -Errno.EFAULT
    }

    private fun echoCharacter(session: TtySession, value: Int) {
        if (session.hasLocalFlag(TermiosConstants.ECHO) ||
            value == '\n'.code && session.hasLocalFlag(TermiosConstants.ECHONL)
        ) {
            echoText(ASCII_TEXT[value and 0x7F])
        }
    }

    private fun echoErase(session: TtySession) {
        if (session.hasLocalFlag(TermiosConstants.ECHO) &&
            session.hasLocalFlag(TermiosConstants.ECHOE)
        ) {
            echoText(ERASE_TEXT)
        }
    }

    private fun echoText(text: String) {
        lock.withLock {
            bridge.terminal_process(terminal, text)
        }
    }

    private fun timeoutDeadline(deciseconds: Int): ULong =
        Hpet.nanoTime() + deciseconds.toULong() * NANOSECONDS_PER_DECISECOND

    private fun deadlineReached(deadline: ULong): Boolean =
        Hpet.isReady && Hpet.nanoTime() >= deadline

    private fun waitForInterrupt() {
        bridge.fast_handoff_yield()
        val flags = bridge.irq_save()
        bridge.enable_interrupt()
        bridge.wait_for_interrupt()
        bridge.irq_restore(flags)
    }

    private fun TtySession.hasInputFlag(flag: Int): Boolean =
        termios.cIflag and flag != 0

    private fun TtySession.hasLocalFlag(flag: Int): Boolean =
        termios.cLflag and flag != 0

    private fun TtySession.controlCharacter(index: Int): Int =
        termios.cCc.getOrNull(index)?.toUByte()?.toInt() ?: 0

    private fun TtySession.isCanonicalLineEnd(value: Int): Boolean {
        if (value == '\n'.code) {
            return true
        }
        val endOfLine = controlCharacter(TermiosConstants.VEOL)
        val secondEndOfLine = controlCharacter(TermiosConstants.VEOL2)
        return endOfLine != 0 && value == endOfLine ||
            secondEndOfLine != 0 && value == secondEndOfLine
    }

    private companion object {
        const val INPUT_BUFFER_SIZE = 4096
        const val MAX_CANONICAL_RECORDS = 1024
        const val NANOSECONDS_PER_DECISECOND = 100_000_000uL

        const val NEWLINE_TEXT = "\n"
        const val ERASE_TEXT = "\b \b"
        val ASCII_TEXT = Array(128) { code -> code.toChar().toString() }
    }

}
