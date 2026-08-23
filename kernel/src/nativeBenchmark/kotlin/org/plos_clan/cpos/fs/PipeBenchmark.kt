package org.plos_clan.cpos.fs

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown
import org.plos_clan.cpos.fs.vfs.ByteCircularBuffer
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.MappedUserMemory
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource

@State(Scope.Benchmark)
class PipeBufferBenchmark {
    @Param("512", "4096", "8192", "65536")
    var byteCount = 0

    private lateinit var buffer: ByteCircularBuffer
    private var source: PreparedBufferSource? = null
    private var destination: PreparedBufferDestination? = null

    @Setup
    fun prepare() {
        buffer = ByteCircularBuffer(64 * 1024)
        source = ByteArrayBuffer(ByteArray(byteCount) { it.toByte() })
            .prepareRead(0, byteCount)
        destination = ByteArrayBuffer(ByteArray(byteCount))
            .prepareWrite(0, byteCount)
    }

    @Benchmark
    fun roundTrip(): Int {
        val written = buffer.write(checkNotNull(source), 0, byteCount)
        return buffer.read(checkNotNull(destination), 0, written)
    }
}

@State(Scope.Benchmark)
class PipeTransferBenchmark {
    private companion object {
        const val PIPE_CAPACITY_BYTES = 64 * 1024
        const val TRANSFER_BYTES = 8 * 1024
        const val READ_BYTES = 512
    }

    private lateinit var buffer: ByteCircularBuffer
    private lateinit var memory: MappedUserMemory

    @Setup
    fun prepare() {
        buffer = ByteCircularBuffer(PIPE_CAPACITY_BYTES)
        memory = MappedUserMemory(TRANSFER_BYTES)
    }

    @TearDown
    fun release() = memory.close()

    @Benchmark
    fun pumpEightKiBIn512ByteReads(): Int {
        val source = checkNotNull(memory.create().prepareRead(0, TRANSFER_BYTES))
        val written = buffer.write(source, 0, TRANSFER_BYTES)
        var transferred = 0
        while (transferred < written) {
            val destination = checkNotNull(memory.create().prepareWrite(0, READ_BYTES))
            transferred += buffer.read(destination, 0, READ_BYTES)
        }
        return transferred
    }
}
