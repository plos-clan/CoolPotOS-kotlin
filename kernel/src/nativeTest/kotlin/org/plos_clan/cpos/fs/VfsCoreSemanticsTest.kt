package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.vfs.AccessPermission
import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.DirectoryLookup
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.VfsError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VfsCoreSemanticsTest {
    @Test
    fun preservesAnyLinuxErrno() {
        val stale = VfsError.fromErrno(116)
        val result = IoResult.failure(stale)

        assertEquals(116, stale.errno)
        assertEquals(116, result.error?.errno)
        assertEquals(VfsError.IO, VfsError.fromErrno(0))
        assertEquals(VfsError.IO, VfsError.fromErrno(4096))
    }

    @Test
    fun representsFiniteAndUnboundedCacheValidity() {
        val finite = CacheValidity.expiresAfter(100uL, 2uL, 5u)

        assertFalse(finite.isValid(2_000_000_105uL))
        assertTrue(finite.isValid(2_000_000_104uL))
        assertSame(CacheValidity.Volatile, CacheValidity.expiresAfter(0uL, 0uL, 0u))
        assertSame(
            CacheValidity.Persistent,
            CacheValidity.expiresAfter(ULong.MAX_VALUE, 1uL, 0u),
        )
    }

    @Test
    fun rejectsReferencesOnNegativeLookups() {
        assertFailsWith<IllegalArgumentException> {
            DirectoryLookup(null, reference = { })
        }
    }

    @Test
    fun validatesAccessPermissionSets() {
        val permissions = requireNotNull(AccessPermissions.fromBits(0x7u))

        assertTrue(AccessPermission.READ in permissions)
        assertTrue(AccessPermission.WRITE in permissions)
        assertTrue(AccessPermission.EXECUTE in permissions)
        assertEquals(null, AccessPermissions.fromBits(0x8u))
    }

    @Test
    fun decodesSetIdExecutionMode() {
        val helperMode = FileMode(0x848u)

        assertTrue(helperMode.setUserId)
        assertFalse(helperMode.setGroupId)
        assertTrue(helperMode.groupExecutable)
    }
}
