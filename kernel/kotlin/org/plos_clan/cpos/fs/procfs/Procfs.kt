package org.plos_clan.cpos.fs.procfs

import KERNEL_NAME
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.DirectoryBackend
import org.plos_clan.cpos.fs.DirectoryEntry
import org.plos_clan.cpos.fs.EmptyFileSystemOptions
import org.plos_clan.cpos.fs.FileMode
import org.plos_clan.cpos.fs.FilePosition
import org.plos_clan.cpos.fs.FileSystemOptions
import org.plos_clan.cpos.fs.FileSystemType
import org.plos_clan.cpos.fs.Inode
import org.plos_clan.cpos.fs.InodeBackend
import org.plos_clan.cpos.fs.InodeId
import org.plos_clan.cpos.fs.InodeMetadata
import org.plos_clan.cpos.fs.InodeType
import org.plos_clan.cpos.fs.IoResult
import org.plos_clan.cpos.fs.OpenFileBackend
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.RegularFileBackend
import org.plos_clan.cpos.fs.SuperBlock
import org.plos_clan.cpos.fs.SuperBlockBackend
import org.plos_clan.cpos.fs.SymlinkBackend
import org.plos_clan.cpos.fs.VfsError
import org.plos_clan.cpos.fs.VfsName
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager

object Procfs : FileSystemType("proc", 0x9fa0uL) {
    override fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend> =
        if (options === EmptyFileSystemOptions) {
            VfsResult.Ok(ProcfsInstance())
        } else {
            VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
}

interface ProcFSRender {
    fun render(): ByteArray
}

internal class ProcfsInstance : SuperBlockBackend {
    override fun createRoot(superBlock: SuperBlock): Inode = directory(
        superBlock = superBlock,
        id = ROOT_INODE,
        backend = ProcRootDirectory(this),
    )

    fun rootEntry(
        superBlock: SuperBlock,
        name: VfsName
    ): Inode? {
        RootFile.from(name)?.let { file ->
            return text(superBlock, file.inodeId) { file.render() }
        }
        if (name.toString() == SELF_NAME) {
            return symlink(superBlock, SELF_INODE) {
                ProcessManager.currentProcess()
                    ?.takeUnless(Process::isKernelProcess)
                    ?.id
                    ?.toString()
            }
        } else if (name.toString() == MOUNTS_NAME) {
            return symlink(superBlock, MOUNTS_INODE) { "self/mounts" }
        } else if (name.toString() == COROUTINES_NAME) {
            return directory(
                superBlock,
                COROUTINES_INODE,
                ProcCoroutineDirectory(this),
            )
        }
        return name.pid()?.let { processDirectory(superBlock, it) }
    }

    fun rootEntries(): List<DirectoryEntry> =
        buildList {
            RootFile.entries.forEach { file ->
                add(
                    entry(
                        file.fileName,
                        file.inodeId,
                        InodeType.REGULAR
                    )
                )
            }
            add(
                entry(
                    SELF_NAME,
                    SELF_INODE,
                    InodeType.SYMLINK
                )
            )
            add(
                entry(
                    MOUNTS_NAME,
                    MOUNTS_INODE,
                    InodeType.SYMLINK
                )
            )
            add(entry(COROUTINES_NAME, COROUTINES_INODE, InodeType.DIRECTORY))
            ProcessManager.snapshotProcesses().forEach { process ->
                add(
                    entry(
                        process.id.toString(),
                        processInode(process.id),
                        InodeType.DIRECTORY
                    )
                )
            }
        }

    fun processEntry(
        superBlock: SuperBlock,
        pid: Int,
        name: VfsName
    ): Inode? {
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return null
        val file = ProcessFile.from(name) ?: return null
        return text(superBlock, processInode(pid, file.ordinal + 1)) {
            ProcessManager.findProcess(pid)
                ?.takeUnless(Process::isKernelProcess)
                ?.let(file::render)
        }.withOwner(process)
    }

    fun processEntries(process: Process): List<DirectoryEntry> =
        ProcessFile.entries.map { file ->
            entry(
                file.fileName,
                processInode(process.id, file.ordinal + 1),
                InodeType.REGULAR,
            )
        }

    private fun processDirectory(
        superBlock: SuperBlock,
        pid: Int
    ): Inode? {
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return null
        return directory(
            superBlock = superBlock,
            id = processInode(pid),
            backend = ProcProcessDirectory(this, pid),
        ).withOwner(process)
    }

    private fun directory(
        superBlock: SuperBlock,
        id: ULong,
        backend: DirectoryBackend,
    ): Inode = Inode(
        id = InodeId(id),
        superBlock = superBlock,
        backend = backend,
        metadata = InodeMetadata(
            FileMode(
                DIRECTORY_MODE
            ), linkCount = 2u
        ),
    )

    fun text(
        superBlock: SuperBlock,
        id: ULong,
        render: () -> ByteArray?,
    ): Inode = Inode(
        id = InodeId(id),
        superBlock = superBlock,
        backend = ProcTextFile(render),
        metadata = InodeMetadata(
            FileMode(
                FILE_MODE
            )
        ),
    )

    private fun symlink(
        superBlock: SuperBlock,
        id: ULong,
        target: () -> String?,
    ): Inode = Inode(
        id = InodeId(id),
        superBlock = superBlock,
        backend = ProcSymlink(target),
        metadata = InodeMetadata(
            FileMode(
                SYMLINK_MODE
            )
        ),
    )

    private fun Inode.withOwner(process: Process): Inode =
        apply {
            updateMetadata { it.copy(uid = process.euid.toUInt(), gid = process.egid.toUInt()) }
        }

    fun entry(
        name: String,
        id: ULong,
        type: InodeType
    ): DirectoryEntry {
        val bytes = name.encodeToByteArray()
        return DirectoryEntry(
            VfsName.fromPath(
                bytes,
                0,
                bytes.size
            ), InodeId(id), type
        )
    }

    private fun processInode(pid: Int, entry: Int = 0): ULong =
        PROCESS_INODE_BASE + (pid.toULong() shl PROCESS_INODE_SHIFT) + entry.toULong()
}

private class ProcRootDirectory(
    private val fileSystem: ProcfsInstance,
) : DirectoryBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override val cacheNegativeLookups: Boolean = false

    override fun lookup(
        directory: Inode,
        name: VfsName
    ): VfsResult<Inode?> =
        VfsResult.Ok(
            fileSystem.rootEntry(
                directory.superBlock,
                name
            )
        )

    override fun open(
        inode: Inode,
        options: OpenOptions
    ): VfsResult<OpenFileBackend> =
        VfsResult.Ok(
            ProcDirectoryHandle(
                fileSystem.rootEntries()
            )
        )
}

private class ProcProcessDirectory(
    private val fileSystem: ProcfsInstance,
    private val pid: Int,
) : DirectoryBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override val cacheNegativeLookups: Boolean = false

    override fun lookup(
        directory: Inode,
        name: VfsName
    ): VfsResult<Inode?> =
        VfsResult.Ok(
            fileSystem.processEntry(
                directory.superBlock,
                pid,
                name
            )
        )

    override fun open(
        inode: Inode,
        options: OpenOptions
    ): VfsResult<OpenFileBackend> {
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return VfsResult.Ok(
            ProcDirectoryHandle(
                fileSystem.processEntries(
                    process
                )
            )
        )
    }
}

class ProcDirectoryHandle(
    private val entries: List<DirectoryEntry>,
) : OpenFileBackend {
    override fun iterate(
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> {
        if (position.value !in 0..Int.MAX_VALUE.toLong()) {
            return VfsResult.Ok(Unit)
        }
        var index = position.value.toInt()
        while (index < entries.size) {
            val next = index.toLong() + 1
            if (!emit(entries[index], next)) break
            position.value = next
            index++
        }
        return VfsResult.Ok(Unit)
    }
}

private class ProcTextFile(
    private val render: () -> ByteArray?,
) : RegularFileBackend() {

    override fun open(
        inode: Inode,
        options: OpenOptions
    ): VfsResult<OpenFileBackend> =
        render()?.let { VfsResult.Ok(ProcTextHandle(it)) }
            ?: VfsResult.Err(VfsError.NOT_FOUND)
}

private class ProcTextHandle(
    private val content: ByteArray,
) : OpenFileBackend {
    override fun read(
        inode: Inode,
        destination: PreparedBufferDestination,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        if (position.value < 0 || position.value >= content.size || count == 0) {
            return IoResult.success(0)
        }
        val start = position.value.toInt()
        val requested = minOf(count, content.size - start)
        val copied = destination.copyFrom(destinationOffset, content, start, requested)
        if (copied == 0) return IoResult.failure(
            VfsError.FAULT
        )
        position.value += copied
        return IoResult.success(copied)
    }
}

private class ProcSymlink(
    private val target: () -> String?,
) : SymlinkBackend {
    override val type: InodeType = InodeType.SYMLINK

    override fun readLink(inode: Inode): VfsResult<VfsPathname> =
        target()?.let {
            VfsResult.Ok(
                VfsPathname.fromString(
                    it
                )
            )
        }
            ?: VfsResult.Err(VfsError.NOT_FOUND)

    override fun open(
        inode: Inode,
        options: OpenOptions
    ): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
}

private enum class RootFile(
    val fileName: String,
    val inodeId: ULong,
) : ProcFSRender {
    LOAD_AVERAGE("loadavg", 2uL),
    MEMORY_INFO("meminfo", 3uL),
    STATISTICS("stat", 4uL),
    UPTIME("uptime", 5uL),
    VERSION("version", 6uL),
    INTERRUPTS("interrupts", 7UL),
    FILESYSTEMS("filesystems", 8UL),
    ;

    override fun render(): ByteArray = when (this) {
        LOAD_AVERAGE -> {
            val processes = ProcessManager.snapshotProcesses()
            val runnable = processes.count(Process::isRunnable)
            val lastPid = processes.maxOfOrNull(Process::id) ?: 0
            "0.00 0.00 0.00 $runnable/${processes.size} $lastPid\n".encodeToByteArray()
        }

        MEMORY_INFO -> MemoryInfoFile.render()
        STATISTICS -> "cpu  0 0 0 0 0 0 0 0 0 0\n".encodeToByteArray()
        UPTIME -> {
            val centiseconds = TscClock.nanoTime() / 10_000_000uL
            val seconds = centiseconds / 100uL
            val fraction = (centiseconds % 100uL).toString().padStart(2, '0')
            "$seconds.$fraction $seconds.$fraction\n".encodeToByteArray()
        }
        VERSION -> "CoolPotOS version $KERNEL_NAME\n".encodeToByteArray()
        INTERRUPTS -> InterruptsFile.render()
        FILESYSTEMS -> FilesystemsFile.render()
    }

    companion object {
        fun from(name: VfsName): RootFile? =
            entries.firstOrNull { it.fileName == name.toString() }
    }
}

private fun VfsName.pid(): Int? = toString().toIntOrNull()?.takeIf { it > 0 }

private const val ROOT_INODE = 1uL
private const val SELF_INODE = 10uL
private const val MOUNTS_INODE = 11uL
private const val COROUTINES_INODE = 12uL
private const val PROCESS_INODE_BASE = 0x100uL
private const val PROCESS_INODE_SHIFT = 8
private const val DIRECTORY_MODE = 0x16Du
private const val FILE_MODE = 0x124u
private const val SYMLINK_MODE = 0x1FFu
const val MAX_COMM_LENGTH = 15
private const val SELF_NAME = "self"
private const val MOUNTS_NAME = "mounts"
private const val COROUTINES_NAME = "coroutines"
const val KIBIBYTE = 1024uL
