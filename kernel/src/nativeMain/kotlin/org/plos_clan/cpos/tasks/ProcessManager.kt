@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.drivers.TscClock
import org.plos_clan.cpos.fs.FileDescriptorTable
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.vfs.FileSystemContext
import org.plos_clan.cpos.fs.vfs.VfsOperationContext
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.tasks.cgroup.CgroupHierarchy
import org.plos_clan.cpos.tasks.cgroup.CgroupPlacement
import org.plos_clan.cpos.tasks.cgroup.Cgroups
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.page.KernelPageDirectory
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters
import org.plos_clan.cpos.utils.alignDown
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val DEFAULT_THREAD_STACK_PAGES = 64uL

enum class TaskState {
    READY,
    RUNNING,
    BLOCKED,
    ZOMBIE,
}

internal enum class ProcessState {
    READY,
    RUNNING,
    STOPPED,
    EXITING,
    ZOMBIE,
    DEAD,
    ;

    val canReceiveSignals: Boolean
        get() = this != EXITING && this != ZOMBIE && this != DEAD
}

internal enum class ProcessGroupResult {
    SUCCESS,
    NO_SUCH_PROCESS,
    NOT_PERMITTED,
}

internal class PidHandle(
    val thread: Thread,
    val scope: Scope,
) {
    interface Provider {
        val target: PidHandle
    }

    enum class Scope {
        PROCESS,
        THREAD,
    }

    enum class State {
        RUNNING,
        EXITED,
        DEAD,
    }

    init {
        require(scope == Scope.THREAD || thread.id == thread.process.id)
    }

    val state: State
        get() = when (scope) {
            Scope.PROCESS -> when (thread.process.state) {
                ProcessState.ZOMBIE -> State.EXITED
                ProcessState.DEAD -> State.DEAD
                else -> State.RUNNING
            }

            Scope.THREAD -> when {
                thread.process.state == ProcessState.DEAD -> State.DEAD
                thread.state != TaskState.ZOMBIE -> State.RUNNING
                thread.id == thread.process.id -> State.EXITED
                else -> State.DEAD
            }
        }
}

internal enum class MemoryCloneMode {
    COPY,
    SHARE,
}

internal data class ProcessMembership(
    val sessionId: Int,
    val processGroupId: Int,
)

internal enum class ChildEventKind {
    EXITED,
    STOPPED,
    CONTINUED,
}

internal data class ChildWaitEvent(
    val child: Process,
    val kind: ChildEventKind,
    val status: Int,
) {
    fun signalInfo(signal: Signal = Signal.CHILD): SignalInfo {
        val termination = status and 0x7f
        val code = when (kind) {
            ChildEventKind.EXITED -> when {
                termination == 0 -> SignalInfo.CHILD_EXITED
                status and 0x80 != 0 -> SignalInfo.CHILD_DUMPED
                else -> SignalInfo.CHILD_KILLED
            }
            ChildEventKind.STOPPED -> SignalInfo.CHILD_STOPPED
            ChildEventKind.CONTINUED -> SignalInfo.CHILD_CONTINUED
        }
        val childStatus = when (kind) {
            ChildEventKind.EXITED -> if (termination == 0) status ushr 8 and 0xff else termination
            ChildEventKind.STOPPED -> status ushr 8 and 0xff
            ChildEventKind.CONTINUED -> Signal.CONTINUE.number
        }
        return SignalInfo(
            signal = signal,
            code = code,
            payload = SignalPayload.Child(
                pid = child.id,
                uid = child.credentials.userIds.real,
                status = childStatus,
            ),
        )
    }
}

internal class ChildWaitQueue {
    private val lock = IrqSpinLock()
    private val events = mutableListOf<ChildWaitEvent>()
    private val waiters = mutableSetOf<Thread>()
    private var sequence = 0uL

    fun sequence(): ULong = lock.withLock { sequence }

    fun publish(event: ChildWaitEvent) {
        val awakened = lock.withLock {
            events += event
            sequence++
            waiters.toList().also { waiters.clear() }
        }
        awakened.forEach(Scheduler::wake)
    }

    fun notifyChange() {
        val awakened = lock.withLock {
            sequence++
            waiters.toList().also { waiters.clear() }
        }
        awakened.forEach(Scheduler::wake)
    }

    fun poll(
        children: Set<Process>,
        exited: Boolean,
        stopped: Boolean,
        continued: Boolean,
        consume: Boolean,
    ): ChildWaitEvent? =
        lock.withLock {
            val index = events.indexOfFirst { event ->
                event.child in children && when (event.kind) {
                    ChildEventKind.EXITED -> exited
                    ChildEventKind.STOPPED -> stopped
                    ChildEventKind.CONTINUED -> continued
                }
            }
            if (index < 0) null
            else if (consume) events.removeAt(index)
            else events[index]
        }

    fun restore(event: ChildWaitEvent) {
        val awakened = lock.withLock {
            events.add(0, event)
            sequence++
            waiters.toList().also { waiters.clear() }
        }
        awakened.forEach(Scheduler::wake)
    }

    fun discard(child: Process) {
        lock.withLock { events.removeAll { it.child === child } }
    }

    fun awaitChange(thread: Thread, observedSequence: ULong): Boolean {
        val registered = lock.withLock {
            if (sequence != observedSequence) false
            else {
                waiters += thread
                true
            }
        }
        if (!registered) return true
        if (thread.hasPendingSignal()) {
            lock.withLock { waiters.remove(thread) }
            return false
        }
        val parked = Scheduler.parkCurrent()
        lock.withLock { waiters.remove(thread) }
        return parked
    }
}

class Thread internal constructor(
    val id: Int,
    val process: Process,
    internal val parentThread: Thread? = null,
    val kernelStackTop: ULong = 0uL,
    val kernelStackPhysicalBase: ULong = 0uL,
    val kernelStackPages: ULong = 0uL,
    val kernelFsBase: ULong = 0uL,
    internal val signals: ThreadSignalState = process.signals.newThread(),
    var name: String = "",
    val affinityMask: ULong = 0UL,
    val capabilities: CapabilityState = CapabilityState(),
    internal val cgroup: CgroupHierarchy.Task? = null,
) {
    private val scheduledCpu = AtomicLong(-1)
    private val parentDeathSignalNumber = AtomicInt(0)

    internal var parentDeathSignal: Signal?
        get() = Signal.from(parentDeathSignalNumber.load())
        set(value) = parentDeathSignalNumber.store(value?.number ?: 0)

    internal fun bindToCpu(lapicId: UInt) {
        val requested = lapicId.toLong()
        val assigned = scheduledCpu.load()
        check(
            assigned == requested || assigned == -1L &&
                    scheduledCpu.compareAndSet(-1L, requested)
        ) {
            "thread $id cannot migrate from LAPIC $assigned to $lapicId"
        }
    }

    var clearChildTid: ULong = 0uL

    var robustListHead: ULong = 0uL

    val nativeContext: ULong = bridge.fast_handoff_create_task(
        id.toULong(),
        process.addressSpace.pageDirectory.pml4PhysicalAddress,
        kernelStackTop,
        kernelFsBase,
    ).also { handle ->
        require(handle != 0uL) { "Cannot allocate native context for thread $id" }
    }

    var state: TaskState
        get() = TaskState.entries.getOrElse(
            bridge.fast_handoff_task_state(nativeContext).toInt(),
        ) { TaskState.ZOMBIE }
        set(value) = bridge.fast_handoff_set_task_state(
            nativeContext,
            value.ordinal.toUByte(),
        )

    fun initializeContext(
        entryPoint: ULong,
        stackTop: ULong,
        argument: ULong = 0uL,
        fsBase: ULong = 0uL,
    ) {
        bridge.fast_handoff_init_kernel(
            nativeContext,
            entryPoint,
            stackTop,
            argument,
            fsBase,
        )
    }

    fun initializeUserContext(
        entryPoint: ULong,
        stackPointer: ULong,
        fsBase: ULong = 0uL,
    ) {
        require(!process.isKernelProcess) { "Kernel process cannot own a user context" }
        bridge.fast_handoff_init_user(
            nativeContext,
            entryPoint,
            stackPointer,
            fsBase,
        )
    }

    fun initializeUserContext(
        registers: ULongArray,
        stackPointer: ULong,
        fsBase: ULong = 0uL,
    ) {
        require(!process.isKernelProcess) { "Kernel process cannot own a user context" }
        require(registers.size == PtraceRegisters.REGISTER_COUNT)
        registers.usePinned { snapshot ->
            bridge.fast_handoff_init_user_registers(
                nativeContext,
                snapshot.addressOf(0),
                stackPointer,
                fsBase,
            )
        }
    }

    internal fun replaceAddressSpace(addressSpace: AddressSpace): Boolean =
        bridge.fast_handoff_replace_address_space(
            nativeContext,
            addressSpace.pageDirectory.pml4PhysicalAddress,
        )

    internal val pendingSignalMask: ULong
        get() = signals.pending.mask or process.signals.pending.mask

    internal val pendingSignalVersion: Int
        get() = signals.pending.version + process.signals.pending.version

    internal fun takePendingSignal(accepted: ULong): SignalInfo? =
        signals.pending.take(accepted) ?: process.signals.pending.take(accepted)

    internal fun hasPendingSignal(): Boolean {
        val accepted = signals.mask.inv()
        return process.signals.hasActionable(pendingSignalMask and accepted)
    }

    internal fun <T> updateCredentials(update: Credentials.() -> T): T {
        val credentials = process.credentials
        val previousUserIds = credentials.userIds
        val previousGroupIds = credentials.groupIds
        val result = credentials.update()
        val userIds = credentials.userIds
        val groupIds = credentials.groupIds
        val identityChanged = previousUserIds.effective != userIds.effective ||
            previousUserIds.filesystem != userIds.filesystem ||
            previousGroupIds.effective != groupIds.effective ||
            previousGroupIds.filesystem != groupIds.filesystem
        if (identityChanged && parentDeathSignal != null) {
            ProcessManager.setParentDeathSignal(this, null)
        }
        return result
    }

    internal fun commitExecution(execution: Credentials.Execution) {
        updateCredentials { commitExec(execution) }
        capabilities.applyExec(execution)
        if (execution.privileged && parentDeathSignal != null) {
            ProcessManager.setParentDeathSignal(this, null)
        }
    }
}

private class VforkCompletion(parent: Thread) {
    private val waiter = AtomicReference<Thread?>(parent)

    fun await() {
        while (waiter.load() != null) {
            check(Scheduler.parkCurrent()) { "Cannot suspend a vfork parent" }
        }
    }

    fun complete() {
        waiter.exchange(null)?.let(Scheduler::wake)
    }
}

class Process internal constructor(
    val id: Int,
    name: String,
    val isKernelProcess: Boolean,
    addressSpace: AddressSpace,
    var context: FileSystemContext?,
    val credentials: Credentials = Credentials(),
    var fileCreationMask: UInt = 0x12u,
    var dumpable: Boolean = true,
    val parentId: Int = 0,
    val startTimeTicks: ULong,
    internal val terminationSignal: Signal? = Signal.CHILD,
    vforkParent: Thread? = null,
) {
    private data class Lifecycle(
        val state: ProcessState = ProcessState.READY,
        val threads: List<Thread> = emptyList(),
        val liveThreads: Int = 0,
        val waitStatus: Int = 0,
    )

    val vfsOperationContext: VfsOperationContext
        get() = VfsOperationContext(
            uid = credentials.userIds.filesystem.toUInt(),
            gid = credentials.groupIds.filesystem.toUInt(),
            supplementaryGroups = credentials.supplementaryGroups,
            processId = id.toUInt(),
            fileCreationMask = fileCreationMask,
            privileged = credentials.userIds.effective == 0,
        )

    private val vforkCompletion = vforkParent?.let(::VforkCompletion)
    var name = name
        internal set
    var addressSpace = addressSpace
        internal set
    private val membershipState = AtomicReference(ProcessMembership(id, id))

    internal val membership: ProcessMembership
        get() = membershipState.load()

    val sessionId: Int
        get() = membership.sessionId
    val processGroupId: Int
        get() = membership.processGroupId

    private val lifecycle = AtomicReference(Lifecycle())
    val threads: List<Thread>
        get() = lifecycle.load().threads
    var commandLine: ByteArray = name.encodeToByteArray() + byteArrayOf(0)
        internal set
    internal val state: ProcessState
        get() = lifecycle.load().state
    val resourceLimits = ProcessLimits()
    internal val signals = ProcessSignalState(
        uid = { credentials.userIds.real },
        limit = { resourceLimits.get(ProcessResource.PENDING_SIGNALS).soft },
    )
    internal val childEvents = ChildWaitQueue()

    val fdTable = FileDescriptorTable()

    internal fun transitionState(expected: ProcessState, replacement: ProcessState): Boolean {
        var observed = lifecycle.load()
        while (observed.state == expected) {
            if (lifecycle.compareAndSet(observed, observed.copy(state = replacement))) return true
            observed = lifecycle.load()
        }
        return false
    }

    internal fun stop(): ProcessState? {
        var observed = lifecycle.load()
        while (observed.state.canReceiveSignals) {
            if (observed.state == ProcessState.STOPPED) return ProcessState.STOPPED
            val replacement = observed.copy(state = ProcessState.STOPPED)
            if (lifecycle.compareAndSet(observed, replacement)) return observed.state
            observed = lifecycle.load()
        }
        return null
    }

    fun addThread(thread: Thread) {
        require(thread.process === this) { "Thread ${thread.id} belongs to another process" }
        while (true) {
            val observed = lifecycle.load()
            check(observed.state.canReceiveSignals) {
                "process $id cannot add a thread while ${observed.state}"
            }
            if (thread in observed.threads) return
            val replacement = observed.copy(
                threads = observed.threads + thread,
                liveThreads = observed.liveThreads + 1,
            )
            if (lifecycle.compareAndSet(observed, replacement)) return
        }
    }

    internal fun beginExit(waitStatus: Int): List<Thread>? {
        var observed = lifecycle.load()
        while (observed.state.canReceiveSignals) {
            val replacement = observed.copy(
                state = ProcessState.EXITING,
                waitStatus = waitStatus,
            )
            if (lifecycle.compareAndSet(observed, replacement)) return observed.threads
            observed = lifecycle.load()
        }
        return null
    }

    internal fun completeThreadExit(waitStatus: Int): Boolean {
        while (true) {
            val observed = lifecycle.load()
            check(observed.liveThreads > 0) { "process $id has no live thread to exit" }
            check(observed.state.canReceiveSignals || observed.state == ProcessState.EXITING) {
                "process $id cannot complete a thread exit while ${observed.state}"
            }
            val remaining = observed.liveThreads - 1
            val beginExit = remaining == 0 && observed.state.canReceiveSignals
            val replacement = observed.copy(
                state = if (beginExit) ProcessState.EXITING else observed.state,
                liveThreads = remaining,
                waitStatus = if (beginExit) waitStatus else observed.waitStatus,
            )
            if (lifecycle.compareAndSet(observed, replacement)) return remaining == 0
        }
    }

    internal val requestedExitStatus: Int
        get() = lifecycle.load().let { lifecycle ->
            check(lifecycle.state == ProcessState.EXITING && lifecycle.liveThreads == 0) {
                "process $id is not ready for reaping"
            }
            lifecycle.waitStatus
        }

    internal fun releaseOwnedResources() {
        val fileSystemContext = context
        context = null
        fdTable.closeAll(vfsOperationContext)
        fileSystemContext?.release()
        addressSpace.release()
    }

    fun getFSContext(): FileSystemContext = requireNotNull(context) {
        "Filesystem context is unavailable before VFS initialization"
    }

    internal fun establishSession() {
        membershipState.store(ProcessMembership(id, id))
    }

    internal fun joinProcessGroup(group: Int) {
        membershipState.store(membership.copy(processGroupId = group))
    }

    internal fun inherit(parent: Process) {
        credentials.inherit(parent.credentials)
        fileCreationMask = parent.fileCreationMask
        dumpable = parent.dumpable
        membershipState.store(parent.membership)
        signals.inherit(parent.signals)
        resourceLimits.inherit(parent.resourceLimits)
        commandLine = parent.commandLine.copyOf()
    }

    internal fun installExecutable(path: String, arguments: List<String>) {
        name = path.substringAfterLast('/').ifEmpty { path }
        commandLine = arguments.joinToString(separator = "\u0000", postfix = "\u0000")
            .encodeToByteArray()
    }

    internal fun awaitVfork() = vforkCompletion?.await()

    internal fun completeVfork() = vforkCompletion?.complete()
}

object ProcessManager {
    private val nextTaskId = AtomicInt(2)
    private val processes = mutableListOf<Process>()
    private val processLock = IrqSpinLock()
    private val threadTable = mutableMapOf<Int, Thread>()
    private val parentDeathSubscribers = mutableMapOf<Thread, MutableSet<Thread>>()
    private val threadTableLock = IrqSpinLock()
    private var bootstrapThread: Thread? = null

    private var kernelProcess: Process? = null

    fun initialize() {
        if (processLock.withLock { processes.isNotEmpty() }) {
            return
        }

        val systemProcess = newProcess(
            name = "{system}",
            addressSpace = AddressSpace.user(KernelPageDirectory.getDirectory()),
            isKernelProcess = true,
            context = null,
            pid = 0,
        ).also { process ->
            check(process.transitionState(ProcessState.READY, ProcessState.RUNNING))
        }
        kernelProcess = systemProcess

        bootstrapThread = newThread(systemProcess).also { thread ->
            thread.state = TaskState.RUNNING
        }

        println("ProcessManager initialized.")
    }

    fun createThreadFromContext(
        entryPoint: ULong,
        stackPointer: ULong,
        fsBase: ULong = 0uL,
    ): Thread? {
        if (entryPoint == 0uL || stackPointer == 0uL) {
            return null
        }

        val process = kernelProcess ?: return null
        return newThread(process).also { thread ->
            thread.initializeContext(entryPoint, stackPointer, fsBase = fsBase)
        }.also(Scheduler::enqueueThread)
    }

    fun getBootstrapThread(): Thread? = bootstrapThread

    fun getKernelProcess(): Process? = bootstrapThread?.process

    fun currentThread(): Thread? {
        val id = bridge.fast_handoff_current_task_id()
        if (id > Int.MAX_VALUE.toULong()) {
            return null
        }
        return threadTableLock.withLock { threadTable[id.toInt()] }
    }

    fun currentProcess(): Process? = currentThread()?.process

    fun getNewApIdleThread(): Thread = newThread(requireNotNull(kernelProcess)).also { thread ->
        thread.state = TaskState.READY
    }

    internal fun createUserProcess(
        name: String,
        parent: Process? = null,
        memory: MemoryCloneMode = MemoryCloneMode.COPY,
        vforkParent: Thread? = null,
        terminationSignal: Signal? = Signal.CHILD,
        pid: Int = nextTaskId.fetchAndAdd(1),
    ): Process {
        val addressSpace = when {
            parent == null ->
                AddressSpace.user(KernelPageDirectory.getDirectory().createUserDirectory())

            memory == MemoryCloneMode.SHARE -> parent.addressSpace.share()
            else -> parent.addressSpace.fork()
        }
        val context = parent?.context?.fork() ?: FileSystemManager.kernelContext?.fork()
        val child = newProcess(
            name = name,
            addressSpace = addressSpace,
            isKernelProcess = false,
            context = context,
            parentId = parent?.id ?: 0,
            inherit = parent,
            vforkParent = vforkParent,
            terminationSignal = terminationSignal,
            pid = pid
        )
        if (parent == null) return child

        check(parent.fdTable.copyInto(child.fdTable))
        return child
    }

    internal fun createUserThread(
        process: Process,
        entryPoint: ULong,
        stackPointer: ULong,
        parentThread: Thread? = null,
        fsBase: ULong = 0uL,
        kernelStackPages: ULong = DEFAULT_THREAD_STACK_PAGES,
        registers: ULongArray? = null,
        signals: ThreadSignalState? = null,
        placement: CgroupPlacement? = null,
    ): VfsResult<Thread> {
        if (process.isKernelProcess || entryPoint == 0uL || stackPointer == 0uL) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val parent = currentThread()?.cgroup
        val membership = Cgroups.lock.withLock {
            val id = if (process.threads.isEmpty()) process.id else nextTaskId.fetchAndAdd(1)
            placement?.fork(id, process.id, parent) ?: Cgroups.hierarchy.fork(id, process.id, parent)
        }
        val cgroup = when (membership) {
            is VfsResult.Ok -> membership.value
            is VfsResult.Err -> return membership
        }
        var published = false
        try {
            val stack = allocateKernelStack(
                name = "process ${process.id} thread",
                stackPages = kernelStackPages,
            ) ?: return VfsResult.Err(VfsError.NO_MEMORY)
            val kernelFsBase = bridge.create_kernel_runtime_tcb()
            if (kernelFsBase == 0uL) {
                BuddyFrameAllocator.free(stack.physicalBase, stack.pages)
                return VfsResult.Err(VfsError.NO_MEMORY)
            }
            val thread = newThread(
                process = process,
                parentThread = parentThread,
                kernelStackTop = stack.top,
                kernelStackPhysicalBase = stack.physicalBase,
                kernelStackPages = stack.pages,
                kernelFsBase = kernelFsBase,
                signals = signals,
                cgroup = cgroup,
            )
            if (registers == null) thread.initializeUserContext(entryPoint, stackPointer, fsBase)
            else thread.initializeUserContext(registers, stackPointer, fsBase)
            published = true
            Cgroups.published(thread)
            return VfsResult.Ok(thread)
        } finally {
            if (!published) Cgroups.lock.withLock { Cgroups.hierarchy.exit(cgroup) }
        }
    }

    fun discardUserProcess(process: Process): Boolean {
        if (process.isKernelProcess || process.threads.isNotEmpty()) return false
        val removed = processLock.withLock { processes.remove(process) }
        if (!removed) return false

        process.releaseOwnedResources()
        return true
    }

    fun installUserAddressSpace(process: Process, replacement: AddressSpace): Boolean {
        if (process.threads.isNotEmpty()) {
            val current = currentThread() ?: return false
            val hasLiveSibling = process.threads.any { thread ->
                thread !== current && thread.state != TaskState.ZOMBIE
            }
            if (current.process !== process || hasLiveSibling) {
                return false
            }
            if (!current.replaceAddressSpace(replacement)) return false
        }

        val previous = process.addressSpace
        process.addressSpace = replacement
        previous.release()
        process.completeVfork()
        return true
    }

    fun findProcess(pid: Int): Process? {
        return processLock.withLock { processes.firstOrNull { it.id == pid } }
    }

    fun findThread(tid: Int): Thread? = threadTableLock.withLock { threadTable[tid] }

    internal fun setParentDeathSignal(thread: Thread, signal: Signal?) {
        threadTableLock.withLock {
            thread.parentDeathSignal = signal
            val parent = thread.parentThread ?: return@withLock
            if (signal != null && parent.state != TaskState.ZOMBIE) {
                parentDeathSubscribers.getOrPut(parent, ::mutableSetOf) += thread
                return@withLock
            }
            parentDeathSubscribers[parent]?.let { subscribers ->
                subscribers.remove(thread)
                if (subscribers.isEmpty()) parentDeathSubscribers.remove(parent)
            }
        }
    }

    internal fun notifyThreadExited(thread: Thread) {
        Cgroups.exit(thread)
        val deliveries = threadTableLock.withLock {
            thread.parentThread?.let { parent ->
                parentDeathSubscribers[parent]?.let { subscribers ->
                    subscribers.remove(thread)
                    if (subscribers.isEmpty()) parentDeathSubscribers.remove(parent)
                }
            }
            parentDeathSubscribers.remove(thread).orEmpty().map { child ->
                child.process to checkNotNull(child.parentDeathSignal)
            }
        }
        for ((target, signal) in deliveries) {
            SignalRouter.sendProcess(
                sender = null,
                target = target,
                info = SignalInfo.fromSender(signal, thread.process, SignalInfo.KERNEL),
            )
        }
    }

    fun snapshotProcesses(): List<Process> = processLock.withLock {
        processes.filterNot(Process::isKernelProcess).sortedBy(Process::id)
    }

    fun processesInGroup(groupId: Int): List<Process> = processLock.withLock {
        processes.filter { !it.isKernelProcess && it.processGroupId == groupId }
    }

    fun childrenOf(parentId: Int): List<Process> =
        processLock.withLock { processes.filter { it.parentId == parentId } }

    internal fun createSession(process: Process): Boolean = processLock.withLock {
        if (processes.any { it.processGroupId == process.id }) {
            return@withLock false
        }
        process.establishSession()
        true
    }

    internal fun setProcessGroup(
        caller: Process,
        pid: Int,
        requestedGroup: Int,
    ): ProcessGroupResult = processLock.withLock {
        val target = if (pid == 0) caller else processes.firstOrNull { it.id == pid }
            ?: return@withLock ProcessGroupResult.NO_SUCH_PROCESS
        if (target !== caller && target.parentId != caller.id) {
            return@withLock ProcessGroupResult.NO_SUCH_PROCESS
        }
        if (target.sessionId != caller.sessionId || target.sessionId == target.id) {
            return@withLock ProcessGroupResult.NOT_PERMITTED
        }

        val group = requestedGroup.takeUnless { it == 0 } ?: target.id
        val groupExists = group == target.id || processes.any {
            it.sessionId == target.sessionId && it.processGroupId == group
        }
        if (!groupExists) return@withLock ProcessGroupResult.NOT_PERMITTED

        target.joinProcessGroup(group)
        ProcessGroupResult.SUCCESS
    }

    internal fun finishExited(process: Process) {
        val waitStatus = process.requestedExitStatus
        process.releaseOwnedResources()
        val parent = processLock.withLock {
            check(process.transitionState(ProcessState.EXITING, ProcessState.ZOMBIE))
            processes.firstOrNull { it.id == process.parentId }
        }
        process.completeVfork()
        val target = parent ?: return
        val event = ChildWaitEvent(process, ChildEventKind.EXITED, waitStatus)
        val signal = process.terminationSignal
        if (signal == null) {
            target.childEvents.publish(event)
            return
        }
        val childAction = if (signal == Signal.CHILD) {
            target.signals.action(Signal.CHILD)
        } else {
            null
        }
        val autoReap = childAction?.let { action ->
            action.isIgnored || action.has(SignalActionFlag.NO_CHILD_WAIT)
        } == true
        if (!autoReap) target.childEvents.publish(event)
        if (childAction?.isIgnored != true) {
            SignalRouter.sendProcess(
                sender = null,
                target = target,
                info = event.signalInfo(signal),
            )
        }
        if (autoReap) reapChild(target.id, process)
    }

    internal fun markStopped(process: Process, signal: Signal) = publishChildState(
        process = process,
        kind = ChildEventKind.STOPPED,
        waitStatus = signal.number shl 8 or 0x7f,
    )

    internal fun markContinued(process: Process) = publishChildState(
        process = process,
        kind = ChildEventKind.CONTINUED,
        waitStatus = 0xffff,
    )

    fun reapChild(parentId: Int, child: Process): Boolean {
        var parent: Process? = null
        val reaped = processLock.withLock {
            val removable = child.parentId == parentId &&
                    child.state == ProcessState.ZOMBIE && processes.remove(child)
            if (!removable) return@withLock false
            check(child.transitionState(ProcessState.ZOMBIE, ProcessState.DEAD))
            parent = processes.firstOrNull { it.id == parentId }
            true
        }
        if (reaped) {
            parent?.childEvents?.apply {
                discard(child)
                notifyChange()
            }
        }
        return reaped
    }

    private fun publishChildState(
        process: Process,
        kind: ChildEventKind,
        waitStatus: Int,
    ) {
        val parent = processLock.withLock {
            processes.firstOrNull { it.id == process.parentId }
        } ?: return
        val event = ChildWaitEvent(process, kind, waitStatus)
        parent.childEvents.publish(event)
        val action = parent.signals.action(Signal.CHILD)
        if (action.isIgnored || action.has(SignalActionFlag.NO_CHILD_STOP)) return
        SignalRouter.sendProcess(
            sender = null,
            target = parent,
            info = event.signalInfo(),
        )
    }

    private fun allocateKernelStack(name: String, stackPages: ULong): KernelStack? {
        if (!BuddyFrameAllocator.isReady && !BuddyFrameAllocator.initialize()) {
            println("ProcessManager: frame allocator unavailable for thread '$name'")
            return null
        }
        if (!Hhdm.isReady && Hhdm.initialize() == null) {
            println("ProcessManager: HHDM unavailable for thread '$name'")
            return null
        }

        val pages = stackPages.takeIf { it != 0uL } ?: DEFAULT_THREAD_STACK_PAGES
        val physicalBase = BuddyFrameAllocator.allocate(pages)
        if (physicalBase == INVALID_FRAME) {
            println("ProcessManager: failed to allocate stack for thread '$name'")
            return null
        }
        val top = Hhdm.toVirtual(physicalBase + pages * PAGE_SIZE_BYTES).alignDown(16uL)
        return KernelStack(physicalBase, pages, top)
    }

    private fun newProcess(
        name: String,
        addressSpace: AddressSpace,
        isKernelProcess: Boolean,
        context: FileSystemContext?,
        parentId: Int = 0,
        inherit: Process? = null,
        vforkParent: Thread? = null,
        terminationSignal: Signal? = Signal.CHILD,
        pid: Int = nextTaskId.fetchAndAdd(1),
    ): Process = Process(
        id = pid,
        name = name,
        isKernelProcess = isKernelProcess,
        addressSpace = addressSpace,
        context,
        parentId = parentId,
        startTimeTicks = TscClock.nanoTime() / NANOSECONDS_PER_USER_TICK,
        terminationSignal = terminationSignal,
        vforkParent = vforkParent,
    ).also { created ->
        inherit?.let(created::inherit)
        processLock.withLock { processes += created }
    }

    private fun newThread(
        process: Process,
        parentThread: Thread? = null,
        kernelStackTop: ULong = 0uL,
        kernelStackPhysicalBase: ULong = 0uL,
        kernelStackPages: ULong = 0uL,
        kernelFsBase: ULong = 0uL,
        signals: ThreadSignalState? = null,
        cgroup: CgroupHierarchy.Task? = null,
    ): Thread =
        Thread(
            id = cgroup?.id ?: if (process.threads.isEmpty()) process.id else nextTaskId.fetchAndAdd(1),
            process = process,
            parentThread = parentThread,
            kernelStackTop = kernelStackTop,
            kernelStackPhysicalBase = kernelStackPhysicalBase,
            kernelStackPages = kernelStackPages,
            kernelFsBase = kernelFsBase,
            signals = signals ?: process.signals.newThread(),
            cgroup = cgroup,
        ).also { thread ->
            process.addThread(thread)
            threadTableLock.withLock { threadTable[thread.id] = thread }
        }
}

private data class KernelStack(
    val physicalBase: ULong,
    val pages: ULong,
    val top: ULong,
)

private const val NANOSECONDS_PER_USER_TICK = 10_000_000uL
