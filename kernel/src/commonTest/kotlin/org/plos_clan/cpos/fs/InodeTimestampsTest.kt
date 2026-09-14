package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.vfs.InodeTimestampEvent
import org.plos_clan.cpos.fs.vfs.InodeTimestampSet
import org.plos_clan.cpos.fs.vfs.InodeTimestamps
import org.plos_clan.cpos.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InodeTimestampsTest {
    private val original = InodeTimestamps(
        accessTime = Instant(30, 300u),
        modificationTime = Instant(20, 200u),
        changeTime = Instant(10, 100u),
        birthTime = Instant(0, 0u),
    )

    @Test
    fun ordersTimestampsAndValidatesNanoseconds() {
        assertTrue(Instant(-1, 999_999_999u) < Instant(0, 0u))
        assertTrue(Instant(1, 1u) < Instant(1, 2u))
        assertEquals(0, Instant(1, 2u).compareTo(Instant(1, 2u)))
        assertFailsWith<IllegalArgumentException> {
            Instant(0, 1_000_000_000u)
        }
    }

    @Test
    fun appliesBasicTimestampEvents() {
        val now = Instant(40, 400u)

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
        val justBeforeInterval = Instant(86_430, 299u)
        val atInterval = Instant(86_430, 300u)

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
        val now = Instant(40, 400u)
        val exact = Instant(50, 500u)
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
