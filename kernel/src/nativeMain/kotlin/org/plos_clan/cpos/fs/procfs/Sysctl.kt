@file:OptIn(ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.BootIdentity
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal object ProcSysTree {
    private val rootEntries = listOf<ProcStaticEntry>(KernelDirectory)

    fun create(
        fileSystem: ProcfsInstance,
        superBlock: SuperBlock,
        inodeId: ULong,
    ): Inode = fileSystem.directory(
        superBlock,
        inodeId,
        ProcStaticDirectory(fileSystem, rootEntries),
    )

    private object KernelDirectory : ProcStaticEntry {
        override val fileName = "kernel"
        override val inodeId = KERNEL_INODE
        override val type = InodeType.DIRECTORY
        private val entries: List<ProcStaticEntry> = KernelSetting.entries + RandomDirectory

        override fun create(fileSystem: ProcfsInstance, superBlock: SuperBlock): Inode =
            fileSystem.directory(
                superBlock,
                inodeId,
                ProcStaticDirectory(fileSystem, entries),
            )
    }

    private object RandomDirectory : ProcStaticEntry {
        override val fileName = "random"
        override val inodeId = KERNEL_INODE + KernelSetting.entries.size.toULong() + 1uL
        override val type = InodeType.DIRECTORY

        override fun create(fileSystem: ProcfsInstance, superBlock: SuperBlock): Inode =
            fileSystem.directory(
                superBlock,
                inodeId,
                ProcStaticDirectory(fileSystem, RandomFile.entries),
            )
    }

    private enum class KernelSetting(
        override val fileName: String,
    ) : ProcStaticEntry {
        OVERFLOW_UID("overflowuid"),
        OVERFLOW_GID("overflowgid"),
        ;

        override val inodeId: ULong
            get() = KERNEL_INODE + ordinal.toULong() + 1uL
        override val type: InodeType
            get() = InodeType.REGULAR
        private val value = AtomicInt(DEFAULT_OVERFLOW_ID)

        override fun create(fileSystem: ProcfsInstance, superBlock: SuperBlock): Inode =
            fileSystem.text(
                superBlock = superBlock,
                id = inodeId,
                mode = SYSCTL_MODE,
                write = ::update,
            ) {
                "${value.load()}\n".encodeToByteArray()
            }

        private fun update(input: ByteArray): VfsResult<Unit> {
            val replacement = input.decodeToString().trim().toIntOrNull()
            if (replacement == null || replacement !in 0..MAX_OLD_ID) {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            value.store(replacement)
            return VfsResult.Ok(Unit)
        }
    }

    private enum class RandomFile(
        override val fileName: String,
    ) : ProcStaticEntry {
        BOOT_ID("boot_id"),
        ;

        override val inodeId: ULong
            get() = RandomDirectory.inodeId + ordinal.toULong() + 1uL
        override val type: InodeType
            get() = InodeType.REGULAR
        private val content = "${BootIdentity.id}\n".encodeToByteArray()

        override fun create(fileSystem: ProcfsInstance, superBlock: SuperBlock): Inode =
            fileSystem.text(superBlock, inodeId) { content }
    }
}

private val KERNEL_INODE = SYS_INODE + 1uL
private const val DEFAULT_OVERFLOW_ID = 65_534
private const val MAX_OLD_ID = 65_535
private const val SYSCTL_MODE = 0x1a4u
