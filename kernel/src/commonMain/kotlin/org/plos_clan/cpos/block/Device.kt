package org.plos_clan.cpos.block

import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.IoBuffer

data class BlockGeometry(val blockSize: Int, val blockCount: ULong) {
    init {
        require(blockSize > 0 && blockCount > 0uL)
        require(blockCount <= Long.MAX_VALUE.toULong() / blockSize.toUInt())
    }

    val byteSize: ULong
        get() = blockCount * blockSize.toUInt()
}

enum class BlockOperation {
    READ,
    WRITE,
}

enum class BlockStatus {
    SUCCESS,
    IO_ERROR,
    NO_DEVICE,
    READ_ONLY,
    INVALID,
    NO_MEMORY,
}

data class BlockResult(val status: BlockStatus, val bytes: Int = 0) {
    val successful: Boolean
        get() = status == BlockStatus.SUCCESS
}

abstract class BlockDevice {
    abstract val geometry: BlockGeometry
    open val connected: Boolean = true
    open val readOnly: Boolean = false
    open val preferredTransferBytes: Int = 128 * 1024

    abstract suspend fun transfer(
        operation: BlockOperation,
        block: ULong,
        buffer: IoBuffer,
        offset: Int,
        length: Int,
    ): BlockResult

    abstract suspend fun flush(): BlockStatus
}

class BlockBytes(private val device: BlockDevice) {
    suspend fun transfer(
        operation: BlockOperation,
        position: ULong,
        buffer: IoBuffer,
        offset: Int,
        length: Int,
    ): BlockResult {
        if (offset < 0 || length < 0 || offset > Int.MAX_VALUE - length) {
            return BlockResult(BlockStatus.INVALID)
        }
        if (operation == BlockOperation.WRITE && device.readOnly)
            return BlockResult(BlockStatus.READ_ONLY)
        val geometry = device.geometry
        if (position >= geometry.byteSize || length == 0) return BlockResult(BlockStatus.SUCCESS)
        val count = minOf(length.toULong(), geometry.byteSize - position).toInt()
        val prepared =
            if (operation == BlockOperation.READ) buffer.prepareWrite(offset, count)
            else buffer.prepareRead(offset, count)
        if (prepared == null) return BlockResult(BlockStatus.INVALID)
        val blockSize = geometry.blockSize
        var completed = 0
        var scratch: ByteArrayBuffer? = null
        while (completed < count) {
            val current = position + completed.toUInt()
            val block = current / blockSize.toUInt()
            val within = (current % blockSize.toUInt()).toInt()
            val remaining = count - completed
            if (within == 0 && remaining >= blockSize) {
                val chunk = remaining - remaining % blockSize
                val result = device.transfer(operation, block, buffer, offset + completed, chunk)
                if (!result.successful || result.bytes != chunk) {
                    return BlockResult(
                        if (result.successful) BlockStatus.IO_ERROR else result.status,
                        completed,
                    )
                }
                completed += chunk
                continue
            }
            val temporary =
                scratch
                    ?: try {
                        ByteArrayBuffer(ByteArray(blockSize)).also { scratch = it }
                    } catch (_: OutOfMemoryError) {
                        return BlockResult(BlockStatus.NO_MEMORY, completed)
                    }
            val chunk = minOf(remaining, blockSize - within)
            val read = device.transfer(BlockOperation.READ, block, temporary, 0, blockSize)
            if (!read.successful || read.bytes != blockSize) {
                return BlockResult(
                    if (read.successful) BlockStatus.IO_ERROR else read.status,
                    completed,
                )
            }
            if (operation == BlockOperation.READ) {
                if (buffer.copyFrom(offset + completed, temporary, within, chunk) != chunk) {
                    return BlockResult(BlockStatus.INVALID, completed)
                }
            } else {
                if (buffer.copyTo(offset + completed, temporary, within, chunk) != chunk) {
                    return BlockResult(BlockStatus.INVALID, completed)
                }
                val written = device.transfer(BlockOperation.WRITE, block, temporary, 0, blockSize)
                if (!written.successful || written.bytes != blockSize) {
                    return BlockResult(
                        if (written.successful) BlockStatus.IO_ERROR else written.status,
                        completed,
                    )
                }
            }
            completed += chunk
        }
        return BlockResult(BlockStatus.SUCCESS, completed)
    }
}
