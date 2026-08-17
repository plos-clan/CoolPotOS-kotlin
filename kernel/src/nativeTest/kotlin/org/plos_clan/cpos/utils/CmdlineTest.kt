package org.plos_clan.cpos.utils

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CmdlineTest {
    @AfterTest
    fun reset() = Cmdline.parse("")

    @Test
    fun parsesFlagsValuesAndDuplicateArguments() {
        val commandLine = "quiet root=/dev/vda1 root=/dev/vdb2 empty= =ignored"
        Cmdline.parse(commandLine)

        assertEquals(commandLine, Cmdline.raw)
        assertEquals(
            listOf(
                Argument("quiet", null),
                Argument("root", "/dev/vda1"),
                Argument("root", "/dev/vdb2"),
                Argument("empty", ""),
            ),
            Cmdline.arguments,
        )
        assertTrue("quiet" in Cmdline)
        assertTrue(Cmdline.isFlag("quiet"))
        assertFalse(Cmdline.isFlag("empty"))
        assertEquals("/dev/vdb2", Cmdline["root"])
        assertEquals(
            listOf(Argument("root", "/dev/vda1"), Argument("root", "/dev/vdb2")),
            Cmdline.getAll("root"),
        )
    }

    @Test
    fun honorsQuotesEscapesAndEmptyValues() {
        Cmdline.parse(
            "init=\"/bin/my init\" message='hello world' " +
                "escaped=one\\ two empty=\"\" trailing=\\",
        )

        assertEquals("/bin/my init", Cmdline["init"])
        assertEquals("hello world", Cmdline["message"])
        assertEquals("one two", Cmdline["escaped"])
        assertEquals("", Cmdline["empty"])
        assertEquals("\\", Cmdline["trailing"])
    }

    @Test
    fun convertsBooleanAndNumericValues() {
        Cmdline.parse(
            "enabled=TRUE disabled=off flag invalid=maybe " +
                "decimal=-42 hex=ff max=18446744073709551615 overflow=18446744073709551616",
        )

        assertEquals(true, Cmdline.boolean("enabled"))
        assertEquals(false, Cmdline.boolean("disabled"))
        assertEquals(true, Cmdline.boolean("flag"))
        assertNull(Cmdline.boolean("invalid"))
        assertNull(Cmdline.boolean("missing"))
        assertEquals(-42, Cmdline.int("decimal"))
        assertEquals(255, Cmdline.int("hex", 16))
        assertEquals(ULong.MAX_VALUE, Cmdline.uLong("max"))
        assertNull(Cmdline.uLong("overflow"))
    }
}
