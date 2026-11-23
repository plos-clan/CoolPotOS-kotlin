package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class KernelCoroutinesTest {
    @Test
    fun awaitReturnsNullAndPreservesSuspendedFailures() {
        assertNull(KernelCoroutines.await<String?> { null })
        val expected = IllegalStateException("failed after yield")
        val thrown = assertFailsWith<IllegalStateException> {
            KernelCoroutines.await {
                yield()
                throw expected
            }
        }
        assertSame(expected, thrown)
    }
}
