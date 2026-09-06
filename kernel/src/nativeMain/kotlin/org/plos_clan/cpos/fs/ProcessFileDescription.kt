@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.IrqSpinLock
import kotlin.concurrent.atomics.AtomicReference

object OpenFlags {
    const val O_ACCMODE = 0x000003
    const val O_RDONLY = 0x000000
    const val O_WRONLY = 0x000001
    const val O_RDWR = 0x000002

    const val O_CREAT = 0x000040
    const val O_EXCL = 0x000080
    const val O_NOCTTY = 0x000100
    const val O_TRUNC = 0x000200
    const val O_APPEND = 0x000400
    const val O_NONBLOCK = 0x000800

    const val O_DSYNC = 0x001000
    const val O_SYNC = 0x101000
    const val O_RSYNC = 0x101000

    const val O_DIRECTORY = 0x010000
    const val O_NOFOLLOW = 0x020000
    const val O_CLOEXEC = 0x080000

    const val O_ASYNC = 0x002000
    const val O_DIRECT = 0x004000
    const val O_LARGEFILE = 0x008000
    const val O_NOATIME = 0x040000

    const val O_PATH = 0x200000
    const val O_TMPFILE = 0x410000

    const val O_NDELAY = O_NONBLOCK
}

object FileDescriptorFlags {
    const val FD_CLOEXEC = 1uL
}

class FileDescriptorTable {
    internal sealed interface Entry

    private data class FileDescriptor(
        val file: OpenFileDescription,
        val flags: ULong,
    ) : Entry

    internal inner class Reservation internal constructor(
        val fd: Int,
        file: OpenFileDescription,
        flags: ULong,
    ) : Entry, AutoCloseable {
        private val descriptor = FileDescriptor(file, flags)

        fun install(): Int = lock.withLock {
            check(entries.slot(fd) === this) { "Descriptor reservation is no longer pending" }
            entries[fd] = descriptor
            fd
        }

        override fun close() {
            val cancelled = lock.withLock {
                if (entries.slot(fd) !== this) return@withLock false
                entries[fd] = null
                true
            }
            if (cancelled) descriptor.file.release()
        }
    }

    enum class CloseRangeAction {
        CLOSE,
        MARK_CLOSE_ON_EXEC,
    }

    private val entries = DescriptorEntries(LIMIT)
    private val lock = IrqSpinLock()

    fun installExact(
        fd: Int, file: OpenFileDescription, flags: ULong
    ): Boolean = lock.withLock {
        if (fd !in entries.indices || entries.slot(fd) != null) {
            return@withLock false
        }

        entries[fd] = FileDescriptor(file, flags)
        true
    }

    fun install(
        file: OpenFileDescription,
        flags: ULong,
        minimum: Int = 0,
    ): Int? = lock.withLock {
        val fd = entries.firstEmpty(minimum) ?: return@withLock null
        entries[fd] = FileDescriptor(file, flags)
        fd
    }

    internal fun reserve(
        file: OpenFileDescription,
        flags: ULong,
        limit: ULong,
    ): Reservation? = lock.withLock {
        val fd = entries.firstEmpty(0, limit) ?: return@withLock null
        Reservation(fd, file, flags).also { entries[fd] = it }
    }

    fun installAll(
        files: List<OpenFileDescription>,
        flags: ULong,
        minimum: Int = 0,
    ): IntArray? = lock.withLock {
        val descriptors = entries.emptyIndices(files.size, minimum) ?: return@withLock null
        entries.install(descriptors, files, flags)
        descriptors
    }

    fun installAvailable(
        files: List<OpenFileDescription>,
        flags: ULong,
        maximum: Int = files.size,
    ): IntArray = lock.withLock {
        val count = minOf(files.size, maximum.coerceAtLeast(0))
        val descriptors = entries.emptyIndicesUpTo(count)
        entries.install(descriptors, files, flags)
        descriptors
    }

    fun contains(fd: Int): Boolean = entries[fd] != null

    fun descriptorFlags(fd: Int): ULong? = entries[fd]?.flags

    fun setDescriptorFlags(fd: Int, flags: ULong): Boolean = lock.withLock {
        val descriptor = entries[fd] ?: return@withLock false
        entries[fd] = descriptor.copy(flags = flags)
        true
    }

    fun duplicate(fd: Int, minimum: Int, flags: ULong, limit: ULong): VfsResult<Int> = lock.withLock {
        val source = entries[fd] ?: return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        val target = entries.firstEmpty(minimum, limit)
            ?: return@withLock VfsResult.Err(VfsError.TOO_MANY_OPEN_FILES)
        if (!source.file.retain()) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        entries[target] = FileDescriptor(source.file, flags)
        VfsResult.Ok(target)
    }

    fun acquire(fd: Int): OpenFileDescription? {
        val file = entries[fd]?.file ?: return null
        return if (file.retain()) file else null
    }

    fun acquire(fd: ULong): OpenFileDescription? {
        val file = entries[fd]?.file ?: return null
        return if (file.retain()) file else null
    }

    fun snapshotDescriptors(): IntArray = lock.withLock { entries.occupiedIndices() }

    fun duplicateTo(
        caller: VfsOperationContext,
        oldFd: Int,
        newFd: Int,
        flags: ULong = 0uL,
        limit: ULong = LIMIT.toULong(),
    ): VfsResult<Int> {
        var replaced: OpenFileDescription? = null
        val result = lock.withLock {
            val source = entries[oldFd] ?: return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            if (oldFd == newFd) return@withLock VfsResult.Ok(newFd)
            if (newFd !in entries.indices || newFd.toULong() >= limit) {
                return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)
            }
            val target = entries.slot(newFd)
            if (target is Reservation) return@withLock VfsResult.Err(VfsError.BUSY)
            if (!source.file.retain()) return@withLock VfsResult.Err(VfsError.BAD_DESCRIPTOR)

            replaced = (target as? FileDescriptor)?.file
            entries[newFd] = FileDescriptor(source.file, flags)
            VfsResult.Ok(newFd)
        }
        // Closing can block; dup2/dup3 deliberately ignore the displaced descriptor's close errors.
        replaced?.closeDescriptor(caller)
        return result
    }

    fun close(caller: VfsOperationContext, fd: Int): VfsResult<Unit> {
        val file = lock.withLock {
            val openFile = entries[fd]?.file ?: return@withLock null
            entries[fd] = null
            openFile
        } ?: return VfsResult.Err(VfsError.BAD_DESCRIPTOR)

        return file.closeDescriptor(caller)
    }

    fun closeRange(
        caller: VfsOperationContext,
        first: UInt,
        last: UInt,
        action: CloseRangeAction,
    ) {
        if (first >= LIMIT.toUInt()) return

        val end = minOf(last.toULong() + 1uL, LIMIT.toULong()).toInt()
        val descriptors = lock.withLock {
            entries.replaceRange(first.toInt(), end) { descriptor ->
                when (action) {
                    CloseRangeAction.CLOSE -> null
                    CloseRangeAction.MARK_CLOSE_ON_EXEC ->
                        if (descriptor.flags and FileDescriptorFlags.FD_CLOEXEC != 0uL) descriptor
                        else descriptor.copy(
                            flags = descriptor.flags or FileDescriptorFlags.FD_CLOEXEC,
                        )
                }
            }
        }
        descriptors.forEach { it.file.closeDescriptor(caller) }
    }

    fun copyInto(destination: FileDescriptorTable): Boolean {
        val descriptors = lock.withLock {
            entries.indices.mapNotNull { fd ->
                val descriptor = entries[fd] ?: return@mapNotNull null
                check(descriptor.file.retain())
                fd to descriptor
            }
        }
        return destination.lock.withLock {
            if (descriptors.any { (fd, _) -> destination.entries.slot(fd) != null }) {
                descriptors.forEach { (_, descriptor) -> descriptor.file.release() }
                false
            } else {
                descriptors.forEach { (fd, descriptor) -> destination.entries[fd] = descriptor }
                true
            }
        }
    }

    fun closeOnExec(caller: VfsOperationContext) = closeMatching(caller) { descriptor ->
        descriptor.flags and FileDescriptorFlags.FD_CLOEXEC != 0uL
    }

    fun closeAll(caller: VfsOperationContext) = closeMatching(caller) { true }

    private fun closeMatching(
        caller: VfsOperationContext,
        predicate: (FileDescriptor) -> Boolean,
    ) {
        val descriptors = lock.withLock { entries.removeIf(predicate) }
        descriptors.forEach { it.file.closeDescriptor(caller) }
    }

    private fun OpenFileDescription.closeDescriptor(
        caller: VfsOperationContext,
    ): VfsResult<Unit> {
        val result = flush(caller)
        release()
        return result
    }

    private class DescriptorEntries(val size: Int) {
        val indices = 0 until size
        private val segments = Array((size + SEGMENT_SIZE - 1) / SEGMENT_SIZE) { index ->
            val remaining = size - index * SEGMENT_SIZE
            AtomicReference(arrayOfNulls<Entry>(minOf(SEGMENT_SIZE, remaining)))
        }

        fun slot(index: Int): Entry? {
            if (index !in indices) return null
            return segments[index / SEGMENT_SIZE].load()[index % SEGMENT_SIZE]
        }

        operator fun get(index: Int): FileDescriptor? = slot(index) as? FileDescriptor

        operator fun get(index: ULong): FileDescriptor? {
            if (index >= size.toULong()) return null
            return get(index.toInt())
        }

        operator fun set(index: Int, descriptor: Entry?) {
            require(index in indices)
            val segment = index / SEGMENT_SIZE
            val updated = segments[segment].load().copyOf()
            updated[index % SEGMENT_SIZE] = descriptor
            segments[segment].store(updated)
        }

        fun firstEmpty(minimum: Int, limit: ULong = size.toULong()): Int? {
            val end = minOf(limit, size.toULong()).toInt()
            if (minimum !in 0 until end) return null
            var index = minimum
            while (index < end) {
                val segmentIndex = index / SEGMENT_SIZE
                val snapshot = segments[segmentIndex].load()
                val segmentEnd = minOf(snapshot.size, end - segmentIndex * SEGMENT_SIZE)
                for (offset in index % SEGMENT_SIZE until segmentEnd) {
                    if (snapshot[offset] == null) return segmentIndex * SEGMENT_SIZE + offset
                }
                index = (segmentIndex + 1) * SEGMENT_SIZE
            }
            return null
        }

        fun emptyIndices(count: Int, minimum: Int): IntArray? {
            if (count < 0 || minimum !in indices && !(count == 0 && minimum == size)) return null
            val result = IntArray(count)
            var next = minimum
            repeat(count) { index ->
                val fd = firstEmpty(next) ?: return null
                result[index] = fd
                next = fd + 1
            }
            return result
        }

        fun emptyIndicesUpTo(count: Int): IntArray {
            if (count <= 0) return IntArray(0)
            val result = IntArray(minOf(count, size))
            var found = 0
            var next = 0
            while (found < result.size) {
                val fd = firstEmpty(next) ?: break
                result[found++] = fd
                next = fd + 1
            }
            return if (found == result.size) result else result.copyOf(found)
        }

        fun install(indices: IntArray, files: List<OpenFileDescription>, flags: ULong) {
            require(indices.size <= files.size)
            var first = 0
            while (first < indices.size) {
                val segmentIndex = indices[first] / SEGMENT_SIZE
                val updated = segments[segmentIndex].load().copyOf()
                var index = first
                while (index < indices.size && indices[index] / SEGMENT_SIZE == segmentIndex) {
                    updated[indices[index] % SEGMENT_SIZE] = FileDescriptor(files[index], flags)
                    index++
                }
                segments[segmentIndex].store(updated)
                first = index
            }
        }

        fun occupiedIndices(): IntArray {
            val count = segments.sumOf { segment -> segment.load().count { it is FileDescriptor } }
            val result = IntArray(count)
            var resultIndex = 0
            segments.forEachIndexed { segmentIndex, segment ->
                segment.load().forEachIndexed { offset, descriptor ->
                    if (descriptor is FileDescriptor) {
                        result[resultIndex++] = segmentIndex * SEGMENT_SIZE + offset
                    }
                }
            }
            return result
        }

        fun removeIf(predicate: (FileDescriptor) -> Boolean): List<FileDescriptor> = buildList {
            for (segment in segments) {
                val snapshot = segment.load()
                var updated: Array<Entry?>? = null
                for (index in snapshot.indices) {
                    val descriptor = snapshot[index] as? FileDescriptor ?: continue
                    if (!predicate(descriptor)) continue
                    val replacement = updated ?: snapshot.copyOf()
                    replacement[index] = null
                    updated = replacement
                    add(descriptor)
                }
                updated?.let(segment::store)
            }
        }

        fun replaceRange(
            first: Int,
            end: Int,
            replacement: (FileDescriptor) -> FileDescriptor?,
        ): List<FileDescriptor> = buildList {
            var index = first
            while (index < end) {
                val segmentIndex = index / SEGMENT_SIZE
                val segment = segments[segmentIndex]
                val snapshot = segment.load()
                val segmentEnd = minOf(end - segmentIndex * SEGMENT_SIZE, snapshot.size)
                var updated: Array<Entry?>? = null
                for (offset in index % SEGMENT_SIZE until segmentEnd) {
                    val descriptor = snapshot[offset] as? FileDescriptor ?: continue
                    val result = replacement(descriptor)
                    if (result === descriptor) continue
                    val target = updated ?: snapshot.copyOf().also { updated = it }
                    target[offset] = result
                    if (result == null) add(descriptor)
                }
                updated?.let(segment::store)
                index = (segmentIndex + 1) * SEGMENT_SIZE
            }
        }

        companion object {
            private const val SEGMENT_SIZE = 64
        }
    }

    companion object {
        const val LIMIT = 1024
    }
}
