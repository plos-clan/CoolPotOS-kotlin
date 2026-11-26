@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.syscall

import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryMapResult
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.USER_MMAP_START
import org.plos_clan.cpos.syscall.fs.poll
import org.plos_clan.cpos.syscall.fs.pselect6
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.Errno
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.PollEvents
import org.plos_clan.cpos.utils.PtraceRegisters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PollAbiTest {
    @Test
    fun onlyReturnedEventsRequireWriteAccess() = memScoped {
        val process = ProcessManager.createUserProcess("poll-abi")
        try {
            val space = process.addressSpace
            val start = USER_MMAP_START
            val access = MEMORY_REGION_READABLE or MEMORY_REGION_WRITABLE
            val region = MemoryRegion(start, start + 2uL * PAGE_SIZE_BYTES, access, null)
            assertTrue(space.insert(region))
            val entry = UserMemory(space, start + PAGE_SIZE_BYTES - 6uL)
            val invalid = Int.MAX_VALUE.toUInt()
            assertTrue(entry.writeUInt(invalid))
            assertTrue(entry.writeUInt(0xffff0001u, 4))
            assertTrue(entry.writeULong(ULong.MAX_VALUE, 8))
            assertIs<MemoryMapResult.Ok<Unit>>(space.protect(start, PAGE_SIZE_BYTES, MEMORY_REGION_READABLE))
            val frame = allocArray<ULongVar>(PtraceRegisters.REGISTER_COUNT)
            val regs = PtraceRegisters(frame)
            regs[PtraceRegisters.IDX_RDI] = entry.address
            regs[PtraceRegisters.IDX_RSI] = 2uL
            regs[PtraceRegisters.IDX_RDX] = 0uL
            assertEquals(1L, poll(regs, process))
            assertEquals(invalid, entry.readUIntLE())
            val reported = (PollEvents.POLLNVAL.toUInt() shl 16) or 1u
            assertEquals(reported, entry.readUIntLE(4))
            assertEquals(0x0000ffffffffffffuL, entry.readULongLE(8))
            regs[PtraceRegisters.IDX_RDI] = 0uL
            assertEquals(-Errno.EFAULT.toLong(), poll(regs, process))
            regs[PtraceRegisters.IDX_RSI] = 0uL
            assertEquals(0L, poll(regs, process))
        } finally {
            assertTrue(ProcessManager.discardUserProcess(process))
        }
    }

    @Test
    fun pselectIgnoresPaddingButRejectsMissingDescriptorsInTheLastWord() = memScoped {
        val process = ProcessManager.createUserProcess("pselect-abi")
        try {
            val space = process.addressSpace
            val start = USER_MMAP_START
            val access = MEMORY_REGION_READABLE or MEMORY_REGION_WRITABLE
            val region = MemoryRegion(start, start + PAGE_SIZE_BYTES, access, null)
            assertTrue(space.insert(region))
            val set = UserMemory(space, start)
            val frame = allocArray<ULongVar>(PtraceRegisters.REGISTER_COUNT)
            val regs = PtraceRegisters(frame)
            regs[PtraceRegisters.IDX_RDI] = 65uL
            regs[PtraceRegisters.IDX_RSI] = start
            regs[PtraceRegisters.IDX_RDX] = 0uL
            regs[PtraceRegisters.IDX_R10] = 0uL
            regs[PtraceRegisters.IDX_R8] = start + 16uL
            regs[PtraceRegisters.IDX_R9] = 0uL
            assertTrue(set.writeULong(1uL.inv(), ULong.SIZE_BYTES))
            assertEquals(0L, pselect6(regs, process))
            assertEquals(0uL, set.readULongLE(ULong.SIZE_BYTES))
            assertTrue(set.writeULong(1uL, ULong.SIZE_BYTES))
            assertEquals(-Errno.EBADF.toLong(), pselect6(regs, process))
            assertEquals(1uL, set.readULongLE(ULong.SIZE_BYTES))
        } finally {
            assertTrue(ProcessManager.discardUserProcess(process))
        }
    }
}
