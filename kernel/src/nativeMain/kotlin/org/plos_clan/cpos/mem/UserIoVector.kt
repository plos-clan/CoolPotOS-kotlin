@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.plus
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal class UserIoVector private constructor(
    private val segments: List<Segment>,
    val size: Int,
) : NativeBuffer(), IoBuffer {
    override fun prepareRead(offset: Int, count: Int): PreparedBufferSource? =
        if (prepare(offset, count, writable = false)) PreparedBufferSource(this) else null

    override fun prepareWrite(
        offset: Int,
        count: Int,
        faultPolicy: BufferFaultPolicy,
    ): PreparedBufferDestination? =
        if (prepare(offset, count, true, faultPolicy)) PreparedBufferDestination(this) else null

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

    override fun copyToNative(sourceOffset: Int, destination: CPointer<UByteVar>, count: Int): Int =
        transfer(sourceOffset, count) { segment, segmentOffset, copied, chunk ->
            segment.memory.copyToNative(segmentOffset, requireNotNull(destination + copied), chunk)
        }

    override fun copyFrom(
        destinationOffset: Int,
        source: BufferSource,
        sourceOffset: Int,
        count: Int,
    ): Int = transfer(destinationOffset, count) { segment, segmentOffset, copied, chunk ->
        segment.memory.copyFrom(segmentOffset, source, sourceOffset + copied, chunk)
    }

    override fun fill(destinationOffset: Int, count: Int, value: Byte): Int =
        transfer(destinationOffset, count) { segment, segmentOffset, _, chunk ->
            segment.memory.fill(segmentOffset, chunk, value)
        }

    private fun prepare(
        offset: Int,
        count: Int,
        writable: Boolean,
        faultPolicy: BufferFaultPolicy = BufferFaultPolicy.PREFAULT,
    ): Boolean =
        transfer(offset, count) { segment, segmentOffset, _, chunk ->
            val prepared = if (writable) {
                segment.memory.prepareWrite(segmentOffset, chunk, faultPolicy)
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
            val cursor = offset + copied
            val chunk = minOf(count - copied, segment.endOffset - cursor)
            val current = operation(segment, cursor - segmentStart, copied, chunk)
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
        const val NATIVE_SEGMENT_SIZE = ULong.SIZE_BYTES * 2
        private val empty = UserIoVector(emptyList(), 0)

        fun fromUser(
            addressSpace: AddressSpace,
            address: ULong,
            count: Int,
            maximumSize: Int,
        ): VfsResult<UserIoVector> {
            require(count >= 0 && maximumSize >= 0)
            if (count == 0) return VfsResult.Ok(empty)
            if (count > Int.MAX_VALUE / NATIVE_SEGMENT_SIZE) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val table = UserMemory(addressSpace, address)
            val size = count * NATIVE_SEGMENT_SIZE
            return table.readRecord(size) { import(addressSpace, it, count, maximumSize) }
                ?: VfsResult.Err(VfsError.FAULT)
        }

        private fun import(
            addressSpace: AddressSpace,
            input: LittleEndianBuffer,
            count: Int,
            maximumSize: Int,
        ): VfsResult<UserIoVector> {
            val segments = ArrayList<Segment>(count)
            var size = 0
            for (index in 0 until count) {
                val offset = index * NATIVE_SEGMENT_SIZE
                val length = input.readU64(offset + ULong.SIZE_BYTES)
                if (length.toLong() < 0) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                val base = input.readU64(offset)
                val memory = UserMemory(addressSpace, base)
                if (!memory.covers(length)) return VfsResult.Err(VfsError.FAULT)
                if (length == 0uL || size == maximumSize) continue
                val available = (maximumSize - size).toULong()
                size += minOf(length, available).toInt()
                segments += Segment(memory, size)
            }
            val vector = UserIoVector(segments, size)
            return VfsResult.Ok(vector)
        }
    }
}
