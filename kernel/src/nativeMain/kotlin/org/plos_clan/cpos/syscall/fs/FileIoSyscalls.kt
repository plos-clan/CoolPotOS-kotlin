@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall.fs

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.plus
import org.plos_clan.cpos.fs.IoResult
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.mem.IoBuffer
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.syscall.FsConstants.IO_VECTOR_SIZE
import org.plos_clan.cpos.syscall.FsConstants.MAX_IO_VECTORS
import org.plos_clan.cpos.syscall.FsConstants.MAX_RW_COUNT
import org.plos_clan.cpos.syscall.Syscall.errno
import org.plos_clan.cpos.syscall.Syscall.fileDescriptor
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PtraceRegisters

internal fun read(regs: PtraceRegisters, process: Process): Long =
    FileIo.scalar(FileIo.Direction.READ, null, regs, process)

internal fun write(regs: PtraceRegisters, process: Process): Long =
    FileIo.scalar(FileIo.Direction.WRITE, null, regs, process)

internal fun pread64(regs: PtraceRegisters, process: Process): Long =
    FileIo.scalar(FileIo.Direction.READ, regs[PtraceRegisters.IDX_R10], regs, process)

internal fun pwrite64(regs: PtraceRegisters, process: Process): Long =
    FileIo.scalar(FileIo.Direction.WRITE, regs[PtraceRegisters.IDX_R10], regs, process)

internal fun readv(regs: PtraceRegisters, process: Process): Long =
    FileIo.vector(FileIo.Direction.READ, regs, process)

internal fun writev(regs: PtraceRegisters, process: Process): Long =
    FileIo.vector(FileIo.Direction.WRITE, regs, process)

private object FileIo {
    fun scalar(
        direction: Direction,
        position: ULong?,
        regs: PtraceRegisters,
        process: Process,
    ): Long = withFile(regs, process) { file ->
        val count = minOf(regs[PtraceRegisters.IDX_RDX], MAX_RW_COUNT).toInt()
        val buffer = UserMemory(process.addressSpace, regs[PtraceRegisters.IDX_RSI])
        direction.transfer(file, buffer, count, position).raw
    }

    fun vector(
        direction: Direction,
        regs: PtraceRegisters,
        process: Process,
    ): Long = withFile(regs, process) { file ->
        val vectorCount = regs[PtraceRegisters.IDX_RDX]
        if (vectorCount > MAX_IO_VECTORS.toULong()) return@withFile errno(Errno.EINVAL)
        val buffer = UserIoVector.fromUser(
            process,
            regs[PtraceRegisters.IDX_RSI],
            vectorCount.toInt(),
        ) ?: return@withFile errno(Errno.EFAULT)
        direction.transfer(file, buffer, buffer.size, null).raw
    }

    private inline fun withFile(
        regs: PtraceRegisters,
        process: Process,
        operation: (OpenFileDescription) -> Long,
    ): Long {
        val descriptor = fileDescriptor(regs[PtraceRegisters.IDX_RDI])
            ?: return errno(Errno.EBADF)
        val file = process.fdTable.acquire(descriptor) ?: return errno(Errno.EBADF)
        return try {
            operation(file)
        } finally {
            file.release()
        }
    }

    enum class Direction {
        READ,
        WRITE;

        fun transfer(
            file: OpenFileDescription,
            buffer: IoBuffer,
            count: Int,
            position: ULong?,
        ): IoResult = when (this) {
            READ -> if (position == null) {
                file.read(buffer, 0, count)
            } else {
                file.readAt(position, buffer, 0, count)
            }

            WRITE -> if (position == null) {
                file.write(buffer, 0, count)
            } else {
                file.writeAt(position, buffer, 0, count)
            }
        }
    }
}

private class UserIoVector private constructor(
    private val segments: Array<Segment>,
    val size: Int,
) : IoBuffer {
    override fun prepareRead(offset: Int, count: Int): PreparedBufferSource? =
        if (prepare(offset, count, writable = false)) PreparedBufferSource(this) else null

    override fun prepareWrite(offset: Int, count: Int): PreparedBufferDestination? =
        if (prepare(offset, count, writable = true)) PreparedBufferDestination(this) else null

    override fun copyTo(
        sourceOffset: Int,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
    ): Int {
        if (destinationOffset < 0 || count < 0 || destinationOffset > destination.size - count) {
            return 0
        }
        return transfer(sourceOffset, count) { segment, segmentOffset, copied, chunk ->
            segment.memory.copyTo(
                segmentOffset,
                destination,
                destinationOffset + copied,
                chunk,
            )
        }
    }

    override fun copyFrom(
        destinationOffset: Int,
        source: ByteArray,
        sourceOffset: Int,
        count: Int,
    ): Int {
        if (sourceOffset < 0 || count < 0 || sourceOffset > source.size - count) return 0
        return transfer(destinationOffset, count) { segment, segmentOffset, copied, chunk ->
            segment.memory.copyFrom(segmentOffset, source, sourceOffset + copied, chunk)
        }
    }

    override fun copyFrom(
        destinationOffset: Int,
        source: CPointer<UByteVar>,
        count: Int,
    ): Int = transfer(destinationOffset, count) { segment, segmentOffset, copied, chunk ->
        segment.memory.copyFrom(segmentOffset, requireNotNull(source + copied), chunk)
    }

    override fun fill(destinationOffset: Int, count: Int, value: Byte): Int =
        transfer(destinationOffset, count) { segment, segmentOffset, _, chunk ->
            segment.memory.fill(segmentOffset, chunk, value)
        }

    private fun prepare(offset: Int, count: Int, writable: Boolean): Boolean =
        transfer(offset, count) { segment, segmentOffset, _, chunk ->
            val prepared = if (writable) {
                segment.memory.prepareWrite(segmentOffset, chunk)
            } else {
                segment.memory.prepareRead(segmentOffset, chunk)
            }
            if (prepared == null) 0 else chunk
        } == count

    private inline fun transfer(
        offset: Int,
        count: Int,
        operation: (Segment, Int, Int, Int) -> Int,
    ): Int {
        if (offset < 0 || count < 0 || offset > size - count || count == 0) return 0

        var segmentIndex = segmentIndex(offset)
        var segmentStart = if (segmentIndex == 0) 0 else segments[segmentIndex - 1].endOffset
        var copied = 0
        while (copied < count) {
            val segment = segments[segmentIndex]
            val segmentOffset = offset + copied - segmentStart
            val chunk = minOf(count - copied, segment.endOffset - segmentStart - segmentOffset)
            val current = operation(segment, segmentOffset, copied, chunk)
            if (current !in 1..chunk) break
            copied += current
            if (current < chunk) break
            segmentStart = segment.endOffset
            segmentIndex++
        }
        return copied
    }

    private fun segmentIndex(offset: Int): Int {
        var low = 0
        var high = segments.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (offset < segments[middle].endOffset) high = middle else low = middle + 1
        }
        return low
    }

    private data class Segment(val memory: UserMemory, val endOffset: Int)

    companion object {
        fun fromUser(process: Process, address: ULong, count: Int): UserIoVector? {
            if (count == 0) return UserIoVector(emptyArray(), 0)
            val vectorBytes = UserMemory(process.addressSpace, address)
                .copyFromUser(count * IO_VECTOR_SIZE) ?: return null
            val input = LittleEndianBuffer(vectorBytes)
            val segments = ArrayList<Segment>(count)
            var size = 0
            repeat(count) { index ->
                val vectorOffset = index * IO_VECTOR_SIZE
                val available = MAX_RW_COUNT - size.toULong()
                val length = minOf(input.readU64(vectorOffset + ULong.SIZE_BYTES), available)
                    .toInt()
                if (length != 0) {
                    size += length
                    segments += Segment(
                        UserMemory(process.addressSpace, input.readU64(vectorOffset)),
                        size,
                    )
                }
            }
            return UserIoVector(segments.toTypedArray(), size)
        }
    }
}
