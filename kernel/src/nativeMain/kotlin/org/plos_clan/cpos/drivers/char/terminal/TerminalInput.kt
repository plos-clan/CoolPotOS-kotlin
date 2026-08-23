@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.char.terminal

import bridge.enable_interrupt
import bridge.irq_restore
import bridge.irq_save
import bridge.wait_for_interrupt
import kotlinx.cinterop.ExperimentalForeignApi
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.char.tty.TtySession
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.utils.ByteRingBuffer
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.TermiosConstants

internal class TerminalInput(
    private val echo: (String) -> Unit,
) {
    private val input = ByteRingBuffer(INPUT_BUFFER_SIZE)
    private val canonicalData = ByteArray(INPUT_BUFFER_SIZE)
    private val canonicalRecords = IntArray(MAX_CANONICAL_RECORDS)
    private var canonicalCount = 0
    private var canonicalRecordHead = 0
    private var canonicalRecordTail = 0
    private var canonicalRecordCount = 0

    val availableBytes: Int
        get() = input.available

    fun receive(session: TtySession, data: CharArray) {
        if (data.isEmpty()) return
        var generatedSignals = 0uL
        input.transaction {
            for (character in data) {
                val value = session.translateInput(character.code and 0xff) ?: continue
                val signal = session.inputSignal(value)
                if (signal == null) {
                    receiveInput(session, value)
                    continue
                }
                if (!session.hasLocalFlag(TermiosConstants.NOFLSH)) clearInput()
                generatedSignals = generatedSignals or signal.bit
            }
        }
        while (generatedSignals != 0uL) {
            val signal = Signal.from(generatedSignals.countTrailingZeroBits() + 1)!!
            session.signalForeground(signal)
            generatedSignals = generatedSignals and signal.bit.inv()
        }
    }

    fun read(
        session: TtySession,
        buffer: PreparedBufferDestination,
        offset: Int,
        count: ULong,
    ): Long {
        val limit = count.toInt()
        if (limit == 0) return 0L
        return if (session.hasLocalFlag(TermiosConstants.ICANON)) {
            readCanonical(buffer, offset, limit)
        } else {
            readNonCanonical(session, buffer, offset, limit)
        }
    }

    fun poll(session: TtySession, events: Int): Int {
        var returned = events and PollEvents.NORMAL_OUTPUT
        val readable = input.transaction {
            if (session.hasLocalFlag(TermiosConstants.ICANON)) {
                canonicalRecordCount != 0
            } else {
                available != 0
            }
        }
        if (readable && events and PollEvents.NORMAL_INPUT != 0) {
            returned = returned or PollEvents.NORMAL_INPUT
        }
        return returned
    }

    fun flush() {
        input.transaction { clearInput() }
    }

    private fun ByteRingBuffer.Transaction.clearInput() {
        clear()
        canonicalCount = 0
        canonicalRecordHead = 0
        canonicalRecordTail = 0
        canonicalRecordCount = 0
    }

    private fun TtySession.translateInput(rawValue: Int): Int? {
        var value = rawValue
        if (hasInputFlag(TermiosConstants.ISTRIP)) {
            value = value and 0x7F
        }
        if (value == '\r'.code) {
            if (hasInputFlag(TermiosConstants.IGNCR)) return null
            if (hasInputFlag(TermiosConstants.ICRNL)) {
                value = '\n'.code
            }
        } else if (value == '\n'.code && hasInputFlag(TermiosConstants.INLCR)) {
            value = '\r'.code
        }
        return value
    }

    private fun ByteRingBuffer.Transaction.receiveInput(session: TtySession, value: Int) {
        if (!session.hasLocalFlag(TermiosConstants.ICANON)) {
            offer(value.toByte())
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
                echo(NEWLINE_TEXT)
            }
            return
        }

        val eof = session.controlCharacter(TermiosConstants.VEOF)
        if (eof != 0 && value == eof) {
            commitCanonical(eof = true)
            return
        }

        val lineEnd = session.isCanonicalLineEnd(value)
        if (canonicalCount < canonicalData.lastIndex || lineEnd) {
            canonicalData[canonicalCount++] = value.toByte()
        }
        echoCharacter(session, value)

        if (lineEnd) {
            commitCanonical(eof = false)
        }
    }

    private fun ByteRingBuffer.Transaction.commitCanonical(eof: Boolean) {
        if (canonicalCount == 0) {
            if (eof) {
                enqueueCanonicalRecordLocked(0)
            }
            return
        }

        if (canonicalCount <= remaining &&
            canonicalRecordCount < canonicalRecords.size
        ) {
            write(canonicalData, 0, canonicalCount)
            enqueueCanonicalRecordLocked(canonicalCount)
        }
        canonicalCount = 0
    }

    private fun readCanonical(buffer: PreparedBufferDestination, offset: Int, limit: Int): Long {
        while (true) {
            val result = input.transaction {
                if (canonicalRecordCount == 0) {
                    null
                } else {
                    val recordLength = dequeueCanonicalRecordLocked()
                    if (recordLength == 0) {
                        0
                    } else {
                        val transferred = minOf(limit, recordLength)
                        val copied = read(buffer, offset, transferred)
                        if (copied < recordLength) {
                            prependCanonicalRecordLocked(recordLength - copied)
                        }
                        copied
                    }
                }
            }
            if (result != null) {
                return result.toLong()
            }
            if (interrupted()) return -Errno.EINTR.toLong()
            waitForInterrupt()
        }
    }

    private fun readNonCanonical(
        session: TtySession,
        buffer: PreparedBufferDestination,
        offset: Int,
        limit: Int,
    ): Long {
        val minimum = minOf(session.controlCharacter(TermiosConstants.VMIN), limit)
        val timeout = session.controlCharacter(TermiosConstants.VTIME)

        if (minimum == 0 && timeout == 0) {
            return drainAvailable(buffer, offset, limit).toLong()
        }

        if (minimum == 0) {
            val deadline = timeoutDeadline(timeout)
            while (!hasInput()) {
                if (deadlineReached(deadline)) {
                    return 0L
                }
                if (interrupted()) return -Errno.EINTR.toLong()
                waitForInterrupt()
            }
            return drainAvailable(buffer, offset, limit).toLong()
        }

        var transferred = 0
        while (transferred == 0) {
            transferred += drainAvailable(buffer, offset + transferred, limit - transferred)
            if (transferred == 0) {
                if (interrupted()) return -Errno.EINTR.toLong()
                waitForInterrupt()
            }
        }

        if (timeout == 0) {
            while (transferred < minimum) {
                val current = drainAvailable(buffer, offset + transferred, limit - transferred)
                transferred += current
                if (transferred < minimum) {
                    if (interrupted()) return if (transferred == 0) -Errno.EINTR.toLong()
                    else transferred.toLong()
                    waitForInterrupt()
                }
            }
            return transferred.toLong()
        }

        var deadline = timeoutDeadline(timeout)
        while (transferred < minimum) {
            val current = drainAvailable(buffer, offset + transferred, limit - transferred)
            if (current != 0) {
                transferred += current
                deadline = timeoutDeadline(timeout)
            } else {
                if (deadlineReached(deadline)) {
                    break
                }
                if (interrupted()) return if (transferred == 0) -Errno.EINTR.toLong()
                else transferred.toLong()
                waitForInterrupt()
            }
        }
        return transferred.toLong()
    }

    private fun drainAvailable(buffer: PreparedBufferDestination, offset: Int, limit: Int): Int =
        input.transaction {
            read(buffer, offset, minOf(limit, available))
        }

    private fun hasInput(): Boolean = input.available != 0

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

    private fun echoCharacter(session: TtySession, value: Int) {
        if (session.hasLocalFlag(TermiosConstants.ECHO) ||
            value == '\n'.code && session.hasLocalFlag(TermiosConstants.ECHONL)
        ) {
            echo(ASCII_TEXT[value and 0x7F])
        }
    }

    private fun echoErase(session: TtySession) {
        if (session.hasLocalFlag(TermiosConstants.ECHO) &&
            session.hasLocalFlag(TermiosConstants.ECHOE)
        ) {
            echo(ERASE_TEXT)
        }
    }

    private fun timeoutDeadline(deciseconds: Int): ULong =
        TscClock.nanoTime() + deciseconds.toULong() * NANOSECONDS_PER_DECISECOND

    private fun deadlineReached(deadline: ULong): Boolean =
        TscClock.isReady && TscClock.nanoTime() >= deadline

    private fun waitForInterrupt() {
        Scheduler.yieldCurrent()
        val flags = irq_save()
        enable_interrupt()
        wait_for_interrupt()
        irq_restore(flags)
    }

    private fun interrupted(): Boolean =
        ProcessManager.currentThread()?.hasPendingSignal() == true

    private fun TtySession.inputSignal(value: Int): Signal? {
        if (!hasLocalFlag(TermiosConstants.ISIG)) return null
        return when {
            value != 0 && value == controlCharacter(TermiosConstants.VINTR) -> Signal.INTERRUPT
            value != 0 && value == controlCharacter(TermiosConstants.VQUIT) -> Signal.QUIT
            value != 0 && value == controlCharacter(TermiosConstants.VSUSP) ->
                Signal.TERMINAL_STOP
            else -> null
        }
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
