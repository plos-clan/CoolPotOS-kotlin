@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.mem

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PTE_COUNT
import org.plos_clan.cpos.utils.toPointer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class KernelPageArenaTest {
    @Test
    fun reusesVirtualRangesAcrossPageTables() {
        val arena = KernelPageDirectory.pages
        val pages = PTE_COUNT + 1
        val stride = PAGE_SIZE_BYTES.toInt() / Int.SIZE_BYTES
        repeat(2) { pass ->
            val address = arena.map(pages.toULong())
            assertNotEquals(0uL, address)
            try {
                val memory = checkNotNull(address.toPointer<IntVar>())
                for (index in 0 until pages) memory[index * stride] = index + pass
                for (index in 0 until pages) assertEquals(index + pass, memory[index * stride])
            } finally {
                arena.release(address, pages.toULong())
            }
        }
    }
}
