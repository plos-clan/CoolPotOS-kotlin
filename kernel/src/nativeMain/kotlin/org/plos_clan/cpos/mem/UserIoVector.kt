@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.plus
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal class UserIoVector private constructor(
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

    override fun copyTo(sourceOffset: Int, destination: CPointer<UByteVar>, count: Int): Int =
        transfer(sourceOffset, count) { segment, segmentOffset, copied, chunk ->
            segment.memory.copyTo(segmentOffset, requireNotNull(destination + copied), chunk)
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
        const val NATIVE_SEGMENT_SIZE = ULong.SIZE_BYTES * 2

        fun fromUser(
            addressSpace: AddressSpace,
            address: ULong,
            count: Int,
            maximumSize: Int,
        ): UserIoVector? {
            require(count >= 0 && maximumSize >= 0)
            if (count == 0) return UserIoVector(emptyArray(), 0)
            if (count > Int.MAX_VALUE / NATIVE_SEGMENT_SIZE) return null
            val input = UserMemory(addressSpace, address)
                .copyFromUser(count * NATIVE_SEGMENT_SIZE)
                ?.let(::LittleEndianBuffer)
                ?: return null
            val segments = ArrayList<Segment>(count)
            var size = 0
            repeat(count) { index ->
                val vectorOffset = index * NATIVE_SEGMENT_SIZE
                val available = maximumSize - size
                val length = minOf(
                    input.readU64(vectorOffset + ULong.SIZE_BYTES),
                    available.toULong(),
                ).toInt()
                if (length != 0) {
                    size += length
                    segments += Segment(
                        UserMemory(addressSpace, input.readU64(vectorOffset)),
                        size,
                    )
                }
            }
            return UserIoVector(segments.toTypedArray(), size)
        }
    }
}
