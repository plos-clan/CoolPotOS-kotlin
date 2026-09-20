@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalForeignApi::class, InternalForKotlinNative::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.*
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.toPointer
import kotlin.native.internal.GCUnsafeCall
import kotlin.native.internal.InternalForKotlinNative

@GCUnsafeCall("memcpy")
private external fun copyMemory(destination: ULong, source: ULong, size: ULong): ULong

@GCUnsafeCall("memset")
private external fun clearMemory(destination: ULong, value: Int, size: ULong): ULong

@GCUnsafeCall("fast_handoff_task_cpu_time")
private external fun taskCpuTime(task: ULong): ULong

@GCUnsafeCall("fast_handoff_task_has_exited")
private external fun taskHasExited(task: ULong): Boolean

@GCUnsafeCall("_ZN5mlibc28run_thread_local_destructorsEv")
private external fun runThreadLocalDestructors()

@GCUnsafeCall("deinitRuntimeIfNeeded")
private external fun deinitializeRuntime()

@GCUnsafeCall("fast_handoff_exit_current")
private external fun exitCurrent(): Nothing

internal class NativeTask private constructor(
    private var handle: ULong,
    private val stack: AutoCloseable?,
    private val runtime: Runtime?,
) : AutoCloseable {
    private val lock = IrqSpinLock()
    private var exitedCpuTime = 0uL

    inline fun <T> access(action: (ULong) -> T): T = lock.withLock { action(handle) }

    val hasExited: Boolean
        get() = access { it == 0uL || taskHasExited(it) }

    val cpuTimeNanos: ULong
        get() = access { if (it == 0uL) exitedCpuTime else taskCpuTime(it) }

    fun affinity(): ByteArray? = access {
        if (it == 0uL) return@access null
        val task = it.toPointer<bridge.fast_task_t>()!!.pointed
        task.affinity?.readBytes(task.affinity_size.toInt()) ?: CpuAffinity.online()
    }

    fun setAffinity(mask: ByteArray): Boolean {
        val replacement = nativeHeap.allocArray<UByteVar>(mask.size)
        mask.forEachIndexed { index, byte -> replacement[index] = byte.toUByte() }
        val accepted = access {
            if (it == 0uL) return@access false
            val previous = it.toPointer<bridge.fast_task_t>()!!.pointed.affinity
            bridge.fast_handoff_set_affinity(it, replacement, mask.size.toULong())
            if (previous != null) nativeHeap.free(previous)
            true
        }
        if (!accepted) {
            nativeHeap.free(replacement)
            return false
        }
        while (!access { it == 0uL || bridge.fast_handoff_affinity_settled(it) }) {
            Scheduler.yieldCurrent()
        }
        return true
    }

    fun resetUserXstate() = access { handle ->
        val task = handle.toPointer<bridge.fast_task_t>()!!.pointed
        val frame = (task.kernel_rsp - sizeOf<bridge.kernel_entry_frame_t>().toULong())
            .toPointer<bridge.kernel_entry_frame_t>()!!.pointed
        val destination = frame.xstate.ptr.toLong().toULong()
        val initial = bridge.initial_xstate.ptr.toLong().toULong()
        copyMemory(destination, initial, sizeOf<bridge.xstate_t>().toULong())
    }

    fun initializeUser(
        entry: ULong,
        rsp: ULong,
        fsBase: ULong,
        registers: ULongArray? = null,
    ) = access { handle ->
        val task = handle.toPointer<bridge.fast_task_t>()!!.pointed
        val frameSize = sizeOf<bridge.kernel_entry_frame_t>().toULong()
        val frameMask = (alignOf<bridge.kernel_entry_frame_t>().toULong() - 1uL).inv()
        val frameAddress = (task.kernel_rsp - frameSize) and frameMask
        val frame = frameAddress.toPointer<bridge.kernel_entry_frame_t>()!!.pointed
        val contextSize = sizeOf<bridge.switch_frame_t>().toULong()
        val contextAddress = frameAddress - contextSize
        val context = contextAddress.toPointer<bridge.switch_frame_t>()!!.pointed
        clearMemory(contextAddress, 0, contextSize + frameSize)
        val regs = frame.regs
        var xstate = bridge.initial_xstate.ptr.toLong().toULong()
        if (registers == null) {
            regs.rip = entry
            regs.ds = 0x1buL
            regs.es = 0x1buL
            regs.cs = 0x23uL
            regs.ss = 0x1buL
            regs.rflags = 0x202uL
        } else {
            val registerAddress = regs.ptr.toLong().toULong()
            val registerSize = sizeOf<bridge.pt_regs_t>().toULong()
            registers.usePinned {
                val source = it.addressOf(0).toLong().toULong()
                copyMemory(registerAddress, source, registerSize)
            }
            val parentHandle = bridge.fast_handoff_current_task_handle()
            val parent = parentHandle.toPointer<bridge.fast_task_t>()!!.pointed
            val parentFrameAddress = parent.kernel_rsp - frameSize
            val parentFrame = parentFrameAddress.toPointer<bridge.kernel_entry_frame_t>()!!
            xstate = parentFrame.pointed.xstate.ptr.toLong().toULong()
        }
        val xstateAddress = frame.xstate.ptr.toLong().toULong()
        copyMemory(xstateAddress, xstate, sizeOf<bridge.xstate_t>().toULong())
        regs.rax = 0uL
        regs.func = 0uL
        regs.errcode = 0uL
        regs.rsp = rsp
        regs.fs_base = fsBase
        context.rip = bridge.user_task_entry.toLong().toULong()
        task.rsp = contextAddress
    }

    fun exit(): Nothing {
        runThreadLocalDestructors()
        bridge.set_runtime_use_mask(false)
        bridge.irq_save()
        deinitializeRuntime()
        exitCurrent()
    }

    override fun close() {
        val context = lock.withLock {
            val task = handle
            if (task == 0uL) return
            exitedCpuTime = taskCpuTime(task)
            val context = task.toPointer<bridge.fast_task_t>()!!
            check(context.pointed.cpu == null || taskHasExited(task)) { "Cannot release a scheduled task" }
            handle = 0uL
            context
        }
        context.pointed.affinity?.let { nativeHeap.free(it) }
        bridge.free(context)
        stack?.close()
        runtime?.close()
    }

    internal fun reapRuntime(): Boolean {
        if (!bridge.runtime_tls_reclaimable(checkNotNull(runtime).fsBase)) return false
        close()
        return true
    }

    private class RuntimeStack(private val tcb: ULong) : AutoCloseable {
        override fun close() {
            val owner = (tcb + TcbLayout.SIZE).toPointer<bridge.runtime_tls_t>()!!.pointed
            if (owner.owns_stack == 0.toUByte()) return
            val address = (tcb + TcbLayout.stackAddr).toPointer<ULongVar>()!!.pointed.value
            val size = (tcb + TcbLayout.stackSize).toPointer<ULongVar>()!!.pointed.value
            check(bridge.munmap(address.toPointer<ULongVar>(), size) == 0)
        }
    }

    private class KernelStack private constructor(
        private val physicalBase: ULong,
        private val pages: ULong,
    ) : AutoCloseable {
        val top: ULong
            get() = Hhdm.toVirtual(physicalBase + pages * PAGE_SIZE_BYTES)

        override fun close() {
            check(BuddyFrameAllocator.free(physicalBase, pages))
        }

        companion object {
            fun allocate(pages: ULong): KernelStack? {
                val physicalBase = BuddyFrameAllocator.allocate(pages)
                return if (physicalBase == INVALID_FRAME) null else KernelStack(physicalBase, pages)
            }
        }
    }

    private class Runtime(val fsBase: ULong) : AutoCloseable {
        override fun close() = bridge.runtime_tls_destroy(fsBase)

        companion object {
            fun allocate(): Runtime? = bridge.__rtld_allocateTcb()?.let { Runtime(it.toLong().toULong()) }
        }
    }

    companion object {
        internal fun adoptRuntime(handle: ULong): NativeTask {
            val tcb = handle.toPointer<bridge.fast_task_t>()!!.pointed.kernel_fs_base
            val stack = RuntimeStack(tcb)
            val runtime = Runtime(tcb)
            return NativeTask(handle, stack, runtime)
        }

        fun allocate(id: Int, cr3: ULong, stackPages: ULong = 0uL, nice: Int = 0): NativeTask? {
            val stack = if (stackPages == 0uL) null else KernelStack.allocate(stackPages) ?: return null
            var runtime: Runtime? = null
            var handle = 0uL
            try {
                if (stack != null) runtime = Runtime.allocate() ?: return null
                val stackTop = stack?.top ?: 0uL
                val fsBase = runtime?.fsBase ?: 0uL
                val policy = Scheduler.policy
                val weight = policy.weight(nice)
                handle = bridge.fast_handoff_create_task(
                    id.toUInt(), cr3, stackTop, fsBase, policy.quantumCycles, weight,
                )
                if (handle == 0uL) return null
                return NativeTask(handle, stack, runtime)
            } finally {
                if (handle == 0uL) {
                    runtime?.close()
                    stack?.close()
                }
            }
        }
    }
}
