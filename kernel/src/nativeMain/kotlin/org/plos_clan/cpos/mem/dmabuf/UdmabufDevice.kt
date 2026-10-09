package org.plos_clan.cpos.mem.dmabuf

import org.plos_clan.cpos.drivers.Device
import org.plos_clan.cpos.drivers.DeviceManager
import org.plos_clan.cpos.drivers.DeviceRegistration
import org.plos_clan.cpos.drivers.DeviceType
import org.plos_clan.cpos.drivers.LinuxDeviceMajor
import org.plos_clan.cpos.drivers.PositionlessDeviceBackend
import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.FileDescriptorTable
import org.plos_clan.cpos.fs.sysfs.SysfsDevicePublication
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.FilePageRange
import org.plos_clan.cpos.fs.vfs.PinnableFile
import org.plos_clan.cpos.fs.vfs.PinnedFilePages
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PollEvents

internal object UdmabufDevice : PositionlessDeviceBackend {
    fun initialize(): Boolean {
        val publication = SysfsDevicePublication.virtual("misc", "udmabuf")
        val registration = DeviceRegistration(
            name = "udmabuf",
            type = DeviceType.CHARACTER,
            major = LinuxDeviceMajor.MISC.number,
            backend = this,
            sysfs = publication,
        )
        return DeviceManager.register(registration) != null
    }

    override fun ioctl(device: Device, command: Int, args: UserMemory): Long = try {
        create(command, args)
    } catch (_: OutOfMemoryError) {
        -Errno.ENOMEM.toLong()
    }

    private fun create(command: Int, args: UserMemory): Long {
        val request = when (val result = Request.read(command, args)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return -result.error.errno.toLong()
        }
        val process = ProcessManager.currentProcess() ?: return -Errno.ESRCH.toLong()
        val context = process.context ?: return -Errno.ENOENT.toLong()
        val limit = process.resourceLimits.get(ProcessResource.OPEN_FILES).soft
        val reservation = process.fdTable.reserve(request.descriptorFlags, limit)
            ?: return -Errno.EMFILE.toLong()
        return reservation.use {
            val buffer = when (val result = request.pin(process.fdTable)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return@use -result.error.errno.toLong()
            }
            val file = try {
                when (val result = buffer.export(process.vfsOperationContext, context, AccessMode.READ_WRITE)) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> return@use -result.error.errno.toLong()
                }
            } finally {
                buffer.release()
            }
            try {
                it.install(file).toLong()
            } catch (error: OutOfMemoryError) {
                file.release()
                throw error
            }
        }
    }

    override fun poll(device: Device, events: Int): Long =
        (events and (PollEvents.POLLIN or PollEvents.POLLOUT)).toLong()

    override fun read(
        device: Device,
        buffer: PreparedBufferDestination,
        bufferOffset: Int,
        size: ULong,
    ): Long = -Errno.EINVAL.toLong()

    override fun write(
        device: Device,
        buffer: PreparedBufferSource,
        bufferOffset: Int,
        size: ULong,
    ): Long = -Errno.EINVAL.toLong()

    private class Region(val fd: Int, val range: FilePageRange) {
        fun pin(table: FileDescriptorTable): VfsResult<PinnedFilePages> {
            val invalid = VfsResult.Err(VfsError.fromErrno(Errno.EBADFD))
            val file = table.acquire(fd) ?: return invalid
            return try {
                if (file.access == AccessMode.PATH) return invalid
                val provider = file.inode.backend as? PinnableFile ?: return invalid
                provider.pin(file, range)
            } finally {
                file.release()
            }
        }
    }

    private class Request private constructor(val descriptorFlags: ULong, val regions: List<Region>) {
        fun pin(table: FileDescriptorTable): VfsResult<FileDmaBuffer> {
            val pins = ArrayList<PinnedFilePages>(regions.size)
            var completed = false
            try {
                for (region in regions) {
                    when (val result = region.pin(table)) {
                        is VfsResult.Ok -> pins.add(result.value)
                        is VfsResult.Err -> return result
                    }
                }
                val buffer = FileDmaBuffer(pins)
                val result = VfsResult.Ok(buffer)
                completed = true
                return result
            } finally {
                if (!completed) pins.forEach(PinnedFilePages::close)
            }
        }

        companion object {
            fun read(command: Int, args: UserMemory): VfsResult<Request> {
                val headerSize = when (command) {
                    0x40187542 -> 24
                    0x40087543 -> 8
                    else -> return VfsResult.Err(VfsError.NOT_TTY)
                }
                val header = args.copyFromUser(headerSize) ?: return VfsResult.Err(VfsError.FAULT)
                val data = LittleEndianBuffer(header)
                val single = headerSize == 24
                val flags = data.readU32(if (single) 4 else 0)
                val count = if (single) 1u else data.readU32(4)
                if (count == 0u || count > ((Int.MAX_VALUE - 8) / 24).toUInt()) {
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                val bytes = if (single) header else ByteArray(count.toInt() * 24)
                if (!single && args.copyTo(8, bytes, 0, bytes.size) != bytes.size) {
                    return VfsResult.Err(VfsError.FAULT)
                }
                val items = LittleEndianBuffer(bytes)
                val regions = ArrayList<Region>(count.toInt())
                var pages = 0uL
                for (index in 0 until count.toInt()) {
                    val start = index * 24
                    val offset = items.readU64(start + 8)
                    val size = items.readU64(start + 16)
                    val range = when (val result = FilePageRange.create(offset, size)) {
                        is VfsResult.Ok -> result.value
                        is VfsResult.Err -> return result
                    }
                    pages += range.pageCount.toULong()
                    if (pages > Int.MAX_VALUE.toULong()) return VfsResult.Err(VfsError.NO_MEMORY)
                    val region = Region(items.readU32(start).toInt(), range)
                    regions.add(region)
                }
                val descriptorFlags = if (flags and 1u != 0u) FileDescriptorFlags.FD_CLOEXEC else 0uL
                val request = Request(descriptorFlags, regions)
                return VfsResult.Ok(request)
            }
        }
    }
}
