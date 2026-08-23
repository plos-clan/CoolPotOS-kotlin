package org.plos_clan.cpos.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.plos_clan.cpos.fs.vfs.InodeTimestampEvent
import org.plos_clan.cpos.fs.vfs.InodeTimestampSet
import org.plos_clan.cpos.fs.vfs.InodeTimestamps
import org.plos_clan.cpos.fs.vfs.VfsTimestamp

class VfsTimestampTest {
    private val original = InodeTimestamps(
        accessTime = VfsTimestamp(30, 300u),
        modificationTime = VfsTimestamp(20, 200u),
        changeTime = VfsTimestamp(10, 100u),
        birthTime = VfsTimestamp(0, 0u),
    )

    @Test
    fun ordersTimestampsAndValidatesNanoseconds() {
        assertTrue(VfsTimestamp(-1, 999_999_999u) < VfsTimestamp(0, 0u))
        assertTrue(VfsTimestamp(1, 1u) < VfsTimestamp(1, 2u))
        assertEquals(0, VfsTimestamp(1, 2u).compareTo(VfsTimestamp(1, 2u)))
        assertFailsWith<IllegalArgumentException> {
            VfsTimestamp(0, VfsTimestamp.NANOSECONDS_PER_SECOND)
        }
    }

    @Test
    fun appliesBasicTimestampEvents() {
        val now = VfsTimestamp(40, 400u)

        assertFalse(InodeTimestampEvent.NONE.requiresCurrentTime)
        assertSame(original, InodeTimestampEvent.NONE.apply(original, now))
        assertEquals(
            original.copy(accessTime = now),
            InodeTimestampEvent.ACCESSED.apply(original, now),
        )
        assertEquals(
            original.copy(modificationTime = now, changeTime = now),
            InodeTimestampEvent.CONTENT_CHANGED.apply(original, now),
        )
        assertEquals(
            original.copy(changeTime = now),
            InodeTimestampEvent.STATUS_CHANGED.apply(original, now),
        )
    }

    @Test
    fun appliesRelativeAccessPolicyAtItsBoundaries() {
        val justBeforeInterval = VfsTimestamp(86_430, 299u)
        val atInterval = VfsTimestamp(86_430, 300u)

        assertSame(
            original,
            InodeTimestampEvent.RELATIVE_ACCESS.apply(original, justBeforeInterval),
        )
        assertEquals(
            original.copy(accessTime = atInterval),
            InodeTimestampEvent.RELATIVE_ACCESS.apply(original, atInterval),
        )
        assertEquals(
            original.copy(
                accessTime = justBeforeInterval,
                modificationTime = original.accessTime,
            ),
            InodeTimestampEvent.RELATIVE_ACCESS.apply(
                original.copy(modificationTime = original.accessTime),
                justBeforeInterval,
            ),
        )
    }

    @Test
    fun appliesExplicitTimestampSets() {
        val now = VfsTimestamp(40, 400u)
        val exact = VfsTimestamp(50, 500u)
        val update = InodeTimestampSet(
            accessTime = InodeTimestampSet.Value.Omit,
            modificationTime = InodeTimestampSet.Value.Exact(exact),
        )

        assertEquals(
            original.copy(modificationTime = exact, changeTime = now),
            update.apply(original, now),
        )
        assertTrue(update.requiresCurrentTime)

        val omit = InodeTimestampSet(
            InodeTimestampSet.Value.Omit,
            InodeTimestampSet.Value.Omit,
        )
        assertTrue(omit.omitsBoth)
        assertFalse(omit.requiresCurrentTime)
        assertSame(original, omit.apply(original, now))

        assertTrue(InodeTimestampSet.NOW.setsBothToNow)
        assertEquals(
            original.copy(accessTime = now, modificationTime = now, changeTime = now),
            InodeTimestampSet.NOW.apply(original, now),
        )
    }
}
