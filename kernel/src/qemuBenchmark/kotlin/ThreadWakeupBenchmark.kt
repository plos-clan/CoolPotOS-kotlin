@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.time.Duration
import kotlin.time.TimeSource

class ThreadWakeupBenchmark : TimedBenchmark("scheduler.threadWakeup", "ns") {
    private enum class State { WAITING, REQUESTED, STOPPED }
    private enum class Lifecycle { ALLOCATED, MUTEX, REQUEST, RESPONSE, RUNNING, CLOSED }

    private val mutex = nativeHeap.alloc<pthread_mutex_t>()
    private val request = nativeHeap.alloc<pthread_cond_t>()
    private val response = nativeHeap.alloc<pthread_cond_t>()
    private val thread = nativeHeap.alloc<pthread_tVar>()
    private var reference: StableRef<ThreadWakeupBenchmark>? = null
    private var lifecycle = Lifecycle.ALLOCATED
    private var state = State.WAITING
    private var start = TimeSource.Monotonic.markNow()
    private var elapsed = 0L

    override fun prepare() {
        check(lifecycle == Lifecycle.ALLOCATED)
        check(pthread_mutex_init(mutex.ptr, null) == 0)
        lifecycle = Lifecycle.MUTEX
        check(pthread_cond_init(request.ptr, null) == 0)
        lifecycle = Lifecycle.REQUEST
        check(pthread_cond_init(response.ptr, null) == 0)
        lifecycle = Lifecycle.RESPONSE
        reference = StableRef.create(this)
        val receiver: CPointer<CFunction<(COpaquePointer?) -> COpaquePointer?>> = staticCFunction { pointer ->
            pointer!!.asStableRef<ThreadWakeupBenchmark>().get().receive()
            null
        }
        val result = pthread_create(thread.ptr, null, receiver, reference!!.asCPointer())
        check(result == 0) { "pthread_create failed: $result" }
        lifecycle = Lifecycle.RUNNING
        wake()
    }

    private fun receive() {
        check(pthread_mutex_lock(mutex.ptr) == 0)
        while (true) {
            while (state == State.WAITING) {
                check(pthread_cond_wait(request.ptr, mutex.ptr) == 0)
            }
            if (state == State.STOPPED) break
            elapsed = start.elapsedNow().inWholeNanoseconds
            state = State.WAITING
            check(pthread_cond_signal(response.ptr) == 0)
        }
        check(pthread_mutex_unlock(mutex.ptr) == 0)
    }

    private fun wake(): Long {
        check(pthread_mutex_lock(mutex.ptr) == 0)
        start = TimeSource.Monotonic.markNow()
        state = State.REQUESTED
        check(pthread_cond_signal(request.ptr) == 0)
        while (state == State.REQUESTED) check(pthread_cond_wait(response.ptr, mutex.ptr) == 0)
        val value = elapsed
        check(pthread_mutex_unlock(mutex.ptr) == 0)
        return value
    }

    override fun sample(duration: Duration): Double {
        val start = TimeSource.Monotonic.markNow()
        var elapsed = 0.0
        var operations = 0L
        do {
            elapsed += wake()
            operations++
        } while (start.elapsedNow() < duration)
        return elapsed / operations
    }

    override fun close() {
        if (lifecycle == Lifecycle.CLOSED) return
        if (lifecycle == Lifecycle.RUNNING) {
            check(pthread_mutex_lock(mutex.ptr) == 0)
            state = State.STOPPED
            check(pthread_cond_signal(request.ptr) == 0)
            check(pthread_mutex_unlock(mutex.ptr) == 0)
            check(pthread_join(thread.value, null) == 0)
        }
        reference?.dispose()
        if (lifecycle >= Lifecycle.RESPONSE) pthread_cond_destroy(response.ptr)
        if (lifecycle >= Lifecycle.REQUEST) pthread_cond_destroy(request.ptr)
        if (lifecycle >= Lifecycle.MUTEX) pthread_mutex_destroy(mutex.ptr)
        nativeHeap.free(thread)
        nativeHeap.free(response)
        nativeHeap.free(request)
        nativeHeap.free(mutex)
        lifecycle = Lifecycle.CLOSED
    }
}
