package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.PidHandle
import org.plos_clan.cpos.tasks.ProcessState
import org.plos_clan.cpos.utils.decimalInt

internal class ProcDescriptorDirectory(
    fileSystem: ProcfsInstance,
    private val target: PidHandle,
) : ProcDirectoryBackend(fileSystem) {
    override fun resolve(superBlock: SuperBlock, name: VfsName): Inode? {
        val fd = name.toString().decimalInt() ?: return null
        val process = target.thread.process
        if (process.state == ProcessState.DEAD) return null
        if (!process.fdTable.contains(fd)) return null
        return fileSystem.symlink(
            superBlock = superBlock,
            id = ProcInode.descriptor(process.id, fd),
            mode = DESCRIPTOR_LINK_MODE,
            owner = process,
            backend = ProcFileSymlink(target) { process.fdTable.acquire(fd) },
        )
    }

    override fun snapshot(): VfsResult<List<DirectoryEntry>> {
        val process = target.thread.process
        if (process.state == ProcessState.DEAD) return VfsResult.Err(VfsError.NOT_FOUND)
        val descriptors = process.fdTable.snapshotDescriptors()
        return VfsResult.Ok(
            buildList(descriptors.size) {
                descriptors.forEach { fd ->
                    add(
                        fileSystem.entry(
                            fd.toString(),
                            ProcInode.descriptor(process.id, fd),
                            InodeType.SYMLINK,
                        ),
                    )
                }
            },
        )
    }
}

private const val DESCRIPTOR_LINK_MODE = 0x140u
