package org.plos_clan.cpos.tasks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OomScoreAdjustmentTest {
    @Test
    fun parsesLinuxIntegerSyntaxWithinTheAbiRange() {
        val values = mapOf(
            "-1000\n" to -1000,
            "1000" to 1000,
            "+42" to 42,
            " 0x10\t" to 16,
            "010" to 8,
            "-01750" to -1000,
        )
        for ((input, expected) in values) {
            assertEquals(expected, OomScoreAdjustment.parse(input.encodeToByteArray()), input)
        }
    }

    @Test
    fun rejectsMalformedAndOutOfRangeValues() {
        for (input in listOf("", " ", "1001", "-1001", "08", "0x", "1 0", "1junk")) {
            assertNull(OomScoreAdjustment.parse(input.encodeToByteArray()), input)
        }
    }

    @Test
    fun enforcesAndInheritsThePrivilegedMinimum() {
        val parent = OomScoreAdjustment()
        assertEquals(0, parent.value)
        assertTrue(parent.update(-500, maySetMinimum = true))
        assertTrue(parent.update(500, maySetMinimum = false))

        val child = OomScoreAdjustment().also { it.inherit(parent) }
        assertTrue(child.update(-500, maySetMinimum = false))
        assertFalse(child.update(-501, maySetMinimum = false))
        assertEquals(-500, child.value)

        assertTrue(parent.update(1000, maySetMinimum = false))
        assertEquals(-500, child.value)

        assertTrue(parent.update(250, maySetMinimum = true))
        assertTrue(parent.update(500, maySetMinimum = false))
        assertFalse(parent.update(249, maySetMinimum = false))
        assertEquals(500, parent.value)
    }
}
