package org.plos_clan.cpos.syscall.fs

import org.plos_clan.cpos.fs.OpenFlags
import org.plos_clan.cpos.fs.vfs.PathResolutionBoundary
import org.plos_clan.cpos.fs.vfs.SymlinkResolution
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxOpenHowTest {
    @Test
    fun decodesAllResolutionPolicies() {
        val how = assertNotNull(decode(
            flags = OpenFlags.O_RDONLY.toULong(),
            resolve = 0x01uL or 0x04uL or 0x08uL or 0x20uL,
        ))

        assertEquals(PathResolutionBoundary.BENEATH, how.resolution.boundary)
        assertEquals(SymlinkResolution.NO_SYMLINKS, how.resolution.symlinks)
        assertFalse(how.resolution.allowMountCrossing)
        assertTrue(how.resolution.cachedOnly)

        val inRoot = assertNotNull(decode(resolve = 0x02uL or 0x10uL))
        assertEquals(PathResolutionBoundary.IN_ROOT, inRoot.resolution.boundary)
        assertEquals(SymlinkResolution.NO_MAGIC_LINKS, inRoot.resolution.symlinks)
    }

    @Test
    fun enforcesOpenHowValidation() {
        assertNull(decode(flags = 0x8000_0000uL))
        assertNull(decode(mode = 0x1uL))
        assertNull(decode(flags = OpenFlags.O_CREAT.toULong(), mode = 0x1000uL))
        assertNull(decode(resolve = 0x40uL))
        assertNull(decode(resolve = 0x08uL or 0x10uL))
        assertNull(decode(flags = (OpenFlags.O_PATH or OpenFlags.O_NONBLOCK).toULong()))
        assertNull(decode(
            flags = (OpenFlags.O_CREAT or OpenFlags.O_DIRECTORY).toULong(),
            mode = 0x1c0uL,
        ))
        assertNull(decode(flags = OpenFlags.O_TMPFILE.toULong(), mode = 0x180uL))

        val create = assertNotNull(decode(
            flags = (OpenFlags.O_CREAT or OpenFlags.O_RDWR).toULong(),
            mode = 0x1ffuL,
        ))
        assertEquals(0x1ffu, create.mode)
        assertNotNull(decode(
            flags = (OpenFlags.O_TMPFILE or OpenFlags.O_RDWR).toULong(),
            mode = 0x180uL,
        ))
    }

    private fun decode(
        flags: ULong = 0uL,
        mode: ULong = 0uL,
        resolve: ULong = 0uL,
    ): LinuxOpenHow? {
        val bytes = ByteArray(LinuxOpenHow.NATIVE_SIZE)
        LittleEndianBuffer(bytes).apply {
            writeU64(0, flags)
            writeU64(ULong.SIZE_BYTES, mode)
            writeU64(ULong.SIZE_BYTES * 2, resolve)
        }
        return LinuxOpenHow.decode(bytes)
    }
}
