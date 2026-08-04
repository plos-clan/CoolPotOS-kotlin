package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KernelCoroutineRuntimeTest {
    @Test
    fun childFailureIsReportedWithoutCancellingRootOrSibling() {
        val clock = RuntimeFakeClock()
        val dispatcher = KernelDispatcher(
            nanoTime = clock::nanoTime,
            criticalSection = RuntimeNoOpCriticalSection,
            failureReporter = { throw AssertionError("Unexpected dispatcher failure", it) },
        )
        val reported = mutableListOf<Throwable>()
        val reporter: (Throwable) -> Unit = reported::add
        val runtime = createKernelCoroutineRuntime(dispatcher, reporter)
        var siblingRan = false

        try {
            runtime.scope.launch { error("child failed") }
            runtime.scope.launch { siblingRan = true }

            while (dispatcher.hasReadyWork()) {
                dispatcher.runReadyBatch()
            }

            assertTrue(runtime.job.isActive)
            assertTrue(siblingRan)
            assertEquals(1, reported.size)
            assertEquals("child failed", reported.single().message)
        } finally {
            runtime.job.cancel()
        }
    }
}

private class RuntimeFakeClock(var nowNanos: ULong = 0uL) {
    fun nanoTime(): ULong = nowNanos
}

private object RuntimeNoOpCriticalSection : CriticalSection {
    override fun <T> withLock(block: () -> T): T = block()
}
