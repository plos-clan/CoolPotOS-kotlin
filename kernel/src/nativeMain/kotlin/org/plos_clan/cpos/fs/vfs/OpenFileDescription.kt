@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.vfs

import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.mem.BufferDestination
import org.plos_clan.cpos.mem.BufferSource
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheProvider
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.utils.KernelMutex
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
    private val fixedSizeIoBackend = backend as? FixedSizeIoOpenFileBackend
    private val statusFlags = AtomicInt(initialStatusFlags)

    companion object {
        private const val COPY_BUFFER_SIZE = 1024 * 1024
        private val copyPositionLock = KernelMutex()

        internal fun open(
            caller: VfsOperationContext,
            path: VfsPath,
            inode: Inode,
            options: OpenOptions,
            truncate: Boolean = options.truncate,
            openedBackend: OpenFileBackend? = null,
            initialStatusFlags: Int = 0,
        ): VfsResult<OpenFileDescription> {
            if (!path.mount.retain()) {
                openedBackend?.release()
                return VfsResult.Err(VfsError.NOT_FOUND)
            }
            if (!inode.acquireOpenReference()) {
                openedBackend?.release()
                path.mount.release()
                return VfsResult.Err(VfsError.NOT_FOUND)
            }

            val backend = openedBackend ?: if (options.access == AccessMode.PATH) {
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
            if (truncate && !backend.handlesOpenTruncate && inode.type == InodeType.REGULAR) {
                val result = (inode.backend as? RegularFileBackend)?.resize(caller, inode, 0uL)
                    ?: VfsResult.Err(VfsError.INVALID_ARGUMENT)
                if (result is VfsResult.Err) {
                    backend.release()
                    inode.releaseOpenReference()
                    path.mount.release()
                    return result
                }
                PageCache.invalidate(inode)
                val identity = inode.backend.pageCacheIdentity(inode)
                if (identity != inode) PageCache.invalidate(identity)
            }
            val file = OpenFileDescription(
                path,
                inode,
                options.access,
                initialStatusFlags or
                    (if (options.append) OpenFlags.O_APPEND else 0) or
                    (if (options.nonBlocking) OpenFlags.O_NONBLOCK else 0) or
                    (if (options.noAtime) OpenFlags.O_NOATIME else 0),
                backend,
            )
            if (options.access != AccessMode.PATH) {
                path.notify(inode, FileSystemEvent.OPENED)
                if (truncate && inode.type == InodeType.REGULAR) {
                    path.notify(inode, FileSystemEvent.MODIFIED)
                }
            }
            return VfsResult.Ok(file)
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
        val mutable = OpenFlags.O_APPEND or OpenFlags.O_NONBLOCK or OpenFlags.O_NOATIME
        while (true) {
            val observed = statusFlags.load()
            val updated = observed and mutable.inv() or (flags and mutable)
            if (statusFlags.compareAndSet(observed, updated)) return
        }
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

    fun allocate(
        caller: VfsOperationContext,
        offset: ULong,
        length: ULong,
        mode: FileAllocationMode,
    ): VfsResult<Unit> {
        if (references.load() == 0 || !access.canWrite) {
            return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }
        val result = (backend as? AllocatingOpenFileBackend)?.allocate(
            caller,
            inode,
            offset,
            length,
            mode,
        ) ?: (inode.backend as? RegularFileBackend)?.allocate(
            caller,
            inode,
            offset,
            length,
            mode,
        ) ?: VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (result is VfsResult.Ok) {
            invalidateCachedRange(offset, length)
            path.notify(inode, FileSystemEvent.MODIFIED)
        }
        return result
    }

    fun copyFileRange(
        caller: VfsOperationContext,
        destination: OpenFileDescription,
        sourceOffset: ULong?,
        destinationOffset: ULong?,
        length: ULong,
        flags: UInt,
    ): VfsResult<ULong> {
        if (references.load() == 0 || !access.canRead ||
            destination.references.load() == 0 || !destination.access.canWrite
        ) {
            return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }
        if (inode.type != InodeType.REGULAR || destination.inode.type != InodeType.REGULAR) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        if (MountFlag.READ_ONLY in destination.path.mount.flags) {
            return VfsResult.Err(VfsError.READ_ONLY)
        }
        if (destination.statusFlags.load() and OpenFlags.O_APPEND != 0) {
            return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        }
        if (length == 0uL) return VfsResult.Ok(0uL)
        if (sourceOffset != null && destinationOffset != null) {
            return copyFileRangeAt(
                caller,
                destination,
                sourceOffset,
                destinationOffset,
                length,
                flags,
            )
        }
        return copyPositionLock.withLock {
            positionLock.withLock {
                if (this === destination) {
                    copyFileRangeAtCurrentPositions(
                        caller,
                        destination,
                        sourceOffset,
                        destinationOffset,
                        length,
                        flags,
                    )
                } else {
                    destination.positionLock.withLock {
                        copyFileRangeAtCurrentPositions(
                            caller,
                            destination,
                            sourceOffset,
                            destinationOffset,
                            length,
                            flags,
                        )
                    }
                }
            }
        }
    }

    fun spliceTo(
        caller: VfsOperationContext,
        destination: OpenFileDescription,
        sourceOffset: ULong?,
        destinationOffset: ULong?,
        count: Int,
        nonBlocking: Boolean,
    ): IoResult {
        if (count < 0) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        if (references.load() == 0 || !access.canRead ||
            destination.references.load() == 0 || !destination.access.canWrite
        ) return IoResult.failure(VfsError.BAD_DESCRIPTOR)

        val sourceIsPipe = inode.type == InodeType.PIPE
        val destinationIsPipe = destination.inode.type == InodeType.PIPE
        if (!sourceIsPipe && !destinationIsPipe) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        if ((!sourceIsPipe && backend is AnonymousFileBackend) ||
            (!destinationIsPipe && destination.backend is AnonymousFileBackend)
        ) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        if ((sourceOffset != null && positionlessBackend != null) ||
            (destinationOffset != null && destination.positionlessBackend != null)
        ) return IoResult.failure(VfsError.ILLEGAL_SEEK)
        if (!destinationIsPipe &&
            destination.statusFlags.load() and OpenFlags.O_APPEND != 0
        ) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        if (count == 0) return IoResult.success(0)

        val sourceMode = ioMode(nonBlocking)
        val destinationMode = destination.ioMode(nonBlocking)
        val result = if (!sourceIsPipe && sourceOffset == null && positionlessBackend == null) {
            positionLock.withLock {
                val result = spliceAt(
                    caller,
                    destination,
                    position.value.toULong(),
                    destinationOffset,
                    count,
                    sourceMode,
                    destinationMode,
                )
                if (result.isSuccess) position.value += result.bytesTransferred
                result
            }
        } else if (!destinationIsPipe && destinationOffset == null &&
            destination.positionlessBackend == null
        ) {
            destination.positionLock.withLock {
                val result = spliceAt(
                    caller,
                    destination,
                    sourceOffset,
                    destination.position.value.toULong(),
                    count,
                    sourceMode,
                    destinationMode,
                )
                if (result.isSuccess) destination.position.value += result.bytesTransferred
                result
            }
        } else {
            spliceAt(
                caller,
                destination,
                sourceOffset,
                destinationOffset,
                count,
                sourceMode,
                destinationMode,
            )
        }
        return result.recordSplice(caller, destination)
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
            if (access != AccessMode.PATH) {
                path.notify(
                    inode,
                    if (access.canWrite) FileSystemEvent.CLOSED_WRITE
                    else FileSystemEvent.CLOSED_READ,
                )
            }
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
        val transferCount = fixedSizeIoBackend?.ioSize ?: count
        val prepared = destination.prepareWrite(offset, transferCount)
            ?: return IoResult.failure(VfsError.FAULT)
        return readBackend(caller, prepared, offset, transferCount, position)
    }

    internal fun read(
        caller: VfsOperationContext,
        destination: PreparedBufferDestination,
        offset: Int,
        count: Int,
    ): IoResult {
        readError(offset, count)?.let { return IoResult.failure(it) }
        return readBackend(caller, destination, offset, fixedSizeIoBackend?.ioSize ?: count, position)
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
        if (positionlessBackend != null) return IoResult.failure(VfsError.ILLEGAL_SEEK)
        readError(offset, count)?.let { return IoResult.failure(it) }
        val transferCount = fixedSizeIoBackend?.ioSize ?: count
        val prepared = destination.prepareWrite(offset, transferCount)
            ?: return IoResult.failure(VfsError.FAULT)
        return readBackend(caller, prepared, offset, transferCount, FilePosition(fileOffset.toLong()))
    }

    fun write(
        caller: VfsOperationContext,
        source: BufferSource,
        offset: Int,
        count: Int,
    ): IoResult {
        writeError(offset, count)?.let { return IoResult.failure(it) }
        val discard = positionlessBackend as? DiscardingOpenFileBackend
        if (discard != null) return discard.discard(inode, count).recordModification()
        val prepared = source.prepareRead(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return writeBackend(caller, prepared, offset, count, position).recordModification()
    }

    internal fun write(
        caller: VfsOperationContext,
        source: PreparedBufferSource,
        offset: Int,
        count: Int,
    ): IoResult {
        writeError(offset, count)?.let { return IoResult.failure(it) }
        return writeBackend(caller, source, offset, count, position).recordModification()
    }

    internal fun readForSplice(
        caller: VfsOperationContext,
        destination: PreparedBufferDestination,
        count: Int,
        fileOffset: ULong?,
        mode: IoMode,
    ): IoResult {
        readError(0, count)?.let { return IoResult.failure(it) }
        if (positionlessBackend == null) {
            val offset = fileOffset ?: return IoResult.failure(VfsError.INVALID_ARGUMENT)
            if (offset > Long.MAX_VALUE.toULong()) {
                return IoResult.failure(VfsError.INVALID_ARGUMENT)
            }
            return readBackend(
                caller,
                destination,
                0,
                fixedSizeIoBackend?.ioSize ?: count,
                FilePosition(offset.toLong()),
                forcedMode = mode,
                notifyAccess = false,
            )
        }
        if (fileOffset != null) return IoResult.failure(VfsError.ILLEGAL_SEEK)
        return readBackend(
            caller,
            destination,
            0,
            count,
            FilePosition(),
            forcedMode = mode,
            notifyAccess = false,
        )
    }

    internal fun writeForSplice(
        caller: VfsOperationContext,
        source: PreparedBufferSource,
        count: Int,
        fileOffset: ULong?,
        mode: IoMode,
    ): IoResult {
        writeError(0, count)?.let { return IoResult.failure(it) }
        if (positionlessBackend == null) {
            val offset = fileOffset ?: return IoResult.failure(VfsError.INVALID_ARGUMENT)
            if (offset > Long.MAX_VALUE.toULong()) {
                return IoResult.failure(VfsError.INVALID_ARGUMENT)
            }
            return writeBackend(
                caller,
                source,
                0,
                count,
                FilePosition(offset.toLong()),
                mode,
            )
        }
        if (fileOffset != null) return IoResult.failure(VfsError.ILLEGAL_SEEK)
        return writeBackend(caller, source, 0, count, FilePosition(), mode)
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
        if (positionlessBackend != null) return IoResult.failure(VfsError.ILLEGAL_SEEK)
        writeError(offset, count)?.let { return IoResult.failure(it) }
        val prepared = source.prepareRead(offset, count)
            ?: return IoResult.failure(VfsError.FAULT)
        return writeBackend(
            caller,
            prepared,
            offset,
            count,
            FilePosition(fileOffset.toLong()),
        ).recordModification()
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
            backend.iterate(caller, inode, position) { entry, nextOffset ->
                entry.lookup?.let { path.dentry.cacheChild(entry.name, it) }
                emit(entry, nextOffset)
            }
        }
        if (result is VfsResult.Ok) {
            recordAccess(caller)
            path.notify(inode, FileSystemEvent.ACCESSED)
        }
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
        if (!backend.seekable) return VfsResult.Err(VfsError.ILLEGAL_SEEK)
        return positionLock.withLock {
            if (references.load() == 0) {
                return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            }
            if (backend is NoopSeekOpenFileBackend) {
                return@withLock VfsResult.Ok(position.value)
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
        forcedMode: IoMode? = null,
        notifyAccess: Boolean = true,
    ): IoResult {
        val positionless = positionlessBackend
        if (positionless == null) {
            if (filePosition !== position) {
                return backend.read(caller, inode, destination, offset, count, filePosition)
                    .recordAccess(caller, notifyAccess)
            }
            return positionLock.withLock {
                backend.read(caller, inode, destination, offset, count, filePosition)
            }.recordAccess(caller, notifyAccess)
        }
        if (positionless is ModeAwareOpenFileBackend) {
            return positionless.read(
                caller,
                inode,
                destination,
                offset,
                count,
                forcedMode ?: currentIoMode(),
            )
                .recordAccess(caller, notifyAccess)
        }
        val waitable = positionless as? WaitableOpenFileBackend
            ?: return positionless.read(caller, inode, destination, offset, count)
                .recordAccess(caller, notifyAccess)
        while (true) {
            val mode = forcedMode ?: currentIoMode()
            val result = waitable.read(caller, inode, destination, offset, count)
            if (result.error != VfsError.WOULD_BLOCK || mode == IoMode.NON_BLOCKING) {
                return result.recordAccess(caller, notifyAccess)
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
        forcedMode: IoMode? = null,
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
            return positionless.write(
                caller,
                inode,
                source,
                offset,
                count,
                forcedMode ?: currentIoMode(),
            )
        }
        val waitable = positionless as? WaitableOpenFileBackend
            ?: return positionless.write(caller, inode, source, offset, count)
        var transferred = 0
        while (transferred < count) {
            val remaining = count - transferred
            val mode = forcedMode ?: currentIoMode()
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
            invalidateCachedRange(start.toULong(), transferred.toULong())
        }
        return result
    }

    private fun spliceAt(
        caller: VfsOperationContext,
        destination: OpenFileDescription,
        sourceOffset: ULong?,
        destinationOffset: ULong?,
        count: Int,
        sourceMode: IoMode,
        destinationMode: IoMode,
    ): IoResult {
        val source = backend as? SpliceSourceOpenFileBackend
        if (source != null) {
            return source.spliceTo(
                caller,
                inode,
                destination,
                destinationOffset,
                count,
                sourceMode,
                destinationMode,
            )
        }
        val target = destination.backend as? SpliceDestinationOpenFileBackend
            ?: return IoResult.failure(VfsError.INVALID_ARGUMENT)
        return target.spliceFrom(
            caller,
            destination.inode,
            this,
            sourceOffset,
            count,
            sourceMode,
            destinationMode,
        )
    }

    private fun copyFileRangeAtCurrentPositions(
        caller: VfsOperationContext,
        destination: OpenFileDescription,
        sourceOffset: ULong?,
        destinationOffset: ULong?,
        length: ULong,
        flags: UInt,
    ): VfsResult<ULong> {
        val sourcePosition = sourceOffset ?: position.value.toULong()
        val destinationPosition = destinationOffset ?: destination.position.value.toULong()
        val result = copyFileRangeAt(
            caller,
            destination,
            sourcePosition,
            destinationPosition,
            length,
            flags,
        )
        if (result is VfsResult.Ok) {
            val copied = result.value.toLong()
            if (sourceOffset == null) position.value += copied
            if (destinationOffset == null) destination.position.value += copied
        }
        return result
    }

    private fun copyFileRangeAt(
        caller: VfsOperationContext,
        destination: OpenFileDescription,
        sourceOffset: ULong,
        destinationOffset: ULong,
        length: ULong,
        flags: UInt,
    ): VfsResult<ULong> {
        if (sourceOffset > Long.MAX_VALUE.toULong() ||
            destinationOffset > Long.MAX_VALUE.toULong() ||
            length > Long.MAX_VALUE.toULong() - sourceOffset ||
            length > Long.MAX_VALUE.toULong() - destinationOffset
        ) {
            return VfsResult.Err(VfsError.FILE_TOO_LARGE)
        }
        if (inode.superBlock === destination.inode.superBlock &&
            inode.id == destination.inode.id && inode.generation == destination.inode.generation &&
            sourceOffset < destinationOffset + length &&
            destinationOffset < sourceOffset + length
        ) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }

        val accelerated = (backend as? CopyingOpenFileBackend)?.copyFileRange(
            caller,
            inode,
            sourceOffset,
            destination.inode,
            destination.backend,
            destinationOffset,
            length,
            flags,
        )
        val result = when (accelerated) {
            null -> copyFileRangeFallback(
                caller,
                destination,
                sourceOffset,
                destinationOffset,
                length,
            )
            is VfsResult.Ok -> accelerated
            is VfsResult.Err -> if (accelerated.error == VfsError.NOT_SUPPORTED ||
                accelerated.error == VfsError.CROSS_DEVICE
            ) {
                copyFileRangeFallback(caller, destination, sourceOffset, destinationOffset, length)
            } else {
                accelerated
            }
        }

        if (result is VfsResult.Ok) {
            if (result.value > length) return VfsResult.Err(VfsError.IO)
            if (result.value != 0uL && accelerated is VfsResult.Ok) {
                destination.invalidateCachedRange(destinationOffset, result.value)
                destination.inode.invalidateAttributes()
                recordAccess(caller)
                path.notify(inode, FileSystemEvent.ACCESSED)
                destination.path.notify(destination.inode, FileSystemEvent.MODIFIED)
            }
        }
        return result
    }

    private fun copyFileRangeFallback(
        caller: VfsOperationContext,
        destination: OpenFileDescription,
        sourceOffset: ULong,
        destinationOffset: ULong,
        length: ULong,
    ): VfsResult<ULong> {
        val bytes = try {
            ByteArray(minOf(length, COPY_BUFFER_SIZE.toULong()).toInt())
        } catch (_: OutOfMemoryError) {
            return VfsResult.Err(VfsError.NO_MEMORY)
        }
        val buffer = ByteArrayBuffer(bytes)
        var copied = 0uL
        while (copied < length) {
            val count = minOf(bytes.size.toULong(), length - copied).toInt()
            val read = readAt(caller, sourceOffset + copied, buffer, 0, count)
            if (!read.isSuccess) {
                return if (copied == 0uL) VfsResult.Err(checkNotNull(read.error))
                else VfsResult.Ok(copied)
            }
            val available = read.bytesTransferred
            if (available == 0) break
            val written = destination.writeAt(
                caller,
                destinationOffset + copied,
                buffer,
                0,
                available,
            )
            if (!written.isSuccess) {
                return if (copied == 0uL) VfsResult.Err(checkNotNull(written.error))
                else VfsResult.Ok(copied)
            }
            copied += written.bytesTransferred.toULong()
            if (written.bytesTransferred < available) break
        }
        return VfsResult.Ok(copied)
    }

    private fun invalidateCachedRange(offset: ULong, length: ULong) {
        PageCache.invalidate(inode, offset, length)
        val backendIdentity = inode.backend.pageCacheIdentity(inode)
        if (backendIdentity != inode) PageCache.invalidate(backendIdentity, offset, length)
        val handleIdentity = cacheSource?.identity
        if (handleIdentity != null && handleIdentity != inode &&
            handleIdentity != backendIdentity
        ) {
            PageCache.invalidate(handleIdentity, offset, length)
        }
    }

    private fun readError(offset: Int, count: Int): VfsError? = when {
        offset < 0 || count < 0 -> VfsError.INVALID_ARGUMENT
        count < backend.minimumReadSize -> VfsError.INVALID_ARGUMENT
        references.load() == 0 || !access.canRead -> VfsError.BAD_DESCRIPTOR
        inode.type == InodeType.DIRECTORY -> VfsError.IS_DIRECTORY
        else -> null
    }

    private fun writeError(offset: Int, count: Int): VfsError? = when {
        offset < 0 || count < 0 -> VfsError.INVALID_ARGUMENT
        fixedSizeIoBackend != null && count != fixedSizeIoBackend.ioSize ->
            VfsError.INVALID_ARGUMENT
        references.load() == 0 || !access.canWrite -> VfsError.BAD_DESCRIPTOR
        inode.type == InodeType.REGULAR && MountFlag.READ_ONLY in path.mount.flags ->
            VfsError.READ_ONLY
        inode.type == InodeType.DIRECTORY -> VfsError.IS_DIRECTORY
        else -> null
    }

    private fun currentIoMode(): IoMode =
        ioMode(forceNonBlocking = false)

    private fun ioMode(forceNonBlocking: Boolean): IoMode =
        if (!forceNonBlocking && statusFlags.load() and OpenFlags.O_NONBLOCK == 0) {
            IoMode.BLOCKING
        } else {
            IoMode.NON_BLOCKING
        }

    private fun IoResult.recordAccess(
        caller: VfsOperationContext,
        enabled: Boolean = true,
    ): IoResult {
        if (enabled && isSuccess && bytesTransferred != 0) {
            this@OpenFileDescription.recordAccess(caller)
            path.notify(inode, FileSystemEvent.ACCESSED)
        }
        return this
    }

    private fun IoResult.recordModification(): IoResult {
        if (isSuccess && bytesTransferred != 0) path.notify(inode, FileSystemEvent.MODIFIED)
        return this
    }

    private fun IoResult.recordSplice(
        caller: VfsOperationContext,
        destination: OpenFileDescription,
    ): IoResult {
        if (isSuccess && bytesTransferred != 0) {
            this@OpenFileDescription.recordAccess(caller)
            path.notify(inode, FileSystemEvent.ACCESSED)
            destination.path.notify(destination.inode, FileSystemEvent.MODIFIED)
        }
        return this
    }
}
