package org.plos_clan.cpos.drivers.char.terminal

import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.drivers.char.tty.TtySession
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.tasks.IoWaitQueue
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.Scheduler
import org.plos_clan.cpos.tasks.Signal
import org.plos_clan.cpos.utils.ByteRingBuffer
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.KernelMutex
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.TermiosConstants

internal class TerminalInput(
    private val echo: (TtySession, ByteArray, Int, Int) -> Unit,
) {
    private val input = ByteRingBuffer(INPUT_BUFFER_SIZE)
    private val canonicalData = ByteArray(INPUT_BUFFER_SIZE)
    private val canonicalRecords = IntArray(MAX_CANONICAL_RECORDS)
    private val waiterLock = IrqSpinLock()
    private val readWaiters = IoWaitQueue()
    private val receiveLock = KernelMutex()
    private var canonicalCount = 0
    private var canonicalRecordHead = 0
    private var canonicalRecordTail = 0
    private var canonicalRecordCount = 0

    val availableBytes: Int
        get() = input.available

    fun receive(
        session: TtySession,
        data: ByteArray,
        offset: Int,
        count: Int,
    ) {
        if (count == 0 || offset < 0 || count < 0 || offset > data.size - count) return
        receiveLock.withLock { receiveLocked(session, data, offset, count) }
    }

    private fun receiveLocked(
        session: TtySession,
        data: ByteArray,
        offset: Int,
        count: Int,
    ) {
        var generatedSignals = 0uL
        for (index in offset until offset + count) {
            val value = session.translateInput(data[index].toUByte().toInt()) ?: continue
            val signal = session.inputSignal(value)
            if (signal == null) {
                receiveInput(session, value)
            } else {
                if (!session.hasLocalFlag(TermiosConstants.NOFLSH)) {
                    input.transaction { clearInput() }
                }
                generatedSignals = generatedSignals or signal.bit
            }
        }
        while (generatedSignals != 0uL) {
            val signal = Signal.from(generatedSignals.countTrailingZeroBits() + 1)!!
            session.signalForeground(signal)
            generatedSignals = generatedSignals and signal.bit.inv()
        }
        if (input.transaction { isReadable(session.hasLocalFlag(TermiosConstants.ICANON)) }) {
            wakeReader()
        }
    }

    fun read(
        file: TtySession.OpenFile,
        buffer: PreparedBufferDestination,
        offset: Int,
        count: ULong,
    ): Long {
        if (offset < 0 || count > Int.MAX_VALUE.toULong()) return -Errno.EINVAL.toLong()
        val limit = count.toInt()
        if (limit == 0 || file.isHungUp) return 0L
        val session = file.session
        val canonical = session.hasLocalFlag(TermiosConstants.ICANON)
        val result = if (canonical) {
            readCanonical(file, buffer, offset, limit)
        } else {
            readNonCanonical(file, buffer, offset, limit)
        }
        if (input.transaction { isReadable(canonical) }) wakeReader()
        return result
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

    fun flush() = receiveLock.withLock {
        input.transaction { clearInput() }
    }

    fun hangup() {
        flush()
        waiterLock.withLock { readWaiters.wakeAll() }
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

    private fun receiveInput(session: TtySession, value: Int) {
        if (!session.hasLocalFlag(TermiosConstants.ICANON)) {
            input.offer(value.toByte())
            echoCharacter(session, value)
            return
        }

        val erase = session.controlCharacter(TermiosConstants.VERASE)
        if (value == 0x7F || erase != 0 && value == erase) {
            val erased = input.transaction {
                if (canonicalCount == 0) false else {
                    canonicalCount--
                    true
                }
            }
            if (erased) echoErase(session)
            return
        }

        val kill = session.controlCharacter(TermiosConstants.VKILL)
        if (kill != 0 && value == kill) {
            val erased = input.transaction { canonicalCount.also { canonicalCount = 0 } }
            repeat(erased) { echoErase(session) }
            if (session.hasLocalFlag(TermiosConstants.ECHO) &&
                session.hasLocalFlag(TermiosConstants.ECHOK)
            ) {
                echoCharacter(session, '\n'.code)
            }
            return
        }

        val eof = session.controlCharacter(TermiosConstants.VEOF)
        if (eof != 0 && value == eof) {
            input.transaction { commitCanonical(eof = true) }
            return
        }

        val lineEnd = session.isCanonicalLineEnd(value)
        input.transaction {
            if (canonicalCount < canonicalData.lastIndex || lineEnd) {
                canonicalData[canonicalCount++] = value.toByte()
            }
            if (lineEnd) commitCanonical(eof = false)
        }
        echoCharacter(session, value)
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

    private fun readCanonical(
        file: TtySession.OpenFile,
        buffer: PreparedBufferDestination,
        offset: Int,
        limit: Int,
    ): Long {
        while (true) {
            val result = input.transaction {
                if (file.isHungUp) {
                    0
                } else if (canonicalRecordCount == 0) {
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
            if (interrupted() || !waitForInput(file, canonical = true)) {
                return if (file.isHungUp) 0L else -Errno.EINTR.toLong()
            }
        }
    }

    private fun readNonCanonical(
        file: TtySession.OpenFile,
        buffer: PreparedBufferDestination,
        offset: Int,
        limit: Int,
    ): Long {
        val minimum = minOf(file.session.controlCharacter(TermiosConstants.VMIN), limit)
        val timeout = file.session.controlCharacter(TermiosConstants.VTIME)
        var deadline = if (minimum == 0 && timeout != 0) timeoutDeadline(timeout) else null
        var transferred = 0
        while (true) {
            val copied = input.transaction {
                if (file.isHungUp) 0 else read(buffer, offset + transferred, minOf(limit - transferred, available))
            }
            transferred += copied
            if (file.isHungUp || transferred >= maxOf(minimum, 1) || minimum == 0 && timeout == 0) {
                return transferred.toLong()
            }
            if (copied != 0 && timeout != 0) deadline = timeoutDeadline(timeout)
            if (deadline != null && deadlineReached(deadline)) return transferred.toLong()
            if (interrupted() || !waitForInput(file, canonical = false, deadlineNanos = deadline)) {
                return if (transferred != 0 || file.isHungUp) transferred.toLong() else -Errno.EINTR.toLong()
            }
        }
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

    private fun echoCharacter(session: TtySession, value: Int) {
        if (session.hasLocalFlag(TermiosConstants.ECHO) ||
            value == '\n'.code && session.hasLocalFlag(TermiosConstants.ECHONL)
        ) {
            echo(session, BYTE_VALUES, value and 0xFF, 1)
        }
    }

    private fun echoErase(session: TtySession) {
        if (session.hasLocalFlag(TermiosConstants.ECHO) &&
            session.hasLocalFlag(TermiosConstants.ECHOE)
        ) {
            echo(session, ERASE_BYTES, 0, ERASE_BYTES.size)
        }
    }

    private fun timeoutDeadline(deciseconds: Int): ULong =
        TscClock.nanoTime() + deciseconds.toULong() * NANOSECONDS_PER_DECISECOND

    private fun deadlineReached(deadline: ULong): Boolean =
        TscClock.isReady && TscClock.nanoTime() >= deadline

    private fun waitForInput(
        file: TtySession.OpenFile,
        canonical: Boolean,
        deadlineNanos: ULong? = null,
    ): Boolean {
        val thread = ProcessManager.currentThread() ?: return false
        val waiter = waiterLock.withLock { readWaiters.add(thread) }
        if (file.isHungUp) {
            waiterLock.withLock { readWaiters.wakeAll() }
        } else if (input.transaction { isReadable(canonical) }) {
            wakeReader()
        }
        return readWaiters.await(waiterLock, waiter, deadlineNanos)
    }

    private fun wakeReader() =
        waiterLock.withLock { readWaiters.takeOne() }?.let { Scheduler.wake(it) }

    private fun ByteRingBuffer.Transaction.isReadable(canonical: Boolean): Boolean =
        if (canonical) canonicalRecordCount != 0 else available != 0

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

        val BYTE_VALUES = ByteArray(256) { it.toByte() }
        val ERASE_BYTES = byteArrayOf(
            '\b'.code.toByte(),
            ' '.code.toByte(),
            '\b'.code.toByte(),
        )
    }
}
