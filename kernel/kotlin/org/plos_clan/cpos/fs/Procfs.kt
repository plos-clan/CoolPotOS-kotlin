package org.plos_clan.cpos.fs

import KERNEL_NAME
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.drivers.char.TtyManager
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.ProcessResource
import org.plos_clan.cpos.tasks.TaskState
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES

object Procfs : FileSystemType {
    override val name: String = "proc"
    override val magic: ULong = 0x9fa0uL

    override fun createSuperBlock(options: FileSystemOptions): VfsResult<SuperBlock> {
        if (options != EmptyFileSystemOptions) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val instance = ProcfsInstance()
        return VfsResult.Ok(SuperBlock(this, instance, instance::root))
    }
}

private class ProcfsInstance : SuperBlockBackend {
    fun root(superBlock: SuperBlock): Inode = directory(
        superBlock = superBlock,
        id = ROOT_INODE,
        backend = ProcRootDirectory(this),
    )

    fun rootEntry(superBlock: SuperBlock, name: VfsName): Inode? {
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
        }
        return name.pid()?.let { processDirectory(superBlock, it) }
    }

    fun rootEntries(superBlock: SuperBlock): List<DirectoryEntry> = buildList {
        RootFile.entries.forEach { file ->
            add(entry(file.fileName, file.inodeId, InodeType.REGULAR))
        }
        add(entry(SELF_NAME, SELF_INODE, InodeType.SYMLINK))
        ProcessManager.snapshotProcesses().forEach { process ->
            add(entry(process.id.toString(), processInode(process.id), InodeType.DIRECTORY))
        }
    }

    fun processEntry(superBlock: SuperBlock, pid: Int, name: VfsName): Inode? {
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

    private fun processDirectory(superBlock: SuperBlock, pid: Int): Inode? {
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
        metadata = InodeMetadata(FileMode(DIRECTORY_MODE), linkCount = 2u),
    )

    private fun text(
        superBlock: SuperBlock,
        id: ULong,
        render: () -> ByteArray?,
    ): Inode = Inode(
        id = InodeId(id),
        superBlock = superBlock,
        backend = ProcTextFile(render),
        metadata = InodeMetadata(FileMode(FILE_MODE)),
    )

    private fun symlink(
        superBlock: SuperBlock,
        id: ULong,
        target: () -> String?,
    ): Inode = Inode(
        id = InodeId(id),
        superBlock = superBlock,
        backend = ProcSymlink(target),
        metadata = InodeMetadata(FileMode(SYMLINK_MODE)),
    )

    private fun Inode.withOwner(process: Process): Inode = apply {
        updateMetadata { it.copy(uid = process.euid.toUInt(), gid = process.egid.toUInt()) }
    }

    private fun entry(name: String, id: ULong, type: InodeType): DirectoryEntry {
        val bytes = name.encodeToByteArray()
        return DirectoryEntry(VfsName.fromPath(bytes, 0, bytes.size), InodeId(id), type)
    }

    private fun processInode(pid: Int, entry: Int = 0): ULong =
        PROCESS_INODE_BASE + (pid.toULong() shl PROCESS_INODE_SHIFT) + entry.toULong()
}

private class ProcRootDirectory(
    private val fileSystem: ProcfsInstance,
) : DirectoryBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override val cacheNegativeLookups: Boolean = false

    override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
        VfsResult.Ok(fileSystem.rootEntry(directory.superBlock, name))

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(ProcDirectoryHandle(fileSystem.rootEntries(inode.superBlock)))
}

private class ProcProcessDirectory(
    private val fileSystem: ProcfsInstance,
    private val pid: Int,
) : DirectoryBackend {
    override val type: InodeType = InodeType.DIRECTORY
    override val cacheNegativeLookups: Boolean = false

    override fun lookup(directory: Inode, name: VfsName): VfsResult<Inode?> =
        VfsResult.Ok(fileSystem.processEntry(directory.superBlock, pid, name))

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> {
        val process = ProcessManager.findProcess(pid)?.takeUnless(Process::isKernelProcess)
            ?: return VfsResult.Err(VfsError.NOT_FOUND)
        return VfsResult.Ok(ProcDirectoryHandle(fileSystem.processEntries(process)))
    }
}

private class ProcDirectoryHandle(
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
) : InodeBackend {
    override val type: InodeType = InodeType.REGULAR

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        render()?.let { VfsResult.Ok(ProcTextHandle(it)) }
            ?: VfsResult.Err(VfsError.NOT_FOUND)
}

private class ProcTextHandle(
    private val content: ByteArray,
) : OpenFileBackend {
    override fun read(
        inode: Inode,
        destination: ByteArray,
        destinationOffset: Int,
        count: Int,
        position: FilePosition,
    ): IoResult {
        if (position.value < 0 || position.value >= content.size || count == 0) {
            return IoResult.success(0)
        }
        val start = position.value.toInt()
        val copied = minOf(count, content.size - start)
        content.copyInto(destination, destinationOffset, start, start + copied)
        position.value += copied
        return IoResult.success(copied)
    }
}

private class ProcSymlink(
    private val target: () -> String?,
) : SymlinkBackend {
    override val type: InodeType = InodeType.SYMLINK

    override fun readLink(inode: Inode): VfsResult<VfsPathname> =
        target()?.let { VfsResult.Ok(VfsPathname.fromString(it)) }
            ?: VfsResult.Err(VfsError.NOT_FOUND)

    override fun open(inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Err(VfsError.TOO_MANY_SYMLINKS)
}

private enum class RootFile(
    val fileName: String,
    val inodeId: ULong,
) {
    LOAD_AVERAGE("loadavg", 2uL),
    MEMORY_INFO("meminfo", 3uL),
    STATISTICS("stat", 4uL),
    UPTIME("uptime", 5uL),
    VERSION("version", 6uL),
    ;

    fun render(): ByteArray = when (this) {
        LOAD_AVERAGE -> {
            val processes = ProcessManager.snapshotProcesses()
            val runnable = processes.count(Process::isRunnable)
            val lastPid = processes.maxOfOrNull(Process::id) ?: 0
            "0.00 0.00 0.00 $runnable/${processes.size} $lastPid\n".encodeToByteArray()
        }
        MEMORY_INFO -> BuddyFrameAllocator.statistics().let { memory ->
            """
            MemTotal:       ${memory.totalBytes / KIBIBYTE} kB
            MemFree:        ${memory.availableBytes / KIBIBYTE} kB
            MemAvailable:   ${memory.availableBytes / KIBIBYTE} kB
            Buffers:        0 kB
            Cached:         0 kB
            SwapCached:     0 kB
            SwapTotal:      0 kB
            SwapFree:       0 kB
            """.trimIndent().plus("\n").encodeToByteArray()
        }
        STATISTICS -> "cpu  0 0 0 0 0 0 0 0 0 0\n".encodeToByteArray()
        UPTIME -> {
            val centiseconds = Hpet.nanoTime() / 10_000_000uL
            val seconds = centiseconds / 100uL
            val fraction = (centiseconds % 100uL).toString().padStart(2, '0')
            "$seconds.$fraction $seconds.$fraction\n".encodeToByteArray()
        }
        VERSION -> "CoolPotOS version $KERNEL_NAME\n".encodeToByteArray()
    }

    companion object {
        fun from(name: VfsName): RootFile? = entries.firstOrNull { it.fileName == name.toString() }
    }
}

private enum class ProcessFile(val fileName: String) {
    COMMAND_LINE("cmdline"),
    COMMAND_NAME("comm"),
    MEMORY("statm"),
    STATISTICS("stat"),
    STATUS("status"),
    ;

    fun render(process: Process): ByteArray = when (this) {
        COMMAND_LINE -> process.commandLine.copyOf()
        COMMAND_NAME -> "${process.comm}\n".encodeToByteArray()
        MEMORY -> {
            val pages = process.addressSpace.used / PAGE_SIZE_BYTES
            "$pages 0 0 0 0 0 0\n".encodeToByteArray()
        }
        STATISTICS -> process.stat().encodeToByteArray()
        STATUS -> process.status().encodeToByteArray()
    }

    companion object {
        fun from(name: VfsName): ProcessFile? = entries.firstOrNull { it.fileName == name.toString() }
    }
}

private val Process.comm: String
    get() = name.substringAfterLast('/').ifEmpty { name }.take(MAX_COMM_LENGTH)

private val Process.isRunnable: Boolean
    get() = state != TaskState.ZOMBIE && threads.any {
        it.state == TaskState.READY || it.state == TaskState.RUNNING
    }

private fun Process.stat(): String {
    val terminal = TtyManager.processTerminal(this)
    val fields = buildList {
        add(stateCode().toString())
        add(parentId.toString())
        add(processGroupId.toString())
        add(sessionId.toString())
        add((terminal?.deviceNumber ?: 0uL).toString())
        add((terminal?.foregroundProcessGroup ?: -1).toString())
        repeat(9) { add("0") } // flags, faults and CPU times
        add("20") // priority
        add("0") // nice
        add(threads.count { it.state != TaskState.ZOMBIE }.toString())
        add("0") // itrealvalue
        add(startTimeTicks.toString())
        add(addressSpace.used.toString())
        add("0") // resident pages are not accounted yet
        add(resourceLimits.get(ProcessResource.RSS).soft.toString())
        repeat(27) { add("0") }
    }
    return "$id ($comm) ${fields.joinToString(" ")}\n"
}

private fun Process.status(): String {
    val (stateCode, stateName) = stateDescription()
    return buildString {
        append("Name:\t").append(comm).append('\n')
        append("State:\t").append(stateCode).append(" (").append(stateName).append(")\n")
        append("Tgid:\t").append(id).append('\n')
        append("Pid:\t").append(id).append('\n')
        append("PPid:\t").append(parentId).append('\n')
        append("Uid:\t").append(ruid).append('\t').append(euid).append('\t')
            .append(suid).append('\t').append(fsuid).append('\n')
        append("Gid:\t").append(rgid).append('\t').append(egid).append('\t')
            .append(sgid).append('\t').append(fsgid).append('\n')
        append("FDSize:\t").append(resourceLimits.get(ProcessResource.OPEN_FILES).soft)
            .append('\n')
        append("Groups:\t").append(egid).append('\n')
        append("VmSize:\t").append(addressSpace.used / KIBIBYTE).append(" kB\n")
        append("VmRSS:\t0 kB\n")
        append("Threads:\t").append(threads.count { it.state != TaskState.ZOMBIE }).append('\n')
        append("SigPnd:\t0000000000000000\n")
        append("SigBlk:\t").append(signalMask.toString(16).padStart(16, '0')).append('\n')
        append("SigIgn:\t0000000000000000\n")
        append("SigCgt:\t0000000000000000\n")
    }
}

private fun Process.stateCode(): Char = stateDescription().first

private fun Process.stateDescription(): Pair<Char, String> = when {
    state == TaskState.ZOMBIE -> 'Z' to "zombie"
    threads.any { it.state == TaskState.RUNNING } -> 'R' to "running"
    threads.any { it.state == TaskState.READY } -> 'R' to "runnable"
    else -> 'S' to "sleeping"
}

private fun VfsName.pid(): Int? = toString().toIntOrNull()?.takeIf { it > 0 }

private const val ROOT_INODE = 1uL
private const val SELF_INODE = 7uL
private const val PROCESS_INODE_BASE = 0x100uL
private const val PROCESS_INODE_SHIFT = 8
private const val DIRECTORY_MODE = 0x16Du
private const val FILE_MODE = 0x124u
private const val SYMLINK_MODE = 0x1FFu
private const val MAX_COMM_LENGTH = 15
private const val SELF_NAME = "self"
private const val KIBIBYTE = 1024uL
