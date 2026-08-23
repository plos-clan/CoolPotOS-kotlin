@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.module

import org.plos_clan.cpos.mem.UserMemory
import org.plos_clan.cpos.mem.addressspace.AddressSpace
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.addressspace.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.addressspace.MemoryRegion
import org.plos_clan.cpos.mem.addressspace.MemoryRegionType
import org.plos_clan.cpos.mem.page.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.module.elf.ElfInterpreterLoadResult
import org.plos_clan.cpos.module.elf.ElfLoadResult
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.KernelRandom
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.isPageAligned

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
private const val AT_SYSINFO_EHDR = 33uL

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
        systemInfoHeader: ULong,
        stackTop: ULong = DEFAULT_USER_STACK_TOP,
        stackSize: ULong = DEFAULT_USER_STACK_SIZE,
        randomBytes: ByteArray? = null,
        addressSpace: AddressSpace = process.addressSpace,
    ): UserStackResult? {
        if (!validateStackRange(stackTop, stackSize)) {
            println("UserStack: invalid stack range")
            return null
        }
        if (process.isKernelProcess) {
            println("UserStack: kernel process cannot own a user stack")
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
        if (!addressSpace.insert(
                MemoryRegion(
                    start = stackStart,
                    end = stackTop,
                    access = MEMORY_REGION_READABLE or MEMORY_REGION_WRITABLE,
                    name = "[stack]",
                    type = MemoryRegionType.STACK,
                ),
            )
        ) {
            println("UserStack: stack range overlaps an existing mapping")
            return null
        }

        val writer = StackWriter(addressSpace, stackStart, stackTop)
        val execfnAddress = writer.pushCString(executablePath)
        val argumentAddresses = arguments.map { writer.pushCString(it) }
        val environmentAddresses = environment.map { writer.pushCString(it) }
        if (execfnAddress == null ||
            argumentAddresses.any { it == null } ||
            environmentAddresses.any { it == null }
        ) {
            println("UserStack: strings do not fit in the stack")
            rollback(addressSpace, stackStart, stackSize)
            return null
        }

        val random = randomBytes ?: KernelRandom.bytes(
            AUX_RANDOM_SIZE,
            salt = stackTop xor process.id.toULong(),
        )
        if (random.size != AUX_RANDOM_SIZE) {
            println("UserStack: AT_RANDOM must contain exactly $AUX_RANDOM_SIZE bytes")
            rollback(addressSpace, stackStart, stackSize)
            return null
        }
        val randomAddress = writer.push(random) ?: run {
            println("UserStack: random data does not fit in the stack")
            rollback(addressSpace, stackStart, stackSize)
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
        auxiliary(AT_UID, process.ruid.toUInt().toULong())
        auxiliary(AT_EUID, process.euid.toUInt().toULong())
        auxiliary(AT_GID, process.rgid.toUInt().toULong())
        auxiliary(AT_EGID, process.egid.toUInt().toULong())
        auxiliary(AT_SECURE, 0uL)
        auxiliary(AT_RANDOM, randomAddress)
        auxiliary(AT_EXECFN, execfnAddress)
        auxiliary(AT_SYSINFO_EHDR, systemInfoHeader)
        auxiliary(AT_NULL, 0uL)

        val stackPointer = writer.pushAlignedWords(words) ?: run {
            println("UserStack: initial process vector does not fit in the stack")
            rollback(addressSpace, stackStart, stackSize)
            return null
        }
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

    private fun rollback(addressSpace: AddressSpace, start: ULong, length: ULong) {
        addressSpace.unmap(start, length)
    }
}

private class StackWriter(
    private val addressSpace: AddressSpace,
    private val bottom: ULong,
    top: ULong,
) {
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
        if (!UserMemory(addressSpace, address).copyToUser(bytes)) {
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
        if (address < bottom || !UserMemory(addressSpace, address).copyToUser(bytes)) {
            return null
        }
        cursor = address
        return address
    }
}
