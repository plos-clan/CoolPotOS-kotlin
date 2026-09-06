package org.plos_clan.cpos.fs.cgroupfs

import org.plos_clan.cpos.fs.vfs.*
import org.plos_clan.cpos.mem.PreparedBufferDestination
import org.plos_clan.cpos.mem.PreparedBufferSource
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.tasks.cgroup.CgroupHierarchy
import org.plos_clan.cpos.tasks.cgroup.CgroupHierarchy.Controller
import org.plos_clan.cpos.tasks.cgroup.Cgroups
import org.plos_clan.cpos.tasks.cgroup.CgroupPlacement
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PollEvents

/** All mounts share the unified hierarchy and inode identities, including after unmount. */
object Cgroupfs : FileSystemType("cgroup2", 0x63677270uL) {
    private class Options(val names: List<String>) : FileSystemOptions
    private var shared: SuperBlock? = null

    internal fun placement(caller: VfsOperationContext, file: OpenFileDescription, thread: Boolean): VfsResult<CgroupPlacement> {
        val inode = file.inode
        val directory = inode.backend as? CgroupDirectory ?: return VfsResult.Err(VfsError.BAD_DESCRIPTOR)
        if (MountFlag.READ_ONLY in file.path.mount.flags) return VfsResult.Err(VfsError.READ_ONLY)
        return VfsResult.Ok(CgroupPlacement { id, pid, parent ->
            val access = directory.migrationAccess(caller, inode, listOf(parent?.group ?: Cgroups.hierarchy.root), checkTarget = true)
            if (access is VfsResult.Err) access
            else Cgroups.hierarchy.fork(id, pid, parent, directory.group, thread)
        })
    }

    override fun configure(source: String?, data: ByteArray?): VfsResult<FileSystemOptions> {
        val names = data?.decodeToString()?.split(',')?.filter(String::isNotEmpty).orEmpty()
        if (names.any { it != "nsdelegate" && it != "memory_recursiveprot" && it != "favordynmods" }) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        return VfsResult.Ok(Options(names.distinct()))
    }

    override fun createBackend(options: FileSystemOptions): VfsResult<SuperBlockBackend> =
        when (options) {
            EmptyFileSystemOptions -> VfsResult.Ok(CgroupfsInstance(emptyList()))
            is Options -> VfsResult.Ok(CgroupfsInstance(options.names))
            else -> VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }

    internal override fun createSuperBlock(source: String?, options: FileSystemOptions): VfsResult<SuperBlock> =
        Cgroups.lock.withLock {
            if (options !is Options && options !== EmptyFileSystemOptions) {
                return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val existing = shared
            if (existing != null) {
                check(existing.retain())
                return@withLock VfsResult.Ok(existing)
            }
            when (val result = super.createSuperBlock(source, options)) {
                is VfsResult.Ok -> {
                    shared = result.value
                    check(result.value.retain()) // The unified hierarchy outlives its mounts.
                    result
                }
                is VfsResult.Err -> result
            }
        }

    internal override fun createSuperBlock(request: MountRequest): VfsResult<SuperBlock> =
        when (val options = configure(request.source, request.data)) {
            is VfsResult.Ok -> createSuperBlock(request.source, options.value)
            is VfsResult.Err -> options
        }
}

private enum class ControlFile(val fileName: String, val mode: UInt = 0x124u, val controller: Controller? = null) {
    TYPE("cgroup.type", 0x1a4u),
    PROCS("cgroup.procs", 0x1a4u),
    THREADS("cgroup.threads", 0x1a4u),
    CONTROLLERS("cgroup.controllers"),
    SUBTREE_CONTROL("cgroup.subtree_control", 0x1a4u),
    EVENTS("cgroup.events"),
    MAX_DEPTH("cgroup.max.depth", 0x1a4u),
    MAX_DESCENDANTS("cgroup.max.descendants", 0x1a4u),
    STAT("cgroup.stat"),
    FREEZE("cgroup.freeze", 0x1a4u),
    KILL("cgroup.kill", 0x80u),
    PIDS_MAX("pids.max", 0x1a4u, Controller.PIDS),
    PIDS_CURRENT("pids.current", controller = Controller.PIDS),
    PIDS_PEAK("pids.peak", controller = Controller.PIDS),
    PIDS_EVENTS("pids.events", controller = Controller.PIDS),
    PIDS_EVENTS_LOCAL("pids.events.local", controller = Controller.PIDS),
    ;

    val vfsName = fileName.encodeToByteArray().let { VfsName.fromPath(it, 0, it.size) }
    val writable: Boolean get() = mode and 0x80u != 0u

    fun visible(group: CgroupHierarchy.Group): Boolean = when {
        controller != null -> group.parent != null && controller in group.controllers
        this == TYPE || this == EVENTS || this == FREEZE || this == KILL -> group.parent != null
        else -> true
    }

    fun version(group: CgroupHierarchy.Group): Int = when (this) {
        EVENTS -> group.eventsVersion
        PIDS_EVENTS -> group.pidsVersion
        PIDS_EVENTS_LOCAL -> group.pidsLocalVersion
        else -> 0
    }

    companion object {
        val byName = entries.associateBy { it.vfsName }
    }
}

private class CgroupfsInstance(override val mountOptions: List<String>) : SuperBlockBackend {
    private var nextId = 1uL
    private val directories = mutableMapOf<CgroupHierarchy.Group, Inode>()

    override fun createRoot(superBlock: SuperBlock): Inode {
        Cgroups.observer = { group, event ->
            val file = when (event) {
                CgroupHierarchy.Event.POPULATED -> ControlFile.EVENTS
                CgroupHierarchy.Event.PIDS_MAX -> ControlFile.PIDS_EVENTS
                CgroupHierarchy.Event.PIDS_MAX_LOCAL -> ControlFile.PIDS_EVENTS_LOCAL
            }
            val directory = directories[group]?.backend as? CgroupDirectory
            directory?.files?.get(file)?.notify(FileSystemEvent.MODIFIED)
        }
        return directory(superBlock, Cgroups.hierarchy.root)
    }

    fun directory(
        superBlock: SuperBlock,
        group: CgroupHierarchy.Group,
        metadata: InodeMetadata? = null,
    ): Inode = directories.getOrPut(group) {
        val backend = CgroupDirectory(this, group)
        val directory = inode(superBlock, backend, metadata ?: InodeMetadata(FileMode(0x1edu), linkCount = 2u))
        for (file in ControlFile.entries) {
            if (file.visible(group)) backend.control(directory, file)
        }
        directory
    }

    fun inode(superBlock: SuperBlock, backend: InodeBackend, metadata: InodeMetadata): Inode = Inode(
        InodeId(nextId++), superBlock, backend,
        InodeAttributeSnapshot(InodeAttributes(metadata), CacheValidity.Persistent),
    )

    fun remove(group: CgroupHierarchy.Group) {
        val inode = directories.remove(group) ?: return
        (inode.backend as CgroupDirectory).files.values.forEach { file ->
            file.updateMetadata { it.copy(linkCount = 0u) }
        }
        inode.updateMetadata { it.copy(linkCount = 0u) }
    }

    fun refreshControllers(group: CgroupHierarchy.Group, changed: Set<Controller>) {
        for (child in group.children.values) {
            val inode = directories[child] ?: continue
            val directory = inode.backend as CgroupDirectory
            val iterator = directory.files.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.controller !in changed) continue
                entry.value.updateMetadata { it.copy(linkCount = 0u) }
                iterator.remove()
            }
            for (file in ControlFile.entries) {
                if (file.controller in changed && file.visible(child)) directory.control(inode, file)
            }
        }
    }
}

private class CgroupDirectory(
    val fileSystem: CgroupfsInstance,
    val group: CgroupHierarchy.Group,
) : DirectoryBackend, MutableInodeBackend {
    override val type = InodeType.DIRECTORY
    val files = mutableMapOf<ControlFile, Inode>()

    fun control(directory: Inode, file: ControlFile): Inode = files.getOrPut(file) {
        val owner = directory.metadata()
        fileSystem.inode(
            directory.superBlock, CgroupControl(this, file),
            InodeMetadata(FileMode(file.mode), uid = owner.uid, gid = owner.gid),
        )
    }

    fun migrationAccess(
        caller: VfsOperationContext, inode: Inode, sources: List<CgroupHierarchy.Group>, checkTarget: Boolean = false,
    ): VfsResult<Unit> {
        if (!group.live) return VfsResult.Err(VfsError.NO_DEVICE)
        val ancestors = sources.map { it.commonAncestor(group) }.toMutableSet()
        if (checkTarget) ancestors += group
        for (ancestor in ancestors) {
            val directory = fileSystem.directory(inode.superBlock, ancestor)
            val procs = (directory.backend as CgroupDirectory).control(directory, ControlFile.PROCS)
            val access = procs.backend.checkAccess(caller, procs, AccessPermissions.WRITE)
            if (access is VfsResult.Err) return access
        }
        return VfsResult.Ok(Unit)
    }

    override fun lookup(caller: VfsOperationContext, directory: Inode, name: VfsName): VfsResult<DirectoryLookup> =
        Cgroups.lock.withLock {
            if (!group.live) return@withLock VfsResult.Err(VfsError.NO_DEVICE)
            val file = ControlFile.byName[name]?.takeIf { it.visible(group) }
            val inode = if (file != null) control(directory, file)
                else group.children[name]?.let { fileSystem.directory(directory.superBlock, it) }
            VfsResult.Ok(DirectoryLookup(inode, CacheValidity.Volatile))
        }

    override fun create(
        caller: VfsOperationContext, directory: Inode, name: VfsName, node: NodeCreation,
    ): VfsResult<Inode> = Cgroups.lock.withLock {
        if (node.kind != NodeKind.Directory) return@withLock VfsResult.Err(VfsError.NOT_PERMITTED)
        if (name in ControlFile.byName) return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
        when (val result = Cgroups.hierarchy.create(group, name)) {
            is VfsResult.Err -> result
            is VfsResult.Ok -> {
                val inode = fileSystem.directory(
                    directory.superBlock, result.value,
                    InodeMetadata(node.mode, linkCount = 2u, uid = node.uid, gid = node.gid),
                )
                directory.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED) { it.copy(linkCount = it.linkCount + 1u) }
                VfsResult.Ok(inode)
            }
        }
    }

    override fun remove(
        caller: VfsOperationContext, directory: Inode, name: VfsName, target: Inode, mode: RemoveMode,
    ): VfsResult<Unit> = Cgroups.lock.withLock {
        val child = target.backend as? CgroupDirectory
            ?: return@withLock VfsResult.Err(VfsError.NOT_PERMITTED)
        if (group.children[name] !== child.group) return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        when (val result = Cgroups.hierarchy.remove(child.group)) {
            is VfsResult.Err -> result
            is VfsResult.Ok -> {
                fileSystem.remove(child.group)
                directory.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED) { it.copy(linkCount = it.linkCount - 1u) }
                result
            }
        }
    }

    override fun rename(
        caller: VfsOperationContext, sourceDirectory: Inode, sourceName: VfsName, source: Inode,
        targetDirectory: Inode, targetName: VfsName, target: Inode?, mode: RenameMode,
    ): VfsResult<Unit> = Cgroups.lock.withLock {
        if (sourceDirectory !== targetDirectory) return@withLock VfsResult.Err(VfsError.CROSS_DEVICE)
        val child = source.backend as? CgroupDirectory
            ?: return@withLock VfsResult.Err(VfsError.NOT_PERMITTED)
        if (group.children[sourceName] !== child.group) return@withLock VfsResult.Err(VfsError.NOT_FOUND)
        if (mode == RenameMode.EXCHANGE) return@withLock VfsResult.Err(VfsError.INVALID_ARGUMENT)
        if (sourceName == targetName) return@withLock VfsResult.Ok(Unit)
        if (target != null || targetName in ControlFile.byName) return@withLock VfsResult.Err(VfsError.ALREADY_EXISTS)
        group.children.remove(sourceName)
        group.children[targetName] = child.group
        child.group.name = targetName
        sourceDirectory.updateMetadata(InodeTimestampEvent.CONTENT_CHANGED)
        VfsResult.Ok(Unit)
    }

    override fun open(caller: VfsOperationContext, inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        VfsResult.Ok(object : OpenFileBackend {
            private var entries: List<DirectoryEntry>? = null

            override fun iterate(
                caller: VfsOperationContext, inode: Inode, position: FilePosition,
                emit: (entry: DirectoryEntry, nextOffset: Long) -> Boolean,
            ): VfsResult<Unit> {
                if (position.value !in 0..Int.MAX_VALUE.toLong()) return VfsResult.Ok(Unit)
                val snapshot = if (entries == null || position.value == 0L) Cgroups.lock.withLock {
                    if (!group.live) return@withLock emptyList()
                    buildList {
                        for (file in ControlFile.entries) {
                            if (file.visible(group)) add(DirectoryEntry(file.vfsName, control(inode, file).id, InodeType.REGULAR))
                        }
                        for ((name, child) in group.children) {
                            add(DirectoryEntry(name, fileSystem.directory(inode.superBlock, child).id, InodeType.DIRECTORY))
                        }
                    }.also { entries = it }
                } else checkNotNull(entries)
                var index = position.value.toInt()
                while (index < snapshot.size) {
                    val next = index.toLong() + 1
                    if (!emit(snapshot[index], next)) break
                    position.value = next
                    index++
                }
                return VfsResult.Ok(Unit)
            }
        })
}

private class CgroupControl(val directory: CgroupDirectory, val file: ControlFile) :
    RegularFileBackend(), MutableInodeBackend {
    val group: CgroupHierarchy.Group get() = directory.group

    override fun resize(caller: VfsOperationContext, inode: Inode, size: ULong): VfsResult<Unit> =
        if (file.writable && size == 0uL) VfsResult.Ok(Unit) else VfsResult.Err(VfsError.INVALID_ARGUMENT)

    override fun open(caller: VfsOperationContext, inode: Inode, options: OpenOptions): VfsResult<OpenFileBackend> =
        if (options.access.canWrite && !file.writable || options.access.canRead && file == ControlFile.KILL) {
            VfsResult.Err(VfsError.PERMISSION_DENIED)
        } else VfsResult.Ok(CgroupHandle(this, caller))

    fun show(): VfsResult<ByteArray> {
        val text = when (file) {
            ControlFile.TYPE -> group.type.text + "\n"
            ControlFile.PROCS -> {
                if (group.threaded) return VfsResult.Err(VfsError.NOT_SUPPORTED)
                val tasks = if (group.threadRoot) group.subtree(threadedOnly = true).flatMap { it.tasks.asSequence() }
                    else group.tasks.asSequence()
                tasks.map { it.processId }.distinct().joinToString("") { "$it\n" }
            }
            ControlFile.THREADS -> group.tasks.joinToString("") { "${it.id}\n" }
            ControlFile.CONTROLLERS -> group.controllers.joinToString(" ", postfix = "\n") { it.fileName }
            ControlFile.SUBTREE_CONTROL -> group.subtreeControl.joinToString(" ", postfix = "\n") { it.fileName }
            ControlFile.EVENTS -> "populated ${if (group.taskCount != 0L) 1 else 0}\nfrozen ${if (group.frozen) 1 else 0}\n"
            ControlFile.STAT -> "nr_descendants ${group.descendants}\nnr_dying_descendants 0\n"
            ControlFile.FREEZE -> if (group.freeze) "1\n" else "0\n"
            ControlFile.PIDS_CURRENT -> "${group.taskCount}\n"
            ControlFile.PIDS_PEAK -> "${group.pidsPeak}\n"
            ControlFile.PIDS_EVENTS -> "max ${group.pidsEvents}\n"
            ControlFile.PIDS_EVENTS_LOCAL -> "max ${group.pidsEventsLocal}\n"
            ControlFile.KILL -> return VfsResult.Err(VfsError.PERMISSION_DENIED)
            else -> {
                val limit = when (file) {
                    ControlFile.MAX_DEPTH -> group.maxDepth
                    ControlFile.MAX_DESCENDANTS -> group.maxDescendants
                    else -> group.pidsMax
                }
                if (limit == Long.MAX_VALUE) "max\n" else "$limit\n"
            }
        }
        return VfsResult.Ok(text.encodeToByteArray())
    }

    fun store(caller: VfsOperationContext, credentials: VfsOperationContext, inode: Inode, text: String): VfsResult<Unit> {
        when (file) {
            ControlFile.PROCS, ControlFile.THREADS -> {
                val id = text.toIntOrNull()?.takeIf { it >= 0 }
                    ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                val thread = file == ControlFile.THREADS
                val tid = if (id != 0) id else if (thread) ProcessManager.currentThread()?.id
                    else caller.processId.toInt()
                val task = tid?.let(Cgroups.hierarchy::task)
                val selected = if (thread) listOfNotNull(task)
                    else Cgroups.hierarchy.process(task?.processId ?: tid ?: 0).toList()
                if (selected.isEmpty()) return VfsResult.Err(VfsError.NO_SUCH_PROCESS)
                val access = directory.migrationAccess(credentials, inode, selected.map { it.group })
                if (access is VfsResult.Err) return access
                val result = Cgroups.hierarchy.move(group, selected, thread)
                if (result is VfsResult.Ok) Cgroups.wake(selected)
                return result
            }
            ControlFile.TYPE -> return if (text == "threaded") Cgroups.hierarchy.enableThreaded(group)
                else VfsResult.Err(VfsError.INVALID_ARGUMENT)
            ControlFile.SUBTREE_CONTROL -> {
                val previous = group.subtreeControl
                val result = Cgroups.hierarchy.subtreeControl(group, text)
                if (result is VfsResult.Ok && previous != group.subtreeControl) {
                    val changed = (previous - group.subtreeControl) + (group.subtreeControl - previous)
                    directory.fileSystem.refreshControllers(group, changed)
                }
                return result
            }
            ControlFile.MAX_DEPTH, ControlFile.MAX_DESCENDANTS, ControlFile.PIDS_MAX -> {
                val limit = if (text == "max") Long.MAX_VALUE else text.toLongOrNull()?.takeIf { it >= 0 }
                    ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                when (file) {
                    ControlFile.MAX_DEPTH -> group.maxDepth = limit
                    ControlFile.MAX_DESCENDANTS -> group.maxDescendants = limit
                    else -> group.pidsMax = limit
                }
            }
            ControlFile.FREEZE -> {
                if (text != "0" && text != "1") return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                Cgroups.wake(Cgroups.hierarchy.freeze(group, text == "1"))
            }
            ControlFile.KILL -> {
                if (text != "1") return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                if (group.threaded) return VfsResult.Err(VfsError.NOT_SUPPORTED)
                Cgroups.kill(group)
            }
            else -> return VfsResult.Err(VfsError.PERMISSION_DENIED)
        }
        return VfsResult.Ok(Unit)
    }
}

private class CgroupHandle(private val control: CgroupControl, private val opener: VfsOperationContext) : OpenFileBackend {
    override val supportsEpoll: Boolean get() = true
    private var content: ByteArray? = null
    private var observed = readinessVersion
    override val readinessVersion: Int get() = Cgroups.lock.withLock { control.file.version(control.group) }

    override fun read(
        caller: VfsOperationContext, inode: Inode, destination: PreparedBufferDestination,
        destinationOffset: Int, count: Int, position: FilePosition,
    ): IoResult {
        if (count == 0) return IoResult.success(0)
        if (position.value < 0) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        if (content == null || position.value == 0L) {
            val result = Cgroups.lock.withLock {
                if (!live(inode)) return@withLock VfsResult.Err(VfsError.NO_DEVICE)
                control.show().also { if (it is VfsResult.Ok) observed = control.file.version(control.group) }
            }
            content = when (result) {
                is VfsResult.Ok -> result.value
                is VfsResult.Err -> return IoResult.failure(result.error)
            }
        }
        val bytes = checkNotNull(content)
        if (position.value >= bytes.size) return IoResult.success(0)
        val start = position.value.toInt()
        val copied = destination.copyFrom(destinationOffset, bytes, start, minOf(count, bytes.size - start))
        if (copied == 0) return IoResult.failure(VfsError.FAULT)
        position.value += copied
        return IoResult.success(copied)
    }

    override fun write(
        caller: VfsOperationContext, inode: Inode, source: PreparedBufferSource,
        sourceOffset: Int, count: Int, position: FilePosition, append: Boolean,
    ): IoResult {
        if (!control.file.writable) return IoResult.failure(VfsError.PERMISSION_DENIED)
        if (count == 0) return IoResult.success(0)
        if (count > PAGE_SIZE_BYTES.toInt()) return IoResult.failure(VfsError.FILE_TOO_LARGE)
        val bytes = ByteArray(count)
        if (source.copyTo(sourceOffset, bytes, 0, count) != count) return IoResult.failure(VfsError.FAULT)
        if (bytes.any { it == 0.toByte() || it < 0 }) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        val text = bytes.decodeToString().trim()
        if (text.isEmpty()) return IoResult.failure(VfsError.INVALID_ARGUMENT)
        val result = Cgroups.lock.withLock {
            if (!live(inode)) VfsResult.Err(VfsError.NO_DEVICE) else control.store(caller, opener, inode, text)
        }
        if (result is VfsResult.Err) return IoResult.failure(result.error)
        position.value += count
        content = null
        return IoResult.success(count)
    }

    override fun poll(caller: VfsOperationContext, inode: Inode, events: Int): Long = Cgroups.lock.withLock {
        var ready = PollEvents.DEFAULT_FILE_EVENTS
        if (!live(inode) || observed != control.file.version(control.group)) ready = ready or PollEvents.POLLPRI or PollEvents.POLLERR
        (ready and (events or PollEvents.UNCONDITIONALLY_REPORTED)).toLong()
    }

    private fun live(inode: Inode): Boolean = control.group.live && control.file.visible(control.group) &&
        inode.metadata().linkCount != 0u
}
