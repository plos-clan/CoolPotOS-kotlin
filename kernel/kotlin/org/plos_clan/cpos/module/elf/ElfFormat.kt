package org.plos_clan.cpos.module.elf

import org.plos_clan.cpos.fs.OpenFileDescription

internal enum class ElfObjectType {
    EXECUTABLE,
    DYNAMIC,
}

internal data class ElfPageCacheIdentity(
    val file: Any,
    val segments: List<LoadSegment>,
)

internal data class ElfFile(
    val file: OpenFileDescription,
    val image: ElfImage,
)

internal data class ElfHeader(
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

internal data class ElfImage(
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

internal data class PagePlan(
    var readable: Boolean = false,
    var writable: Boolean = false,
    var executable: Boolean = false,
)

internal object ElfLayout {
    fun checkedAdd(left: ULong, right: ULong): ULong? =
        if (left > ULong.MAX_VALUE - right) null else left + right

    fun fitsInFile(offset: ULong, size: ULong, fileSize: ULong): Boolean =
        offset <= fileSize && size <= fileSize - offset

    fun ULong.isPowerOfTwo(): Boolean =
        this != 0uL && (this and (this - 1uL)) == 0uL
}
