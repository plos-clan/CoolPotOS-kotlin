package org.plos_clan.cpos.fs.tmpfs

import org.plos_clan.cpos.fs.vfs.AccessMode
import org.plos_clan.cpos.fs.vfs.Dentry
import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSeals
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

internal value class MemFdFlags private constructor(val bits: UInt) {
    val closeOnExec: Boolean
        get() = bits and CLOEXEC != 0u
    val executable: Boolean
        get() = bits and NOEXEC_SEAL == 0u
    val initialSeals: Int
        get() = when {
            !executable -> FileSeals.EXEC
            bits and ALLOW_SEALING != 0u -> 0
            else -> FileSeals.SEAL
        }

    companion object {
        const val CLOEXEC = 0x01u
        const val ALLOW_SEALING = 0x02u
        const val HUGETLB = 0x04u
        const val NOEXEC_SEAL = 0x08u
        const val EXEC = 0x10u
        private const val HUGE_SIZE_MASK = 0xfc00_0000u

        fun from(bits: UInt): VfsResult<MemFdFlags> {
            val huge = bits and HUGETLB != 0u
            val allowed = CLOEXEC or ALLOW_SEALING or HUGETLB or NOEXEC_SEAL or EXEC or
                (if (huge) HUGE_SIZE_MASK else 0u)
            if (bits and allowed.inv() != 0u || bits and (NOEXEC_SEAL or EXEC) == (NOEXEC_SEAL or EXEC)) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            if (huge) return VfsResult.Err(VfsError.NOT_SUPPORTED)
            return VfsResult.Ok(MemFdFlags(bits))
        }
    }
}

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
