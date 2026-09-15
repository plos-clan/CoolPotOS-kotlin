@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.CLOCK_BOOTTIME
import platform.posix.clock_gettime
import platform.posix.getpid
import platform.posix.system
import platform.posix.timespec
import kotlin.time.Duration
import kotlin.time.TimeSource

class BenchmarkSettings(val warmups: Int, val iterations: Int, val duration: Duration) {
    init {
        require(warmups >= 0 && iterations > 0 && duration.isPositive() && duration.isFinite())
    }
}

abstract class Benchmark(val name: String, private val unit: String) {
    abstract fun samples(settings: BenchmarkSettings): DoubleArray

    fun run(report: VerificationReport, settings: BenchmarkSettings) = report.benchmark(name, unit) {
        samples(settings)
    }
}

class Snapshot(name: String, unit: String, private val measure: () -> Double) : Benchmark(name, unit) {
    override fun samples(settings: BenchmarkSettings) = doubleArrayOf(measure())

    companion object {
        fun bootNanoseconds(): Long = memScoped {
            val time = alloc<timespec>()
            check(clock_gettime(CLOCK_BOOTTIME, time.ptr) == 0)
            time.tv_sec * 1_000_000_000L + time.tv_nsec
        }

        fun usedMemory(): Double {
            val memory = SystemFileSystem.source(Path("/proc/meminfo")).buffered().use { it.readString() }
                .lineSequence().filter { ':' in it }.associate { line ->
                    val name = line.substringBefore(':')
                    val value = line.substringAfter(':').trim().substringBefore(' ').toLong()
                    name to value
                }
            return (memory.getValue("MemTotal") - memory.getValue("MemAvailable")) * 1024.0
        }
    }
}

abstract class TimedBenchmark(name: String, unit: String) : Benchmark(name, unit), AutoCloseable {
    protected open fun prepare() {}
    protected abstract fun sample(duration: Duration): Double
    override fun close() {}

    final override fun samples(settings: BenchmarkSettings): DoubleArray = try {
        prepare()
        repeat(settings.warmups) { sample(settings.duration) }
        DoubleArray(settings.iterations) { sample(settings.duration) }
    } finally {
        close()
    }
}

object GetPidBenchmark : TimedBenchmark("syscall.getpid", "ns/op") {
    private const val BATCH_SIZE = 1024

    override fun sample(duration: Duration): Double {
        val expected = getpid().also { check(it > 0) { "getpid failed: $it" } }
        var observed = 0
        var operations = 0L
        val start = TimeSource.Monotonic.markNow()
        do {
            repeat(BATCH_SIZE) { observed = getpid() }
            operations += BATCH_SIZE
        } while (start.elapsedNow() < duration)
        val elapsed = start.elapsedNow().inWholeNanoseconds
        check(observed == expected)
        return elapsed.toDouble() / operations
    }
}

class CommandBenchmark(name: String, private val command: String) : TimedBenchmark(name, "ns") {
    override fun sample(duration: Duration): Double {
        val start = TimeSource.Monotonic.markNow()
        val status = system(command)
        val elapsed = start.elapsedNow().inWholeNanoseconds
        check(status == 0) { "Command exited with wait status $status: $command" }
        return elapsed.toDouble()
    }
}
