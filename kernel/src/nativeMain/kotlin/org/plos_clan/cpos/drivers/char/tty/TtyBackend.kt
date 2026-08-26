package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory

internal abstract class TtyDriver(
    val consoleName: String,
    val terminalType: String,
    val bufferedOutput: Boolean,
) {
    abstract fun createEndpoints(invalidate: () -> Unit): List<TtyEndpoint>?
}

internal data class TtyEndpoint(
    val name: String,
    val major: UInt,
    val minor: UInt,
    val backend: TtySessionBackend,
    val virtualTerminalIndex: Int? = null,
    val inputSpeed: Int = 0,
    val outputSpeed: Int = inputSpeed,
)

data class ProcessTerminal(
    val deviceNumber: ULong,
    val foregroundProcessGroup: Int,
)

interface TtySessionBackend {
    fun start(session: TtySession): Boolean = true
    fun receiveInput(session: TtySession, data: ByteArray, offset: Int, count: Int)
    fun write(session: TtySession, buffer: PreparedBufferSource, offset: Int, count: ULong): Long
    fun read(session: TtySession, buffer: PreparedBufferDestination, offset: Int, count: ULong): Long
    fun ioctl(session: TtySession, command: Int, args: UserMemory): Int
    fun poll(session: TtySession, events: Int): Int
    fun flushIfDirty() {}
    fun destroy() {}
}
