package org.plos_clan.cpos.fs.tmpfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TmpfsQuotaTest {
    @Test
    fun enforcesCapacityAndReusesReleasedResources() {
        val quota = TmpfsQuota(3uL)
        assertTrue(quota.reserve(1uL))
        assertFalse(quota.reserve(3uL))
        assertEquals(1uL, quota.used)
        assertEquals(2uL, quota.available)
        assertTrue(quota.reserve(2uL))
        assertFalse(quota.reserve(1uL))
        assertEquals(0uL, quota.available)

        quota.release(1uL)
        assertEquals(1uL, quota.available)
        assertTrue(quota.reserve(1uL))
        quota.release(3uL)
        assertEquals(0uL, quota.used)
        assertEquals(3uL, quota.available)
    }

    @Test
    fun distinguishesAnEmptyQuotaFromAnUnlimitedQuota() {
        val empty = TmpfsQuota(0uL)
        assertTrue(empty.reserve(0uL))
        assertFalse(empty.reserve(1uL))

        val unlimited = TmpfsQuota(null)
        assertTrue(unlimited.reserve(ULong.MAX_VALUE))
        assertFalse(unlimited.reserve(1uL))
        assertEquals(ULong.MAX_VALUE, unlimited.used)
        unlimited.release(ULong.MAX_VALUE)
        assertTrue(unlimited.reserve(1uL))
    }

    @Test
    fun rejectsOverReleaseWithoutChangingUsage() {
        val quota = TmpfsQuota(5uL)
        assertTrue(quota.reserve(2uL))
        assertFailsWith<IllegalStateException> { quota.release(3uL) }
        assertEquals(2uL, quota.used)
        assertEquals(3uL, quota.available)
    }
}
