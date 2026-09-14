package org.plos_clan.cpos.fs.tmpfs

import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.Dentry
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.InodeMetadata
import org.plos_clan.cpos.fs.vfs.Mount
import org.plos_clan.cpos.fs.vfs.OpenFileDescription
import org.plos_clan.cpos.fs.vfs.OpenOptions
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsPath
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult

internal object MemFd {
    const val NAME_MAX = VfsName.MAX_LENGTH - 6 // Linux reserves the "memfd:" prefix.
    private val mount by lazy {
        Mount(SuperBlock(Tmpfs, TmpfsInstance(TmpfsOptions())), Tmpfs.name, "")
    }

    fun create(caller: VfsOperationContext, name: ByteArray, flags: MemFdFlags): VfsResult<OpenFileDescription> {
        if (name.size > NAME_MAX || name.any { it == 0.toByte() }) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val instance = mount.superBlock.backend as TmpfsInstance
        val backend = TmpfsRegularFile(
            instance,
            flags.initialSeals,
            VfsPathname.fromBytes("/memfd:".encodeToByteArray() + name + " (deleted)".encodeToByteArray()),
        )
        val inode = instance.newInode(
            mount.superBlock,
            backend,
            InodeMetadata(
                mode = FileMode(if (flags.executable) 0x1FFu else 0x1B6u),
                linkCount = 0u,
                uid = caller.uid,
                gid = caller.gid,
            ),
        ) ?: return VfsResult.Err(VfsError.NO_SPACE)
        val path = VfsPath(mount, Dentry(mount.superBlock, VfsName.ROOT, null, inode))
        return OpenFileDescription.open(caller, path, inode, OpenOptions(access = AccessMode.READ_WRITE))
    }
}
