package org.plos_clan.cpos.coroutines

import kotlin.test.Test
import kotlin.test.assertEquals

class KernelBootContractTest {
    @Test
    fun smokeTestSuccessMarkerIsStable() {
        assertEquals("Coroutine smoke test passed", COROUTINE_SMOKE_SUCCESS_MARKER)
    }
}
