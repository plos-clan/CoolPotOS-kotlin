@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.fs.procfs

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.plos_clan.cpos.fs.Inode
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.fs.SuperBlock
import org.plos_clan.cpos.fs.VfsError
import org.plos_clan.cpos.fs.VfsResult

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

    private data object KernelDirectory : ProcStaticEntry {
        override val fileName = "kernel"
        override val inodeId = 14uL
        override val type = InodeType.DIRECTORY

        override fun create(fileSystem: ProcfsInstance, superBlock: SuperBlock): Inode =
            fileSystem.directory(
                superBlock,
                inodeId,
                ProcStaticDirectory(fileSystem, KernelSetting.entries),
            )
    }

    private enum class KernelSetting(
        override val fileName: String,
        override val inodeId: ULong,
    ) : ProcStaticEntry {
        OVERFLOW_UID("overflowuid", 15uL),
        OVERFLOW_GID("overflowgid", 16uL),
        ;

        override val type = InodeType.REGULAR
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
}

private const val DEFAULT_OVERFLOW_ID = 65_534
private const val MAX_OLD_ID = 65_535
private const val SYSCTL_MODE = 0x1a4u
