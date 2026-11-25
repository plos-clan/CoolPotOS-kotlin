package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.AccessPermission
import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.CacheValidity
import org.plos_clan.cpos.fs.vfs.DirectoryLookup
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.InodeAttributes
import org.plos_clan.cpos.fs.vfs.IoResult
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.RenameMode
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.time.MonotonicClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class VfsCoreSemanticsTest {
    @Test
    fun directoryIndexMovesTheOriginalDentriesOnExchangeAndReplacement() {
        val vfs = Vfs()
        assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
        val context = assertIs<VfsResult.Ok<FileSystemContext>>(vfs.createContext(Tmpfs.name)).value
        val caller = VfsOperationContext.KERNEL
        val root = context.root
        val mode = FileMode(0x1edu)
        val directory = NodeCreation(NodeKind.Directory, mode)
        fun mkdir(path: VfsPathname): VfsPath {
            val created = vfs.createNode(caller, context, path, directory)
            return assertIs<VfsResult.Ok<VfsPath>>(created).value
        }
        fun resolve(path: VfsPathname): VfsPath {
            val resolved = vfs.resolve(caller, context, path)
            return assertIs<VfsResult.Ok<VfsPath>>(resolved).value
        }
        try {
            val parent = VfsPathname.fromString("/dir")
            val left = VfsPathname.fromString("/left")
            val right = VfsPathname.fromString("/dir/right")
            val next = VfsPathname.fromString("/dir/next")
            mkdir(parent)
            val original = mkdir(left)
            val first = original.dentry
            val second = mkdir(right).dentry
            val replaced = mkdir(next).dentry
            val exchange = vfs.rename(caller, context, root, left, root, right, RenameMode.EXCHANGE)
            assertIs<VfsResult.Ok<Unit>>(exchange)
            assertSame(first, resolve(right).dentry)
            assertSame(second, resolve(left).dentry)
            val rename = vfs.rename(caller, context, root, right, root, next, RenameMode.REPLACE)
            assertIs<VfsResult.Ok<Unit>>(rename)
            assertSame(first, resolve(next).dentry)
            val absolute = assertIs<VfsResult.Ok<ByteArray>>(vfs.absolutePath(context, original))
            assertEquals(next.toString(), absolute.value.decodeToString())
            assertTrue(replaced.isUnlinked)
            val missing = assertIs<VfsResult.Err>(vfs.resolve(caller, context, right))
            assertEquals(VfsError.NOT_FOUND, missing.error)
            val alias = VfsPathname.fromString("/alias")
            val target = VfsPathname.fromString("./left/../dir")
            val link = NodeKind.SymbolicLink(target)
            val creation = NodeCreation(link, mode)
            assertIs<VfsResult.Ok<VfsPath>>(vfs.createNode(caller, context, alias, creation))
            val throughLink = VfsPathname.fromString("/alias/next")
            assertSame(first, resolve(throughLink).dentry)
        } finally {
            context.release()
        }
    }

    @Test
    fun invalidatingAttributesDoesNotChangeAPreviousRead() {
        val vfs = Vfs()
        assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
        val context = assertIs<VfsResult.Ok<FileSystemContext>>(vfs.createContext(Tmpfs.name)).value
        val caller = VfsOperationContext.KERNEL
        try {
            val inode = checkNotNull(context.root.inode)
            val previous = assertIs<VfsResult.Ok<InodeAttributes>>(inode.attributes(caller))
            val owner = previous.value.metadata.uid
            assertSame(previous, inode.attributes(caller, cachedOnly = true))
            inode.updateMetadata { it.copy(uid = owner + 1u) }
            val stale = assertIs<VfsResult.Err>(inode.attributes(caller, cachedOnly = true))
            assertEquals(VfsError.WOULD_BLOCK, stale.error)
            val refreshed = assertIs<VfsResult.Ok<InodeAttributes>>(inode.attributes(caller))
            assertEquals(owner + 1u, refreshed.value.metadata.uid)
            assertEquals(owner, previous.value.metadata.uid)
        } finally {
            context.release()
        }
    }

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

        assertFalse(finite.isValid { 2_000_000_105uL })
        assertTrue(finite.isValid { 2_000_000_104uL })
        val unused = MonotonicClock { error("Non-expiring validity read the clock") }
        assertTrue(CacheValidity.Persistent.isValid(unused))
        assertFalse(CacheValidity.Volatile.isValid(unused))
        val invalidated = CacheValidity.Invalidatable(finite)
        invalidated.invalidate()
        assertFalse(invalidated.isValid(unused))
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
