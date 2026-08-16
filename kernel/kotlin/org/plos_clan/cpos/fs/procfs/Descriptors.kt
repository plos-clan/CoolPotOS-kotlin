package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fs.DirectoryEntry
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.Inode
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.fs.SuperBlock
import org.plos_clan.cpos.fs.VfsName
import org.plos_clan.cpos.fs.VfsError
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager

internal class ProcDescriptorDirectory(
    private val fileSystem: ProcfsInstance,
    private val pid: Int,
) : ProcDirectoryBackend() {
    override fun resolve(superBlock: SuperBlock, name: VfsName): Inode? {
        val fd = name.decimalInt() ?: return null
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return null
        if (!process.fdTable.contains(fd)) return null
        return fileSystem.symlink(
            superBlock = superBlock,
            id = ProcInode.descriptor(pid, fd),
            mode = DESCRIPTOR_LINK_MODE,
            owner = process,
        ) { descriptorTarget(fd) }
    }

    override fun snapshot(): VfsResult<List<DirectoryEntry>> {
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        val descriptors = process.fdTable.snapshotDescriptors()
        return VfsResult.Ok(
            buildList(descriptors.size) {
                descriptors.forEach { fd ->
                    add(
                        fileSystem.entry(
                            fd.toString(),
                            ProcInode.descriptor(pid, fd),
                            InodeType.SYMLINK,
                        ),
                    )
                }
            },
        )
    }

    private fun descriptorTarget(fd: Int): String? {
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return null
        val file = process.fdTable.acquire(fd) ?: return null
        return try {
            val inodeId = file.inode.id.value
            if (file.inode.type == InodeType.PIPE &&
                file.path.dentry === file.path.mount.root
            ) {
                return "pipe:[$inodeId]"
            }
            if (file.inode.type == InodeType.SOCKET) return "socket:[$inodeId]"

            val context = process.context ?: return null
            val result = FileSystemManager.vfs.absolutePath(context, file.path)
            val path = (result as? VfsResult.Ok)?.value?.decodeToString() ?: return null
            if (file.path.inode === file.inode) path else "$path (deleted)"
        } finally {
            file.release()
        }
    }
}

private const val DESCRIPTOR_LINK_MODE = 0x140u
