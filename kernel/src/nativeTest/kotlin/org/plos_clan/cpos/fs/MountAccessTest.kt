package org.plos_clan.cpos.fs

import org.plos_clan.cpos.fs.tmpfs.Tmpfs
import org.plos_clan.cpos.fs.vfs.AccessPermissions
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.MountAttributeUpdate
import org.plos_clan.cpos.fs.vfs.MountFlag
import org.plos_clan.cpos.fs.vfs.MountFlags
import org.plos_clan.cpos.fs.vfs.NodeCreation
import org.plos_clan.cpos.fs.vfs.NodeKind
import org.plos_clan.cpos.fs.vfs.Vfs
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MountAccessTest {
    @Test
    fun accessHonorsReadOnlyAndNoExecEvenForRoot() {
        val vfs = Vfs()
        val caller = VfsOperationContext.KERNEL
        assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
        val context = assertIs<VfsResult.Ok<FileSystemContext>>(vfs.createContext(Tmpfs.name)).value
        try {
            val pathname = VfsPathname.fromString("/program")
            val node = NodeCreation(NodeKind.Regular, FileMode(0x1ffu))
            assertIs<VfsResult.Ok<*>>(vfs.createNode(caller, context, pathname, node))
            val file = assertIs<VfsResult.Ok<VfsPath>>(vfs.resolve(caller, context, pathname)).value
            val rootName = VfsPathname.fromString("/")
            val root = assertIs<VfsResult.Ok<VfsPath>>(vfs.resolve(caller, context, rootName)).value
            val write = AccessPermissions.WRITE
            val execute = AccessPermissions.EXECUTE
            assertIs<VfsResult.Ok<Unit>>(vfs.access(caller, file, write))
            assertIs<VfsResult.Ok<Unit>>(vfs.access(caller, file, execute))
            val flags = MountFlags.of(MountFlag.READ_ONLY, MountFlag.NO_EXEC)
            val attributes = MountAttributeUpdate(flags, MountFlags.NONE)
            assertIs<VfsResult.Ok<Unit>>(vfs.setMountAttributes(context, root, attributes, false))
            assertEquals(VfsResult.Err(VfsError.READ_ONLY), vfs.access(caller, root, write))
            assertEquals(VfsResult.Err(VfsError.READ_ONLY), vfs.access(caller, file, write))
            assertEquals(VfsResult.Err(VfsError.PERMISSION_DENIED), vfs.access(caller, file, execute))
            assertIs<VfsResult.Ok<Unit>>(vfs.access(caller, root, execute))
            assertIs<VfsResult.Ok<Unit>>(vfs.access(caller, file, AccessPermissions.NONE))
        } finally {
            context.release()
        }
    }

    @Test
    fun filesystemRemountSharesReadOnlyStateAcrossBindMounts() {
        val vfs = Vfs()
        val caller = VfsOperationContext.KERNEL
        assertIs<VfsResult.Ok<Unit>>(vfs.register(Tmpfs))
        val context = assertIs<VfsResult.Ok<FileSystemContext>>(vfs.createContext(Tmpfs.name)).value
        try {
            val rootName = VfsPathname.fromString("/")
            val viewName = VfsPathname.fromString("/view")
            val mode = FileMode(0x1edu)
            val directory = NodeCreation(NodeKind.Directory, mode)
            assertIs<VfsResult.Ok<*>>(vfs.createNode(caller, context, viewName, directory))
            assertIs<VfsResult.Ok<Unit>>(vfs.bindMount(caller, context, rootName, viewName))
            val root = assertIs<VfsResult.Ok<VfsPath>>(vfs.resolve(caller, context, rootName)).value
            val view = assertIs<VfsResult.Ok<VfsPath>>(vfs.resolve(caller, context, viewName)).value
            val readOnly = MountFlags.of(MountFlag.READ_ONLY)
            val lock = MountAttributeUpdate(readOnly, MountFlags.NONE)
            assertIs<VfsResult.Ok<Unit>>(vfs.remount(caller, context, root, lock))
            val denied = VfsResult.Err(VfsError.READ_ONLY)
            val write = AccessPermissions.WRITE
            assertEquals(denied, vfs.access(caller, view, write))
            val unlock = MountAttributeUpdate(MountFlags.NONE, readOnly)
            assertIs<VfsResult.Ok<Unit>>(vfs.remount(caller, context, root, unlock))
            assertIs<VfsResult.Ok<Unit>>(vfs.access(caller, view, write))
        } finally {
            context.release()
        }
    }
}
