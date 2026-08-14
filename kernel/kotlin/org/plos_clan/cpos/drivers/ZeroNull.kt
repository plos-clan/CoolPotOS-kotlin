package org.plos_clan.cpos.drivers

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.Errno

object NullDev : DiscardingDeviceBackend {
    fun initialize() {
        DeviceManager.installDevice(DEV_CHAR, DEV_SYSDEV, this, "null", 0UL, this)
        DeviceManager.installDevice(DEV_CHAR, DEV_SYSDEV, ZeroDev, "zero", 0UL, ZeroDev)
    }

    override fun ioctl(
        device: Device,
        command: Int,
        args: UserMemory
    ): Long = 0

    override fun poll(device: Device, events: Int): Long = events.toLong()

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong
    ): Long = 0

    override fun discard(device: Device, size: ULong): Long = size.toLong()
}

object ZeroDev : DiscardingDeviceBackend {
    override fun ioctl(
        device: Device,
        command: Int,
        args: UserMemory
    ): Long = 0

    override fun poll(device: Device, events: Int): Long = events.toLong()

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong
    ): Long {
        val count = size.toInt()
        val transferred = buffer.fill(bufferOffset, count)
        return if (transferred != 0 || count == 0) transferred.toLong() else -Errno.EFAULT.toLong()
    }

    override fun discard(device: Device, size: ULong): Long = size.toLong()
}
