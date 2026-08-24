@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.mem.BufferDestination
import org.plos_clan.cpos.mem.BufferSource
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheProvider
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.KernelMutex

enum class SeekOrigin {
    START,
    CURRENT,
    END,
}

private data object PathOnlyHandle : OpenFileBackend
private const val FIONBIO = 0x5421

class OpenFileDescription private constructor(
    val path: VfsPath,
    val inode: Inode,
    val access: AccessMode,
    initialStatusFlags: Int,
    internal val backend: OpenFileBackend,
) {
    private val references = AtomicInt(1)
    private val positionLock = KernelMutex()
    private val position = FilePosition()
    private val positionlessBackend = backend as? PositionlessOpenFileBackend
    private val statusFlags = AtomicInt(initialStatusFlags)

    companion object {
        internal fun open(
            caller: VfsOperationContext,
            path: VfsPath,
            inode: Inode,
            options: OpenOptions,
            truncate: Boolean = options.truncate,
        ): VfsResult<OpenFileDescription> {
            if (!path.mount.retain()) return VfsResult.Err(VfsError.NOT_FOUND)
            if (!inode.acquireOpenReference()) {
                path.mount.release()
                return VfsResult.Err(VfsError.NOT_FOUND)
            }

            val backend = if (options.access == AccessMode.PATH) {
                PathOnlyHandle
            } else {
                when (val result = inode.backend.open(caller, inode, options)) {
                    is VfsResult.Ok -> result.value
                    is VfsResult.Err -> {
                        inode.releaseOpenReference()
                        path.mount.release()
                        return result
                    }
                }
            }
            if (truncate && inode.type == InodeType.REGULAR) {
                val result = (inode.backend as? RegularFileBackend)?.resize(caller, inode, 0uL)
                    ?: VfsResult.Err(VfsError.INVALID_ARGUMENT)
                if (result is VfsResult.Err) {
                    backend.release()
                    inode.releaseOpenReference()
                    path.mount.release()
                    return result
                }
            }
            return VfsResult.Ok(
                OpenFileDescription(
                    path,
                    inode,
                    options.access,
                    (if (options.append) OpenFlags.O_APPEND else 0) or
                        (if (options.nonBlocking) OpenFlags.O_NONBLOCK else 0) or
                        (if (options.noAtime) OpenFlags.O_NOATIME else 0),
                    backend,
                ),
            )
        }
    }

    val offset: Long
        get() = positionLock.withLock { position.value }

    internal val cacheSource: PageCacheSource?
        get() = (backend as? PageCacheProvider)?.cacheSource

    internal val mountResource: MountResource?
        get() = backend as? MountResource
            ?: (backend as? MountResourceProvider)?.mountResource

    fun getStatusFlags(): Int = statusFlags.load()

    fun setStatusFlags(flags: Int) {
        statusFlags.store(
            flags and (OpenFlags.O_APPEND or OpenFlags.O_NONBLOCK or OpenFlags.O_NOATIME),
        )
    }

    internal fun recordAccess(caller: VfsOperationContext) {
        if (statusFlags.load() and OpenFlags.O_NOATIME == 0) {
            path.mount.recordAccess(caller, inode)
        }
    }

    fun sync(caller: VfsOperationContext, dataOnly: Boolean): VfsResult<Unit> = when {
        references.load() == 0 || access == AccessMode.PATH ->
            VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        inode.type == InodeType.PIPE || inode.type == InodeType.SOCKET ->
            VfsResult.Err(VfsError.INVALID_ARGUMENT)
        else -> backend.syncHandle(caller, inode, dataOnly)
    }

    internal fun flush(caller: VfsOperationContext): VfsResult<Unit> = when {
        references.load() == 0 || access == AccessMode.PATH -> VfsResult.Ok(Unit)
        else -> backend.flush(caller, inode)
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

    fun read(
        caller: VfsOperationContext,
        destination: BufferDestination,
        offset: Int,
        count: Int,
    ): IoResult {
        readError(offset, count)?.let { return IoResult.failure(it) }
        val prepared = destination.prepareWrite(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return readBackend(caller, prepared, offset, count, position)
    }

    internal fun read(
        caller: VfsOperationContext,
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
    ): IoResult {
        readError(offset, count)?.let { return IoResult.failure(it) }
        return readBackend(caller, destination, offset, count, position)
    }

    fun readAt(
        caller: VfsOperationContext,
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
        return readBackend(caller, prepared, offset, count, FilePosition(fileOffset.toLong()))
    }

    fun write(
        caller: VfsOperationContext,
        source: BufferSource,
        offset: Int,
        count: Int,
    ): IoResult {
        writeError(offset, count)?.let { return IoResult.failure(it) }
        val discard = positionlessBackend as? DiscardingOpenFileBackend
        if (discard != null) return discard.discard(inode, count)
        val prepared = source.prepareRead(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return writeBackend(caller, prepared, offset, count, position)
    }

    internal fun write(
        caller: VfsOperationContext,
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
    ): IoResult {
        writeError(offset, count)?.let { return IoResult.failure(it) }
        return writeBackend(caller, source, offset, count, position)
    }

    fun writeAt(
        caller: VfsOperationContext,
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
        return writeBackend(caller, prepared, offset, count, FilePosition(fileOffset.toLong()))
    }

    fun iterate(
        caller: VfsOperationContext,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> {
        if (!access.canRead || references.load() == 0) {
            return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }
        if (inode.type != InodeType.DIRECTORY) {
            return VfsResult.Err(VfsError.NOT_DIRECTORY)
        }
        val result = positionLock.withLock {
            if (references.load() == 0) {
                return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            }
            backend.iterate(caller, inode, position, emit)
        }
        if (result is VfsResult.Ok) recordAccess(caller)
        return result
    }

    fun ioctl(caller: VfsOperationContext, command: Int, args: UserMemory): Long {
        if (references.load() == 0) {
            return -VfsError.BAD_DESCRIPTOR.errno.toLong()
        } else if (command == FIONBIO) {
            val enabled = args.readUIntLE() ?: return -VfsError.FAULT.errno.toLong()
            while (true) {
                val observed = statusFlags.load()
                val updated = if (enabled == 0u) {
                    observed and OpenFlags.O_NONBLOCK.inv()
                } else {
                    observed or OpenFlags.O_NONBLOCK
                }
                if (statusFlags.compareAndSet(observed, updated)) break
            }
            return 0L
        } else {
            return backend.ioctl(caller, inode, command, args)
        }
    }

    fun poll(caller: VfsOperationContext, events: Int): Long =
        if (references.load() == 0) -VfsError.BAD_DESCRIPTOR.errno.toLong()
        else backend.poll(caller, inode, events)

    fun seek(caller: VfsOperationContext, offset: Long, origin: SeekOrigin): VfsResult<Long> {
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
                SeekOrigin.END -> {
                    val attributes = when (val result = inode.attributes(caller)) {
                        is VfsResult.Ok -> result.value
                        is VfsResult.Err -> return@withLock result
                    }
                    attributes.metadata.size.takeIf { it <= Long.MAX_VALUE.toULong() }?.toLong()
                        ?: return@withLock VfsResult.Err(VfsError.FILE_TOO_LARGE)
                }
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
        caller: VfsOperationContext,
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
        filePosition: FilePosition,
    ): IoResult {
        val positionless = positionlessBackend
        if (positionless == null) {
            if (filePosition !== position) {
                return backend.read(caller, inode, destination, offset, count, filePosition)
                    .recordAccess(caller, count)
            }
            return positionLock.withLock {
                backend.read(caller, inode, destination, offset, count, filePosition)
            }.recordAccess(caller, count)
        }
        if (positionless is ModeAwareOpenFileBackend) {
            return positionless.read(caller, inode, destination, offset, count, currentIoMode())
                .recordAccess(caller, count)
        }
        val waitable = positionless as? WaitableOpenFileBackend
            ?: return positionless.read(caller, inode, destination, offset, count)
                .recordAccess(caller, count)
        while (true) {
            val mode = currentIoMode()
            val result = waitable.read(caller, inode, destination, offset, count)
            if (result.error != VfsError.WOULD_BLOCK || mode == IoMode.NON_BLOCKING) {
                return result.recordAccess(caller, count)
            }
            if (!waitable.await(IoEvent.READABLE, count)) {
                return IoResult.failure(VfsError.INTERRUPTED)
            }
        }
    }

    private fun writeBackend(
        caller: VfsOperationContext,
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        filePosition: FilePosition,
    ): IoResult {
        val positionless = positionlessBackend
        if (positionless == null) {
            if (filePosition !== position) {
                return writePositioned(caller, source, offset, count, filePosition, false)
            }
            return positionLock.withLock {
                val append = statusFlags.load() and OpenFlags.O_APPEND != 0
                writePositioned(caller, source, offset, count, filePosition, append)
            }
        }
        if (positionless is ModeAwareOpenFileBackend) {
            return positionless.write(caller, inode, source, offset, count, currentIoMode())
        }
        val waitable = positionless as? WaitableOpenFileBackend
            ?: return positionless.write(caller, inode, source, offset, count)
        var transferred = 0
        while (transferred < count) {
            val remaining = count - transferred
            val mode = currentIoMode()
            val result = waitable.write(
                caller,
                inode,
                source,
                offset + transferred,
                remaining,
                mode,
            )
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

            if (!waitable.await(IoEvent.WRITABLE, count - transferred)) {
                return if (transferred == 0) IoResult.failure(VfsError.INTERRUPTED)
                else IoResult.success(transferred)
            }
        }
        return IoResult.success(transferred)
    }

    private fun writePositioned(
        caller: VfsOperationContext,
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
        filePosition: FilePosition,
        append: Boolean,
    ): IoResult {
        val initialPosition = filePosition.value
        val result = backend.write(
            caller,
            inode,
            source,
            offset,
            count,
            filePosition,
            append,
        )
        val transferred = result.bytesTransferred
        if (!result.isSuccess || transferred == 0) return result

        val end = filePosition.value
        val start = if (end != initialPosition && end >= transferred.toLong()) {
            end - transferred
        } else {
            initialPosition
        }
        if (start >= 0) {
            PageCache.invalidate(inode, start.toULong(), transferred.toULong())
            val backendIdentity = inode.backend.pageCacheIdentity(inode)
            if (backendIdentity != inode) {
                PageCache.invalidate(backendIdentity, start.toULong(), transferred.toULong())
            }
            val handleIdentity = cacheSource?.identity
            if (handleIdentity != null && handleIdentity != inode &&
                handleIdentity != backendIdentity
            ) {
                PageCache.invalidate(handleIdentity, start.toULong(), transferred.toULong())
            }
        }
        return result
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

    private fun IoResult.recordAccess(caller: VfsOperationContext, requested: Int): IoResult {
        if (isSuccess && requested != 0) this@OpenFileDescription.recordAccess(caller)
        return this
    }
}
