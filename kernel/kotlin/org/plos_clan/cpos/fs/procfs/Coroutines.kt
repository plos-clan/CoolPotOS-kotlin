package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.coroutines.CoroutineEntry
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.fs.DirectoryBackend
import org.plos_clan.cpos.fs.DirectoryEntry
import org.plos_clan.cpos.fs.Inode
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.fs.OpenFileBackend
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.SuperBlock
import org.plos_clan.cpos.fs.VfsName
import org.plos_clan.cpos.fs.VfsResult

private const val COROUTINE_INODE_BASE = 0x1_0000_0000uL

class ProcCoroutineDirectory: DirectoryBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override val cacheNegativeLookups: Boolean = false

    override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
        VfsResult.Ok(ProcCoroutine.coroutineEntry(directory.superBlock, name))

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(ProcDirectoryHandle(ProcCoroutine.coroutineDirectoryEntries()))
}

object ProcCoroutine {
    fun coroutineEntry(superBlock: SuperBlock, name: VfsName): Inode? {
        val id = name.toString().toIntOrNull()?.takeIf { it != 0 } ?: return null
        if (KernelCoroutines.snapshotJobs().none { it.id == id }) return null
        return Procfs.getInstance.text(superBlock, coroutineInode(id)) {
            KernelCoroutines.snapshotJobs().firstOrNull { it.id == id }?.let(::render)
        }
    }

    fun coroutineDirectoryEntries(): List<DirectoryEntry> =
        KernelCoroutines.snapshotJobs().map { coroutine ->
            Procfs.getInstance.entry(coroutine.id.toString(), coroutineInode(coroutine.id), InodeType.REGULAR)
        }

    private fun coroutineInode(id: Int): ULong = COROUTINE_INODE_BASE + id.toULong()

    private fun render(coroutine: CoroutineEntry): ByteArray {
        val job = coroutine.job
        val active = job.isActive
        val completed = job.isCompleted
        val cancelled = job.isCancelled
        val state = when {
            completed && cancelled -> "cancelled"
            completed -> "completed"
            cancelled -> "cancelling"
            active -> "active"
            else -> "new"
        }
        return buildString {
            append("id:\t").append(coroutine.id).append('\n')
            append("name:\t").append(coroutine.name).append('\n')
            append("state:\t").append(state).append('\n')
            append("active:\t").append(if (active) 1 else 0).append('\n')
            append("completed:\t").append(if (completed) 1 else 0).append('\n')
            append("cancelled:\t").append(if (cancelled) 1 else 0).append('\n')
            append("children:\t").append(job.children.count()).append('\n')
        }.encodeToByteArray()
    }
}