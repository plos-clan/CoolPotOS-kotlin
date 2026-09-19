package org.plos_clan.cpos.block

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

interface ByteSource : AutoCloseable {
    val size: ULong

    fun read(position: ULong, destination: ByteArray): Boolean
}

class BlockVolume(val cache: BufferCache, val partition: Partition? = null) {
    val geometry =
        partition?.let { BlockGeometry(cache.device.geometry.blockSize, it.blockCount) }
            ?: cache.device.geometry
    val offset = (partition?.firstBlock ?: 0uL) * geometry.blockSize.toUInt()
    val size: ULong
        get() = geometry.byteSize

    val readOnly: Boolean
        get() = cache.device.readOnly || partition?.readOnly == true

    fun openReadOnly(): ByteSource {
        val lease = cache.protect(offset, size)
        return object : ByteSource {
            override val size: ULong
                get() = this@BlockVolume.size

            override fun read(position: ULong, destination: ByteArray): Boolean =
                this@BlockVolume.read(position, destination)

            override fun close() = lease.close()
        }
    }

    fun read(position: ULong, destination: ByteArray): Boolean {
        val buffer = ByteArrayBuffer(destination).prepareWrite(0, destination.size) ?: return false
        val result = read(position, buffer, 0, destination.size)
        return result.successful && result.bytes == destination.size
    }

    fun read(
        position: ULong,
        destination: PreparedBufferDestination,
        bufferOffset: Int,
        length: Int,
    ): BlockResult {
        if (length < 0) return BlockResult(BlockStatus.INVALID)
        val count = if (position >= size) 0 else minOf(length.toULong(), size - position).toInt()
        if (count == 0)
            return BlockResult(if (cache.connected) BlockStatus.SUCCESS else BlockStatus.NO_DEVICE)
        return cache.read(offset + position, destination, bufferOffset, count)
    }

    fun write(
        position: ULong,
        source: PreparedBufferSource,
        bufferOffset: Int,
        length: Int,
    ): BlockResult {
        if (readOnly) return BlockResult(BlockStatus.READ_ONLY)
        if (length < 0) return BlockResult(BlockStatus.INVALID)
        val count = if (position >= size) 0 else minOf(length.toULong(), size - position).toInt()
        if (count == 0)
            return BlockResult(if (cache.connected) BlockStatus.SUCCESS else BlockStatus.NO_DEVICE)
        return cache.write(offset + position, source, bufferOffset, count)
    }
}
