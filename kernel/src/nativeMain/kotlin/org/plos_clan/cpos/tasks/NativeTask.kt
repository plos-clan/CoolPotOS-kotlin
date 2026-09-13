@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
@file:OptIn(ExperimentalForeignApi::class, InternalForKotlinNative::class)

package org.plos_clan.cpos.tasks

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.INVALID_FRAME
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.IrqSpinLock
import org.plos_clan.cpos.utils.toPointer
import kotlin.native.internal.GCUnsafeCall
import kotlin.native.internal.InternalForKotlinNative

@GCUnsafeCall("calloc")
private external fun allocateZeroed(count: ULong, size: ULong): ULong

@GCUnsafeCall("memcpy")
private external fun copyMemory(destination: ULong, source: ULong, size: ULong): ULong

@GCUnsafeCall("kernel_tls_template")
private external fun kernelTlsTemplate(): ULong

@GCUnsafeCall("allocate_runtime_tid")
private external fun allocateRuntimeTid(): ULong

@GCUnsafeCall("fast_handoff_task_cpu_time")
private external fun taskCpuTime(task: ULong): ULong

@GCUnsafeCall("fast_handoff_task_has_exited")
private external fun taskHasExited(task: ULong): Boolean

@GCUnsafeCall("fast_handoff_destroy_task")
private external fun destroyTask(task: ULong): Boolean

@GCUnsafeCall("_ZN5mlibc28run_thread_local_destructorsEv")
private external fun runThreadLocalDestructors()

@GCUnsafeCall("deinitRuntimeIfNeeded")
private external fun deinitializeRuntime()

@GCUnsafeCall("fast_handoff_set_task_state")
private external fun markNativeTaskExited(task: ULong, state: UByte)

@GCUnsafeCall("fast_handoff_yield")
private external fun yieldFromExitedTask(): Boolean

@GCUnsafeCall("fast_handoff_idle")
private external fun continueScheduling(): Nothing

internal class NativeTask private constructor(
    private var handle: ULong,
    private val stack: KernelStack?,
    private val runtime: Runtime?,
) : AutoCloseable {
    private val lock = IrqSpinLock()
    private var exitedCpuTime = 0uL

    inline fun <T> access(action: (ULong) -> T): T = lock.withLock { action(handle) }

    val hasExited: Boolean
        get() = access { it == 0uL || taskHasExited(it) }

    val cpuTimeNanos: ULong
        get() = access { if (it == 0uL) exitedCpuTime else taskCpuTime(it) }

    fun exit(): Nothing {
        val task = access { it }
        val zombie = TaskState.ZOMBIE.ordinal.toUByte()
        runThreadLocalDestructors()
        bridge.set_runtime_use_mask(false)
        bridge.irq_save()
        deinitializeRuntime()
        markNativeTaskExited(task, zombie)
        yieldFromExitedTask()
        continueScheduling()
    }

    override fun close() {
        lock.withLock {
            val task = handle
            if (task == 0uL) return
            exitedCpuTime = taskCpuTime(task)
            check(destroyTask(task)) { "Cannot release a scheduled task" }
            handle = 0uL
        }
        runtime?.close()
        stack?.close()
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

    private class Runtime(private val allocation: ULong, val fsBase: ULong) : AutoCloseable {
        override fun close() = bridge.free(allocation.toPointer<ULongVar>())

        companion object {
            fun allocate(): Runtime? {
                val template = kernelTlsTemplate().toPointer<ULongVar>()!!
                val tlsAlign = template[3].coerceAtLeast(1uL)
                val tlsSize = (template[2] + tlsAlign - 1uL) and (tlsAlign - 1uL).inv()
                val alignment = maxOf(tlsAlign, TcbLayout.ALIGN, LocalKeysLayout.ALIGN)
                val keysOffset = (TcbLayout.SIZE + LocalKeysLayout.ALIGN - 1uL) and
                    (LocalKeysLayout.ALIGN - 1uL).inv()
                val dtvOffset = keysOffset + LocalKeysLayout.SIZE
                val allocation = allocateZeroed(1uL, tlsSize + alignment - 1uL + dtvOffset + ULong.SIZE_BYTES.toULong())
                if (allocation == 0uL) return null
                val tcb = (allocation + tlsSize + alignment - 1uL) and (alignment - 1uL).inv()
                copyMemory(tcb - tlsSize, template[0], template[1])
                (tcb + TcbLayout.selfPointer).toPointer<ULongVar>()!![0] = tcb
                (tcb + TcbLayout.dtvSize).toPointer<ULongVar>()!![0] = if (tlsSize == 0uL) 0uL else 1uL
                (tcb + TcbLayout.dtvPointers).toPointer<ULongVar>()!![0] = tcb + dtvOffset
                (tcb + dtvOffset).toPointer<ULongVar>()!![0] = tcb - tlsSize
                (tcb + TcbLayout.localKeys).toPointer<ULongVar>()!![0] = tcb + keysOffset
                (tcb + TcbLayout.tid).toPointer<IntVar>()!![0] = allocateRuntimeTid().toInt()
                val currentTcb = bridge.rdmsr(0xc0000100u)
                (tcb + TcbLayout.stackCanary).toPointer<ULongVar>()!![0] =
                    (currentTcb + TcbLayout.stackCanary).toPointer<ULongVar>()!![0]
                return Runtime(allocation, tcb)
            }
        }
    }

    companion object {
        fun allocate(id: Int, cr3: ULong, stackPages: ULong = 0uL): NativeTask? {
            val stack = if (stackPages == 0uL) null else KernelStack.allocate(stackPages) ?: return null
            var runtime: Runtime? = null
            var handle = 0uL
            try {
                if (stack != null) runtime = Runtime.allocate() ?: return null
                handle = bridge.fast_handoff_create_task(
                    id.toUInt(), cr3, stack?.top ?: 0uL, runtime?.fsBase ?: 0uL, Scheduler.policy.quantumCycles,
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
