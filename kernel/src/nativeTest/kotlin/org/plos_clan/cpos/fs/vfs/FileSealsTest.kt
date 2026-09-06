package org.plos_clan.cpos.fs.vfs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileSealsTest {
    private val executable = FileMode(0x1FFu)
    private val data = FileMode(0x1B6u)

    @Test
    fun sealsAreMonotonicAndUpdatesAreAtomic() {
        val seals = FileSeals(0)
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.GROW, data))
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.GROW, data))
        assertEquals(VfsResult.Err(VfsError.INVALID_ARGUMENT), seals.add(FileSeals.SHRINK or 0x40, data))
        assertEquals(FileSeals.GROW, seals.bits)
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.SEAL or FileSeals.SHRINK, data))
        assertEquals(VfsResult.Err(VfsError.NOT_PERMITTED), seals.add(0, data))
        assertEquals(FileSeals.SEAL or FileSeals.SHRINK or FileSeals.GROW, seals.bits)
        assertEquals(VfsResult.Err(VfsError.NOT_PERMITTED), FileSeals().add(FileSeals.WRITE, data))
    }

    @Test
    fun sizeSealsOnlyRestrictTheirDirection() {
        val grow = FileSeals(FileSeals.GROW)
        assertTrue(grow.allowsResize(4096uL, 4096uL))
        assertTrue(grow.allowsResize(4096uL, 0uL))
        assertFalse(grow.allowsResize(4096uL, 4097uL))
        assertTrue(grow.allowsWrite(4096uL, 4096uL))
        assertFalse(grow.allowsWrite(4096uL, 4097uL))
        val shrink = FileSeals(FileSeals.SHRINK)
        assertFalse(shrink.allowsResize(4096uL, 4095uL))
        assertTrue(shrink.allowsResize(4096uL, ULong.MAX_VALUE))
        assertTrue(shrink.allowsWrite(4096uL, 8192uL))
    }

    @Test
    fun writeSealWaitsForAllMappingsThatMayBecomeWritable() {
        val seals = FileSeals(0)
        // A read-only VMA opened from an O_RDWR descriptor may later gain PROT_WRITE.
        assertEquals(VfsResult.Ok(7uL), seals.acquireMapping(true, 1uL, 7uL))
        assertEquals(VfsResult.Ok(7uL), seals.acquireMapping(true, 3uL, 7uL))
        assertEquals(VfsResult.Err(VfsError.BUSY), seals.add(FileSeals.WRITE or FileSeals.SEAL, data))
        assertEquals(0, seals.bits)
        seals.releaseMapping(true, 7uL)
        assertEquals(VfsResult.Err(VfsError.BUSY), seals.add(FileSeals.WRITE, data))
        seals.releaseMapping(true, 7uL)
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.WRITE, data))
    }

    @Test
    fun privateAndReadOnlyDescriptorMappingsDoNotBlockSealing() {
        val seals = FileSeals(0)
        assertEquals(VfsResult.Ok(7uL), seals.acquireMapping(false, 3uL, 7uL))
        assertEquals(VfsResult.Ok(5uL), seals.acquireMapping(true, 1uL, 5uL))
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.WRITE, data))
        seals.releaseMapping(false, 7uL)
        seals.releaseMapping(true, 5uL)
        assertEquals(VfsResult.Ok(7uL), seals.acquireMapping(false, 3uL, 7uL))
        assertFalse(seals.allowsWrite(4096uL, 1uL))
        assertTrue(seals.allowsResize(4096uL, 8192uL))
    }

    @Test
    fun futureWritePreservesExistingMappingsAndRestrictsNewOnes() {
        val seals = FileSeals(0)
        val original = assertIs<VfsResult.Ok<ULong>>(seals.acquireMapping(true, 1uL, 7uL)).value
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.FUTURE_WRITE, data))
        assertEquals(7uL, original)
        assertEquals(VfsResult.Err(VfsError.NOT_PERMITTED), seals.acquireMapping(true, 3uL, 7uL))
        assertEquals(VfsResult.Ok(5uL), seals.acquireMapping(true, 1uL, 7uL))
        assertFalse(seals.allowsWrite(4096uL, 1uL))
        assertEquals(VfsResult.Err(VfsError.BUSY), seals.add(FileSeals.WRITE, data))
        seals.releaseMapping(true, original)
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.WRITE, data))
        seals.releaseMapping(true, 5uL)
    }

    @Test
    fun executableSealAlsoMakesExecutableContentsImmutable() {
        val seals = FileSeals(0)
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.EXEC, executable))
        assertEquals(FileSeals.ALL and FileSeals.SEAL.inv(), seals.bits)
        assertFalse(seals.allowsMode(executable, data))
        assertTrue(seals.allowsMode(executable, FileMode(0x149u)))
        assertFalse(seals.allowsWrite(4096uL, 1uL))
        assertFalse(seals.allowsResize(4096uL, 0uL))
        assertFalse(seals.allowsResize(4096uL, 8192uL))
    }

    @Test
    fun noexecSealPreservesDataWritesAndPreventsAddingExecuteBits() {
        val seals = FileSeals(FileSeals.EXEC)
        assertTrue(seals.allowsWrite(0uL, 4096uL))
        assertTrue(seals.allowsResize(0uL, 4096uL))
        assertTrue(seals.allowsMode(data, FileMode(0x180u)))
        for (executeBit in listOf(1u, 8u, 64u)) {
            assertFalse(seals.allowsMode(data, FileMode(data.bits or executeBit)))
        }
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.SEAL, data))
    }

    @Test
    fun implicitWriteSealCannotBypassAnExistingWritableMapping() {
        val seals = FileSeals(0)
        seals.acquireMapping(true, 3uL, 7uL)
        assertEquals(VfsResult.Err(VfsError.BUSY), seals.add(FileSeals.EXEC, executable))
        assertEquals(0, seals.bits)
        seals.releaseMapping(true, 7uL)
        assertIs<VfsResult.Ok<Unit>>(seals.add(FileSeals.EXEC, executable))
    }
}
