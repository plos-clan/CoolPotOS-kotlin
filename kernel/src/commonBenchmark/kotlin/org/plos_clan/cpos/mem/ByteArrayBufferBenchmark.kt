package org.plos_clan.cpos.mem

import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import kotlinx.benchmark.Scope
import kotlinx.benchmark.Setup
import kotlinx.benchmark.State

@State(Scope.Benchmark)
class ByteArrayBufferBenchmark {
    @Param("512", "4096", "8192", "65536")
    var byteCount = 0

    private lateinit var sourceBytes: ByteArray
    private lateinit var destinationBytes: ByteArray
    private lateinit var source: ByteArrayBuffer
    private lateinit var destination: ByteArrayBuffer

    @Setup
    fun prepare() {
        sourceBytes = ByteArray(byteCount) { it.toByte() }
        destinationBytes = ByteArray(byteCount)
        source = ByteArrayBuffer(sourceBytes)
        destination = ByteArrayBuffer(destinationBytes)
    }

    @Benchmark
    fun copyToByteArray(): Int = source.copyTo(0, destinationBytes, 0, byteCount)

    @Benchmark
    fun copyFromByteArray(): Int = destination.copyFrom(0, sourceBytes, 0, byteCount)
}
