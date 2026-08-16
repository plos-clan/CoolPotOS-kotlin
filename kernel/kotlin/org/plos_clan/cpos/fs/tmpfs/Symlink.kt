package org.plos_clan.cpos.fs

internal class TmpfsSymlink(
    private val target: VfsPathname,
) : SymlinkBackend, MutableInodeBackend {
    override val type: InodeType = InodeType.SYMLINK

    override fun readLink(inode: Inode): VfsResult<VfsPathname> = VfsResult.Ok(target)

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
}
