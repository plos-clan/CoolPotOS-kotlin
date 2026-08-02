@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package org.plos_clan.cpos.tasks

import bridge.get_kernel_idle_entry_address
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.mem.PageDirectory
import org.plos_clan.cpos.mem.VMA
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PtraceRegisters
import org.plos_clan.cpos.utils.alignDown
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

private const val DEFAULT_THREAD_STACK_PAGES = 8uL
private const val KERNEL_CODE_SELECTOR = 0x08uL
private const val KERNEL_DATA_SELECTOR = 0x10uL
private const val USER_CODE_SELECTOR = 0x23uL
private const val USER_DATA_SELECTOR = 0x1buL
private const val DEFAULT_THREAD_RFLAGS = 0x202uL

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
    private val context: ULongArray = ULongArray(PtraceRegisters.REGISTER_COUNT),
    private val fpuContext: ByteArray = initialFpuContext(),
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

    fun initializeUserContext(
        entryPoint: ULong,
        stackPointer: ULong,
        fsBase: ULong = 0uL,
    ) {
        require(!process.isKernelProcess) { "Kernel process cannot own a user context" }
        context.apply {
            fill(0uL)
            this[PtraceRegisters.IDX_RIP] = entryPoint
            this[PtraceRegisters.IDX_RSP] = stackPointer
            this[PtraceRegisters.IDX_RBP] = 0uL
            this[PtraceRegisters.IDX_RFLAGS] = DEFAULT_THREAD_RFLAGS
            this[PtraceRegisters.IDX_CS] = USER_CODE_SELECTOR
            this[PtraceRegisters.IDX_SS] = USER_DATA_SELECTOR
            this[PtraceRegisters.IDX_DS] = USER_DATA_SELECTOR
            this[PtraceRegisters.IDX_ES] = USER_DATA_SELECTOR
            this[PtraceRegisters.IDX_FS_BASE] = fsBase
        }
        hasSavedContext = true
        state = TaskState.READY
    }

    fun saveFrom(registers: PtraceRegisters) {
        registers.copyInto(context)
        registers.copyFpuInto(fpuContext)
        hasSavedContext = true
    }

    fun restoreTo(registers: PtraceRegisters): Boolean {
        if (hasSavedContext) {
            registers.restoreFrom(context)
            registers.restoreFpuFrom(fpuContext)
        }
        return hasSavedContext
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
        ).also(process::addThread)
}

private data class KernelStack(
    val physicalBase: ULong,
    val pages: ULong,
    val top: ULong,
)

private fun initialFpuContext(): ByteArray =
    ByteArray(PtraceRegisters.FPU_STATE_SIZE).apply {
        this[0] = 0x7f
        this[1] = 0x03
        this[24] = 0x80.toByte()
        this[25] = 0x1f
    }
