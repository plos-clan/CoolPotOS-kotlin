package org.plos_clan.cpos.fs.tmpfs

import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.MutableInodeBackend
import org.plos_clan.cpos.fs.vfs.SymlinkBackend
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult

internal class TmpfsSymlink(
    private val target: VfsPathname,
) : SymlinkBackend, MutableInodeBackend {
    override fun readLink(
        caller: VfsOperationContext,
        inode: Inode,
    ): VfsResult<VfsPathname> = VfsResult.Ok(target)
}
