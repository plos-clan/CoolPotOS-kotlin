package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.DirectoryEntry
import org.plos_clan.cpos.fs.vfs.Inode
import org.plos_clan.cpos.fs.vfs.InodeType
import org.plos_clan.cpos.fs.vfs.SuperBlock
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager

internal class ProcDescriptorDirectory(
    fileSystem: ProcfsInstance,
    private val pid: Int,
) : ProcDirectoryBackend(fileSystem) {
    override fun resolve(superBlock: SuperBlock, name: VfsName): Inode? {
        val fd = name.toString().decimalInt() ?: return null
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

    private fun descriptorTarget(fd: Int): VfsPathname? {
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return null
        val file = process.fdTable.acquire(fd) ?: return null
        return try {
            val inode = file.inode
            val inodeId = inode.id.value
            if (inode.type == InodeType.PIPE &&
                file.path.dentry === file.path.mount.root
            ) {
                return VfsPathname.fromString("pipe:[$inodeId]")
            }
            if (inode.type == InodeType.SOCKET) {
                return VfsPathname.fromString("socket:[$inodeId]")
            }

            val context = process.context ?: return null
            val result = FileSystemManager.vfs.absolutePath(context, file.path)
            val path = (result as? VfsResult.Ok)?.value ?: return null
            val pathInode = file.path.inode
            val linked = pathInode?.superBlock === inode.superBlock && pathInode.id == inode.id
            VfsPathname.fromBytes(if (linked) path else path + DELETED_SUFFIX)
        } finally {
            file.release()
        }
    }
}

private val DELETED_SUFFIX = " (deleted)".encodeToByteArray()
private const val DESCRIPTOR_LINK_MODE = 0x140u
