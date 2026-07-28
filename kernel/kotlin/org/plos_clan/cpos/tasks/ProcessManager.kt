@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.tasks

import bridge.get_kernel_idle_entry_address
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters
import org.plos_clan.cpos.utils.alignDown

private const val DEFAULT_THREAD_STACK_PAGES = 8uL
private const val KERNEL_CODE_SELECTOR = 0x08uL
private const val KERNEL_DATA_SELECTOR = 0x10uL
private const val DEFAULT_THREAD_RFLAGS = 0x202uL

private val idleThreadEntryPoint: ULong by lazy(LazyThreadSafetyMode.NONE) {
    get_kernel_idle_entry_address()
}

enum class TaskState {
    READY,
    RUNNING,
}

class Thread(
    val id: Int,
    private val context: ULongArray = ULongArray(PtraceRegisters.REGISTER_COUNT),
) {
    var state: TaskState = TaskState.READY
    var isQueued: Boolean = false
    var hasSavedContext: Boolean = false
        private set

    fun initializeContext(
        entryPoint: ULong,
        stackTop: ULong,
        argument: ULong = 0uL,
        fsBase: ULong = 0uL,
    ) {
        context.apply {
            fill(0uL)
            this[PtraceRegisters.IDX_RIP] = entryPoint
            this[PtraceRegisters.IDX_RSP] = stackTop
            this[PtraceRegisters.IDX_RBP] = stackTop
            this[PtraceRegisters.IDX_RFLAGS] = DEFAULT_THREAD_RFLAGS
            this[PtraceRegisters.IDX_CS] = KERNEL_CODE_SELECTOR
            this[PtraceRegisters.IDX_SS] = KERNEL_DATA_SELECTOR
            this[PtraceRegisters.IDX_DS] = KERNEL_DATA_SELECTOR
            this[PtraceRegisters.IDX_ES] = KERNEL_DATA_SELECTOR
            this[PtraceRegisters.IDX_FS_BASE] = fsBase
            this[PtraceRegisters.IDX_RDI] = argument
        }
        hasSavedContext = true
        state = TaskState.READY
    }

    fun saveFrom(registers: PtraceRegisters) {
        registers.copyInto(context)
        hasSavedContext = true
    }

    fun restoreTo(registers: PtraceRegisters): Boolean {
        if (hasSavedContext) {
            registers.restoreFrom(context)
        }
        return hasSavedContext
    }
}

class Process(val id: Int, val name: String) {
    val threads = mutableListOf<Thread>()
    var state: TaskState = TaskState.READY

    fun addThread(thread: Thread) {
        threads += thread
    }
}

object ProcessManager {
    private var nextThreadId = 0
    private var nextProcessId = 0
    private val threads = mutableListOf<Thread>()
    private val process = mutableListOf<Process>()
    private var bootstrapThread: Thread? = null

    private var kernelProcess: Process? = null

    fun initialize() {
        if (process.isNotEmpty()) {
            return
        }

        bootstrapThread = newThread().also { thread ->
            thread.state = TaskState.RUNNING
        }

        kernelProcess = newProcess("{system}").also { process ->
            process.state = TaskState.RUNNING
        }
        kernelProcess?.addThread(bootstrapThread!!)

        createKernelThread(
            name = "idle",
            entryPoint = idleThreadEntryPoint,
        )

        println("ProcessManager initialized threads=${threads.size}")
    }

    fun createThreadFromContext(
        entryPoint: ULong,
        stackPointer: ULong,
        fsBase: ULong = 0uL,
    ): Thread? {
        if (threads.isEmpty() || entryPoint == 0uL || stackPointer == 0uL) {
            return null
        }

        return newThread().also { thread ->
            thread.initializeContext(entryPoint, stackPointer, fsBase = fsBase)
        }.also(Scheduler::enqueueThread)
    }

    fun getBootstrapThread(): Thread? = bootstrapThread

    fun allThreads(): List<Thread> = threads

    private fun createKernelThread(
        name: String,
        entryPoint: ULong,
        argument: ULong = 0uL,
        stackPages: ULong = DEFAULT_THREAD_STACK_PAGES,
    ): Thread? {
        if (!BuddyFrameAllocator.isReady && !BuddyFrameAllocator.initialize()) {
            println("ProcessManager: frame allocator unavailable for thread '$name'")
            return null
        }
        if (!Hhdm.isReady && Hhdm.initialize() == null) {
            println("ProcessManager: HHDM unavailable for thread '$name'")
            return null
        }

        val pages = stackPages.takeIf { it != 0uL } ?: DEFAULT_THREAD_STACK_PAGES
        val stackBasePhysical = BuddyFrameAllocator.allocateFrames(pages) ?: run {
            println("ProcessManager: failed to allocate stack for thread '$name'")
            return null
        }
        val stackSizeBytes = pages * PAGE_SIZE_BYTES
        val stackTopVirtual = Hhdm.toVirtual(stackBasePhysical + stackSizeBytes).alignDown(16uL)

        return newThread().also { thread ->
            thread.initializeContext(entryPoint, stackTopVirtual, argument)
        }.also(Scheduler::enqueueThread).also { thread ->
            kernelProcess?.addThread(thread)
        }
    }

    private fun newProcess(name: String): Process =
        Process(nextProcessId++, name).also { process += it }

    private fun newThread(): Thread =
        Thread(nextThreadId++).also { threads += it }
}
