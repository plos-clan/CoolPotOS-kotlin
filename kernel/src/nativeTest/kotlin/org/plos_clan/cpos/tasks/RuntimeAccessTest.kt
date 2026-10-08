@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeAccessTest {
    @Test
    fun accessStateIsPrivateToEachRuntimeThread() = memScoped {
        val previous = bridge.can_use_runtime()
        val result = alloc<IntVar>()
        val thread = alloc<pthread_tVar>()
        val entry = staticCFunction<COpaquePointer?, COpaquePointer?> { argument ->
            val output = requireNotNull(argument).reinterpret<IntVar>()
            val initial = bridge.can_use_runtime()
            bridge.set_runtime_use_mask(true)
            bridge.fast_handoff_yield()
            val enabled = bridge.can_use_runtime()
            bridge.set_runtime_use_mask(false)
            val disabled = !bridge.can_use_runtime()
            output.pointed.value = if (!initial && enabled && disabled) 1 else 0
            null
        }
        bridge.set_runtime_use_mask(true)
        try {
            assertEquals(0, pthread_create(thread.ptr, null, entry, result.ptr))
            assertEquals(0, pthread_join(thread.value, null))
            assertEquals(1, result.value)
            assertTrue(bridge.can_use_runtime())
        } finally {
            bridge.set_runtime_use_mask(previous)
        }
    }
}
