package org.plos_clan.cpos.drivers

import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.KernelRandom
import org.plos_clan.cpos.utils.PollEvents

internal interface MemoryDeviceBackend : DiscardingDeviceBackend {
    override fun ioctl(device: Device, command: Int, args: UserMemory): Long =
        -Errno.ENOTTY.toLong()

    override fun poll(device: Device, events: Int): Long =
        (events and PollEvents.DEFAULT_FILE_EVENTS).toLong()

    override fun discard(device: Device, size: ULong): Long =
        if (size <= Long.MAX_VALUE.toULong()) size.toLong() else -Errno.EINVAL.toLong()
}

internal enum class MemoryDevice(
    private val nodeName: String,
    private val minor: UInt,
) : MemoryDeviceBackend {
    NULL("null", 3u) {
        override fun read(
            device: Device,
            buffer: PreparedBufferDestination,
            bufferOffset: Int,
            size: ULong,
        ): Long = 0
    },
    ZERO("zero", 5u) {
        override fun read(
            device: Device,
            buffer: PreparedBufferDestination,
            bufferOffset: Int,
            size: ULong,
        ): Long = readZeros(buffer, bufferOffset, size)
    },
    FULL("full", 7u) {
        override fun read(
            device: Device,
            buffer: PreparedBufferDestination,
            bufferOffset: Int,
            size: ULong,
        ): Long = readZeros(buffer, bufferOffset, size)

        override fun discard(device: Device, size: ULong): Long = -Errno.ENOSPC.toLong()
    },
    RANDOM("random", 8u),
    URANDOM("urandom", 9u),
    ;

    override fun open(device: Device): DeviceBackend =
        if (this == RANDOM || this == URANDOM) RandomOpenFile() else this

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong,
    ): Long = -Errno.ENODEV.toLong()

    protected fun readZeros(
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong,
    ): Long {
        if (size > Int.MAX_VALUE.toULong()) return -Errno.EINVAL.toLong()
        val count = size.toInt()
        val transferred = buffer.fill(bufferOffset, count)
        return if (transferred != 0 || count == 0) transferred.toLong()
        else -Errno.EFAULT.toLong()
    }

    private class RandomOpenFile : MemoryDeviceBackend {
        private val scratch = ByteArray(RANDOM_CHUNK_SIZE)

        override fun close(device: Device) = scratch.fill(0)

        override fun read(
            device: Device,
            buffer: PreparedBufferDestination,
            bufferOffset: Int,
            size: ULong,
        ): Long {
            if (size > Int.MAX_VALUE.toULong()) return -Errno.EINVAL.toLong()
            val count = size.toInt()
            var transferred = 0
            while (transferred < count) {
                val chunkSize = minOf(count - transferred, scratch.size)
                KernelRandom.fill(
                    scratch,
                    size = chunkSize,
                    salt = device.number.value xor transferred.toULong(),
                )
                val copied = buffer.copyFrom(bufferOffset + transferred, scratch, 0, chunkSize)
                if (copied != chunkSize) {
                    return if (transferred == 0) -Errno.EFAULT.toLong()
                    else transferred.toLong()
                }
                transferred += chunkSize
            }
            return transferred.toLong()
        }
    }

    companion object {
        private const val RANDOM_CHUNK_SIZE = 4096

        fun initialize(): Boolean {
            val installed = ArrayList<Device>(entries.size)
            for (memoryDevice in entries) {
                val registration = DeviceRegistration(
                    name = memoryDevice.nodeName,
                    type = DeviceType.CHARACTER,
                    major = LinuxDeviceMajor.MEMORY.number,
                    minor = memoryDevice.minor,
                    backend = memoryDevice,
                )
                val device = DeviceManager.register(registration)
                if (device == null) {
                    for (registered in installed) DeviceManager.unregister(registered)
                    return false
                }
                installed += device
            }
            return true
        }
    }
}
