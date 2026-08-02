@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.experimental.ExperimentalNativeApi

private const val MSR_EFER = 0xC0000080U // EFER MSR寄存器
private const val MSR_STAR = 0xC0000081U // STAR MSR寄存器
private const val MSR_LSTAR = 0xC0000082U // LSTAR MSR寄存器
private const val MSR_SYSCALL_MASK = 0xC0000084U
private const val EFER_SYSCALL_ENABLE = 1uL
private const val SYSCALL_RFLAGS_MASK = 0x47700uL
private const val KERNEL_CODE_SELECTOR = 0x08uL
private const val USER_DATA_SELECTOR = 0x1buL
private const val ENOSYS = 38uL

@ExperimentalNativeApi
@ExperimentalForeignApi
@Suppress("unused")
@CName("syscall_handler")
fun syscallHandler(frame: COpaquePointer?) {
    Syscall.syscallHandle(PtraceRegisters(requireNotNull(frame).reinterpret()))
}

object Syscall {
    fun syscallHandle(regs: PtraceRegisters) {
        regs[PtraceRegisters.IDX_RAX] = 0uL - ENOSYS
    }

    fun initialize(lapicId: ULong, isBsp: Boolean) {
        val syscallUserBase = USER_DATA_SELECTOR - 8uL
        val star = (syscallUserBase shl 48) or (KERNEL_CODE_SELECTOR shl 32)

        bridge.setup_syscall_cpu(lapicId, if (isBsp) 1u else 0u)
        bridge.wrmsr(MSR_EFER, bridge.rdmsr(MSR_EFER) or EFER_SYSCALL_ENABLE)
        bridge.wrmsr(MSR_STAR, star)
        bridge.wrmsr(MSR_LSTAR, bridge.get_asm_syscall_handle_address())
        bridge.wrmsr(MSR_SYSCALL_MASK, SYSCALL_RFLAGS_MASK)
    }
}
