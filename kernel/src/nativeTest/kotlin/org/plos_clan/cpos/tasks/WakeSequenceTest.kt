@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ULongVar
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

class WakeSequenceTest {
    @Test
    fun nestedWaitCannotConsumeOuterWakeup() = memScoped {
        val elapsed = alloc<ULongVar>()
        val thread = alloc<pthread_tVar>()
        val entry = staticCFunction<COpaquePointer?, COpaquePointer?> { argument ->
            val output = requireNotNull(argument).reinterpret<ULongVar>()
            val outer = bridge.fast_handoff_prepare_park()
            bridge.fast_handoff_unpark(bridge.fast_handoff_current_task_handle())
            val inner = bridge.fast_handoff_prepare_park()
            val innerDeadline = bridge.runtime_clock_nanos() + 10_000_000uL
            bridge.fast_handoff_park_current(innerDeadline, inner)
            val started = bridge.runtime_clock_nanos()
            bridge.fast_handoff_park_current(started + 1_000_000_000uL, outer)
            output.pointed.value = bridge.runtime_clock_nanos() - started
            null
        }
        assertEquals(0, pthread_create(thread.ptr, null, entry, elapsed.ptr))
        assertEquals(0, pthread_join(thread.value, null))
        assertTrue(elapsed.value < 500_000_000uL, "Outer wakeup was lost: ${elapsed.value} ns")
    }
}
