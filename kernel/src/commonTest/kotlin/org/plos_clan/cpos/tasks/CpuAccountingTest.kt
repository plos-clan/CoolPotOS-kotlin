package org.plos_clan.cpos.tasks

import org.plos_clan.cpos.tasks.cgroup.CpuAccounting
import org.plos_clan.cpos.tasks.cgroup.CpuTime
import kotlin.test.Test
import kotlin.test.assertEquals

class CpuAccountingTest {
    @Test
    fun settlesEachIntervalOnceAcrossMovesExitsAndDeletion() {
        var now = CpuTime(4u, 2u)
        val ledger = CpuAccounting<String, Int> { now }
        ledger.move(1, "left")
        now = CpuTime(7u, 11u)
        ledger.move(1, "left")
        ledger.move(1, "right")
        assertEquals(CpuTime(3u, 9u), ledger.read(sequenceOf("left")))
        now = CpuTime(17u, 13u)
        assertEquals(CpuTime(10u, 2u), ledger.read(sequenceOf("right")))
        ledger.move(1, null)
        ledger.remove("right", "parent")
        now = CpuTime(51u, 53u)
        assertEquals(CpuTime(13u, 11u), ledger.read(sequenceOf("parent", "left")))
    }
}
