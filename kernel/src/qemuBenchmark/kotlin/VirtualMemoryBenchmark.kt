@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.Duration
import kotlin.time.TimeSource

class VirtualMemoryBenchmark(private val workers: Int) :
    TimedBenchmark("vm.privateFaultUnmap[workers=$workers]", "pages/s") {
    private val pageSize = getpagesize()
    private val pages = 64
    private val length = (pages * pageSize).toULong()
    private val protection = PROT_READ or PROT_WRITE
    private val mapping = MAP_PRIVATE or MAP_ANONYMOUS

    override fun sample(duration: Duration): Double = memScoped {
        val parent = mmap(null, length, protection, mapping, -1, 0)
        check(parent != MAP_FAILED && parent != null)
        val source = parent.reinterpret<ByteVar>()
        val status = alloc<IntVar>()
        val children = IntArray(workers)
        val rounds = 64
        var started = 0
        var valid = true
        try {
            repeat(pages) { source[it * pageSize] = 1 }
            val start = TimeSource.Monotonic.markNow()
            for (index in children.indices) {
                val pid = fork()
                if (pid == 0) _exit(work(source, rounds))
                if (pid < 0) break
                children[started++] = pid
            }
            for (index in 0 until started) {
                val pid = children[index]
                val reaped = waitpid(pid, status.ptr, 0)
                if (reaped != pid || status.value != 0) valid = false
            }
            val elapsed = start.elapsedNow().inWholeNanoseconds
            check(started == workers && valid)
            check((0 until pages).all { source[it * pageSize] == 1.toByte() })
            workers * pages * (rounds + 1) * 1e9 / elapsed
        } finally {
            check(munmap(parent, length) == 0)
        }
    }

    private fun work(source: CPointer<ByteVar>, rounds: Int): Int {
        repeat(pages) { source[it * pageSize] = 2 }
        repeat(rounds) {
            val address = mmap(null, length, protection, mapping, -1, 0)
            if (address == null || address == MAP_FAILED) return 1
            val bytes = address.reinterpret<ByteVar>()
            repeat(pages) { bytes[it * pageSize] = 3 }
            if (mprotect(address, length, PROT_READ) != 0) return 2
            if (munmap(address, length) != 0) return 3
        }
        return 0
    }
}
