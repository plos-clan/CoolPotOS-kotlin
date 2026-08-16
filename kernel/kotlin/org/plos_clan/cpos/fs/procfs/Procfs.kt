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
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

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
        RootNode.from(name)?.let { node ->
            return node.create(this, superBlock)
        }
        return name.decimalInt()?.takeIf { it > 0 }?.let { processDirectory(superBlock, it) }
    }

    fun rootEntries(): List<DirectoryEntry> =
        buildList {
            RootFile.entries.forEach { file ->
                add(entry(file.fileName, file.inodeId, InodeType.REGULAR))
            }
            RootNode.entries.forEach { node ->
                add(entry(node.fileName, node.inodeId, node.type))
            }
            ProcessManager.snapshotProcesses().forEach { process ->
                add(entry(process.id.toString(), ProcInode.process(process.id), InodeType.DIRECTORY))
            }
        }

    fun processEntry(
        superBlock: SuperBlock,
        pid: Int,
        name: VfsName
    ): Inode? {
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return null
        ProcessFile.from(name)?.let { file ->
            return text(
                superBlock = superBlock,
                id = ProcInode.process(pid, file.ordinal.toUInt() + 1u),
                owner = process,
            ) {
                ProcessManager.findProcess(pid)
                    ?.takeUnless(Process::isKernelProcess)
                    ?.let(file::render)
            }
        }
        return if (name.toString() == FD_NAME) {
            directory(
                superBlock = superBlock,
                id = ProcInode.process(pid, FD_DIRECTORY_ENTRY),
                backend = ProcDescriptorDirectory(this, pid),
                mode = DESCRIPTOR_DIRECTORY_MODE,
                owner = process,
            )
        } else {
            null
        }
    }

    fun processEntries(process: Process): List<DirectoryEntry> =
        buildList(ProcessFile.entries.size + 1) {
            ProcessFile.entries.forEach { file ->
                add(
                    entry(
                        file.fileName,
                        ProcInode.process(process.id, file.ordinal.toUInt() + 1u),
                        InodeType.REGULAR,
                    ),
                )
            }
            add(
                entry(
                    FD_NAME,
                    ProcInode.process(process.id, FD_DIRECTORY_ENTRY),
                    InodeType.DIRECTORY,
                ),
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
            id = ProcInode.process(pid),
            backend = ProcProcessDirectory(this, pid),
            owner = process,
        )
    }

    internal fun directory(
        superBlock: SuperBlock,
        id: ULong,
        backend: DirectoryBackend,
        mode: UInt = DIRECTORY_MODE,
        owner: Process? = null,
    ): Inode = Inode(
        id = InodeId(id),
        superBlock = superBlock,
        backend = backend,
        metadata = InodeMetadata(
            mode = FileMode(mode),
            linkCount = 2u,
            uid = owner?.euid?.toUInt() ?: 0u,
            gid = owner?.egid?.toUInt() ?: 0u,
        ),
    )

    internal fun text(
        superBlock: SuperBlock,
        id: ULong,
        mode: UInt = FILE_MODE,
        owner: Process? = null,
        write: ((ByteArray) -> VfsResult<Unit>)? = null,
        render: () -> ByteArray?,
    ): Inode = Inode(
        id = InodeId(id),
        superBlock = superBlock,
        backend = ProcTextFile(render, write),
        metadata = InodeMetadata(
            mode = FileMode(mode),
            uid = owner?.euid?.toUInt() ?: 0u,
            gid = owner?.egid?.toUInt() ?: 0u,
        ),
    )

    internal fun symlink(
        superBlock: SuperBlock,
        id: ULong,
        mode: UInt = SYMLINK_MODE,
        owner: Process? = null,
        target: () -> String?,
    ): Inode = Inode(
        id = InodeId(id),
        superBlock = superBlock,
        backend = ProcSymlink(target),
        metadata = InodeMetadata(
            mode = FileMode(mode),
            uid = owner?.euid?.toUInt() ?: 0u,
            gid = owner?.egid?.toUInt() ?: 0u,
        ),
    )

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

}

private class ProcRootDirectory(
    private val fileSystem: ProcfsInstance,
) : ProcDirectoryBackend() {
    override fun resolve(superBlock: SuperBlock, name: VfsName): Inode? =
        fileSystem.rootEntry(superBlock, name)

    override fun snapshot(): VfsResult<List<DirectoryEntry>> =
        VfsResult.Ok(fileSystem.rootEntries())
}

private class ProcProcessDirectory(
    private val fileSystem: ProcfsInstance,
    private val pid: Int,
) : ProcDirectoryBackend() {
    override fun resolve(superBlock: SuperBlock, name: VfsName): Inode? =
        fileSystem.processEntry(superBlock, pid, name)

    override fun snapshot(): VfsResult<List<DirectoryEntry>> {
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return VfsResult.Ok(fileSystem.processEntries(process))
    }
}

internal abstract class ProcDirectoryBackend : DirectoryBackend {
    final override val type: InodeType = InodeType.DIRECTORY
    override val cachePositiveLookups: Boolean = false
    final override val cacheNegativeLookups: Boolean = false

    protected abstract fun resolve(superBlock: SuperBlock, name: VfsName): Inode?

    protected abstract fun snapshot(): VfsResult<List<DirectoryEntry>>

    final override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
        VfsResult.Ok(resolve(directory.superBlock, name))

    final override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(ProcDirectoryHandle(::snapshot))
}

internal interface ProcStaticEntry {
    val fileName: String
    val inodeId: ULong
    val type: InodeType

    fun create(fileSystem: ProcfsInstance, superBlock: SuperBlock): Inode
}

internal class ProcStaticDirectory(
    private val fileSystem: ProcfsInstance,
    private val entries: List<ProcStaticEntry>,
) : ProcDirectoryBackend() {
    override val cachePositiveLookups: Boolean = true

    override fun resolve(superBlock: SuperBlock, name: VfsName): Inode? =
        entries.firstOrNull { it.fileName == name.toString() }
            ?.create(fileSystem, superBlock)

    override fun snapshot(): VfsResult<List<DirectoryEntry>> = VfsResult.Ok(
        entries.map { fileSystem.entry(it.fileName, it.inodeId, it.type) },
    )
}

private class ProcDirectoryHandle(
    private val snapshot: () -> VfsResult<List<DirectoryEntry>>,
) : OpenFileBackend {
    private var entries: List<DirectoryEntry>? = null

    override fun iterate(
        inode: Inode,
        position: FilePosition,
        emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
    ): VfsResult<Unit> {
        if (position.value !in 0..Int.MAX_VALUE.toLong()) {
            return VfsResult.Ok(Unit)
        }
        val entries = this.entries ?: when (val result = snapshot()) {
            is VfsResult.Ok -> result.value.also { this.entries = it }
            is VfsResult.Err -> return result
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
    private val write: ((ByteArray) -> VfsResult<Unit>)?,
) : RegularFileBackend() {

    override fun resize(inode: Inode, size: ULong): VfsResult<Unit> =
        if (write != null && size == 0uL) VfsResult.Ok(Unit)
        else VfsResult.Err(VfsError.NOT_SUPPORTED)

    override fun open(
        inode: Inode,
        options: OpenOptions
    ): VfsResult<OpenFileBackend> {
        if (options.access.canWrite &&
            (write == null || ProcessManager.currentProcess()?.euid != 0)
        ) {
            return VfsResult.Err(VfsError.PERMISSION_DENIED)
        }
        return render()?.let { VfsResult.Ok(ProcTextHandle(it, write)) }
            ?: VfsResult.Err(VfsError.NOT_FOUND)
    }
}

private class ProcTextHandle(
    private val content: ByteArray,
    private val write: ((ByteArray) -> VfsResult<Unit>)?,
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

    override fun write(
        inode: Inode,
        source: PreparedBufferSource,
        sourceOffset: Int,
        count: Int,
        position: FilePosition,
        append: Boolean,
    ): IoResult {
        val update = write ?: return IoResult.failure(VfsError.PERMISSION_DENIED)
        if (count == 0) return IoResult.success(0)
        if (position.value != 0L || count >= PAGE_SIZE_BYTES.toInt()) {
            return IoResult.failure(VfsError.INVALID_ARGUMENT)
        }
        val input = ByteArray(count)
        if (source.copyTo(sourceOffset, input, 0, count) != count) {
            return IoResult.failure(VfsError.FAULT)
        }
        return when (val result = update(input)) {
            is VfsResult.Ok -> {
                position.value = count.toLong()
                IoResult.success(count)
            }
            is VfsResult.Err -> IoResult.failure(result.error)
        }
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

private enum class RootNode(
    override val fileName: String,
    override val inodeId: ULong,
    override val type: InodeType,
) : ProcStaticEntry {
    SELF("self", SELF_INODE, InodeType.SYMLINK),
    MOUNTS("mounts", MOUNTS_INODE, InodeType.SYMLINK),
    COROUTINES("coroutines", COROUTINES_INODE, InodeType.DIRECTORY),
    SYS("sys", SYS_INODE, InodeType.DIRECTORY),
    ;

    override fun create(fileSystem: ProcfsInstance, superBlock: SuperBlock): Inode = when (this) {
        SELF -> fileSystem.symlink(superBlock, inodeId) {
            ProcessManager.currentProcess()
                ?.takeUnless(Process::isKernelProcess)
                ?.id
                ?.toString()
        }
        MOUNTS -> fileSystem.symlink(superBlock, inodeId) { "self/mounts" }
        COROUTINES -> fileSystem.directory(
            superBlock,
            inodeId,
            ProcCoroutineDirectory(fileSystem),
        )
        SYS -> ProcSysTree.create(fileSystem, superBlock, inodeId)
    }

    companion object {
        fun from(name: VfsName): RootNode? =
            entries.firstOrNull { it.fileName == name.toString() }
    }
}

internal object ProcInode {
    private const val COROUTINE_PREFIX = 0x8000_0000_0000_0000uL
    private const val ID_SHIFT = UInt.SIZE_BITS
    private const val DESCRIPTOR_FLAG = 0x8000_0000u

    fun process(pid: Int, entry: UInt = 0u): ULong {
        require(pid > 0)
        return (pid.toULong() shl ID_SHIFT) or entry.toULong()
    }

    fun descriptor(pid: Int, fd: Int): ULong {
        require(fd >= 0)
        return process(pid, DESCRIPTOR_FLAG or fd.toUInt())
    }

    fun coroutine(id: Int): ULong {
        require(id >= 0)
        return COROUTINE_PREFIX or id.toUInt().toULong()
    }
}

internal fun VfsName.decimalInt(): Int? {
    val value = toString()
    if (value.isEmpty() || value.length > 1 && value[0] == '0' ||
        value.any { it !in '0'..'9' }
    ) {
        return null
    }
    return value.toIntOrNull()
}

private const val ROOT_INODE = 1uL
private const val SELF_INODE = 10uL
private const val MOUNTS_INODE = 11uL
private const val COROUTINES_INODE = 12uL
private const val SYS_INODE = 13uL
private const val FD_DIRECTORY_ENTRY = 0x100u
private const val DIRECTORY_MODE = 0x16du
private const val DESCRIPTOR_DIRECTORY_MODE = 0x140u
private const val FILE_MODE = 0x124u
private const val SYMLINK_MODE = 0x1ffu
const val MAX_COMM_LENGTH = 15
private const val FD_NAME = "fd"
const val KIBIBYTE = 1024uL
