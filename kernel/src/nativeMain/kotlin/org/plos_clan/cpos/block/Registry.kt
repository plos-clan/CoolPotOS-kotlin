@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.block

import kotlin.concurrent.atomics.AtomicInt
import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceBackend
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents

class BlockDeviceBackend(val cache: BufferCache) : DeviceBackend {
    override val byteSize: ULong
        get() = cache.device.geometry.byteSize

    override fun open(device: Device): VfsResult<DeviceBackend> =
        if (cache.connected) VfsResult.Ok(this) else VfsResult.Err(VfsError.NO_DEVICE)

    override fun sync(device: Device): Long = cache.flush().error

    override fun ioctl(device: Device, command: Int, args: UserMemory): Long {
        if (!cache.connected) return -Errno.ENODEV.toLong()
        val geometry = cache.device.geometry
        val (width, value) =
            when (command) {
                0x125e -> 4 to if (cache.device.readOnly) 1uL else 0uL
                0x1260 -> 8 to geometry.byteSize / 512uL
                0x1268 -> 4 to geometry.blockSize.toULong()
                0x80081272.toInt() -> 8 to geometry.byteSize
                else -> return -Errno.ENOTTY.toLong()
            }
        val bytes = ByteArray(width)
        val fields = LittleEndianBuffer(bytes)
        if (width == 4) fields.writeU32(0, value.toUInt()) else fields.writeU64(0, value)
        return if (args.copyToUser(bytes)) 0 else -Errno.EFAULT.toLong()
    }

    override fun poll(device: Device, events: Int): Long =
        if (cache.connected) (events and PollEvents.DEFAULT_FILE_EVENTS).toLong()
        else (PollEvents.POLLERR or PollEvents.POLLHUP).toLong()

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long {
        if (
            size > Int.MAX_VALUE.toULong() ||
                bufferOffset < 0 ||
                bufferOffset > Int.MAX_VALUE - size.toInt()
        ) {
            return -Errno.EINVAL.toLong()
        }
        return cache.read(offset, buffer, bufferOffset, size.toInt()).value
    }

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        offset: ULong,
        size: ULong,
    ): Long {
        if (
            size > Int.MAX_VALUE.toULong() ||
                bufferOffset < 0 ||
                bufferOffset > Int.MAX_VALUE - size.toInt()
        ) {
            return -Errno.EINVAL.toLong()
        }
        if (size != 0uL && offset >= byteSize) return -Errno.ENOSPC.toLong()
        return cache.write(offset, buffer, bufferOffset, size.toInt()).value
    }

    private val BlockResult.value: Long
        get() = if (bytes > 0 || successful) bytes.toLong() else status.error

    private val BlockStatus.error: Long
        get() =
            when (this) {
                BlockStatus.SUCCESS -> 0
                BlockStatus.IO_ERROR -> -Errno.EIO.toLong()
                BlockStatus.NO_DEVICE -> -Errno.ENODEV.toLong()
                BlockStatus.READ_ONLY -> -Errno.EROFS.toLong()
                BlockStatus.INVALID -> -Errno.EINVAL.toLong()
                BlockStatus.NO_MEMORY -> -Errno.ENOMEM.toLong()
            }
}

object BlockDevices {
    private val sequence = AtomicInt(0)

    fun register(block: BlockDevice): Device? {
        val name = "disk${sequence.fetchAndAdd(1)}"
        return DeviceManager.register(
            DeviceRegistration(
                name,
                DeviceType.BLOCK,
                259u,
                backend = BlockDeviceBackend(BufferCache(block)),
                sysfs = SysfsDevicePublication.virtual("block", name),
            )
        )
    }

    fun unregister(device: Device) {
        val backend = device.backend as? BlockDeviceBackend ?: return
        backend.cache.detach()
        DeviceManager.unregister(device)
    }
}
