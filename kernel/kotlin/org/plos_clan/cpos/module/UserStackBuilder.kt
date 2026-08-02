@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.module

import org.plos_clan.cpos.drivers.Hpet
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.MemChunk
import org.plos_clan.cpos.mem.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.VMA_READ
import org.plos_clan.cpos.mem.VMA_WRITE
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.isPageAligned
import kotlinx.cinterop.UByteVar
import platform.posix.memset

const val DEFAULT_USER_STACK_SIZE = 0x0080_0000uL
const val DEFAULT_USER_STACK_TOP = 0x0000_7fff_ffff_f000uL

private const val INITIAL_STACK_ALIGNMENT = 16uL
private const val AUX_RANDOM_SIZE = 16

private const val AT_NULL = 0uL
private const val AT_PHDR = 3uL
private const val AT_PHENT = 4uL
private const val AT_PHNUM = 5uL
private const val AT_PAGESZ = 6uL
private const val AT_BASE = 7uL
private const val AT_ENTRY = 9uL
private const val AT_UID = 11uL
private const val AT_EUID = 12uL
private const val AT_GID = 13uL
private const val AT_EGID = 14uL
private const val AT_SECURE = 23uL
private const val AT_RANDOM = 25uL
private const val AT_EXECFN = 31uL

data class UserStackResult(
    val stackPointer: ULong,
    val stackStart: ULong,
    val stackTop: ULong,
    val guardPage: ULong,
)

object UserStackBuilder {
    fun build(
        process: Process,
        arguments: List<String>,
        environment: List<String>,
        executablePath: String,
        executable: ElfLoadResult,
        interpreter: ElfInterpreterLoadResult? = null,
        stackTop: ULong = DEFAULT_USER_STACK_TOP,
        stackSize: ULong = DEFAULT_USER_STACK_SIZE,
        randomBytes: ByteArray? = null,
    ): UserStackResult? {
        if (!validateStackRange(stackTop, stackSize)) {
            println("UserStack: invalid stack range")
            return null
        }
        if (process.isKernelProcess) {
            println("UserStack: kernel process cannot own a user stack")
            return null
        }
        if (!Hhdm.isReady) {
            println("UserStack: HHDM is not initialized")
            return null
        }
        if (executable.programHeaderAddress == null) {
            println("UserStack: executable program headers are not mapped")
            return null
        }
        if (!validCString(executablePath) ||
            arguments.any { !validCString(it) } ||
            environment.any { !validCString(it) }
        ) {
            println("UserStack: argument or environment contains a null byte")
            return null
        }

        val stackStart = stackTop - stackSize
        val guardPage = stackStart - PAGE_SIZE_BYTES
        val directory = process.vma.pageDirectory
        var address = guardPage
        while (address < stackTop) {
            if (directory.resolveUserPhysicalAddress(address, false) != null) {
                println("UserStack: stack range overlaps an existing mapping")
                return null
            }
            address += PAGE_SIZE_BYTES
        }

        val mappedPages = mutableListOf<ULong>()
        val allocatedFrames = mutableListOf<ULong>()
        address = stackStart
        while (address < stackTop) {
            val physicalAddress = BuddyFrameAllocator.allocateFrames(1uL) ?: run {
                println("UserStack: cannot allocate stack page")
                rollback(process, mappedPages, allocatedFrames)
                return null
            }
            val destination = Hhdm.toVirtualPointer<UByteVar>(physicalAddress) ?: run {
                BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
                rollback(process, mappedPages, allocatedFrames)
                println("UserStack: cannot access stack page through HHDM")
                return null
            }
            memset(destination, 0, PAGE_SIZE_BYTES)
            allocatedFrames += physicalAddress
            if (!directory.mapUserPage(
                    virtualAddress = address,
                    physicalAddress = physicalAddress,
                    writable = true,
                    executable = false,
                )
            ) {
                println("UserStack: cannot map stack page")
                rollback(process, mappedPages, allocatedFrames)
                return null
            }
            mappedPages += address
            address += PAGE_SIZE_BYTES
        }

        val writer = StackWriter(process, stackStart, stackTop)
        val execfnAddress = writer.pushCString(executablePath)
        val argumentAddresses = arguments.map { writer.pushCString(it) }
        val environmentAddresses = environment.map { writer.pushCString(it) }
        if (execfnAddress == null ||
            argumentAddresses.any { it == null } ||
            environmentAddresses.any { it == null }
        ) {
            println("UserStack: strings do not fit in the stack")
            rollback(process, mappedPages, allocatedFrames)
            return null
        }

        val random = randomBytes ?: createRandomBytes(process, stackTop)
        if (random.size != AUX_RANDOM_SIZE) {
            println("UserStack: AT_RANDOM must contain exactly $AUX_RANDOM_SIZE bytes")
            rollback(process, mappedPages, allocatedFrames)
            return null
        }
        val randomAddress = writer.push(random) ?: run {
            println("UserStack: random data does not fit in the stack")
            rollback(process, mappedPages, allocatedFrames)
            return null
        }

        val words = mutableListOf<ULong>()
        words += arguments.size.toULong()
        argumentAddresses.forEach { words += requireNotNull(it) }
        words += 0uL
        environmentAddresses.forEach { words += requireNotNull(it) }
        words += 0uL

        fun auxiliary(type: ULong, value: ULong) {
            words += type
            words += value
        }

        auxiliary(AT_PHDR, executable.programHeaderAddress)
        auxiliary(AT_PHENT, executable.programHeaderEntrySize.toULong())
        auxiliary(AT_PHNUM, executable.programHeaderCount.toULong())
        auxiliary(AT_PAGESZ, PAGE_SIZE_BYTES)
        interpreter?.let { auxiliary(AT_BASE, it.loadBias) }
        auxiliary(AT_ENTRY, executable.entryPoint)
        auxiliary(AT_UID, 0uL)
        auxiliary(AT_EUID, 0uL)
        auxiliary(AT_GID, 0uL)
        auxiliary(AT_EGID, 0uL)
        auxiliary(AT_SECURE, 0uL)
        auxiliary(AT_RANDOM, randomAddress)
        auxiliary(AT_EXECFN, execfnAddress)
        auxiliary(AT_NULL, 0uL)

        val stackPointer = writer.pushAlignedWords(words) ?: run {
            println("UserStack: initial process vector does not fit in the stack")
            rollback(process, mappedPages, allocatedFrames)
            return null
        }
        process.vma.chunks += MemChunk(
            start = stackStart,
            end = stackTop,
            flags = VMA_READ or VMA_WRITE,
            name = "[stack]",
        )
        return UserStackResult(
            stackPointer = stackPointer,
            stackStart = stackStart,
            stackTop = stackTop,
            guardPage = guardPage,
        )
    }

    private fun validateStackRange(stackTop: ULong, stackSize: ULong): Boolean =
        stackTop.isPageAligned() &&
            stackSize.isPageAligned() &&
            stackSize != 0uL &&
            stackTop <= USER_VIRTUAL_ADDRESS_LIMIT &&
            stackSize < stackTop &&
            stackTop - stackSize >= PAGE_SIZE_BYTES

    private fun validCString(value: String): Boolean = '\u0000' !in value

    private fun rollback(
        process: Process,
        mappedPages: List<ULong>,
        allocatedFrames: List<ULong>,
    ) {
        mappedPages.asReversed().forEach(process.vma.pageDirectory::unmapPage)
        allocatedFrames.asReversed().forEach { physicalAddress ->
            BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
        }
    }
}

private class StackWriter(
    process: Process,
    private val bottom: ULong,
    top: ULong,
) {
    private val directory = process.vma.pageDirectory
    private var cursor = top

    fun pushCString(value: String): ULong? {
        val encoded = value.encodeToByteArray()
        return push(ByteArray(encoded.size + 1).also { bytes ->
            encoded.copyInto(bytes)
        })
    }

    fun push(bytes: ByteArray): ULong? {
        val size = bytes.size.toULong()
        if (size > cursor - bottom) {
            return null
        }
        val address = cursor - size
        if (!UserMemory(directory, address).copyToUser(bytes)) {
            return null
        }
        cursor = address
        return address
    }

    fun pushAlignedWords(words: List<ULong>): ULong? {
        if (words.size > Int.MAX_VALUE / ULong.SIZE_BYTES) {
            return null
        }
        val bytes = ByteArray(words.size * ULong.SIZE_BYTES)
        words.forEachIndexed { wordIndex, word ->
            repeat(ULong.SIZE_BYTES) { byteIndex ->
                bytes[wordIndex * ULong.SIZE_BYTES + byteIndex] =
                    (word shr (byteIndex * Byte.SIZE_BITS)).toByte()
            }
        }
        if (bytes.size.toULong() > cursor - bottom) {
            return null
        }
        val address = (cursor - bytes.size.toULong()).alignDown(INITIAL_STACK_ALIGNMENT)
        if (address < bottom || !UserMemory(directory, address).copyToUser(bytes)) {
            return null
        }
        cursor = address
        return address
    }
}

private fun createRandomBytes(process: Process, stackTop: ULong): ByteArray {
    var state = Hpet.nanoTime() xor stackTop xor
        (process.id.toULong() * 0x9e37_79b9_7f4a_7c15uL)
    if (state == 0uL) {
        state = 0xa5a5_5a5a_c3c3_3c3cuL
    }
    return ByteArray(AUX_RANDOM_SIZE) {
        state = state xor (state shl 13)
        state = state xor (state shr 7)
        state = state xor (state shl 17)
        state.toByte()
    }
}
