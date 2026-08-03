package org.plos_clan.cpos.fs

import org.plos_clan.cpos.module.ModuleManager
import org.plos_clan.cpos.module.ModuleData
import org.plos_clan.cpos.utils.Cmdline


internal enum class InitrdFormat(val displayName: String) {
    NEWC("cpio-newc"),
    NEWC_CRC("cpio-newc-crc"),
    ODC("cpio-odc"),
    GZIP("gzip"),
    XZ("xz"),
    LZ4("lz4"),
    ZSTD("zstd"),
    LZMA("lzma"),
    UNKNOWN("unknown"),
    ;

    val isCompressed: Boolean
        get() = this == GZIP || this == XZ || this == LZ4 || this == ZSTD || this == LZMA

    companion object {
        fun detect(data: ModuleData): InitrdFormat = when {
            data.hasAsciiPrefix("070701") -> NEWC
            data.hasAsciiPrefix("070702") -> NEWC_CRC
            data.hasAsciiPrefix("070707") -> ODC
            data.hasBytePrefix(0x1f, 0x8b) -> GZIP
            data.hasBytePrefix(0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00) -> XZ
            data.hasBytePrefix(0x04, 0x22, 0x4d, 0x18) -> LZ4
            data.hasBytePrefix(0x28, 0xb5, 0x2f, 0xfd) -> ZSTD
            data.hasBytePrefix(0x5d, 0x00, 0x00, 0x80) -> LZMA
            else -> UNKNOWN
        }
    }
}

internal sealed interface CpioLoadResult {
    data class Success(
        val entries: Int,
        val payloadBytes: ULong,
    ) : CpioLoadResult

    data class Failure(val reason: String) : CpioLoadResult
}

internal object CpioArchive {
    private const val HEADER_SIZE = 110
    private const val FIELD_SIZE = 8
    private const val MODE_OFFSET = 14
    private const val NLINK_OFFSET = 38
    private const val FILE_SIZE_OFFSET = 54
    private const val NAME_SIZE_OFFSET = 94
    private const val CHECK_OFFSET = 102
    private const val WRITE_CHUNK_SIZE = 64 * 1024
    private const val MAX_PATH_SIZE = 4096

    private const val TYPE_MASK = 0xf000u
    private const val TYPE_REGULAR = 0x8000u
    private const val TYPE_DIRECTORY = 0x4000u
    private const val TYPE_SYMLINK = 0xa000u
    private const val PERMISSION_MASK = 0x0fffu

    fun load(
        data: ModuleData,
        format: InitrdFormat,
        vfs: Vfs,
        context: FileSystemContext,
    ): CpioLoadResult {
        if (format != InitrdFormat.NEWC && format != InitrdFormat.NEWC_CRC) {
            return CpioLoadResult.Failure("unsupported CPIO format ${format.displayName}")
        }

        val expectedMagic = if (format == InitrdFormat.NEWC) "070701" else "070702"
        var offset = 0
        var entryCount = 0
        var payloadBytes = 0uL
        val writeBuffer = ByteArray(WRITE_CHUNK_SIZE)

        while (true) {
            if (!data.hasRange(offset, HEADER_SIZE)) {
                return failure(offset, "truncated newc header")
            }
            if (!data.matchesAscii(offset, expectedMagic)) {
                return failure(offset, "unexpected CPIO magic")
            }

            val mode = data.readHexField(offset + MODE_OFFSET)
                ?: return failure(offset, "invalid mode field")
            val linkCount = data.readHexField(offset + NLINK_OFFSET)
                ?: return failure(offset, "invalid link-count field")
            val fileSize = data.readHexField(offset + FILE_SIZE_OFFSET)
                ?: return failure(offset, "invalid file-size field")
            val nameSize = data.readHexField(offset + NAME_SIZE_OFFSET)
                ?: return failure(offset, "invalid name-size field")
            val expectedChecksum = data.readHexField(offset + CHECK_OFFSET)
                ?: return failure(offset, "invalid checksum field")

            if (mode > UInt.MAX_VALUE.toULong()) {
                return failure(offset, "mode is too large")
            }
            if (linkCount > UInt.MAX_VALUE.toULong()) {
                return failure(offset, "link count is too large")
            }
            val nameLength = nameSize.toArrayLength()
                ?: return failure(offset, "invalid name size $nameSize")
            val payloadLength = fileSize.toArrayLength()
                ?: return failure(offset, "file is too large: $fileSize bytes")
            if (nameLength == 0) {
                return failure(offset, "entry name does not include a terminator")
            }
            if (nameLength - 1 > MAX_PATH_SIZE) {
                return failure(offset, "entry path is longer than $MAX_PATH_SIZE bytes")
            }

            val nameOffset = offset + HEADER_SIZE
            if (!data.hasRange(nameOffset, nameLength)) {
                return failure(offset, "entry name exceeds archive bounds")
            }
            val nameEnd = nameOffset + nameLength
            if (data[nameEnd - 1] != 0.toByte()) {
                return failure(offset, "entry name is not NUL-terminated")
            }
            for (index in nameOffset until nameEnd - 1) {
                if (data[index] == 0.toByte()) {
                    return failure(offset, "entry name contains an embedded NUL")
                }
            }

            val payloadOffset = align4(nameEnd)
                ?: return failure(offset, "entry-name alignment overflow")
            if (!data.hasRange(payloadOffset, payloadLength)) {
                return failure(offset, "entry payload exceeds archive bounds")
            }
            val payloadEnd = payloadOffset + payloadLength
            val nextOffset = align4(payloadEnd)
                ?: return failure(offset, "entry-payload alignment overflow")
            if (nextOffset > data.size) {
                return failure(offset, "entry padding exceeds archive bounds")
            }

            val rawName = data.copyOfRange(nameOffset, nameEnd - 1)
            if (rawName.matchesAscii("TRAILER!!!")) {
                if (payloadLength != 0) {
                    return failure(offset, "CPIO trailer has a payload")
                }
                return CpioLoadResult.Success(entryCount, payloadBytes)
            }

            val entryMode = mode.toUInt()
            if (format == InitrdFormat.NEWC_CRC && entryMode and TYPE_MASK == TYPE_REGULAR) {
                val actualChecksum = data.checksum(payloadOffset, payloadEnd)
                if (expectedChecksum != actualChecksum.toULong()) {
                    return failure(
                        offset,
                        "checksum mismatch for ${rawName.displayName()}: " +
                                "expected $expectedChecksum, got $actualChecksum",
                    )
                }
            }

            val normalized = normalizeArchivePath(rawName)
            when (normalized) {
                is ArchivePath.Invalid -> return failure(offset, normalized.reason)
                ArchivePath.Root -> Unit
                is ArchivePath.Valid -> {
                    val result = materializeEntry(
                        data = data,
                        payloadOffset = payloadOffset,
                        payloadLength = payloadLength,
                        linkCount = linkCount.toUInt(),
                        mode = entryMode,
                        pathname = normalized.pathname,
                        displayName = normalized.displayName,
                        writeBuffer = writeBuffer,
                        vfs = vfs,
                        context = context,
                    )
                    if (result != null) {
                        return failure(offset, result)
                    }
                    entryCount++
                    payloadBytes += fileSize
                }
            }

            offset = nextOffset
        }
    }

    private fun materializeEntry(
        data: ModuleData,
        payloadOffset: Int,
        payloadLength: Int,
        linkCount: UInt,
        mode: UInt,
        pathname: VfsPathname,
        displayName: String,
        writeBuffer: ByteArray,
        vfs: Vfs,
        context: FileSystemContext,
    ): String? {
        val permissions = FileMode(mode and PERMISSION_MASK)
        return when (mode and TYPE_MASK) {
            TYPE_DIRECTORY -> when (val result = vfs.mkdir(context, pathname, permissions)) {
                is VfsResult.Ok -> null
                is VfsResult.Err -> if (
                    result.error == VfsError.ALREADY_EXISTS &&
                    isExistingDirectory(vfs, context, pathname)
                ) {
                    null
                } else {
                    "cannot create directory $displayName: ${result.error}"
                }
            }

            TYPE_SYMLINK -> {
                if (payloadLength > MAX_PATH_SIZE) {
                    return "cannot create symlink $displayName: target is too long"
                }
                val target = VfsPathname.fromBytes(
                    data.copyOfRange(payloadOffset, payloadOffset + payloadLength)
                )
                when (val result = vfs.symlink(context, target, pathname)) {
                    is VfsResult.Ok -> null
                    is VfsResult.Err -> "cannot create symlink $displayName: ${result.error}"
                }
            }

            TYPE_REGULAR -> {
                if (linkCount > 1u) {
                    "cannot create hard-linked file $displayName: hard links are not supported"
                } else {
                    writeRegularFile(
                        data,
                        payloadOffset,
                        payloadLength,
                        permissions,
                        pathname,
                        displayName,
                        writeBuffer,
                        vfs,
                        context,
                    )
                }
            }

            else -> "cannot create $displayName: unsupported CPIO file type 0x${(mode and TYPE_MASK).toString(16)}"
        }
    }

    private fun isExistingDirectory(
        vfs: Vfs,
        context: FileSystemContext,
        pathname: VfsPathname,
    ): Boolean = when (val result = vfs.resolve(context, pathname)) {
        is VfsResult.Ok -> result.value.inode?.type == InodeType.DIRECTORY
        is VfsResult.Err -> false
    }

    private fun writeRegularFile(
        data: ModuleData,
        payloadOffset: Int,
        payloadLength: Int,
        mode: FileMode,
        pathname: VfsPathname,
        displayName: String,
        writeBuffer: ByteArray,
        vfs: Vfs,
        context: FileSystemContext,
    ): String? {
        val file = when (
            val result = vfs.open(
                context,
                pathname,
                OpenOptions(
                    access = AccessMode.WRITE,
                    create = CreateDisposition.CREATE_NEW,
                    createMode = mode,
                ),
            )
        ) {
            is VfsResult.Ok -> result.value
            is VfsResult.Err -> return "cannot create file $displayName: ${result.error}"
        }

        var written = 0
        var failure: String? = null
        while (written < payloadLength) {
            val chunk = minOf(WRITE_CHUNK_SIZE, payloadLength - written)
            data.copyInto(writeBuffer, 0, payloadOffset + written, chunk)
            val result = file.write(writeBuffer, 0, chunk)
            if (!result.isSuccess) {
                failure = "cannot write file $displayName: ${result.error}"
                break
            }
            if (result.bytesTransferred == 0) {
                failure = "cannot write file $displayName: zero-length write"
                break
            }
            written += result.bytesTransferred
        }
        file.release()

        if (failure != null) {
            vfs.unlink(context, pathname)
        }
        return failure
    }

    private fun failure(offset: Int, reason: String): CpioLoadResult.Failure =
        CpioLoadResult.Failure("at archive offset $offset: $reason")

    private fun normalizeArchivePath(rawName: ByteArray): ArchivePath {
        val components = mutableListOf<ByteArray>()
        var cursor = 0
        while (cursor < rawName.size) {
            while (cursor < rawName.size && rawName[cursor] == '/'.code.toByte()) {
                cursor++
            }
            if (cursor == rawName.size) {
                break
            }
            val start = cursor
            while (cursor < rawName.size && rawName[cursor] != '/'.code.toByte()) {
                cursor++
            }
            val component = rawName.copyOfRange(start, cursor)
            when {
                component.matchesAscii(".") -> Unit
                component.matchesAscii("..") ->
                    return ArchivePath.Invalid("unsafe CPIO path ${rawName.displayName()}")
                else -> components += component
            }
        }

        if (components.isEmpty()) {
            return ArchivePath.Root
        }

        val pathSize = 1 + components.sumOf { it.size + 1 } - 1
        val path = ByteArray(pathSize)
        var output = 0
        path[output++] = '/'.code.toByte()
        components.forEachIndexed { index, component ->
            component.copyInto(path, output)
            output += component.size
            if (index != components.lastIndex) {
                path[output++] = '/'.code.toByte()
            }
        }
        return ArchivePath.Valid(
            pathname = VfsPathname.fromBytes(path),
            displayName = path.displayName(),
        )
    }

    private sealed interface ArchivePath {
        data object Root : ArchivePath
        data class Invalid(val reason: String) : ArchivePath
        data class Valid(val pathname: VfsPathname, val displayName: String) : ArchivePath
    }

    private fun ModuleData.readHexField(offset: Int): ULong? {
        if (!hasRange(offset, FIELD_SIZE)) {
            return null
        }
        var value = 0uL
        for (index in offset until offset + FIELD_SIZE) {
            val digit = when (val character = this[index].toInt() and 0xff) {
                in '0'.code..'9'.code -> character - '0'.code
                in 'a'.code..'f'.code -> character - 'a'.code + 10
                in 'A'.code..'F'.code -> character - 'A'.code + 10
                else -> return null
            }
            value = value * 16uL + digit.toULong()
        }
        return value
    }

    private fun ModuleData.checksum(start: Int, end: Int): UInt {
        var sum = 0u
        for (index in start until end) {
            sum += (this[index].toInt() and 0xff).toUInt()
        }
        return sum
    }

    private fun ModuleData.hasRange(offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset <= size - length

    private fun ByteArray.matchesAscii(value: String): Boolean =
        size == value.length && indices.all { index -> this[index] == value[index].code.toByte() }

    private fun ModuleData.matchesAscii(offset: Int, value: String): Boolean =
        hasRange(offset, value.length) && value.indices.all { index ->
            this[offset + index] == value[index].code.toByte()
        }

    private fun ByteArray.displayName(): String = decodeToString()

    private fun ULong.toArrayLength(): Int? =
        takeIf { it <= Int.MAX_VALUE.toULong() }?.toInt()

    private fun align4(value: Int): Int? =
        value.takeIf { it <= Int.MAX_VALUE - 3 }?.let { (it + 3) and -4 }
}

private fun ModuleData.hasBytePrefix(vararg bytes: Int): Boolean =
    bytes.size <= size && bytes.indices.all { index ->
        (this[index].toInt() and 0xff) == bytes[index]
    }

private fun ModuleData.hasAsciiPrefix(value: String): Boolean =
    value.length <= size && value.indices.all { index -> this[index] == value[index].code.toByte() }

object Initrd {
    fun initialize() {
        val initrdPath = Cmdline["initrd"] ?: run {
            println("INITRD: no initrd was specified on the command line")
            return
        }
        val initramfs = ModuleManager[initrdPath] ?: run {
            println("INITRD: cannot find boot module $initrdPath")
            return
        }

        val format = InitrdFormat.detect(initramfs.data)
        when {
            format.isCompressed -> {
                println("INITRD: detected ${format.displayName}; decompression is not implemented")
            }

            format == InitrdFormat.ODC -> {
                println("INITRD: detected ${format.displayName}; extraction is not implemented")
            }

            format == InitrdFormat.UNKNOWN -> {
                println("INITRD: unsupported or unrecognized module format for ${initramfs.name}")
            }

            else -> loadCpio(initramfs.data, format)
        }
    }

    private fun loadCpio(data: ModuleData, format: InitrdFormat) {
        val context = FileSystemManager.kernelContext ?: run {
            println("INITRD: cannot load CPIO before the root filesystem is initialized")
            return
        }
        when (val result = CpioArchive.load(data, format, FileSystemManager.vfs, context)) {
            is CpioLoadResult.Success -> println(
                "INITRD: loaded ${result.entries} entries, ${result.payloadBytes} payload bytes " +
                    "from ${format.displayName}"
            )

            is CpioLoadResult.Failure -> println(
                "INITRD: failed to load ${format.displayName}: ${result.reason}"
            )
        }
    }
}
