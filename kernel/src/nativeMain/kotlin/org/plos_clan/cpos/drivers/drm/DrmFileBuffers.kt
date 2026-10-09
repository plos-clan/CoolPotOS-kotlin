package org.plos_clan.cpos.drivers.drm

import org.plos_clan.cpos.fs.FileDescriptorFlags
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.MappedFile
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.dmabuf.DmaBuffer
import org.plos_clan.cpos.mem.dmabuf.DmaBufferFile
import org.plos_clan.cpos.mem.dmabuf.DmaBufferMapping
import org.plos_clan.cpos.mem.dmabuf.ResidentDmaBuffer
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

internal class DrmFileBuffers {
    private class Entry(val buffer: DmaBuffer, val offset: ULong) {
        val handles = linkedSetOf<UInt>()
        var exported: OpenFileDescription? = null

        fun close() {
            exported?.release()
            buffer.release()
        }
    }

    private val handles = mutableMapOf<UInt, Entry>()
    private val objects = mutableMapOf<DmaBuffer, Entry>()
    private val offsets = mutableMapOf<ULong, DmaBuffer>()
    private var nextHandle = 1u
    private var nextOffset = PAGE_SIZE_BYTES

    fun get(handle: UInt): DmaBuffer? = handles[handle]?.buffer

    fun create(data: LittleEndianBuffer): Long {
        if (data.readU32(12) != 0u) return -Errno.EINVAL.toLong()
        val layout = DumbLayout.create(data.readU32(4), data.readU32(0), data.readU32(8))
            ?: return -Errno.EINVAL.toLong()
        val buffer = when (val result = ResidentDmaBuffer.allocate(layout.size)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return -result.error.errno.toLong()
        }
        val handle = try {
            when (val result = add(buffer)) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return -result.error.errno.toLong()
            }
        } finally {
            buffer.release()
        }
        data.writeU32(16, handle)
        data.writeU32(20, layout.pitch)
        data.writeU64(24, layout.size)
        return 0
    }

    fun add(buffer: DmaBuffer): VfsResult<UInt> {
        if (nextHandle == 0u) return VfsResult.Err(VfsError.NO_SPACE)
        var entry = objects[buffer]
        val fresh = entry == null
        if (fresh) {
            if (buffer.size > Long.MAX_VALUE.toULong() - nextOffset) {
                return VfsResult.Err(VfsError.NO_SPACE)
            }
            entry = Entry(buffer, nextOffset)
        }
        val selected = checkNotNull(entry)
        val id = nextHandle++
        try {
            selected.handles.add(id)
            handles[id] = selected
            if (fresh) {
                objects[buffer] = selected
                offsets[selected.offset] = buffer
            }
        } catch (error: OutOfMemoryError) {
            selected.handles.remove(id)
            handles.remove(id)
            if (fresh) {
                objects.remove(buffer)
                offsets.remove(selected.offset)
            }
            throw error
        }
        if (fresh) {
            check(buffer.retain())
            nextOffset += buffer.size
        }
        return VfsResult.Ok(id)
    }

    fun export(argument: DrmArgument, process: Process): Long {
        val data = argument.data
        val flags = data.readU32(4)
        val supported = (OpenFlags.O_CLOEXEC or OpenFlags.O_RDWR).toUInt()
        if (flags and supported.inv() != 0u) return -Errno.EINVAL.toLong()
        val entry = handles[data.readU32(0)] ?: return -Errno.ENOENT.toLong()
        val context = process.context ?: return -Errno.ENOENT.toLong()
        val access = if (flags and OpenFlags.O_RDWR.toUInt() != 0u) AccessMode.READ_WRITE else AccessMode.READ
        val file = entry.exported ?: when (val result = entry.buffer.export(
            process.vfsOperationContext, context, access,
        )) {
            is VfsResult.Ok -> result.value.also { entry.exported = it }
            is VfsResult.Err -> return -result.error.errno.toLong()
        }
        val closeOnExec = flags and OpenFlags.O_CLOEXEC.toUInt() != 0u
        val descriptorFlags = if (closeOnExec) FileDescriptorFlags.FD_CLOEXEC else 0uL
        val limit = process.resourceLimits.get(ProcessResource.OPEN_FILES).soft
        check(file.retain())
        val reservation = process.fdTable.reserve(file, descriptorFlags, limit) ?: run {
            file.release()
            return -Errno.EMFILE.toLong()
        }
        return reservation.use {
            data.writeU32(8, it.fd.toUInt())
            if (!argument.write()) return@use -Errno.EFAULT.toLong()
            it.install()
            0L
        }
    }

    fun import(file: OpenFileDescription, data: LittleEndianBuffer): Long {
        val backend = file.backend as? DmaBufferFile ?: return -Errno.EINVAL.toLong()
        val buffer = backend.buffer
        val entry = objects[buffer]
        val handle = entry?.handles?.first() ?: when (val result = add(buffer)) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return -result.error.errno.toLong()
        }
        val selected = checkNotNull(objects[buffer])
        if (selected.exported == null) {
            check(file.retain())
            selected.exported = file
        }
        data.writeU32(0, handle)
        return 0
    }

    fun mappingOffset(data: LittleEndianBuffer): Long {
        if (data.readU32(4) != 0u) return -Errno.EINVAL.toLong()
        val entry = handles[data.readU32(0)] ?: return -Errno.ENOENT.toLong()
        data.writeU64(8, entry.offset)
        return 0
    }

    fun destroy(handle: UInt): Long {
        val entry = handles.remove(handle) ?: return -Errno.ENOENT.toLong()
        entry.handles.remove(handle)
        if (entry.handles.isNotEmpty()) return 0
        objects.remove(entry.buffer)
        offsets.remove(entry.offset)
        entry.close()
        return 0
    }

    fun map(
        file: OpenFileDescription,
        offset: ULong,
        length: ULong,
        maximumAccess: ULong,
    ): VfsResult<MappedFile> {
        val buffer = offsets[offset] ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (length > buffer.size) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val mapping = DmaBufferMapping(file, maximumAccess, buffer)
        return VfsResult.Ok(mapping)
    }

    fun close() {
        objects.values.forEach(Entry::close)
        handles.clear()
        objects.clear()
        offsets.clear()
    }
}
