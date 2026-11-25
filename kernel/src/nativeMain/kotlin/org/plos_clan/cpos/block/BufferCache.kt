@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.block

import kotlin.concurrent.atomics.AtomicBoolean
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.DmaMemory
import org.plos_clan.cpos.mem.PageCache
import org.plos_clan.cpos.mem.PageCacheFailure
import org.plos_clan.cpos.mem.PageCacheKind
import org.plos_clan.cpos.mem.PageCacheSource
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.SnapshotSet

class BufferCache(val device: BlockDevice) {
    private val bytes = BlockBytes(device)
    private val order = BlockIoOrder(IrqSpinLock(), KernelCoroutines::await)
    private val online = AtomicBoolean(true)
    private val readers = SnapshotSet<ReadLease>()

    inner class ReadLease internal constructor(val start: ULong, val length: ULong) :
        AutoCloseable {
        override fun close() = readers.remove(this)
    }

    fun protect(start: ULong, length: ULong): ReadLease {
        val size = device.geometry.byteSize
        require(start <= size && length <= size - start)
        val lease = ReadLease(start, length)
        if (length == 0uL) return lease
        return ordered(start, length, BlockOperation.READ) { lease.also(readers::add) }
    }

    private fun <T> ordered(
        start: ULong,
        length: ULong,
        operation: BlockOperation,
        transfer: () -> T,
    ): T {
        val blockSize = device.geometry.blockSize.toUInt()
        val last = (start + length - 1uL) / blockSize
        return order.run(start / blockSize, last, operation, transfer)
    }

    private val source =
        object : PageCacheSource {
            override val cacheKind = PageCacheKind.BLOCK
            override val readAheadSize: Int
                get() = device.preferredTransferBytes

            override fun read(offset: ULong, destination: ByteArray): Int {
                if (!connected) return PageCacheSource.READ_ERROR
                val buffer = ByteArrayBuffer(destination)
                val result = KernelCoroutines.await {
                    bytes.transfer(BlockOperation.READ, offset, buffer, 0, destination.size)
                }
                return if (result.successful || result.bytes > 0) result.bytes
                else PageCacheSource.READ_ERROR
            }
        }

    val connected: Boolean
        get() = online.load() && device.connected

    fun detach() {
        online.store(false)
        PageCache.invalidate(source.identity)
    }

    fun read(
        position: ULong,
        destination: PreparedBufferDestination,
        offset: Int,
        length: Int,
    ): BlockResult = access(BlockOperation.READ, position, offset, length) { count ->
        val result = PageCache.read(source, position, destination, offset, count)
        val status = when {
            result.isSuccess -> BlockStatus.SUCCESS
            result.failure == PageCacheFailure.OUT_OF_MEMORY -> BlockStatus.NO_MEMORY
            else -> BlockStatus.IO_ERROR
        }
        BlockResult(status, result.bytes)
    }

    fun write(
        position: ULong,
        source: PreparedBufferSource,
        offset: Int,
        length: Int,
    ): BlockResult = access(BlockOperation.WRITE, position, offset, length) { count ->
        val end = position + count.toUInt()
        val protected = readers.snapshot.any { position < it.start + it.length && it.start < end }
        if (protected) return@access BlockResult(BlockStatus.READ_ONLY)
        val capacity = minOf(count, maxOf(device.geometry.blockSize, device.preferredTransferBytes))
        val memory = DmaMemory.allocate(capacity) ?: return@access BlockResult(BlockStatus.NO_MEMORY)
        val identity = this.source.identity
        var completed = 0
        try {
            PageCache.invalidate(identity, position, count.toULong())
            while (completed < count) {
                val chunk = minOf(count - completed, capacity)
                if (source.copyTo(offset + completed, memory, 0, chunk) != chunk) {
                    return@access BlockResult(BlockStatus.INVALID, completed)
                }
                val current = position + completed.toUInt()
                val result = KernelCoroutines.await {
                    bytes.transfer(BlockOperation.WRITE, current, memory, 0, chunk)
                }
                completed += result.bytes
                if (!result.successful || result.bytes != chunk) return@access BlockResult(result.status, completed)
            }
            BlockResult(BlockStatus.SUCCESS, completed)
        } finally {
            memory.close()
            PageCache.invalidate(identity, position, count.toULong())
        }
    }

    private fun access(
        operation: BlockOperation,
        position: ULong,
        offset: Int,
        length: Int,
        transfer: (Int) -> BlockResult,
    ): BlockResult {
        if (offset < 0 || length < 0 || offset > Int.MAX_VALUE - length) {
            return BlockResult(BlockStatus.INVALID)
        }
        val size = device.geometry.byteSize
        val count = if (position >= size) 0 else minOf(length.toULong(), size - position).toInt()
        fun perform(): BlockResult = when {
            !connected -> BlockResult(BlockStatus.NO_DEVICE)
            operation == BlockOperation.WRITE && device.readOnly -> BlockResult(BlockStatus.READ_ONLY)
            count == 0 -> BlockResult(BlockStatus.SUCCESS)
            else -> transfer(count)
        }
        if (count == 0) return perform()
        return ordered(position, count.toULong(), operation, ::perform)
    }

    fun flush(): BlockStatus =
        order.run(0uL, ULong.MAX_VALUE, BlockOperation.WRITE) {
            if (connected) KernelCoroutines.await { device.flush() } else BlockStatus.NO_DEVICE
        }
}
