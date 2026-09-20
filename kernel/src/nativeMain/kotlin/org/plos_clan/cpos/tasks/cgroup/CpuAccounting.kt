@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.tasks.cgroup

import kotlinx.cinterop.*
import org.plos_clan.cpos.tasks.SMProcessor
import org.plos_clan.cpos.tasks.Thread

internal class CpuAccounting(parent: CpuAccounting?) : AutoCloseable {
    private val processors = SMProcessor.cpu_count.toInt()
    val pointer: CPointer<bridge.cpu_account_t>

    init {
        val stride = bridge.cpu_account_stride.toLong() * ULong.SIZE_BYTES
        val bytes = sizeOf<bridge.cpu_account_t>() + processors * stride
        val storage = nativeHeap.alloc(bytes, alignOf<bridge.cpu_account_t>())
        pointer = storage.reinterpret<bridge.cpu_account_t>().ptr
        platform.posix.memset(pointer, 0, bytes.toULong())
        pointer.pointed.parent = parent?.pointer
    }

    fun attach(thread: Thread) = thread.nativeTask.access { handle ->
        if (handle != 0uL) bridge.fast_handoff_set_account(handle, pointer)
    }

    fun render(): String {
        var user = 0uL
        var system = 0uL
        for (cpu in 0 until processors) {
            val slot = cpu.toULong() * bridge.cpu_account_stride
            user += bridge.fast_handoff_account_time(pointer, slot)
            system += bridge.fast_handoff_account_time(pointer, slot + 1uL)
        }
        val usageMicros = (user + system) / 1000u
        val userMicros = user / 1000u
        val systemMicros = system / 1000u
        return "usage_usec $usageMicros\nuser_usec $userMicros\nsystem_usec $systemMicros\n"
    }

    override fun close() = nativeHeap.free(pointer)
}
