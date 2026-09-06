package org.plos_clan.cpos.drivers.char

import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.drivers.char.terminal.NativeTerminal
import org.plos_clan.cpos.drivers.char.terminal.TerminalInput
import org.plos_clan.cpos.drivers.char.tty.ConsoleDisplayMode
import org.plos_clan.cpos.drivers.char.tty.IoctlConstants
import org.plos_clan.cpos.drivers.char.tty.Termios
import org.plos_clan.cpos.drivers.char.tty.Termios2
import org.plos_clan.cpos.drivers.char.tty.TtySession
import org.plos_clan.cpos.drivers.char.tty.TtySessionBackend
import org.plos_clan.cpos.drivers.char.tty.WinSize
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.TermiosConstants

abstract class TerminalBackend : TtySessionBackend {
    internal val input = TerminalInput(::echo)
    protected val outputLock = KernelMutex()
    private val transferBuffer = ByteArray(OUTPUT_CHUNK_SIZE)
    private val processedOutput = ByteArray(OUTPUT_CHUNK_SIZE * 2)
    private var outputColumn = 0

    final override fun receiveInput(
        session: TtySession,
        data: ByteArray,
        offset: Int,
        count: Int,
    ) = input.receive(session, data, offset, count)

    final override fun write(
        session: TtySession,
        buffer: PreparedBufferSource,
        offset: Int,
        count: ULong,
    ): Long {
        if (offset < 0 || count > Int.MAX_VALUE.toULong()) return -Errno.EINVAL.toLong()
        val requested = count.toInt()
        return outputLock.withLock {
            var transferred = 0
            while (transferred < requested) {
                val chunkSize = minOf(requested - transferred, transferBuffer.size)
                val copied = buffer.copyTo(offset + transferred, transferBuffer, 0, chunkSize)
                if (copied == 0) {
                    return@withLock if (transferred == 0) {
                        -Errno.EFAULT.toLong()
                    } else {
                        transferred.toLong()
                    }
                }
                processOutput(session, transferBuffer, 0, copied)
                transferred += copied
                if (copied < chunkSize) break
            }
            transferred.toLong()
        }
    }

    final override fun read(
        file: TtySession.OpenFile,
        buffer: PreparedBufferDestination,
        offset: Int,
        count: ULong,
    ): Long = input.read(file, buffer, offset, count)

    final override fun ioctl(
        file: TtySession.OpenFile,
        command: Int,
        args: UserMemory,
    ): Int {
        consoleIoctl(file, command, args)?.let { return it }
        return file.control(command) {
            val session = file.session
            when (command) {
                IoctlConstants.TIOCGWINSZ ->
                    if (args.copyToUser(windowSize().toNativeBytes())) Errno.EOK else -Errno.EFAULT

                IoctlConstants.TIOCSCTTY ->
                    if (session.attachCurrentProcess()) Errno.EOK else -Errno.ENOTTY

                IoctlConstants.TIOCGPGRP ->
                    copyIntToUser(args, session.foregroundProcessGroup)

                IoctlConstants.TIOCSPGRP -> {
                    val processGroup = args.readUIntLE()?.toInt()
                    val process = ProcessManager.currentProcess()
                    when {
                        processGroup == null -> -Errno.EFAULT
                        process == null -> -Errno.ESRCH
                        session.sessionId != process.sessionId -> -Errno.ENOTTY
                        !session.setForegroundProcessGroup(process, processGroup) -> -Errno.EINVAL
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
                    if (args.copyToUser(session.termios.toNativeBytes())) Errno.EOK else -Errno.EFAULT

                IoctlConstants.TCGETS2.toInt() ->
                    if (args.copyToUser(session.termios2.toNativeBytes())) Errno.EOK else -Errno.EFAULT

                IoctlConstants.TCSETS,
                IoctlConstants.TCSETSW -> updateTermiosFromUser(session, args)

                IoctlConstants.TCSETSF -> {
                    val result = updateTermiosFromUser(session, args)
                    if (result == Errno.EOK) input.flush()
                    result
                }

                IoctlConstants.TCSETS2,
                IoctlConstants.TCSETSW2 -> updateTermios2FromUser(session, args)

                IoctlConstants.TCSETSF2 -> {
                    val result = updateTermios2FromUser(session, args)
                    if (result == Errno.EOK) input.flush()
                    result
                }

                IoctlConstants.TCFLSH -> {
                    input.flush()
                    Errno.EOK
                }

                IoctlConstants.FIONREAD -> copyIntToUser(args, input.availableBytes)

                IoctlConstants.TIOCSWINSZ,
                IoctlConstants.TCSBRK,
                IoctlConstants.TCSBRKP,
                IoctlConstants.TIOCNXCL -> Errno.EOK

                else -> -Errno.ENOTTY
            }
        }
    }

    final override fun poll(session: TtySession, events: Int): Int =
        input.poll(session, events)

    final override fun flushIfDirty() = outputLock.withLock(::flushOutput)

    override fun hangup(session: TtySession) = input.hangup()

    protected open fun consoleIoctl(file: TtySession.OpenFile, command: Int, args: UserMemory): Int? =
        null

    final override fun destroy() = outputLock.withLock(::closeOutput)

    protected abstract fun writeOutput(data: ByteArray, offset: Int, count: Int)

    protected abstract fun windowSize(): WinSize

    protected open fun flushOutput() {}

    protected open fun closeOutput() {}

    private fun echo(session: TtySession, data: ByteArray, offset: Int, count: Int) =
        outputLock.withLock { processOutput(session, data, offset, count) }

    private fun processOutput(
        session: TtySession,
        data: ByteArray,
        offset: Int,
        count: Int,
    ) {
        val flags = session.termios.cOflag
        if (flags and TermiosConstants.OPOST == 0) {
            writeOutput(data, offset, count)
            return
        }

        var outputCount = 0
        for (index in offset until offset + count) {
            when (val original = data[index].toUByte().toInt()) {
                '\n'.code -> {
                    if (flags and TermiosConstants.ONLCR != 0) {
                        processedOutput[outputCount++] = '\r'.code.toByte()
                        outputColumn = 0
                    }
                    processedOutput[outputCount++] = original.toByte()
                    if (flags and (TermiosConstants.ONLCR or TermiosConstants.ONLRET) != 0) {
                        outputColumn = 0
                    }
                }

                '\r'.code -> {
                    if (flags and TermiosConstants.ONOCR != 0 && outputColumn == 0) continue
                    val value = if (flags and TermiosConstants.OCRNL != 0) '\n'.code else original
                    processedOutput[outputCount++] = value.toByte()
                    if (value == '\r'.code ||
                        value == '\n'.code && flags and TermiosConstants.ONLRET != 0
                    ) {
                        outputColumn = 0
                    }
                }

                '\t'.code -> {
                    processedOutput[outputCount++] = original.toByte()
                    outputColumn = (outputColumn + TAB_WIDTH) and -TAB_WIDTH
                }

                '\b'.code -> {
                    processedOutput[outputCount++] = original.toByte()
                    if (outputColumn != 0) outputColumn--
                }

                else -> {
                    val value = if (flags and TermiosConstants.OLCUC != 0 &&
                        original in 'a'.code..'z'.code
                    ) {
                        original - ('a'.code - 'A'.code)
                    } else {
                        original
                    }
                    processedOutput[outputCount++] = value.toByte()
                    if (value >= ' '.code && value != 0x7F) outputColumn++
                }
            }
        }
        if (outputCount != 0) writeOutput(processedOutput, 0, outputCount)
    }

    private fun updateTermiosFromUser(session: TtySession, args: UserMemory): Int {
        val data = args.copyFromUser(Termios.NATIVE_SIZE)
        if (data == null || !session.termios.updateFromNativeBytes(data)) return -Errno.EFAULT
        session.termios2.cIflag = session.termios.cIflag
        session.termios2.cOflag = session.termios.cOflag
        session.termios2.cCflag = session.termios.cCflag
        session.termios2.cLflag = session.termios.cLflag
        session.termios2.cLine = session.termios.cLine
        session.termios2.cCc = session.termios.cCc.copyOf()
        return Errno.EOK
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

    protected fun copyIntToUser(args: UserMemory, value: Int): Int {
        val data = ByteArray(Int.SIZE_BYTES)
        LittleEndianBuffer(data).writeU32(0, value.toUInt())
        return if (args.copyToUser(data)) Errno.EOK else -Errno.EFAULT
    }

    private companion object {
        const val OUTPUT_CHUNK_SIZE = 4096
        const val TAB_WIDTH = 8
    }
}

internal class FrameBufferTerminal private constructor(
    private val terminal: NativeTerminal,
) : VirtualTerminal() {
    override fun writeOutput(data: ByteArray, offset: Int, count: Int) {
        if (displayMode == ConsoleDisplayMode.TEXT) terminal.process(data, offset, count)
    }

    override fun windowSize(): WinSize {
        val dimensions = terminal.dimensions()
        return WinSize(
            wsRow = minOf(dimensions.rows, UShort.MAX_VALUE.toULong()).toShort(),
            wsCol = minOf(dimensions.columns, UShort.MAX_VALUE.toULong()).toShort(),
            wsXpixel = 0,
            wsYpixel = 0,
        )
    }

    override fun flushOutput() {
        if (displayMode == ConsoleDisplayMode.TEXT) terminal.flushIfDirty()
    }

    override fun redrawOutput() = terminal.redraw()

    override fun closeOutput() = terminal.destroy()

    companion object {
        fun create(
            device: TtyGraphicsDevice,
            invalidate: () -> Unit,
        ): FrameBufferTerminal? = NativeTerminal.create(device, invalidate)?.let(::FrameBufferTerminal)
    }
}
