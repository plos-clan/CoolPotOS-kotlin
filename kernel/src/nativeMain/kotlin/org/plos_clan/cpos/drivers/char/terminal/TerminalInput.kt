package org.plos_clan.cpos.drivers.char.terminal

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.char.TerminalBackend
import org.plos_clan.cpos.drivers.char.tty.TerminalBuffer
import org.plos_clan.cpos.drivers.char.tty.Termios2
import org.plos_clan.cpos.drivers.char.tty.TtySession
import org.plos_clan.cpos.fs.vfs.IoMode
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.TermiosConstants.ECHO
import org.plos_clan.cpos.utils.TermiosConstants.ECHOCTL
import org.plos_clan.cpos.utils.TermiosConstants.ECHOE
import org.plos_clan.cpos.utils.TermiosConstants.ECHOK
import org.plos_clan.cpos.utils.TermiosConstants.ECHOKE
import org.plos_clan.cpos.utils.TermiosConstants.ECHONL
import org.plos_clan.cpos.utils.TermiosConstants.ICANON
import org.plos_clan.cpos.utils.TermiosConstants.ICRNL
import org.plos_clan.cpos.utils.TermiosConstants.IEXTEN
import org.plos_clan.cpos.utils.TermiosConstants.IGNCR
import org.plos_clan.cpos.utils.TermiosConstants.INLCR
import org.plos_clan.cpos.utils.TermiosConstants.ISIG
import org.plos_clan.cpos.utils.TermiosConstants.ISTRIP
import org.plos_clan.cpos.utils.TermiosConstants.IUCLC
import org.plos_clan.cpos.utils.TermiosConstants.IUTF8
import org.plos_clan.cpos.utils.TermiosConstants.IXANY
import org.plos_clan.cpos.utils.TermiosConstants.IXON
import org.plos_clan.cpos.utils.TermiosConstants.NOFLSH
import org.plos_clan.cpos.utils.TermiosConstants.PARMRK
import org.plos_clan.cpos.utils.TermiosConstants.VEOF
import org.plos_clan.cpos.utils.TermiosConstants.VEOL
import org.plos_clan.cpos.utils.TermiosConstants.VEOL2
import org.plos_clan.cpos.utils.TermiosConstants.VERASE
import org.plos_clan.cpos.utils.TermiosConstants.VINTR
import org.plos_clan.cpos.utils.TermiosConstants.VKILL
import org.plos_clan.cpos.utils.TermiosConstants.VLNEXT
import org.plos_clan.cpos.utils.TermiosConstants.VMIN
import org.plos_clan.cpos.utils.TermiosConstants.VQUIT
import org.plos_clan.cpos.utils.TermiosConstants.VSTART
import org.plos_clan.cpos.utils.TermiosConstants.VSTOP
import org.plos_clan.cpos.utils.TermiosConstants.VSUSP
import org.plos_clan.cpos.utils.TermiosConstants.VTIME
import org.plos_clan.cpos.utils.TermiosConstants.VWERASE

internal class TerminalInput(private val terminal: TerminalBackend) {
    private val buffer = TerminalBuffer()
    private val lock = IrqSpinLock()
    private val readWaiters = IoWaitQueue()
    private val writeWaiters = IoWaitQueue()
    private val receiveLock = KernelMutex()
    private val readLock = KernelMutex()
    private var literal = false

    fun available(session: TtySession): Int = lock.withLock {
        if (session.termios.cLflag and ICANON != 0) buffer.available else buffer.size
    }

    fun writable(session: TtySession): Boolean = lock.withLock {
        buffer.remaining > 1 || session.termios.cLflag and ICANON != 0 && buffer.committed == 0
    }

    fun receive(session: TtySession, data: ByteArray, offset: Int, count: Int): Int = receiveLock.withLock {
        val settings = session.termios
        val inputFlags = settings.cIflag
        val localFlags = settings.cLflag
        val canonical = localFlags and ICANON != 0
        if (!canonical && inputFlags and (ISTRIP or INLCR or IGNCR or ICRNL or IUCLC or IXON or PARMRK) == 0 &&
            localFlags and (ISIG or ECHO or ECHONL) == 0
        ) {
            return@withLock lock.withLock {
                buffer.write(data, offset, count).also { if (it != 0) { terminal.changed(); readWaiters.wakeAll() } }
            }
        }
        var accepted = 0
        for (index in offset until offset + count) {
            if (!writable(session)) break
            var value = data[index].toInt() and if (inputFlags and ISTRIP != 0) 0x7F else 0xFF
            accepted++
            if (value == '\r'.code) {
                if (inputFlags and IGNCR != 0) continue
                if (inputFlags and ICRNL != 0) value = '\n'.code
            } else if (value == '\n'.code && inputFlags and INLCR != 0) value = '\r'.code
            if (inputFlags and IUCLC != 0 && localFlags and IEXTEN != 0 && value in 65..90) value += 32
            if (literal) {
                literal = false
                lock.withLock { if (buffer.remaining > 1) buffer.offer(value.toByte()) }
                echo(session, value)
                continue
            }
            if (inputFlags and IXON != 0) {
                if (settings.matches(VSTOP, value) || settings.matches(VSTART, value)) {
                    terminal.setOutputStopped(session, settings.matches(VSTOP, value))
                    continue
                }
                if (inputFlags and IXANY != 0) terminal.setOutputStopped(session, false)
            }
            val signal = if (localFlags and ISIG == 0) null else when {
                settings.matches(VINTR, value) -> Signal.INTERRUPT
                settings.matches(VQUIT, value) -> Signal.QUIT
                settings.matches(VSUSP, value) -> Signal.TERMINAL_STOP
                else -> null
            }
            if (signal != null) {
                if (localFlags and NOFLSH == 0) {
                    clear()
                    terminal.discardOutput()
                }
                echo(session, value)
                session.signalForeground(signal)
                continue
            }
            if (canonical && localFlags and IEXTEN != 0 && settings.matches(VLNEXT, value)) {
                literal = true
                if (localFlags and ECHO != 0) terminal.echo(session, LITERAL, 0, LITERAL.size)
                continue
            }
            if (canonical && (settings.matches(VERASE, value) || settings.matches(VKILL, value) ||
                    localFlags and IEXTEN != 0 && settings.matches(VWERASE, value))) {
                val kill = settings.matches(VKILL, value)
                val word = settings.matches(VWERASE, value)
                var seenWord = false
                while (true) {
                    val previous = lock.withLock {
                        val last = buffer.last() ?: return@withLock null
                        if (word && seenWord && (last == 32 || last == 9)) return@withLock null
                        buffer.erase(inputFlags and IUTF8 != 0)
                        last
                    } ?: break
                    if (previous != 32 && previous != 9) seenWord = true
                    if (localFlags and ECHO != 0 && localFlags and ECHOE != 0 &&
                        (!kill || localFlags and ECHOKE != 0)) {
                        repeat(if (localFlags and ECHOCTL != 0 && previous < 32 && previous != 9) 2 else 1) {
                            terminal.echo(session, ERASE, 0, ERASE.size)
                        }
                    }
                    if (!kill && !word) break
                }
                if (localFlags and ECHO != 0 && (localFlags and ECHOE == 0 || kill && localFlags and ECHOKE == 0)) {
                    echo(session, value)
                    if (kill && localFlags and ECHOK != 0) echo(session, 10)
                }
                continue
            }
            val eof = canonical && settings.matches(VEOF, value)
            val line = canonical && (value == 10 || settings.matches(VEOL, value) ||
                localFlags and IEXTEN != 0 && settings.matches(VEOL2, value))
            lock.withLock {
                if (!canonical || buffer.remaining > 1 || eof || line) {
                    if (inputFlags and PARMRK != 0 && value == 255 && buffer.remaining > 2) buffer.offer(255.toByte())
                    buffer.offer(value.toByte(), if (eof) TerminalBuffer.EOF else if (line) TerminalBuffer.LINE else 0)
                }
            }
            if (!eof) echo(session, value)
        }
        if (accepted != 0) terminal.changed()
        lock.withLock { readWaiters.wakeAll() }
        accepted
    }

    fun read(file: TtySession.OpenFile, destination: PreparedBufferDestination, offset: Int, count: ULong, mode: IoMode): Long {
        if (offset < 0 || count > Int.MAX_VALUE.toULong()) return -Errno.EINVAL.toLong()
        if (count == 0uL) return 0
        return if (mode == IoMode.NON_BLOCKING) {
            readLock.tryWithLock { readLocked(file, destination, offset, count.toInt(), mode) } ?: -Errno.EAGAIN.toLong()
        } else readLock.withLock { readLocked(file, destination, offset, count.toInt(), mode) }
    }

    private fun readLocked(file: TtySession.OpenFile, destination: PreparedBufferDestination, offset: Int, limit: Int, mode: IoMode): Long {
        var transferred = 0
        var deadline: ULong? = null
        while (true) {
            val settings = file.session.termios
            val canonical = settings.cLflag and ICANON != 0
            val minimum = minOf(settings.character(VMIN), limit)
            val timeout = settings.character(VTIME).toULong() * 100_000_000uL
            if (!canonical && minimum == 0 && timeout != 0uL && deadline == null) deadline = TscClock.nanoTime() + timeout
            var record = false
            val copied = lock.withLock {
                record = canonical && buffer.committed != 0
                buffer.read(destination, offset + transferred, limit - transferred, canonical)
                    .also { if (it != 0 || record) terminal.changed(); writeWaiters.wakeAll() }
            }
            if (copied < 0) return if (transferred != 0) transferred.toLong() else -Errno.EFAULT.toLong()
            transferred += copied
            if (file.isHungUp || record || !canonical && (transferred >= maxOf(minimum, 1) || minimum == 0 && timeout == 0uL)) {
                return transferred.toLong()
            }
            if (mode == IoMode.NON_BLOCKING) {
                return if (transferred != 0) transferred.toLong() else -Errno.EAGAIN.toLong()
            }
            if (!canonical && copied != 0 && timeout != 0uL) deadline = TscClock.nanoTime() + timeout
            if (deadline != null && TscClock.nanoTime() >= deadline) return transferred.toLong()
            val thread = ProcessManager.currentThread() ?: return -Errno.EINTR.toLong()
            val waiter = lock.withLock {
                if (file.isHungUp || if (canonical) buffer.committed != 0 else buffer.size != 0) null
                else readWaiters.add(thread)
            } ?: continue
            if (!readWaiters.await(lock, waiter, deadline)) {
                return if (transferred != 0 || file.isHungUp) transferred.toLong() else -Errno.EINTR.toLong()
            }
        }
    }

    fun poll(session: TtySession, events: Int): Int = lock.withLock {
        val settings = session.termios
        val readable = if (settings.cLflag and ICANON != 0) buffer.committed != 0
            else buffer.size >= if (settings.character(VTIME) == 0) maxOf(1, settings.character(VMIN)) else 1
        if (readable) events and PollEvents.NORMAL_INPUT else 0
    }

    fun awaitWritable(session: TtySession, closed: () -> Boolean): Boolean {
        val thread = ProcessManager.currentThread() ?: return false
        val waiter = lock.withLock {
            if (closed() || buffer.remaining > 1 || session.termios.cLflag and ICANON != 0 && buffer.committed == 0) null
            else writeWaiters.add(thread)
        } ?: return true
        return writeWaiters.await(lock, waiter)
    }

    fun reconfigure(session: TtySession, current: Termios2): Termios2 = receiveLock.withLock {
        val previous = session.termios2
        literal = false
        terminal.changed()
        lock.withLock {
            if ((previous.cLflag xor current.cLflag) and ICANON != 0) buffer.setCanonical(current.cLflag and ICANON != 0)
            session.updateTermios(current)
            readWaiters.wakeAll()
            writeWaiters.wakeAll()
        }
        previous
    }

    fun flush() = receiveLock.withLock { clear() }

    fun hangup() = receiveLock.withLock { clear() }

    private fun clear() {
        literal = false
        terminal.changed()
        lock.withLock {
            buffer.clear()
            readWaiters.wakeAll()
            writeWaiters.wakeAll()
        }
    }

    private fun echo(session: TtySession, value: Int) {
        val flags = session.termios.cLflag
        if (flags and ECHO == 0 && !(value == 10 && flags and ECHONL != 0)) return
        if (flags and ECHOCTL != 0 && (value < 32 && value != 9 && value != 10 || value == 127)) {
            terminal.echo(session, BYTES, 94, 1)
            terminal.echo(session, BYTES, value xor 64, 1)
        } else terminal.echo(session, BYTES, value, 1)
    }

    private companion object {
        val BYTES = ByteArray(256) { it.toByte() }
        val ERASE = byteArrayOf(8, 32, 8)
        val LITERAL = byteArrayOf(94, 8)
    }
}
