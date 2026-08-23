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
import org.plos_clan.cpos.drivers.TtyGraphicsDevice
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
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.LittleEndianBuffer

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
    private val input = TerminalInput(::echoText)

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
        lock.withLock {
            bridge.terminal_process(terminal, bytes.decodeToString(0, copied))
        }
        return copied.toLong()
    }

    override fun read(
        session: TtySession,
        buffer: PreparedBufferDestination,
        offset: Int,
        count: ULong,
    ): Long = input.read(session, buffer, offset, count)

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

    private fun echoText(text: String) {
        lock.withLock {
            bridge.terminal_process(terminal, text)
        }
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
