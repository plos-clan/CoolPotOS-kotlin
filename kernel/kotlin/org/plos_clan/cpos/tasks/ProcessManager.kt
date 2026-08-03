@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import bridge.get_kernel_idle_entry_address
import org.plos_clan.cpos.fs.FileDescriptorTable
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.mem.PageDirectory
import org.plos_clan.cpos.mem.VMA
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val DEFAULT_THREAD_STACK_PAGES = 8uL

private val idleThreadEntryPoint: ULong by lazy(LazyThreadSafetyMode.NONE) {
    get_kernel_idle_entry_address()
}

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
) {
    val nativeContext: ULong = bridge.fast_handoff_create_task(
        id.toULong(),
        process.vma.pageDirectory.pml4PhysicalAddress,
        kernelStackTop,
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
}

class Process internal constructor(
    val id: Int,
    val name: String,
    val isKernelProcess: Boolean,
    pageDirectory: PageDirectory,
) {
    val threads = mutableListOf<Thread>()
    var state: TaskState = TaskState.READY

    val fdTable = FileDescriptorTable()

    val vma = VMA(pageDirectory)

    fun addThread(thread: Thread) {
        require(thread.process === this) { "Thread ${thread.id} belongs to another process" }
        if (thread in threads) {
            return
        }
        threads += thread
    }
}

object ProcessManager {
    private var nextThreadId = AtomicInt(0)
    private var nextProcessId = AtomicInt(0)
    private val process = mutableListOf<Process>()
    private val threadTable = mutableMapOf<Int, Thread>()
    private val threadTableLock = IrqSpinLock()
    private var bootstrapThread: Thread? = null

    private var kernelProcess: Process? = null

    fun initialize() {
        if (process.isNotEmpty()) {
            return
        }

        val systemProcess = newProcess(
            name = "{system}",
            pageDirectory = KernelPageDirectory.getDirectory(),
            isKernelProcess = true,
        ).also { process ->
            process.state = TaskState.RUNNING
        }
        kernelProcess = systemProcess

        bootstrapThread = newThread(systemProcess).also { thread ->
            thread.state = TaskState.RUNNING
        }

        createKernelThread(
            name = "idle",
            entryPoint = idleThreadEntryPoint,
        )

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

    fun createUserProcess(name: String, clone: PageDirectory? = null): Process =
        newProcess(
            name = name,
            pageDirectory = clone?.cloneDirectory()
                ?: KernelPageDirectory.getDirectory().createUserDirectory(),
            isKernelProcess = false,
        )

    fun createUserThread(
        process: Process,
        entryPoint: ULong,
        stackPointer: ULong,
        fsBase: ULong = 0uL,
        kernelStackPages: ULong = DEFAULT_THREAD_STACK_PAGES,
    ): Thread? {
        if (process.isKernelProcess || entryPoint == 0uL || stackPointer == 0uL) {
            return null
        }
        val stack = allocateKernelStack(
            name = "process ${process.id} thread",
            stackPages = kernelStackPages,
        ) ?: return null

        return newThread(
            process = process,
            kernelStackTop = stack.top,
            kernelStackPhysicalBase = stack.physicalBase,
            kernelStackPages = stack.pages,
        ).also { thread ->
            thread.initializeUserContext(entryPoint, stackPointer, fsBase)
        }.also(Scheduler::enqueueThread)
    }

    private fun createKernelThread(
        name: String,
        entryPoint: ULong,
        argument: ULong = 0uL,
        stackPages: ULong = DEFAULT_THREAD_STACK_PAGES,
    ): Thread? {
        val stack = allocateKernelStack(name, stackPages) ?: return null

        val process = kernelProcess ?: return null
        return newThread(
            process = process,
            kernelStackTop = stack.top,
            kernelStackPhysicalBase = stack.physicalBase,
            kernelStackPages = stack.pages,
        ).also { thread ->
            thread.initializeContext(entryPoint, stack.top, argument)
        }.also(Scheduler::enqueueThread)
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
        pageDirectory: PageDirectory,
        isKernelProcess: Boolean,
    ): Process = Process(
        id = nextProcessId.fetchAndAdd(1),
        name = name,
        isKernelProcess = isKernelProcess,
        pageDirectory = pageDirectory,
    ).also { process += it }

    private fun newThread(
        process: Process,
        kernelStackTop: ULong = 0uL,
        kernelStackPhysicalBase: ULong = 0uL,
        kernelStackPages: ULong = 0uL,
    ): Thread =
        Thread(
            id = nextThreadId.fetchAndAdd(1),
            process = process,
            kernelStackTop = kernelStackTop,
            kernelStackPhysicalBase = kernelStackPhysicalBase,
            kernelStackPages = kernelStackPages,
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
