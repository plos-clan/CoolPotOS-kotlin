package org.plos_clan.cpos.drivers

import org.plos_clan.cpos.mem.UserMemory

object NullDev : DeviceBackend {

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
        buffer: ByteArray,
        offset: ULong,
        size: ULong
    ): Long = 0

    override fun write(
        device: Device,
        buffer: ByteArray,
        offset: ULong,
        size: ULong
    ): Long = size.toLong()
}

object ZeroDev : DeviceBackend {

    override fun ioctl(
        device: Device,
        command: Int,
        args: UserMemory
    ): Long = 0

    override fun poll(device: Device, events: Int): Long = events.toLong()

    override fun read(
        device: Device,
        buffer: ByteArray,
        offset: ULong,
        size: ULong
    ): Long {
        buffer.fill(element = 0, toIndex = size.toInt())
        return size.toLong()
    }

    override fun write(
        device: Device,
        buffer: ByteArray,
        offset: ULong,
        size: ULong
    ): Long = size.toLong()

}
