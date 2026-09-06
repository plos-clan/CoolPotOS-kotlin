@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks.cgroup

import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsName
import org.plos_clan.cpos.fs.vfs.VfsResult
import kotlin.concurrent.atomics.AtomicBoolean

/** The caller serializes hierarchy operations; the scheduler only reads Task.freezing. */
internal class CgroupHierarchy(
    private val changed: (Group, Event) -> Unit = { _, _ -> },
) {
    enum class Event { POPULATED, PIDS_MAX, PIDS_MAX_LOCAL }
    enum class Controller(val fileName: String, val threaded: Boolean) {
        PIDS("pids", true);

        companion object {
            val all = entries.toSet()
            val byName = entries.associateBy { it.fileName }
        }
    }
    enum class Type(val text: String) {
        DOMAIN("domain"), THREADED_DOMAIN("domain threaded"),
        INVALID("domain invalid"), THREADED("threaded"),
    }

    class Group internal constructor(val parent: Group?, var name: VfsName) {
        val children = linkedMapOf<VfsName, Group>()
        val tasks = linkedSetOf<Task>()
        var live = true
        var threaded = false
        var subtreeControl: Set<Controller> = emptySet()
        var maxDepth = Long.MAX_VALUE
        var maxDescendants = Long.MAX_VALUE
        var descendants = 0L
        var pidsMax = Long.MAX_VALUE
        var taskCount = 0L
        var pidsPeak = 0L
        var pidsEvents = 0L
        var pidsEventsLocal = 0L
        var freeze = false
        var freezing: Boolean = parent?.freezing == true
        var frozenCount = 0L
        var eventsVersion = 0
        var pidsVersion = 0
        var pidsLocalVersion = 0

        val controllers: Set<Controller> get() = parent?.subtreeControl ?: Controller.all
        val hasPids: Boolean get() = parent != null && Controller.PIDS in controllers
        val threadRoot: Boolean
            get() = !threaded && (children.values.any { it.threaded } ||
                tasks.isNotEmpty() && subtreeControl.any { it.threaded })
        val domain: Group
            get() {
                var group = this
                while (group.threaded) group = checkNotNull(group.parent)
                return group
            }
        val type: Type
            get() {
                val domain = domain
                var ancestor = domain.parent
                while (ancestor?.parent != null) {
                    if (ancestor.threaded || ancestor.threadRoot) return Type.INVALID
                    ancestor = ancestor.parent
                }
                return when {
                    threaded -> Type.THREADED
                    threadRoot -> Type.THREADED_DOMAIN
                    else -> Type.DOMAIN
                }
            }
        val frozen: Boolean get() = freezing && taskCount == frozenCount

        fun subtree(threadedOnly: Boolean = false): Sequence<Group> = sequence {
            val pending = ArrayDeque<Group>()
            pending.addLast(this@Group)
            while (pending.isNotEmpty()) {
                val group = pending.removeLast()
                yield(group)
                for (child in group.children.values) {
                    if (!threadedOnly || child.threaded) pending.addLast(child)
                }
            }
        }

        fun commonAncestor(other: Group): Group {
            var first: Group? = this
            var second: Group? = other
            while (first !== second) {
                first = if (first == null) other else first.parent
                second = if (second == null) this else second.parent
            }
            return checkNotNull(first)
        }

        fun path(): ByteArray {
            val names = ArrayList<ByteArray>()
            var group = this
            while (group.parent != null) {
                names += group.name.copyBytes()
                group = checkNotNull(group.parent)
            }
            val bytes = ByteArray(maxOf(1, names.sumOf { it.size + 1 }))
            bytes[0] = '/'.code.toByte()
            var offset = 0
            for (name in names.asReversed()) {
                bytes[offset++] = '/'.code.toByte()
                name.copyInto(bytes, offset)
                offset += name.size
            }
            return bytes
        }
    }

    class Task internal constructor(val id: Int, val processId: Int, var group: Group) {
        private val freezeRequested = AtomicBoolean(false)
        var freezing: Boolean
            get() = freezeRequested.load()
            internal set(value) = freezeRequested.store(value)
        var frozen = false
        var killed = false
    }

    val root = Group(null, VfsName.ROOT)
    private val tasks = mutableMapOf<Int, Task>()
    private val processes = mutableMapOf<Int, MutableSet<Task>>()

    fun task(id: Int): Task? = tasks[id]
    fun process(id: Int): Set<Task> = processes[id].orEmpty()

    fun create(parent: Group, name: VfsName): VfsResult<Group> {
        if (!parent.live) return VfsResult.Err(VfsError.NO_DEVICE)
        if (name in parent.children) return VfsResult.Err(VfsError.ALREADY_EXISTS)
        var ancestor: Group? = parent
        var depth = 1L
        while (ancestor != null) {
            if (depth > ancestor.maxDepth || ancestor.descendants >= ancestor.maxDescendants) {
                return VfsResult.Err(VfsError.WOULD_BLOCK)
            }
            depth++
            ancestor = ancestor.parent
        }
        val group = Group(parent, name)
        parent.children[name] = group
        ancestor = parent
        while (ancestor != null) {
            ancestor.descendants++
            ancestor = ancestor.parent
        }
        return VfsResult.Ok(group)
    }

    fun remove(group: Group): VfsResult<Unit> {
        if (!group.live) return VfsResult.Err(VfsError.NO_DEVICE)
        val parent = group.parent ?: return VfsResult.Err(VfsError.BUSY)
        if (group.tasks.isNotEmpty()) return VfsResult.Err(VfsError.BUSY)
        if (group.children.isNotEmpty()) return VfsResult.Err(VfsError.NOT_EMPTY)
        parent.children.remove(group.name)
        group.live = false
        var ancestor: Group? = parent
        while (ancestor != null) {
            ancestor.descendants--
            ancestor = ancestor.parent
        }
        return VfsResult.Ok(Unit)
    }

    fun enableThreaded(group: Group): VfsResult<Unit> {
        if (group.threaded) return VfsResult.Ok(Unit)
        val domain = group.parent?.domain ?: return VfsResult.Err(VfsError.NOT_SUPPORTED)
        val populatedDomain = domain.children.values.any { !it.threaded && it.taskCount != 0L }
        if (group.taskCount != 0L || group.subtreeControl.any { !it.threaded } || domain.type == Type.INVALID ||
            domain !== root && (populatedDomain || domain.subtreeControl.any { !it.threaded })
        ) return VfsResult.Err(VfsError.NOT_SUPPORTED)
        group.threaded = true
        return VfsResult.Ok(Unit)
    }

    fun subtreeControl(group: Group, input: String): VfsResult<Unit> {
        val enabled = group.subtreeControl.toMutableSet()
        for (word in input.splitToSequence(' ', '\t', '\n').filter(String::isNotEmpty)) {
            if (word.length < 2 || word[0] != '+' && word[0] != '-') {
                return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            val controller = Controller.byName[word.substring(1)]
                ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            if (word[0] == '+') {
                if (controller !in group.controllers) return VfsResult.Err(VfsError.NOT_FOUND)
                enabled += controller
            } else {
                enabled -= controller
            }
        }
        if (enabled.isNotEmpty() && group.type == Type.INVALID ||
            group.threaded && enabled.any { !it.threaded }
        ) return VfsResult.Err(VfsError.NOT_SUPPORTED)
        if (group.children.values.any { !enabled.containsAll(it.subtreeControl) }) {
            return VfsResult.Err(VfsError.BUSY)
        }
        if (enabled.isNotEmpty() && group !== root && !group.threaded && group.tasks.isNotEmpty() &&
            (enabled.any { !it.threaded } || group.children.values.any { !it.threaded && it.taskCount != 0L })
        ) return VfsResult.Err(VfsError.BUSY)
        if ((Controller.PIDS in enabled) != (Controller.PIDS in group.subtreeControl)) {
            for (child in group.children.values) {
                child.pidsMax = Long.MAX_VALUE
                child.pidsPeak = child.taskCount
                child.pidsEvents = 0
                child.pidsEventsLocal = 0
            }
        }
        group.subtreeControl = enabled
        return VfsResult.Ok(Unit)
    }

    fun fork(
        id: Int, processId: Int, parent: Task?, destination: Group? = null, thread: Boolean = false,
    ): VfsResult<Task> {
        check(id !in tasks)
        if (parent?.killed == true) return VfsResult.Err(VfsError.WOULD_BLOCK)
        val group = destination ?: parent?.group ?: root
        if (!group.live) return VfsResult.Err(VfsError.NO_DEVICE)
        if (destination != null) {
            val error = destinationError(group, listOfNotNull(parent), thread)
            if (error != null) return VfsResult.Err(error)
        }
        var ancestor: Group? = group
        while (ancestor != null) {
            if (ancestor.hasPids && ancestor.taskCount >= ancestor.pidsMax) {
                ancestor.pidsEventsLocal++
                notify(ancestor, Event.PIDS_MAX_LOCAL)
                var reported: Group? = ancestor
                while (reported?.parent != null) {
                    reported.pidsEvents++
                    notify(reported, Event.PIDS_MAX)
                    reported = reported.parent
                }
                return VfsResult.Err(VfsError.WOULD_BLOCK)
            }
            ancestor = ancestor.parent
        }
        val task = Task(id, processId, group)
        task.freezing = group.freezing
        tasks[id] = task
        processes.getOrPut(processId, ::linkedSetOf).add(task)
        group.tasks += task
        account(group, 1, 0)
        return VfsResult.Ok(task)
    }

    fun exit(task: Task) {
        if (tasks[task.id] !== task) return
        tasks.remove(task.id)
        processes[task.processId]?.let { members ->
            members.remove(task)
            if (members.isEmpty()) processes.remove(task.processId)
        }
        task.group.tasks.remove(task)
        account(task.group, -1, if (task.frozen) -1 else 0)
        task.frozen = false
        task.freezing = false
    }

    fun move(group: Group, selected: Collection<Task>, thread: Boolean): VfsResult<Unit> {
        val error = destinationError(group, selected, thread)
        if (error != null) return VfsResult.Err(error)
        for (task in selected) {
            val source = task.group
            if (source === group) continue
            val common = source.commonAncestor(group)
            source.tasks.remove(task)
            group.tasks.add(task)
            account(source, -1, if (task.frozen) -1 else 0, common)
            task.group = group
            task.freezing = group.freezing
            account(group, 1, if (task.frozen) 1 else 0, common)
        }
        return VfsResult.Ok(Unit)
    }

    fun freeze(group: Group, value: Boolean): List<Task> {
        if (group.freeze == value) return emptyList()
        group.freeze = value
        val affected = ArrayList<Task>()
        for (child in group.subtree()) {
            val wasFrozen = child.frozen
            val freezing = child.freeze || child.parent?.freezing == true
            child.freezing = freezing
            for (task in child.tasks) {
                if (task.freezing == freezing) continue
                task.freezing = freezing
                affected += task
            }
            if (wasFrozen != child.frozen) notify(child, Event.POPULATED)
        }
        return affected
    }

    fun acknowledgeFreeze(task: Task, value: Boolean) {
        if (task.frozen == value) return
        task.frozen = value
        account(task.group, 0, if (value) 1 else -1)
    }

    private fun destinationError(group: Group, selected: Collection<Task>, thread: Boolean): VfsError? = when {
        !group.live -> VfsError.NO_DEVICE
        group.type == Type.INVALID -> VfsError.NOT_SUPPORTED
        group !== root && !group.threaded && group.subtreeControl.isNotEmpty() &&
            (group.subtreeControl.any { !it.threaded } ||
                group.children.values.any { !it.threaded && it.taskCount != 0L }) -> VfsError.BUSY
        thread && selected.any { it.group.domain !== group.domain } -> VfsError.NOT_SUPPORTED
        else -> null
    }

    private fun account(group: Group, tasks: Long, frozen: Long, stop: Group? = null) {
        var current: Group? = group
        while (current !== stop) {
            val ancestor = checkNotNull(current)
            val wasPopulated = ancestor.taskCount != 0L
            val wasFrozen = ancestor.frozen
            ancestor.taskCount += tasks
            ancestor.frozenCount += frozen
            ancestor.pidsPeak = maxOf(ancestor.pidsPeak, ancestor.taskCount)
            check(ancestor.taskCount >= 0 && ancestor.frozenCount in 0..ancestor.taskCount)
            if (wasPopulated != (ancestor.taskCount != 0L) || wasFrozen != ancestor.frozen) {
                notify(ancestor, Event.POPULATED)
            }
            current = ancestor.parent
        }
    }

    private fun notify(group: Group, event: Event) {
        when (event) {
            Event.POPULATED -> group.eventsVersion++
            Event.PIDS_MAX -> group.pidsVersion++
            Event.PIDS_MAX_LOCAL -> group.pidsLocalVersion++
        }
        changed(group, event)
    }
}
