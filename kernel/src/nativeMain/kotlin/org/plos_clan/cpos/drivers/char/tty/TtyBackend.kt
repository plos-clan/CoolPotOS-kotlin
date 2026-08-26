package org.plos_clan.cpos.drivers.char.tty

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory

data class TtyDevice(val name: String, val device: TtyPhysicalDevice, val type: TtyDeviceType)

data class ProcessTerminal(
    val deviceNumber: ULong,
    val foregroundProcessGroup: Int,
)

interface TtySessionBackend {
    fun keyboardInput(session: TtySession, data: CharArray)
    fun write(session: TtySession, buffer: PreparedBufferSource, offset: Int, count: ULong): Long
    fun read(session: TtySession, buffer: PreparedBufferDestination, offset: Int, count: ULong): Long
    fun ioctl(session: TtySession, command: Int, args: UserMemory): Int
    fun poll(session: TtySession, events: Int): Int
    fun flushIfDirty()
    fun destroy()
}

interface TtyPhysicalDevice {
    fun write(session: TtySession, buffer: PreparedBufferSource, offset: Int, count: ULong): Long
    fun read(session: TtySession, buffer: PreparedBufferDestination, offset: Int, count: ULong): Long
    fun flush(session: TtySession)
    fun ioctl(session: TtySession, command: Int, args: UserMemory): Int
}
