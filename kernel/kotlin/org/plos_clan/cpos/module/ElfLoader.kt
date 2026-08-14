package org.plos_clan.cpos.module

import org.plos_clan.cpos.fs.AccessMode
import org.plos_clan.cpos.fs.FileSystemManager
import org.plos_clan.cpos.fs.OpenFileDescription
import org.plos_clan.cpos.fs.OpenOptions
import org.plos_clan.cpos.fs.VfsPathname
import org.plos_clan.cpos.fs.VfsResult
import org.plos_clan.cpos.mem.AddressSpace
import org.plos_clan.cpos.mem.ByteArrayBuffer
import org.plos_clan.cpos.mem.KernelPageDirectory
import org.plos_clan.cpos.mem.MEMORY_REGION_EXECUTABLE
import org.plos_clan.cpos.mem.MEMORY_REGION_READABLE
import org.plos_clan.cpos.mem.MEMORY_REGION_WRITABLE
import org.plos_clan.cpos.mem.MemoryRegion
import org.plos_clan.cpos.mem.MemoryRegionBacking
import org.plos_clan.cpos.mem.MemoryRegionType
import org.plos_clan.cpos.mem.USER_VIRTUAL_ADDRESS_LIMIT
import org.plos_clan.cpos.mem.VDSFileBacking
import org.plos_clan.cpos.tasks.Process
import org.plos_clan.cpos.tasks.ProcessManager
import org.plos_clan.cpos.utils.LittleEndianBuffer
import org.plos_clan.cpos.utils.PAGE_SIZE_BYTES
import org.plos_clan.cpos.utils.alignDown
import org.plos_clan.cpos.utils.alignUp

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
private const val EIO = 5

const val DEFAULT_INTERPRETER_LOAD_BIAS = 0x0000_1000_0000_0000uL

data class ElfLoadResult(
    val entryPoint: ULong,
    val loadStart: ULong,
    val loadSize: ULong,
    val programHeaderAddress: ULong?,
    val programHeaderEntrySize: UShort,
    val programHeaderCount: UShort,
)

data class ElfInterpreterLoadResult(
    val path: String,
    val loadBias: ULong,
    val image: ElfLoadResult,
) {
    val entryPoint: ULong
        get() = image.entryPoint
}

data class UserProcessImage(
    val entryPoint: ULong,
    val stackPointer: ULong,
)

object ElfLoader {
    fun loadProcess(
        path: String,
        process: Process,
        arguments: List<String> = listOf(path),
        environment: List<String> = emptyList(),
    ): UserProcessImage? {
        val addressSpace = AddressSpace.user(KernelPageDirectory.getDirectory().createUserDirectory())
        var installed = false
        try {
            val executableFile = open(process, path) ?: return null

            val executable = try {
                loadImage(executableFile, addressSpace, path, 0uL)
            } finally {
                executableFile.file.release()
            } ?: return null

            val interpreter = executableFile.image.interpreterPath?.let { interpreterPath ->
                val interpreterFile = open(process, interpreterPath) ?: return null
                try {
                    if (interpreterFile.image.type != ElfObjectType.DYNAMIC) {
                        println("ELF: interpreter $interpreterPath is not ET_DYN")
                        return null
                    }
                    loadImage(
                        file = interpreterFile,
                        addressSpace = addressSpace,
                        name = interpreterPath,
                        loadBias = DEFAULT_INTERPRETER_LOAD_BIAS,
                    )?.let { loaded ->
                        ElfInterpreterLoadResult(
                            path = interpreterPath,
                            loadBias = DEFAULT_INTERPRETER_LOAD_BIAS,
                            image = loaded,
                        )
                    }
                } finally {
                    interpreterFile.file.release()
                }
            }
            if (executableFile.image.interpreterPath != null && interpreter == null) return null

            val vdso = Vdso.install(addressSpace) ?: return null

            val stack = UserStackBuilder.build(
                process = process,
                arguments = arguments.ifEmpty { listOf(path) },
                environment = environment,
                executablePath = path,
                executable = executable,
                interpreter = interpreter,
                systemInfoHeader = vdso,
                addressSpace = addressSpace,
            ) ?: return null

            if (!ProcessManager.installUserAddressSpace(process, addressSpace)) return null
            installed = true
            return UserProcessImage(
                entryPoint = interpreter?.entryPoint ?: executable.entryPoint,
                stackPointer = stack.stackPointer,
            )
        } finally {
            if (!installed) addressSpace.destroy()
        }
    }

    private fun open(process: Process, path: String): ElfFile? {
        val file = when (
            val result = FileSystemManager.vfs.open(
                context = process.getFSContext(),
                pathname = VfsPathname.fromString(path),
                options = OpenOptions(access = AccessMode.READ),
            )
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> {
                println("ELF: cannot open $path: ${result.error}")
                return null
            }
        }
        val image = parseImage(file, path) ?: run {
            file.release()
            return null
        }
        return ElfFile(file, image)
    }

    private fun loadImage(
        file: ElfFile,
        addressSpace: AddressSpace,
        name: String,
        loadBias: ULong,
    ): ElfLoadResult? {
        val segments = file.image.programHeaders.mapNotNull { header ->
            if (header.type != PROGRAM_TYPE_LOAD || header.memorySize == 0uL) {
                null
            } else {
                validateLoadSegment(file.file.inode.metadata().size, header, loadBias)
                    ?: return null
            }
        }
        if (segments.isEmpty()) {
            println("ELF: $name has no loadable segment")
            return null
        }

        val entryPoint = checkedAdd(file.image.header.entryPoint, loadBias) ?: return null
        if (entryPoint >= USER_VIRTUAL_ADDRESS_LIMIT ||
            segments.none { it.executable && entryPoint >= it.start && entryPoint < it.end }
        ) {
            println("ELF: entry point is outside executable segments")
            return null
        }

        val pages = planPages(segments) ?: return null
        val backing = ElfBacking(file.file, segments)
        val regions = pageRegions(pages, name, backing)
        val inserted = try {
            addressSpace.insertAll(regions)
        } finally {
            backing.release()
        }
        if (!inserted) {
            println("ELF: loadable segments overlap an existing mapping")
            return null
        }

        val loadStart = segments.minOf(LoadSegment::start)
        val loadEnd = segments.maxOf(LoadSegment::end)
        return ElfLoadResult(
            entryPoint = entryPoint,
            loadStart = loadStart,
            loadSize = loadEnd - loadStart,
            programHeaderAddress = programHeaderAddress(file.image, segments, loadBias),
            programHeaderEntrySize = file.image.header.programHeaderEntrySize,
            programHeaderCount = file.image.header.programHeaderCount,
        )
    }

    private fun parseImage(file: OpenFileDescription, path: String): ElfImage? {
        fun reject(message: String): ElfImage? {
            println("ELF: $path: $message")
            return null
        }

        val size = file.inode.metadata().size
        val headerData = readExact(file, 0uL, ELF64_HEADER_SIZE)
            ?: return reject("header is truncated")
        val headerInput = LittleEndianBuffer(headerData)
        if (headerData[0].toUByte() != 0x7fu.toUByte() ||
            headerData[1] != 'E'.code.toByte() ||
            headerData[2] != 'L'.code.toByte() ||
            headerData[3] != 'F'.code.toByte()
        ) return reject("invalid magic")
        if (headerData[4].toUByte().toUInt() != ELF_CLASS_64 ||
            headerData[5].toUByte().toUInt() != ELF_DATA_LITTLE_ENDIAN ||
            headerData[6].toUByte().toUInt() != ELF_VERSION_CURRENT
        ) return reject("unsupported class, byte order, or version")

        val header = ElfHeader(
            type = headerInput.readU16(16),
            machine = headerInput.readU16(18),
            version = headerInput.readU32(20),
            entryPoint = headerInput.readU64(24),
            programHeaderOffset = headerInput.readU64(32),
            headerSize = headerInput.readU16(52),
            programHeaderEntrySize = headerInput.readU16(54),
            programHeaderCount = headerInput.readU16(56),
        )
        val type = when (header.type.toUInt()) {
            ELF_TYPE_EXECUTABLE -> ElfObjectType.EXECUTABLE
            ELF_TYPE_SHARED_OBJECT -> ElfObjectType.DYNAMIC
            else -> return reject("unsupported object type ${header.type}")
        }
        if (header.machine.toUInt() != ELF_MACHINE_X86_64 ||
            header.version != ELF_VERSION_CURRENT ||
            header.headerSize.toInt() != ELF64_HEADER_SIZE ||
            header.programHeaderEntrySize.toInt() != ELF64_PROGRAM_HEADER_SIZE
        ) return reject("unsupported machine, version, or header layout")

        val tableSize = header.programHeaderCount.toULong() * ELF64_PROGRAM_HEADER_SIZE.toULong()
        if (header.programHeaderCount > 0u && header.programHeaderOffset == 0uL ||
            !fitsInFile(header.programHeaderOffset, tableSize, size)
        ) return reject("program header table is truncated")
        val table = readExact(file, header.programHeaderOffset, tableSize.toInt())
            ?: return reject("cannot read program header table")
        val tableInput = LittleEndianBuffer(table)
        val programHeaders = List(header.programHeaderCount.toInt()) { index ->
            val cursor = index * ELF64_PROGRAM_HEADER_SIZE
            ProgramHeader(
                type = tableInput.readU32(cursor),
                flags = tableInput.readU32(cursor + 4),
                fileOffset = tableInput.readU64(cursor + 8),
                virtualAddress = tableInput.readU64(cursor + 16),
                fileSize = tableInput.readU64(cursor + 32),
                memorySize = tableInput.readU64(cursor + 40),
                alignment = tableInput.readU64(cursor + 48),
            )
        }
        val interpreters = programHeaders.filter { it.type == PROGRAM_TYPE_INTERPRETER }
        if (interpreters.size > 1) return reject("more than one PT_INTERP segment")
        val interpreterPath = interpreters.firstOrNull()?.let { interpreter ->
            if (interpreter.fileSize < 2uL ||
                interpreter.fileSize > Int.MAX_VALUE.toULong() ||
                !fitsInFile(interpreter.fileOffset, interpreter.fileSize, size)
            ) return reject("PT_INTERP is invalid")
            val bytes = readExact(file, interpreter.fileOffset, interpreter.fileSize.toInt())
                ?: return reject("cannot read PT_INTERP")
            if (bytes.last() != 0.toByte()) return reject("PT_INTERP is not null-terminated")
            val pathBytes = bytes.copyOf(bytes.lastIndex)
            if (pathBytes.isEmpty() || pathBytes.first() != '/'.code.toByte() ||
                pathBytes.any { it == 0.toByte() }
            ) return reject("PT_INTERP path is invalid")
            pathBytes.decodeToString()
        }
        return ElfImage(header, programHeaders, type, interpreterPath)
    }

    private fun validateLoadSegment(
        imageSize: ULong,
        header: ProgramHeader,
        loadBias: ULong,
    ): LoadSegment? {
        if (header.fileSize > header.memorySize ||
            !fitsInFile(header.fileOffset, header.fileSize, imageSize)
        ) {
            println("ELF: invalid load segment size")
            return null
        }
        val start = checkedAdd(header.virtualAddress, loadBias) ?: return null
        val end = checkedAdd(start, header.memorySize) ?: return null
        if (start >= USER_VIRTUAL_ADDRESS_LIMIT || end > USER_VIRTUAL_ADDRESS_LIMIT) {
            println("ELF: load segment is outside userspace")
            return null
        }
        if (header.alignment > 1uL &&
            (!header.alignment.isPowerOfTwo() ||
                start % header.alignment != header.fileOffset % header.alignment)
        ) {
            println("ELF: invalid load segment alignment")
            return null
        }
        return LoadSegment(
            header = header,
            start = start,
            end = end,
            executable = (header.flags and PROGRAM_FLAG_EXECUTABLE) != 0u,
        )
    }

    private fun planPages(segments: List<LoadSegment>): Map<ULong, PagePlan>? {
        val pages = linkedMapOf<ULong, PagePlan>()
        for (segment in segments) {
            var address = segment.start.alignDown(PAGE_SIZE_BYTES)
            val end = segment.end.alignUp(PAGE_SIZE_BYTES) ?: return null
            while (address < end) {
                val page = pages.getOrPut(address, ::PagePlan)
                page.readable = page.readable ||
                    (segment.header.flags and PROGRAM_FLAG_READABLE) != 0u
                page.writable = page.writable ||
                    (segment.header.flags and PROGRAM_FLAG_WRITABLE) != 0u
                page.executable = page.executable || segment.executable
                address += PAGE_SIZE_BYTES
            }
        }
        return pages
    }

    private fun pageRegions(
        pages: Map<ULong, PagePlan>,
        name: String,
        backing: MemoryRegionBacking,
    ): List<MemoryRegion> {
        val regions = mutableListOf<MemoryRegion>()
        for ((address, page) in pages) {
            val access =
                (if (page.readable) MEMORY_REGION_READABLE else 0uL) or
                    (if (page.writable) MEMORY_REGION_WRITABLE else 0uL) or
                    (if (page.executable) MEMORY_REGION_EXECUTABLE else 0uL)
            val previous = regions.lastOrNull()
            if (previous != null && previous.end == address && previous.access == access) {
                previous.end += PAGE_SIZE_BYTES
            } else {
                regions += MemoryRegion(
                    start = address,
                    end = address + PAGE_SIZE_BYTES,
                    access = access,
                    name = name,
                    type = MemoryRegionType.IMAGE,
                    offset = address,
                    backing = backing,
                )
            }
        }
        return regions
    }

    private fun programHeaderAddress(
        image: ElfImage,
        segments: List<LoadSegment>,
        loadBias: ULong,
    ): ULong? {
        image.programHeaders.firstOrNull { it.type == PROGRAM_TYPE_PHDR }?.let { header ->
            return checkedAdd(header.virtualAddress, loadBias)
        }
        val tableSize = image.header.programHeaderCount.toULong() *
            image.header.programHeaderEntrySize.toULong()
        val tableEnd = checkedAdd(image.header.programHeaderOffset, tableSize) ?: return null
        val segment = segments.firstOrNull {
            val fileEnd = checkedAdd(it.header.fileOffset, it.header.fileSize)
                ?: return@firstOrNull false
            image.header.programHeaderOffset >= it.header.fileOffset && tableEnd <= fileEnd
        } ?: return null
        return segment.start + (image.header.programHeaderOffset - segment.header.fileOffset)
    }

    private fun readExact(
        file: OpenFileDescription,
        fileOffset: ULong,
        count: Int,
    ): ByteArray? {
        val data = ByteArray(count)
        var copied = 0
        while (copied < count) {
            val result = file.readAt(
                fileOffset = fileOffset + copied.toULong(),
                destination = ByteArrayBuffer(data),
                offset = copied,
                count = count - copied,
            )
            if (!result.isSuccess || result.bytesTransferred == 0) return null
            copied += result.bytesTransferred
        }
        return data
    }
}

class ElfBacking(
    private val file: OpenFileDescription,
    private val segments: List<LoadSegment>,
) : MemoryRegionBacking(), VDSFileBacking {
    init {
        check(file.retain())
    }

    override val immutablePageSource: Any?
        get() = file.immutablePageSource

    override fun read(offset: ULong, destination: ByteArray): Int {
        val end = checkedAdd(offset, destination.size.toULong()) ?: return -EIO
        for (segment in segments) {
            val fileEnd = checkedAdd(segment.start, segment.header.fileSize) ?: return -EIO
            val start = maxOf(offset, segment.start)
            val segmentEnd = minOf(end, fileEnd)
            if (start >= segmentEnd) continue
            val count = (segmentEnd - start).toInt()
            val result = file.readAt(
                fileOffset = segment.header.fileOffset + (start - segment.start),
                destination = ByteArrayBuffer(destination),
                offset = (start - offset).toInt(),
                count = count,
            )
            if (!result.isSuccess || result.bytesTransferred != count) return -EIO
        }
        return destination.size
    }

    override fun close() = file.release()

    override val getFile get() = file
}

private enum class ElfObjectType {
    EXECUTABLE,
    DYNAMIC,
}

private data class ElfFile(
    val file: OpenFileDescription,
    val image: ElfImage,
)

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

data class ProgramHeader(
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
    val type: ElfObjectType,
    val interpreterPath: String?,
)

data class LoadSegment(
    val header: ProgramHeader,
    val start: ULong,
    val end: ULong,
    val executable: Boolean,
)

private data class PagePlan(
    var readable: Boolean = false,
    var writable: Boolean = false,
    var executable: Boolean = false,
)

private fun checkedAdd(left: ULong, right: ULong): ULong? =
    if (left > ULong.MAX_VALUE - right) null else left + right

private fun fitsInFile(offset: ULong, size: ULong, fileSize: ULong): Boolean =
    offset <= fileSize && size <= fileSize - offset

private fun ULong.isPowerOfTwo(): Boolean =
    this != 0uL && (this and (this - 1uL)) == 0uL
