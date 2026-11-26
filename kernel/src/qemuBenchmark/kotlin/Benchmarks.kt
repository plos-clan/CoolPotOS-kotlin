@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.get
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.plus
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.posix.CLOCK_BOOTTIME
import platform.posix.F_OK
import platform.posix.access
import platform.posix.clock_gettime
import platform.posix.getpid
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar
import platform.posix.system
import platform.posix.timespec
import kotlin.concurrent.atomics.AtomicLong
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

abstract class SyscallBenchmark(name: String) : TimedBenchmark(name, "ns/op") {
    protected abstract fun MemScope.operation(): () -> Int

    override fun sample(duration: Duration): Double {
        val start = TimeSource.Monotonic.markNow()
        val operations = count(duration)
        return start.elapsedNow().inWholeNanoseconds.toDouble() / operations
    }

    internal fun count(duration: Duration): Long = memScoped {
        val invoke = operation()
        val expected = invoke()
        check(expected >= 0) { "$name failed: $expected" }
        var operations = 0L
        val start = TimeSource.Monotonic.markNow()
        do {
            repeat(BATCH_SIZE) { check(invoke() == expected) }
            operations += BATCH_SIZE
        } while (start.elapsedNow() < duration)
        operations
    }

    private companion object {
        const val BATCH_SIZE = 1024
    }
}

object GetPidBenchmark : SyscallBenchmark("syscall.getpid") {
    override fun MemScope.operation() = ::getpid
}

object PathLookupBenchmark : SyscallBenchmark("vfs.cachedAccess") {
    override fun MemScope.operation() = { access("/usr/bin/bash", F_OK) }
}

class ParallelSyscallBenchmark(private val syscall: SyscallBenchmark, private val workers: Int) :
    TimedBenchmark("${syscall.name}[workers=$workers]", "ops/s") {
    private class Batch(val syscall: SyscallBenchmark, val duration: Duration) {
        val operations = AtomicLong(0)
    }

    override fun sample(duration: Duration): Double = memScoped {
        val threads = allocArray<pthread_tVar>(workers)
        val batch = Batch(syscall, duration)
        val reference = StableRef.create(batch)
        var started = 0
        val start = TimeSource.Monotonic.markNow()
        try {
            val run = staticCFunction { pointer: COpaquePointer? ->
                val work = checkNotNull(pointer).asStableRef<Batch>().get()
                work.operations.fetchAndAdd(work.syscall.count(work.duration))
                null as COpaquePointer?
            }
            repeat(workers) {
                check(pthread_create(threads + it, null, run, reference.asCPointer()) == 0)
                started++
            }
        } finally {
            repeat(started) { check(pthread_join(threads[it], null) == 0) }
            reference.dispose()
        }
        batch.operations.load() * 1e9 / start.elapsedNow().inWholeNanoseconds
    }
}

class CommandBenchmark(
    name: String,
    private val command: String,
    unit: String,
    private val measure: (Duration) -> Double,
) : TimedBenchmark(name, unit) {
    override fun sample(duration: Duration): Double {
        val start = TimeSource.Monotonic.markNow()
        val status = system(command)
        val elapsed = start.elapsedNow()
        check(status == 0) { "Command exited with wait status $status: $command" }
        return measure(elapsed)
    }
}
