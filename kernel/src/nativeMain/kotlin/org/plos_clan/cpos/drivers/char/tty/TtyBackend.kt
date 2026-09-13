package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.fs.vfs.IoMode
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory

internal abstract class TtyDriver(
    val consoleName: String,
    val terminalType: String,
    val bufferedOutput: Boolean,
) {
    abstract fun createEndpoints(invalidate: () -> Unit): List<TtyEndpoint>
}

internal data class TtyEndpoint(
    val name: String,
    val major: UInt,
    val minor: UInt,
    val createBackend: () -> TtySessionBackend?,
    val virtualTerminalNumber: Int? = null,
    val inputSpeed: Int = 0,
    val outputSpeed: Int = inputSpeed,
)

data class ProcessTerminal(
    val deviceNumber: ULong,
    val foregroundProcessGroup: Int,
)

enum class TtyEvent(val packetFlag: Int, val oppositeFlag: Int = 0) {
    INPUT_FLUSHED(1),
    OUTPUT_FLUSHED(2),
    OUTPUT_STOPPED(4, 8),
    OUTPUT_STARTED(8, 4),
    FLOW_DISABLED(16, 32),
    FLOW_ENABLED(32, 16),
}

interface TtySessionBackend {
    val readinessVersion: Int
        get() = 0

    fun start(session: TtySession): Boolean = true
    fun open(session: TtySession): Int = 0
    fun close(session: TtySession) {}
    fun receiveInput(session: TtySession, data: ByteArray, offset: Int, count: Int)
    fun write(file: TtySession.OpenFile, buffer: PreparedBufferSource, offset: Int, count: ULong, mode: IoMode): Long
    fun read(file: TtySession.OpenFile, buffer: PreparedBufferDestination, offset: Int, count: ULong, mode: IoMode): Long
    fun ioctl(file: TtySession.OpenFile, command: Int, args: UserMemory): Int
    fun poll(session: TtySession, events: Int): Int
    fun hangup(session: TtySession) {}
    fun redraw() {}
    fun flushIfDirty() {}
    fun destroy() {}
}
