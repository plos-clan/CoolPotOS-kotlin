@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.fs.FileDescriptorTable
import org.plos_clan.cpos.fs.FileSystemContext
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.mem.VirtualAddressSpace
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val DEFAULT_THREAD_STACK_PAGES = 64uL

enum class TaskState {
    READY,
    RUNNING,
    BLOCKED,
    ZOMBIE,
}

class Thread(
    val id: Int,
    val process: Process,
    val kernelStackTop: ULong = 0uL,
    val kernelStackPhysicalBase: ULong = 0uL,
    val kernelStackPages: ULong = 0uL,
    val kernelFsBase: ULong = 0uL,
) {
    /** Address cleared to zero when this thread exits, if set by set_tid_address. */
    var clearChildTid: ULong = 0uL

    /** Userspace robust-futex list registered by set_robust_list. */
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

    val isQueued: Boolean
        get() = bridge.fast_handoff_task_is_queued(nativeContext) != 0u.toUByte()

    val hasSavedContext: Boolean
        get() = bridge.fast_handoff_task_has_context(nativeContext) != 0u.toUByte()

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
        require(registers.size == org.plos_clan.cpos.utils.PtraceRegisters.REGISTER_COUNT)
        registers.usePinned { snapshot ->
            bridge.fast_handoff_init_user_registers(
                nativeContext,
                snapshot.addressOf(0),
                stackPointer,
                fsBase,
            )
        }
    }

    internal fun replaceAddressSpace(addressSpace: VirtualAddressSpace): Boolean =
        bridge.fast_handoff_replace_address_space(
            nativeContext,
            addressSpace.pageDirectory.pml4PhysicalAddress,
        )
}

class Process internal constructor(
    val id: Int,
    name: String,
    val isKernelProcess: Boolean,
    addressSpace: VirtualAddressSpace,
    var context: FileSystemContext?,
    var ruid: Int = 0,
    var euid: Int = 0,
    var suid: Int = 0,
    var fsuid: Int = euid,
    var egid: Int = 0,
    var rgid: Int = 0,
    var sgid: Int = 0,
    var fsgid: Int = egid,
    var sessionId: Int = id,
    var processGroupId: Int = id,
    val parentId: Int = 0,
    val startTimeTicks: ULong,
) {
    var name = name
        internal set
    var addressSpace = addressSpace
        internal set

    val threads = mutableListOf<Thread>()
    var commandLine: ByteArray = name.encodeToByteArray() + byteArrayOf(0)
        internal set
    var state: TaskState = TaskState.READY
    var signalMask: ULong = 0uL
    val signalActions = arrayOfNulls<ByteArray>(64)
    val resourceLimits = ProcessLimits()
    var exitCode: Int = 0

    val fdTable = FileDescriptorTable()

    fun addThread(thread: Thread) {
        require(thread.process === this) { "Thread ${thread.id} belongs to another process" }
        if (thread in threads) {
            return
        }
        threads += thread
    }

    fun getFSContext(): FileSystemContext = requireNotNull(context) {
        "Filesystem context is unavailable before VFS initialization"
    }

    fun setFilesystemUid(requested: Int?): Int {
        val previous = fsuid
        if (requested != null &&
            (euid == 0 || requested == ruid || requested == euid ||
                requested == suid || requested == fsuid)
        ) {
            fsuid = requested
        }
        return previous
    }

    fun setFilesystemGid(requested: Int?): Int {
        val previous = fsgid
        if (requested != null &&
            (euid == 0 || requested == rgid || requested == egid ||
                requested == sgid || requested == fsgid)
        ) {
            fsgid = requested
        }
        return previous
    }

    internal fun inherit(parent: Process) {
        ruid = parent.ruid
        euid = parent.euid
        suid = parent.suid
        fsuid = parent.fsuid
        rgid = parent.rgid
        egid = parent.egid
        sgid = parent.sgid
        fsgid = parent.fsgid
        sessionId = parent.sessionId
        processGroupId = parent.processGroupId
        signalMask = parent.signalMask
        parent.signalActions.forEachIndexed { index, action ->
            signalActions[index] = action?.copyOf()
        }
        commandLine = parent.commandLine.copyOf()
    }

    internal fun installExecutable(path: String, arguments: List<String>) {
        name = path.substringAfterLast('/').ifEmpty { path }
        commandLine = arguments.joinToString(separator = "\u0000", postfix = "\u0000")
            .encodeToByteArray()
    }
}

object ProcessManager {
    private val nextThreadId = AtomicInt(0)
    private val nextProcessId = AtomicInt(0)
    private val process = mutableListOf<Process>()
    private val processLock = IrqSpinLock()
    private val threadTable = mutableMapOf<Int, Thread>()
    private val threadTableLock = IrqSpinLock()
    private var bootstrapThread: Thread? = null

    private var kernelProcess: Process? = null

    fun initialize() {
        if (processLock.withLock { process.isNotEmpty() }) {
            return
        }

        val systemProcess = newProcess(
            name = "{system}",
            addressSpace = VirtualAddressSpace(KernelPageDirectory.getDirectory()),
            isKernelProcess = true,
            null
        ).also { process ->
            process.state = TaskState.RUNNING
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

    fun createUserProcess(
        name: String,
        parent: Process? = null,
    ): Process {
        val addressSpace = parent?.addressSpace?.fork()
            ?: VirtualAddressSpace(KernelPageDirectory.getDirectory().createUserDirectory())
        val context = parent?.context?.fork() ?: FileSystemManager.kernelContext
        val child = newProcess(
            name = name,
            addressSpace = addressSpace,
            isKernelProcess = false,
            context = context,
            parentId = parent?.id ?: 0,
            inherit = parent,
        )
        if (parent == null) return child

        check(parent.fdTable.copyInto(child.fdTable))
        return child
    }

    fun createUserThread(
        process: Process,
        entryPoint: ULong,
        stackPointer: ULong,
        fsBase: ULong = 0uL,
        kernelStackPages: ULong = DEFAULT_THREAD_STACK_PAGES,
        registers: ULongArray? = null,
    ): Thread? {
        if (process.isKernelProcess || entryPoint == 0uL || stackPointer == 0uL) {
            return null
        }
        val stack = allocateKernelStack(
            name = "process ${process.id} thread",
            stackPages = kernelStackPages,
        ) ?: return null
        val kernelFsBase = bridge.create_kernel_runtime_tcb()
        if (kernelFsBase == 0uL) {
            BuddyFrameAllocator.freeFrames(stack.physicalBase, stack.pages)
            return null
        }
        return newThread(
            process = process,
            kernelStackTop = stack.top,
            kernelStackPhysicalBase = stack.physicalBase,
            kernelStackPages = stack.pages,
            kernelFsBase = kernelFsBase,
        ).also { thread ->
            if (registers == null) {
                thread.initializeUserContext(entryPoint, stackPointer, fsBase)
            } else {
                thread.initializeUserContext(registers, stackPointer, fsBase)
            }
        }.also(Scheduler::enqueueThread)
    }

    fun installUserAddressSpace(process: Process, replacement: VirtualAddressSpace): Boolean {
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
        previous.destroy()
        return true
    }

    fun findProcess(pid: Int): Process? {
        return processLock.withLock { process.firstOrNull { it.id == pid } }
    }

    fun snapshotProcesses(): List<Process> = processLock.withLock {
        process.filterNot(Process::isKernelProcess).sortedBy(Process::id)
    }

    fun childrenOf(parentId: Int): List<Process> =
        processLock.withLock { process.filter { it.parentId == parentId } }

    fun markExited(process: Process, status: Int) {
        processLock.withLock {
            process.exitCode = status and 0xff
            process.state = TaskState.ZOMBIE
        }
    }

    fun reapChild(parentId: Int, child: Process): Boolean {
        val reaped = processLock.withLock {
            child.parentId == parentId &&
                child.state == TaskState.ZOMBIE &&
                process.remove(child)
        }
        if (reaped) {
            child.addressSpace.destroy()
        }
        return reaped
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
        val physicalBase = BuddyFrameAllocator.allocateFrames(pages) ?: run {
            println("ProcessManager: failed to allocate stack for thread '$name'")
            return null
        }
        val top = Hhdm.toVirtual(physicalBase + pages * PAGE_SIZE_BYTES).alignDown(16uL)
        return KernelStack(physicalBase, pages, top)
    }

    private fun newProcess(
        name: String,
        addressSpace: VirtualAddressSpace,
        isKernelProcess: Boolean,
        context: FileSystemContext?,
        parentId: Int = 0,
        inherit: Process? = null,
    ): Process = Process(
        id = nextProcessId.fetchAndAdd(1),
        name = name,
        isKernelProcess = isKernelProcess,
        addressSpace = addressSpace,
        context,
        parentId = parentId,
        startTimeTicks = Hpet.nanoTime() / NANOSECONDS_PER_USER_TICK,
    ).also { created ->
        inherit?.let(created::inherit)
        processLock.withLock { process += created }
    }

    private fun newThread(
        process: Process,
        kernelStackTop: ULong = 0uL,
        kernelStackPhysicalBase: ULong = 0uL,
        kernelStackPages: ULong = 0uL,
        kernelFsBase: ULong = 0uL,
    ): Thread =
        Thread(
            id = nextThreadId.fetchAndAdd(1),
            process = process,
            kernelStackTop = kernelStackTop,
            kernelStackPhysicalBase = kernelStackPhysicalBase,
            kernelStackPages = kernelStackPages,
            kernelFsBase = kernelFsBase,
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
