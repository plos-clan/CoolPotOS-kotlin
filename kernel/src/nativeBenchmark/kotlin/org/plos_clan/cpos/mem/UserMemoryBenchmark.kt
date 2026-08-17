package org.plos_clan.cpos.mem

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State
import kotlinx.benchmark.TearDown

@State(Scope.Benchmark)
class UserMemoryBenchmark {
    @Param("512", "8192", "65536")
    var byteCount = 0

    private lateinit var memory: MappedUserMemory
    private lateinit var source: ByteArray
    private lateinit var destination: ByteArray

    @Setup
    fun prepare() {
        memory = MappedUserMemory(byteCount)
        source = ByteArray(byteCount) { it.toByte() }
        destination = ByteArray(byteCount)
    }

    @TearDown
    fun release() = memory.close()

    @Benchmark
    fun copyFromUser(): Int {
        val prepared = checkNotNull(memory.create().prepareRead(0, byteCount))
        return prepared.copyTo(0, destination, 0, byteCount)
    }

    @Benchmark
    fun copyToUser(): Int {
        val prepared = checkNotNull(memory.create().prepareWrite(0, byteCount))
        return prepared.copyFrom(0, source, 0, byteCount)
    }
}
