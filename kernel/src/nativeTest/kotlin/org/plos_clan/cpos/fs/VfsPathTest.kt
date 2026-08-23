package org.plos_clan.cpos.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult

class VfsPathTest {
    @Test
    fun validatesNamesAndCopiesInputBytes() {
        val source = "kernel".encodeToByteArray()
        val name = assertIs<VfsResult.Ok<VfsName>>(VfsName.fromBytes(source)).value
        source[0] = 'x'.code.toByte()

        assertEquals("kernel", name.toString())
        assertEquals(
            assertIs<VfsResult.Ok<VfsName>>(
                VfsName.fromBytes("kernel".encodeToByteArray()),
            ).value,
            name,
        )

        val copy = name.copyBytes()
        copy[0] = 'x'.code.toByte()
        assertEquals("kernel", name.toString())
    }

    @Test
    fun rejectsInvalidAndOversizedNames() {
        assertEquals(
            VfsResult.Err(VfsError.INVALID_ARGUMENT),
            VfsName.fromBytes(ByteArray(0)),
        )
        assertEquals(
            VfsResult.Err(VfsError.INVALID_ARGUMENT),
            VfsName.fromBytes("a/b".encodeToByteArray()),
        )
        assertEquals(
            VfsResult.Err(VfsError.INVALID_ARGUMENT),
            VfsName.fromBytes(byteArrayOf('a'.code.toByte(), 0)),
        )
        assertEquals(
            VfsResult.Err(VfsError.NAME_TOO_LONG),
            VfsName.fromBytes(ByteArray(VfsName.MAX_LENGTH + 1) { 'a'.code.toByte() }),
        )
        assertEquals(
            VfsName.MAX_LENGTH,
            assertIs<VfsResult.Ok<VfsName>>(
                VfsName.fromBytes(ByteArray(VfsName.MAX_LENGTH) { 'a'.code.toByte() }),
            ).value.size,
        )
    }

    @Test
    fun splitsPathsWithoutNormalizingComponents() {
        val path = VfsPathname.fromString("///usr//bin/../")
        val components = assertIs<VfsResult.Ok<List<VfsName>>>(path.components()).value

        assertTrue(path.isAbsolute)
        assertTrue(path.requiresDirectory)
        assertFalse(path.isRoot)
        assertEquals(listOf("usr", "bin", ".."), components.map(VfsName::toString))
        assertTrue(components.last().isDotDot)

        val relative = assertIs<VfsResult.Ok<List<VfsName>>>(
            VfsPathname.fromString("./kernel").components(),
        ).value
        assertTrue(relative.first().isDot)

        val root = VfsPathname.fromString("////")
        assertTrue(root.isRoot)
        assertTrue(assertIs<VfsResult.Ok<List<VfsName>>>(root.components()).value.isEmpty())
    }

    @Test
    fun rejectsInvalidComponentsAndCopiesPathBytes() {
        val source = "/tmp".encodeToByteArray()
        val path = VfsPathname.fromBytes(source)
        source[1] = 'x'.code.toByte()

        assertEquals("/tmp", path.toString())
        val copy = path.copyBytes()
        copy[1] = 'x'.code.toByte()
        assertEquals("/tmp", path.toString())

        assertEquals(
            VfsResult.Err(VfsError.INVALID_ARGUMENT),
            VfsPathname.fromBytes(byteArrayOf('a'.code.toByte(), 0)).components(),
        )
        assertEquals(
            VfsResult.Err(VfsError.NAME_TOO_LONG),
            VfsPathname.fromString("a".repeat(VfsName.MAX_LENGTH + 1)).components(),
        )
    }
}
