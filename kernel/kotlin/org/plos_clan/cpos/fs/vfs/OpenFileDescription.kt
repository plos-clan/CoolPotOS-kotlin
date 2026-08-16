@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs

import org.plos_clan.cpos.mem.BufferDestination
import org.plos_clan.cpos.mem.BufferSource
import org.plos_clan.cpos.mem.PageCacheProvider
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PollEvents
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

enum class SeekOrigin {
    START,
    CURRENT,
    END,
}

private data object PathOnlyHandle : OpenFileBackend

class OpenFileDescription private constructor(
    val path: VfsPath,
    val inode: Inode,
    val access: AccessMode,
    append: Boolean,
    private val backend: OpenFileBackend,
) {
    private val references = AtomicInt(1)
    private val positionLock = IrqSpinLock()
    private val position = FilePosition()
    private val positionlessBackend = backend as? PositionlessOpenFileBackend
    private val statusFlags = AtomicInt(if (append) OpenFlags.O_APPEND else 0)

    companion object {
        internal fun open(
            path: VfsPath,
            inode: Inode,
            options: OpenOptions,
        ): VfsResult<OpenFileDescription> {
            if (!path.mount.retain()) return VfsResult.Err(VfsError.NOT_FOUND)
            if (!inode.acquireOpenReference()) {
                path.mount.release()
                return VfsResult.Err(VfsError.NOT_FOUND)
            }

            val backend = if (options.access == AccessMode.PATH) {
                PathOnlyHandle
            } else {
                when (val result = inode.backend.open(inode, options)) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> {
                        inode.releaseOpenReference()
                        path.mount.release()
                        return result
                    }
                }
            }
            if (options.truncate && inode.type == InodeType.REGULAR) {
                val result = (inode.backend as? RegularFileBackend)?.resize(inode, 0uL)
                    ?: VfsResult.Err(VfsError.INVALID_ARGUMENT)
                if (result is VfsResult.Err) {
                    backend.release()
                    inode.releaseOpenReference()
                    path.mount.release()
                    return result
                }
            }
            return VfsResult.Ok(
                OpenFileDescription(path, inode, options.access, options.append, backend),
            )
        }
    }

    val offset: Long
        get() = positionLock.withLock { position.value }

    internal val cacheSource: PageCacheSource?
        get() = (backend as? PageCacheProvider)?.cacheSource

    fun getStatusFlags(): Int = statusFlags.load()

    fun setStatusFlags(flags: Int) {
        statusFlags.store(flags and (OpenFlags.O_APPEND or OpenFlags.O_NONBLOCK))
    }

    fun sync(dataOnly: Boolean): VfsResult<Unit> = when {
        references.load() == 0 || access == AccessMode.PATH ->
            VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        inode.type == InodeType.PIPE || inode.type == InodeType.SOCKET ->
            VfsResult.Err(VfsError.INVALID_ARGUMENT)
        else -> inode.backend.sync(inode, dataOnly)
    }

    fun retain(): Boolean {
        val previous = references.fetchAndAdd(1)
        if (previous in 1 until Int.MAX_VALUE) return true
        references.fetchAndAdd(-1)
        return false
    }

    fun release() {
        val previous = references.fetchAndAdd(-1)
        if (previous <= 0) {
            references.fetchAndAdd(1)
        } else if (previous == 1) {
            backend.release()
            inode.releaseOpenReference()
            path.mount.release()
        }
    }

    fun read(destination: BufferDestination, offset: Int, count: Int): IoResult {
        readError(offset, count)?.let { return IoResult.failure(it) }
        val prepared = destination.prepareWrite(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return readBackend(prepared, offset, count, position)
    }

    internal fun read(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
    ): IoResult {
        readError(offset, count)?.let { return IoResult.failure(it) }
        return readBackend(destination, offset, count, position)
    }

    fun readAt(
        fileOffset: ULong,
        destination: BufferDestination,
        offset: Int,
        count: Int,
    ): IoResult {
        if (fileOffset > Long.MAX_VALUE.toULong()) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        readError(offset, count)?.let { return IoResult.failure(it) }
        if (positionlessBackend != null) return IoResult.failure(VfsError.ILLEGAL_SEEK)
        val prepared = destination.prepareWrite(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return readBackend(prepared, offset, count, FilePosition(fileOffset.toLong()))
    }

    fun write(source: BufferSource, offset: Int, count: Int): IoResult {
        writeError(offset, count)?.let { return IoResult.failure(it) }
        val discard = positionlessBackend as? DiscardingOpenFileBackend
        if (discard != null) return discard.discard(inode, count)
        val prepared = source.prepareRead(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return writeBackend(prepared, offset, count, position)
    }

    internal fun write(source: PreparedBufferSource, offset: Int, count: Int): IoResult {
        writeError(offset, count)?.let { return IoResult.failure(it) }
        return writeBackend(source, offset, count, position)
    }

    fun writeAt(
        fileOffset: ULong,
        source: BufferSource,
        offset: Int,
        count: Int,
    ): IoResult {
        if (fileOffset > Long.MAX_VALUE.toULong()) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        writeError(offset, count)?.let { return IoResult.failure(it) }
        if (positionlessBackend != null) return IoResult.failure(VfsError.ILLEGAL_SEEK)
        val prepared = source.prepareRead(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return writeBackend(prepared, offset, count, FilePosition(fileOffset.toLong()))
    }

    fun iterate(emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean): VfsResult<Unit> {
        if (!access.canRead || references.load() == 0) {
            return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }
        if (inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        return positionLock.withLock {
            if (references.load() == 0) {
                return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            }
            backend.iterate(inode, position, emit)
        }
    }

    fun ioctl(command: Int, args: UserMemory): Long = positionLock.withLock {
        if (references.load() == 0) {
            -VfsError.BAD_DESCRIPTOR.errno.toLong()
        } else {
            backend.ioctl(inode, command, args)
        }
    }

    fun poll(events: Int): Long = positionLock.withLock {
        if (references.load() == 0) {
            -VfsError.BAD_DESCRIPTOR.errno.toLong()
        } else if (inode.type == InodeType.REGULAR || inode.type == InodeType.DIRECTORY) {
            (events and PollEvents.DEFAULT_FILE_EVENTS).toLong()
        } else {
            backend.poll(inode, events)
        }
    }

    fun seek(offset: Long, origin: SeekOrigin): VfsResult<Long> {
        if (references.load() == 0) {
            return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }
        return positionLock.withLock {
            if (references.load() == 0) {
                return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            }
            val base = when (origin) {
                SeekOrigin.START -> 0L
                SeekOrigin.CURRENT -> position.value
                SeekOrigin.END -> inode.metadata().size.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
                    ?: return@withLock VfsResult.Err(VfsError.FILE_TOO_LARGE)
            }
            if ((offset > 0 && base > Long.MAX_VALUE - offset) ||
                (offset < 0 && base < Long.MIN_VALUE - offset)
            ) {
                return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val next = base + offset
            if (next < 0) {
                return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            position.value = next
            VfsResult.Ok(next)
        }
    }

    private fun readBackend(
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
        filePosition: FilePosition,
    ): IoResult {
        val positionless = positionlessBackend
        if (positionless == null) {
            if (filePosition !== position) {
                return backend.read(inode, destination, offset, count, filePosition)
            }
            return positionLock.withLock {
                backend.read(inode, destination, offset, count, filePosition)
            }
        }
        val waitable = positionless as? WaitableOpenFileBackend
            ?: return positionless.read(inode, destination, offset, count)
        while (true) {
            val mode = currentIoMode()
            val result = waitable.read(inode, destination, offset, count)
            if (result.error != VfsError.WOULD_BLOCK || mode == IoMode.NON_BLOCKING) return result
            waitable.await(IoEvent.READABLE, count)
        }
    }

    private fun writeBackend(
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        filePosition: FilePosition,
    ): IoResult {
        val positionless = positionlessBackend
        if (positionless == null) {
            if (filePosition !== position) {
                return backend.write(inode, source, offset, count, filePosition, false)
            }
            return positionLock.withLock {
                val append = statusFlags.load() and OpenFlags.O_APPEND != 0
                backend.write(inode, source, offset, count, filePosition, append)
            }
        }
        val waitable = positionless as? WaitableOpenFileBackend
            ?: return positionless.write(inode, source, offset, count)
        var transferred = 0
        while (transferred < count) {
            val remaining = count - transferred
            val mode = currentIoMode()
            val result = waitable.write(inode, source, offset + transferred, remaining, mode)
            if (result.isSuccess) {
                val current = result.bytesTransferred
                if (current == 0) return IoResult.success(transferred)
                transferred += current
                if (transferred == count) return IoResult.success(transferred)
            } else if (result.error != VfsError.WOULD_BLOCK) {
                return if (transferred == 0) result else IoResult.success(transferred)
            }
            if (mode == IoMode.NON_BLOCKING) {
                return if (transferred == 0) result else IoResult.success(transferred)
            }

            waitable.await(IoEvent.WRITABLE, count - transferred)
        }
        return IoResult.success(transferred)
    }

    private fun readError(offset: Int, count: Int): VfsError? = when {
        offset < 0 || count < 0 -> VfsError.INVALID_ARGUMENT
        references.load() == 0 || !access.canRead -> VfsError.BAD_DESCRIPTOR
        inode.type == InodeType.DIRECTORY -> VfsError.IS_DIRECTORY
        else -> null
    }

    private fun writeError(offset: Int, count: Int): VfsError? = when {
        offset < 0 || count < 0 -> VfsError.INVALID_ARGUMENT
        references.load() == 0 || !access.canWrite -> VfsError.BAD_DESCRIPTOR
        inode.type == InodeType.REGULAR && MountFlag.READ_ONLY in path.mount.flags ->
            VfsError.READ_ONLY
        inode.type == InodeType.DIRECTORY -> VfsError.IS_DIRECTORY
        else -> null
    }

    private fun currentIoMode(): IoMode =
        if (statusFlags.load() and OpenFlags.O_NONBLOCK == 0) IoMode.BLOCKING
        else IoMode.NON_BLOCKING
}
