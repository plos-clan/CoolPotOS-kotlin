package org.plos_clan.cpos

private const val MSR_EFER = 0xC0000080 // EFER MSR寄存器
private const val MSR_STAR = 0xC0000081 // STAR MSR寄存器
private const val MSR_LSTAR = 0xC0000082 // LSTAR MSR寄存器
private const val MSR_SYSCALL_MASK = 0xC0000084

object Syscall {
    fun initialize() {
        
    }
}
