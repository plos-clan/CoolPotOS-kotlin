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
import org.plos_clan.cpos.utils.KernelReadWriteLock

class BufferCache(val device: BlockDevice) {
    private val bytes = BlockBytes(device)
    private val lock = KernelReadWriteLock()
    private val online = AtomicBoolean(true)
    private val readers = mutableListOf<ReadLease>()

    inner class ReadLease internal constructor(val start: ULong, val length: ULong) :
        AutoCloseable {
        override fun close() =
            lock.withLock(true) {
                readers.remove(this)
                Unit
            }
    }

    fun protect(start: ULong, length: ULong): ReadLease =
        lock.withLock(true) {
            require(start <= device.geometry.byteSize && length <= device.geometry.byteSize - start)
            ReadLease(start, length).also(readers::add)
        }

    private val source =
        object : PageCacheSource {
            override val cacheKind = PageCacheKind.BLOCK
            override val readAheadSize: Int
                get() = device.preferredTransferBytes

            override fun read(offset: ULong, destination: ByteArray): Int {
                if (!connected) return PageCacheSource.READ_ERROR
                val result =
                    KernelCoroutines.await {
                        bytes.transfer(
                            BlockOperation.READ,
                            offset,
                            ByteArrayBuffer(destination),
                            0,
                            destination.size,
                        )
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
    ): BlockResult =
        lock.withLock(false) {
            if (offset < 0 || length < 0 || offset > Int.MAX_VALUE - length) {
                return@withLock BlockResult(BlockStatus.INVALID)
            }
            if (!connected) return@withLock BlockResult(BlockStatus.NO_DEVICE)
            if (position >= device.geometry.byteSize)
                return@withLock BlockResult(BlockStatus.SUCCESS)
            val count = minOf(length.toULong(), device.geometry.byteSize - position).toInt()
            val result = PageCache.read(source, position, destination, offset, count)
            if (result.isSuccess) BlockResult(BlockStatus.SUCCESS, result.bytes)
            else
                BlockResult(
                    if (result.failure == PageCacheFailure.OUT_OF_MEMORY) BlockStatus.NO_MEMORY
                    else BlockStatus.IO_ERROR
                )
        }

    fun write(
        position: ULong,
        source: PreparedBufferSource,
        offset: Int,
        length: Int,
    ): BlockResult =
        lock.withLock(true) {
            if (offset < 0 || length < 0 || offset > Int.MAX_VALUE - length) {
                return@withLock BlockResult(BlockStatus.INVALID)
            }
            if (!connected) return@withLock BlockResult(BlockStatus.NO_DEVICE)
            if (device.readOnly) return@withLock BlockResult(BlockStatus.READ_ONLY)
            if (position >= device.geometry.byteSize || length == 0)
                return@withLock BlockResult(BlockStatus.SUCCESS)
            val count = minOf(length.toULong(), device.geometry.byteSize - position).toInt()
            if (
                readers.any {
                    position < it.start + it.length && it.start < position + count.toUInt()
                }
            ) {
                return@withLock BlockResult(BlockStatus.READ_ONLY)
            }
            val capacity =
                minOf(count, maxOf(device.geometry.blockSize, device.preferredTransferBytes))
            val memory =
                DmaMemory.allocate(capacity) ?: return@withLock BlockResult(BlockStatus.NO_MEMORY)
            var completed = 0
            PageCache.invalidate(this.source.identity, position, count.toULong())
            try {
                while (completed < count) {
                    val chunk = minOf(count - completed, capacity)
                    if (source.copyTo(offset + completed, memory, 0, chunk) != chunk) {
                        return@withLock BlockResult(BlockStatus.INVALID, completed)
                    }
                    val result =
                        KernelCoroutines.await {
                            bytes.transfer(
                                BlockOperation.WRITE,
                                position + completed.toUInt(),
                                memory,
                                0,
                                chunk,
                            )
                        }
                    completed += result.bytes
                    if (!result.successful || result.bytes != chunk)
                        return@withLock BlockResult(result.status, completed)
                }
                BlockResult(BlockStatus.SUCCESS, completed)
            } finally {
                PageCache.invalidate(this.source.identity, position, count.toULong())
                memory.close()
            }
        }

    fun flush(): BlockStatus =
        lock.withLock(true) {
            if (connected) KernelCoroutines.await { device.flush() } else BlockStatus.NO_DEVICE
        }
}
