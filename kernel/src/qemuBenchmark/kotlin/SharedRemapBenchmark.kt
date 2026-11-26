@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration
import kotlin.time.TimeSource

class SharedRemapBenchmark : TimedBenchmark("vm.sharedRemap", "ns/remap") {
    private val pageSize = getpagesize().toULong()
    private val request = AtomicInt(0)
    private val observed = AtomicInt(0)
    private val peer = nativeHeap.alloc<pthread_tVar>()
    private var file: CPointer<FILE>? = null
    private var address: CPointer<ByteVar>? = null
    private var reference: StableRef<SharedRemapBenchmark>? = null
    private var started = false

    override fun prepare() {
        val stream = checkNotNull(tmpfile())
        file = stream
        val fd = fileno(stream)
        val size = pageSize.toLong()
        check(ftruncate(fd, size * 2) == 0)
        memScoped {
            val marker = alloc<ByteVar>()
            marker.value = 1
            check(pwrite(fd, marker.ptr, 1uL, size) == 1L)
        }
        val mapping = mmap(null, pageSize, PROT_READ, MAP_SHARED, fd, 0)
        check(mapping != null && mapping != MAP_FAILED)
        address = mapping.reinterpret()
        val self = StableRef.create(this)
        reference = self
        val read = staticCFunction { data: COpaquePointer? ->
            checkNotNull(data).asStableRef<SharedRemapBenchmark>().get().readMappings()
            null as COpaquePointer?
        }
        check(pthread_create(peer.ptr, null, read, self.asCPointer()) == 0)
        started = true
    }

    private fun readMappings() {
        val bytes = checkNotNull(address)
        var previous = 0
        while (true) {
            val next = request.load()
            if (next == previous) continue
            if (next < 0) return
            if (bytes[0].toInt() != next and 1) {
                observed.store(-1)
                return
            }
            previous = next
            observed.store(next)
        }
    }

    override fun sample(duration: Duration): Double {
        val bytes = checkNotNull(address)
        val fd = fileno(file)
        val start = TimeSource.Monotonic.markNow()
        var rounds = 0
        do {
            val next = request.load() + 1
            val offset = (next and 1) * pageSize.toLong()
            val mapping = mmap(bytes, pageSize, PROT_READ, MAP_FIXED or MAP_SHARED, fd, offset)
            check(mapping == bytes)
            request.store(next)
            while (true) {
                val ack = observed.load()
                check(ack >= 0) { "Reader observed the previous mapping" }
                if (ack == next) break
            }
            rounds++
        } while (start.elapsedNow() < duration)
        return start.elapsedNow().inWholeNanoseconds.toDouble() / rounds
    }

    override fun close() {
        request.store(-1)
        if (started) check(pthread_join(peer.value, null) == 0)
        reference?.dispose()
        address?.let { check(munmap(it, pageSize) == 0) }
        file?.let { check(fclose(it) == 0) }
        nativeHeap.free(peer)
    }
}
