package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import org.plos_clan.cpos.block.BlockBytes
import org.plos_clan.cpos.block.BlockDeviceBackend
import org.plos_clan.cpos.block.BlockDevices
import org.plos_clan.cpos.block.BlockOperation
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.utils.Cmdline
import kotlin.test.Test

class DispatcherScalingTest {
    private fun measure(
        name: String,
        parallelism: Int = 32,
        minCpus: Int = 1,
        work: suspend (Int) -> Long,
    ) = KernelCoroutines.await {
        val samples = LongArray(5)
        val cores = mutableSetOf<Long>()
        var expected: Long? = null
        for (sample in -1 until samples.size) {
            val start = TscClock.nanoTime()
            val results = coroutineScope {
                val jobs = List(parallelism) { index ->
                    async { work(index) to SMProcessor.currentLocal().cpuid }
                }
                jobs.awaitAll()
            }
            val elapsed = TscClock.nanoTime() - start
            val checksum = results.sumOf { it.first }
            check(expected == null || expected == checksum)
            expected = checksum
            if (sample < 0) continue
            samples[sample] = elapsed.toLong()
            results.mapTo(cores) { it.second }
        }
        samples.sort()
        println("Scaling $name cpus=${cores.size} median_ns=${samples[samples.size / 2]} checksum=$expected")
        check(cores.size >= minOf(minCpus, SMProcessor.locals.size))
    }

    @Test
    fun yieldChain() = measure("yield-chain", parallelism = 1) {
        var resumed = 0L
        repeat(16_384) {
            yield()
            resumed++
        }
        resumed
    }

    @Test
    fun continuations() = measure("continuations", minCpus = 2) { index ->
        var value = index.toLong() + 1
        repeat(8) {
            yield()
            repeat(300_000) { value = (value xor (value ushr 17)) * 0x2545f4914f6cdd1dL }
        }
        value
    }

    @Test
    fun parallelBlockReads() = readBlocks(4096)

    @Test
    fun parallelBulkReads() = readBlocks(65536)

    private fun readBlocks(size: Int) {
        val selector = checkNotNull(Cmdline["verify.storage"])
        val device = checkNotNull(KernelCoroutines.await { BlockDevices.await(selector) })
        val volume = (device.backend as BlockDeviceBackend).volume
        val raw = BlockBytes(volume.cache.device)
        measure("block-read-${size}B") { index ->
            val bytes = ByteArray(size)
            val buffer = ByteArrayBuffer(bytes)
            var checksum = 0L
            repeat(8) { block ->
                val offset = (index * 8 + block) * bytes.size
                val position = volume.offset + offset.toUInt()
                val result = raw.transfer(BlockOperation.READ, position, buffer, 0, bytes.size)
                check(result.successful && result.bytes == size) { "read at $position size=$size: $result" }
                checksum += bytes[0]
            }
            checksum
        }
    }
}
