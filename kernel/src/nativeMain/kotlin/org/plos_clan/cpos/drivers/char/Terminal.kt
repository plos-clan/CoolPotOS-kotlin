package org.plos_clan.cpos.drivers.char

import org.plos_clan.cpos.drivers.TtyGraphicsDevice
import org.plos_clan.cpos.drivers.char.terminal.NativeTerminal
import org.plos_clan.cpos.drivers.char.terminal.TerminalInput
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
import org.plos_clan.cpos.utils.LittleEndianBuffer

class TerminalSession private constructor(
    private val terminal: NativeTerminal,
) : TtySessionBackend {
    private val input = TerminalInput(terminal::process)

    override fun keyboardInput(session: TtySession, data: CharArray) =
        input.receive(session, data)

    override fun write(
        session: TtySession,
        buffer: PreparedBufferSource,
        offset: Int,
        count: ULong
    ): Long {
        val length = count.toInt()
        val bytes = ByteArray(length)
        val copied = buffer.copyTo(offset, bytes, 0, length)
        if (copied == 0 && length != 0) return -Errno.EFAULT.toLong()
        terminal.process(bytes, 0, copied)
        return copied.toLong()
    }

    override fun read(
        session: TtySession,
        buffer: PreparedBufferDestination,
        offset: Int,
        count: ULong,
    ): Long = input.read(session, buffer, offset, count)

    override fun ioctl(
        session: TtySession,
        command: Int,
        args: UserMemory
    ): Int = when (command) {
        IoctlConstants.TIOCGWINSZ -> {
            val dimensions = terminal.dimensions()

            val size = WinSize(
                wsRow = minOf(dimensions.rows, UShort.MAX_VALUE.toULong()).toShort(),
                wsCol = minOf(dimensions.columns, UShort.MAX_VALUE.toULong()).toShort(),
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
                input.flush()
            }
            result
        }

        IoctlConstants.TCSETS2,
        IoctlConstants.TCSETSW2 -> updateTermios2FromUser(session, args)

        IoctlConstants.TCSETSF2 -> {
            val result = updateTermios2FromUser(session, args)
            if (result == Errno.EOK) {
                input.flush()
            }
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

        else -> {
            println("warn: no implement tty ioctl 0x${command.toString(16)}")
            -Errno.ENOTTY
        }
    }

    override fun poll(session: TtySession, events: Int): Int =
        input.poll(session, events)

    override fun flushIfDirty() = terminal.flushIfDirty()

    override fun destroy() = terminal.destroy()

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
        return LittleEndianBuffer(data).readU32(0).toInt()
    }

    private fun copyIntToUser(args: UserMemory, value: Int): Int {
        val data = ByteArray(Int.SIZE_BYTES)
        LittleEndianBuffer(data).writeU32(0, value.toUInt())
        return if (args.copyToUser(data)) Errno.EOK else -Errno.EFAULT
    }

    companion object {
        internal fun create(
            device: TtyGraphicsDevice,
            invalidate: () -> Unit,
        ): TerminalSession? = NativeTerminal.create(device, invalidate)?.let(::TerminalSession)
    }
}
