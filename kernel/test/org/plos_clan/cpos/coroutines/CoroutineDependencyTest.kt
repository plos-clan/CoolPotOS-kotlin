package org.plos_clan.cpos.coroutines

import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertTrue

class CoroutineDependencyTest {
    @Test
    fun supervisorJobStartsActive() {
        val job = SupervisorJob()

        try {
            assertTrue(job.isActive)
        } finally {
            job.cancel()
        }
    }
}
