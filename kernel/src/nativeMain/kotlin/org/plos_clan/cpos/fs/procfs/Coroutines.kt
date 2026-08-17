package org.plos_clan.cpos.fs.procfs

import org.plos_clan.cpos.coroutines.CoroutineEntry
import org.plos_clan.cpos.coroutines.KernelCoroutines
import org.plos_clan.cpos.fs.DirectoryEntry
import org.plos_clan.cpos.fs.Inode
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.fs.SuperBlock
import org.plos_clan.cpos.fs.VfsName
import org.plos_clan.cpos.fs.VfsResult

internal class ProcCoroutineDirectory(
    fileSystem: ProcfsInstance,
) : ProcDirectoryBackend(fileSystem) {
    override fun resolve(superBlock: SuperBlock, name: VfsName): Inode? {
        val id = name.toString().decimalInt() ?: return null
        if (KernelCoroutines.snapshotJobs().none { it.id == id }) return null
        return fileSystem.text(superBlock, ProcInode.coroutine(id)) {
            KernelCoroutines.snapshotJobs().firstOrNull { it.id == id }?.let(::render)
        }
    }

    override fun snapshot(): VfsResult<List<DirectoryEntry>> = VfsResult.Ok(
        KernelCoroutines.snapshotJobs().map { coroutine ->
            fileSystem.entry(
                coroutine.id.toString(),
                ProcInode.coroutine(coroutine.id),
                InodeType.REGULAR,
            )
        },
    )

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
