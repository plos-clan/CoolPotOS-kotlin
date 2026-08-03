@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.module

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.plos_clan.cpos.fs.AccessMode
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.mem.BuddyFrameAllocator
import org.plos_clan.cpos.mem.Hhdm
import org.plos_clan.cpos.mem.MemChunk
import org.plos_clan.cpos.mem.PageDirectory
import org.plos_clan.cpos.mem.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.mem.VMA_EXEC
import org.plos_clan.cpos.mem.VMA_READ
import org.plos_clan.cpos.mem.VMA_WRITE
import org.plos_clan.cpos.mem.VmaType
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp
import platform.posix.memcpy
import platform.posix.memset

private const val ELF64_HEADER_SIZE = 64
private const val ELF64_PROGRAM_HEADER_SIZE = 56
private const val ELF_CLASS_64 = 2u
private const val ELF_DATA_LITTLE_ENDIAN = 1u
private const val ELF_VERSION_CURRENT = 1u
private const val ELF_MACHINE_X86_64 = 62u
private const val ELF_TYPE_EXECUTABLE = 2u
private const val ELF_TYPE_SHARED_OBJECT = 3u

private const val PROGRAM_TYPE_LOAD = 1u
private const val PROGRAM_TYPE_INTERPRETER = 3u
private const val PROGRAM_TYPE_PHDR = 6u
private const val PROGRAM_FLAG_EXECUTABLE = 0x1u
private const val PROGRAM_FLAG_WRITABLE = 0x2u
private const val PROGRAM_FLAG_READABLE = 0x4u

const val DEFAULT_INTERPRETER_LOAD_BIAS = 0x0000_1000_0000_0000uL

enum class ElfObjectType {
    EXECUTABLE,
    DYNAMIC,
}

data class ElfImageInfo(
    val type: ElfObjectType,
    val entryPoint: ULong,
    val programHeaderOffset: ULong,
    val programHeaderEntrySize: UShort,
    val programHeaderCount: UShort,
    val interpreterPath: String?,
) {
    val isPositionIndependent: Boolean
        get() = type == ElfObjectType.DYNAMIC

    val requiresInterpreter: Boolean
        get() = interpreterPath != null
}

data class ElfLoadResult(
    val entryPoint: ULong,
    val loadStart: ULong,
    val loadSize: ULong,
    val programHeaderAddress: ULong?,
    val programHeaderEntrySize: UShort,
    val programHeaderCount: UShort,
    val requiresInterpreter: Boolean,
)

data class ElfInterpreterLoadResult(
    val path: String,
    val loadBias: ULong,
    val image: ElfLoadResult,
) {
    val entryPoint: ULong
        get() = image.entryPoint
}

object ElfLoader {
    fun inspect(data: ByteArray): ElfImageInfo? {
        val image = parseImage(data, reportErrors = true) ?: return null
        return inspectImage(data, image, reportErrors = true)
    }

    fun isDynamic(data: ByteArray): Boolean =
        parseImage(data, reportErrors = false)?.let { image ->
            inspectImage(data, image, reportErrors = false)?.requiresInterpreter
        } ?: false

    fun loadInterpreterElf(
        executableData: ByteArray,
        directory: PageDirectory,
        offset: ULong = DEFAULT_INTERPRETER_LOAD_BIAS,
        process: Process? = null,
    ): ElfInterpreterLoadResult? {
        val executableInfo = inspect(executableData) ?: return null
        val path = executableInfo.interpreterPath ?: run {
            println("ELF: executable does not request an interpreter")
            return null
        }
        val interpreterData = readFile(path) ?: return null
        val interpreterInfo = inspect(interpreterData) ?: run {
            println("ELF: interpreter $path is not a valid ELF64 image")
            return null
        }
        if (interpreterInfo.type != ElfObjectType.DYNAMIC) {
            println("ELF: interpreter $path is not an ET_DYN image")
            return null
        }

        val loaded = loadExecutorElf(
            data = interpreterData,
            directory = directory,
            offset = offset,
            process = process,
        ) ?: return null
        return ElfInterpreterLoadResult(
            path = path,
            loadBias = offset,
            image = loaded,
        )
    }

    fun loadExecutorElf(
        data: ByteArray,
        directory: PageDirectory,
        offset: ULong = 0uL,
        process: Process? = null,
    ): ElfLoadResult? {
        if (process != null &&
            process.vma.pageDirectory.pml4PhysicalAddress != directory.pml4PhysicalAddress
        ) {
            println("ELF: process and target page directory do not match")
            return null
        }
        if (!Hhdm.isReady) {
            println("ELF: HHDM is not initialized")
            return null
        }

        val image = parseImage(data, reportErrors = true) ?: return null
        val imageInfo = inspectImage(data, image, reportErrors = true) ?: return null

        val segments = mutableListOf<LoadSegment>()
        for (programHeader in image.programHeaders) {
            if (programHeader.type != PROGRAM_TYPE_LOAD || programHeader.memorySize == 0uL) {
                continue
            }
            val segment = validateLoadSegment(data.size, programHeader, offset) ?: return null
            segments += segment
        }
        if (segments.isEmpty()) {
            println("ELF: image has no loadable segment")
            return null
        }

        val entryPoint = checkedAdd(image.header.entryPoint, offset) ?: run {
            println("ELF: entry point overflows")
            return null
        }
        if (entryPoint >= USER_VIRTUAL_ADDRESS_LIMIT ||
            segments.none {
                it.executable && entryPoint >= it.start && entryPoint < it.end
            }
        ) {
            println("ELF: entry point is outside executable segments")
            return null
        }

        val pages = planPages(segments)
        if (pages.keys.any { directory.resolveUserPhysicalAddress(it, false) != null }) {
            println("ELF: loadable segment overlaps an existing user mapping")
            return null
        }

        val allocatedFrames = mutableListOf<ULong>()
        val mappedPages = mutableListOf<ULong>()
        for ((virtualAddress, page) in pages) {
            val physicalAddress = BuddyFrameAllocator.allocateFrames(1uL) ?: run {
                println("ELF: cannot allocate a segment page")
                rollback(directory, mappedPages, allocatedFrames)
                return null
            }
            val destination = Hhdm.toVirtualPointer<UByteVar>(physicalAddress) ?: run {
                BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
                rollback(directory, mappedPages, allocatedFrames)
                println("ELF: cannot access a segment page through HHDM")
                return null
            }
            memset(destination, 0, PAGE_SIZE_BYTES)
            page.physicalAddress = physicalAddress
            allocatedFrames += physicalAddress

            if (!directory.mapUserPage(
                    virtualAddress = virtualAddress,
                    physicalAddress = physicalAddress,
                    writable = page.writable,
                    executable = page.executable,
                )
            ) {
                println("ELF: cannot map segment page at $virtualAddress")
                rollback(directory, mappedPages, allocatedFrames)
                return null
            }
            mappedPages += virtualAddress
        }

        for (segment in segments) {
            if (!copySegmentData(data, segment, pages) || !zeroSegmentBss(segment, pages)) {
                println("ELF: cannot populate a loadable segment")
                rollback(directory, mappedPages, allocatedFrames)
                return null
            }
        }

        if (process != null && !process.vma.insertAll(pageChunks(pages, process.name))) {
            println("ELF: cannot record loadable segments in the process VMA")
            rollback(directory, mappedPages, allocatedFrames)
            return null
        }

        val loadStart = segments.minOf(LoadSegment::start)
        val loadEnd = segments.maxOf(LoadSegment::end)
        return ElfLoadResult(
            entryPoint = entryPoint,
            loadStart = loadStart,
            loadSize = loadEnd - loadStart,
            programHeaderAddress = programHeaderAddress(image, segments, offset),
            programHeaderEntrySize = image.header.programHeaderEntrySize,
            programHeaderCount = image.header.programHeaderCount,
            requiresInterpreter = imageInfo.requiresInterpreter,
        )
    }

    private fun inspectImage(
        data: ByteArray,
        image: ElfImage,
        reportErrors: Boolean,
    ): ElfImageInfo? {
        fun reject(message: String): ElfImageInfo? {
            if (reportErrors) {
                println("ELF: $message")
            }
            return null
        }

        val type = when (image.header.type.toUInt()) {
            ELF_TYPE_EXECUTABLE -> ElfObjectType.EXECUTABLE
            ELF_TYPE_SHARED_OBJECT -> ElfObjectType.DYNAMIC
            else -> return reject("unsupported object type ${image.header.type}")
        }
        val interpreterHeaders = image.programHeaders.filter {
            it.type == PROGRAM_TYPE_INTERPRETER
        }
        if (interpreterHeaders.size > 1) {
            return reject("image contains more than one PT_INTERP segment")
        }

        val interpreterPath = interpreterHeaders.firstOrNull()?.let { header ->
            if (header.fileSize < 2uL ||
                !fitsInImage(header.fileOffset, header.fileSize, data.size)
            ) {
                return reject("PT_INTERP data is truncated")
            }
            val start = header.fileOffset.toInt()
            val end = start + header.fileSize.toInt()
            if (data[end - 1] != 0.toByte()) {
                return reject("PT_INTERP path is not null-terminated")
            }
            val pathBytes = data.copyOfRange(start, end - 1)
            if (pathBytes.isEmpty() ||
                pathBytes[0] != '/'.code.toByte() ||
                pathBytes.any { byte -> byte == 0.toByte() }
            ) {
                return reject("PT_INTERP path is invalid")
            }
            pathBytes.decodeToString()
        }

        return ElfImageInfo(
            type = type,
            entryPoint = image.header.entryPoint,
            programHeaderOffset = image.header.programHeaderOffset,
            programHeaderEntrySize = image.header.programHeaderEntrySize,
            programHeaderCount = image.header.programHeaderCount,
            interpreterPath = interpreterPath,
        )
    }

    private fun readFile(path: String): ByteArray? {
        val context = FileSystemManager.kernelContext ?: run {
            println("ELF: VFS is not initialized")
            return null
        }
        val file = when (
            val result = FileSystemManager.vfs.open(
                context = context,
                pathname = VfsPathname.fromString(path),
                options = OpenOptions(access = AccessMode.READ),
            )
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                println("ELF: cannot open interpreter $path: ${result.error}")
                return null
            }
        }

        try {
            val size = file.inode.metadata().size
            if (size > Int.MAX_VALUE.toULong()) {
                println("ELF: interpreter $path is too large")
                return null
            }
            val data = ByteArray(size.toInt())
            var offset = 0
            while (offset < data.size) {
                val result = file.read(data, offset, data.size - offset)
                if (!result.isSuccess) {
                    println("ELF: cannot read interpreter $path: ${result.error}")
                    return null
                }
                if (result.bytesTransferred == 0) {
                    println("ELF: unexpected EOF while reading interpreter $path")
                    return null
                }
                offset += result.bytesTransferred
            }
            return data
        } finally {
            file.release()
        }
    }

    private fun parseImage(data: ByteArray, reportErrors: Boolean): ElfImage? {
        fun reject(message: String): ElfImage? {
            if (reportErrors) {
                println("ELF: $message")
            }
            return null
        }

        if (data.size < ELF64_HEADER_SIZE) {
            return reject("header is truncated")
        }
        if (data[0].toUByte() != 0x7fu.toUByte() ||
            data[1] != 'E'.code.toByte() ||
            data[2] != 'L'.code.toByte() ||
            data[3] != 'F'.code.toByte()
        ) {
            return reject("invalid magic")
        }
        if (data[4].toUByte().toUInt() != ELF_CLASS_64 ||
            data[5].toUByte().toUInt() != ELF_DATA_LITTLE_ENDIAN ||
            data[6].toUByte().toUInt() != ELF_VERSION_CURRENT
        ) {
            return reject("unsupported class, byte order, or identification version")
        }

        val header = ElfHeader(
            type = data.readU16(16),
            machine = data.readU16(18),
            version = data.readU32(20),
            entryPoint = data.readU64(24),
            programHeaderOffset = data.readU64(32),
            headerSize = data.readU16(52),
            programHeaderEntrySize = data.readU16(54),
            programHeaderCount = data.readU16(56),
        )
        if (header.machine.toUInt() != ELF_MACHINE_X86_64 ||
            header.version != ELF_VERSION_CURRENT ||
            header.headerSize.toInt() != ELF64_HEADER_SIZE ||
            header.programHeaderEntrySize.toInt() != ELF64_PROGRAM_HEADER_SIZE
        ) {
            return reject("unsupported machine, version, or header layout")
        }

        val tableSize = header.programHeaderCount.toULong() *
            header.programHeaderEntrySize.toULong()
        if (header.programHeaderCount > 0u && header.programHeaderOffset == 0uL) {
            return reject("program header table has no offset")
        }
        if (!fitsInImage(header.programHeaderOffset, tableSize, data.size)) {
            return reject("program header table is truncated")
        }

        val programHeaders = ArrayList<ProgramHeader>(header.programHeaderCount.toInt())
        var cursor = header.programHeaderOffset.toInt()
        repeat(header.programHeaderCount.toInt()) {
            programHeaders += ProgramHeader(
                type = data.readU32(cursor),
                flags = data.readU32(cursor + 4),
                fileOffset = data.readU64(cursor + 8),
                virtualAddress = data.readU64(cursor + 16),
                fileSize = data.readU64(cursor + 32),
                memorySize = data.readU64(cursor + 40),
                alignment = data.readU64(cursor + 48),
            )
            cursor += ELF64_PROGRAM_HEADER_SIZE
        }
        return ElfImage(header, programHeaders)
    }

    private fun validateLoadSegment(
        imageSize: Int,
        programHeader: ProgramHeader,
        offset: ULong,
    ): LoadSegment? {
        if (programHeader.fileSize > programHeader.memorySize) {
            println("ELF: segment file size exceeds memory size")
            return null
        }
        if (!fitsInImage(programHeader.fileOffset, programHeader.fileSize, imageSize)) {
            println("ELF: segment data is truncated")
            return null
        }
        val start = checkedAdd(programHeader.virtualAddress, offset) ?: run {
            println("ELF: segment address overflows")
            return null
        }
        val alignment = programHeader.alignment
        if (alignment > 1uL &&
            (!alignment.isPowerOfTwo() || start % alignment != programHeader.fileOffset % alignment)
        ) {
            println("ELF: invalid segment alignment or load offset")
            return null
        }
        val end = checkedAdd(start, programHeader.memorySize) ?: run {
            println("ELF: segment size overflows")
            return null
        }
        if (start >= USER_VIRTUAL_ADDRESS_LIMIT || end > USER_VIRTUAL_ADDRESS_LIMIT) {
            println("ELF: segment lies outside the user address space")
            return null
        }

        return LoadSegment(
            header = programHeader,
            start = start,
            end = end,
            writable = (programHeader.flags and PROGRAM_FLAG_WRITABLE) != 0u,
            executable = (programHeader.flags and PROGRAM_FLAG_EXECUTABLE) != 0u,
        )
    }

    private fun planPages(segments: List<LoadSegment>): LinkedHashMap<ULong, PagePlan> {
        val pages = linkedMapOf<ULong, PagePlan>()
        for (segment in segments) {
            var address = segment.start.alignDown(PAGE_SIZE_BYTES)
            val end = segment.end.alignUp(PAGE_SIZE_BYTES)
            while (address < end) {
                val page = pages.getOrPut(address) { PagePlan() }
                page.readable = page.readable || (segment.vmaFlags and VMA_READ) != 0uL
                page.writable = page.writable || segment.writable
                page.executable = page.executable || segment.executable
                address += PAGE_SIZE_BYTES
            }
        }
        return pages
    }

    private fun pageChunks(pages: Map<ULong, PagePlan>, name: String): List<MemChunk> {
        val result = mutableListOf<MemChunk>()
        for ((address, page) in pages.entries.sortedBy { it.key }) {
            val flags =
                (if (page.readable) VMA_READ else 0uL) or
                    (if (page.writable) VMA_WRITE else 0uL) or
                    (if (page.executable) VMA_EXEC else 0uL)
            val previous = result.lastOrNull()
            if (previous != null && previous.end == address && previous.flags == flags) {
                previous.end += PAGE_SIZE_BYTES
            } else {
                result += MemChunk(
                    start = address,
                    end = address + PAGE_SIZE_BYTES,
                    flags = flags,
                    name = name,
                    type = VmaType.IMAGE,
                )
            }
        }
        return result
    }

    private fun copySegmentData(
        data: ByteArray,
        segment: LoadSegment,
        pages: Map<ULong, PagePlan>,
    ): Boolean {
        if (segment.header.fileSize == 0uL) {
            return true
        }

        var sourceOffset = segment.header.fileOffset.toInt()
        var destinationAddress = segment.start
        var remaining = segment.header.fileSize.toInt()
        var success = true
        data.usePinned { source ->
            while (remaining > 0) {
                val pageAddress = destinationAddress.alignDown(PAGE_SIZE_BYTES)
                val pageOffset = destinationAddress - pageAddress
                val page = pages[pageAddress]
                val physicalAddress = page?.physicalAddress
                if (physicalAddress == null) {
                    success = false
                    return@usePinned
                }
                val count = minOf(remaining, (PAGE_SIZE_BYTES - pageOffset).toInt())
                val destination =
                    Hhdm.toVirtualPointer<UByteVar>(physicalAddress + pageOffset)
                if (destination == null) {
                    success = false
                    return@usePinned
                }
                memcpy(destination, source.addressOf(sourceOffset), count.toULong())
                sourceOffset += count
                destinationAddress += count.toULong()
                remaining -= count
            }
        }
        return success
    }

    private fun zeroSegmentBss(
        segment: LoadSegment,
        pages: Map<ULong, PagePlan>,
    ): Boolean {
        var destinationAddress = segment.start + segment.header.fileSize
        var remaining = segment.header.memorySize - segment.header.fileSize
        while (remaining > 0uL) {
            val pageAddress = destinationAddress.alignDown(PAGE_SIZE_BYTES)
            val pageOffset = destinationAddress - pageAddress
            val physicalAddress = pages[pageAddress]?.physicalAddress ?: return false
            val count = minOf(remaining, PAGE_SIZE_BYTES - pageOffset)
            val destination =
                Hhdm.toVirtualPointer<UByteVar>(physicalAddress + pageOffset) ?: return false
            memset(destination, 0, count)
            destinationAddress += count
            remaining -= count
        }
        return true
    }

    private fun programHeaderAddress(
        image: ElfImage,
        segments: List<LoadSegment>,
        offset: ULong,
    ): ULong? {
        image.programHeaders.firstOrNull { it.type == PROGRAM_TYPE_PHDR }?.let { phdr ->
            return checkedAdd(phdr.virtualAddress, offset)
        }

        val tableStart = image.header.programHeaderOffset
        val tableSize = image.header.programHeaderCount.toULong() *
            image.header.programHeaderEntrySize.toULong()
        val tableEnd = checkedAdd(tableStart, tableSize) ?: return null
        val containingSegment = segments.firstOrNull { segment ->
            val fileEnd = checkedAdd(segment.header.fileOffset, segment.header.fileSize)
                ?: return@firstOrNull false
            tableStart >= segment.header.fileOffset && tableEnd <= fileEnd
        } ?: return null
        return containingSegment.start + (tableStart - containingSegment.header.fileOffset)
    }

    private fun rollback(
        directory: PageDirectory,
        mappedPages: List<ULong>,
        allocatedFrames: List<ULong>,
    ) {
        mappedPages.asReversed().forEach(directory::unmapPage)
        allocatedFrames.asReversed().forEach { physicalAddress ->
            BuddyFrameAllocator.freeFrames(physicalAddress, 1uL)
        }
    }
}

private data class ElfHeader(
    val type: UShort,
    val machine: UShort,
    val version: UInt,
    val entryPoint: ULong,
    val programHeaderOffset: ULong,
    val headerSize: UShort,
    val programHeaderEntrySize: UShort,
    val programHeaderCount: UShort,
)

private data class ProgramHeader(
    val type: UInt,
    val flags: UInt,
    val fileOffset: ULong,
    val virtualAddress: ULong,
    val fileSize: ULong,
    val memorySize: ULong,
    val alignment: ULong,
)

private data class ElfImage(
    val header: ElfHeader,
    val programHeaders: List<ProgramHeader>,
)

private data class LoadSegment(
    val header: ProgramHeader,
    val start: ULong,
    val end: ULong,
    val writable: Boolean,
    val executable: Boolean,
) {
    val vmaFlags: ULong
        get() =
            (if ((header.flags and PROGRAM_FLAG_READABLE) != 0u) VMA_READ else 0uL) or
                (if (writable) VMA_WRITE else 0uL) or
                (if (executable) VMA_EXEC else 0uL)
}

private data class PagePlan(
    var readable: Boolean = false,
    var writable: Boolean = false,
    var executable: Boolean = false,
    var physicalAddress: ULong? = null,
)

private fun checkedAdd(left: ULong, right: ULong): ULong? =
    if (left > ULong.MAX_VALUE - right) null else left + right

private fun fitsInImage(offset: ULong, size: ULong, imageSize: Int): Boolean =
    offset <= imageSize.toULong() && size <= imageSize.toULong() - offset

private fun ULong.isPowerOfTwo(): Boolean = this != 0uL && (this and (this - 1uL)) == 0uL

private fun ByteArray.readU16(offset: Int): UShort =
    (readUnsigned(offset) or (readUnsigned(offset + 1) shl 8)).toUShort()

private fun ByteArray.readU32(offset: Int): UInt =
    (0 until UInt.SIZE_BYTES).fold(0u) { value, index ->
        value or (readUnsigned(offset + index).toUInt() shl (index * Byte.SIZE_BITS))
    }

private fun ByteArray.readU64(offset: Int): ULong =
    (0 until ULong.SIZE_BYTES).fold(0uL) { value, index ->
        value or (readUnsigned(offset + index) shl (index * Byte.SIZE_BITS))
    }

private fun ByteArray.readUnsigned(offset: Int): ULong = this[offset].toUByte().toULong()
