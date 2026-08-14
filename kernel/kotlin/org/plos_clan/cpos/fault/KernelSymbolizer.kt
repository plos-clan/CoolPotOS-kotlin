@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.fault

import bridge.executable_file_request
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import org.plos_clan.cpos.utils.hex
import org.plos_clan.cpos.utils.isCanonicalKernelAddress
import org.plos_clan.cpos.utils.readAscii
import org.plos_clan.cpos.utils.readU16
import org.plos_clan.cpos.utils.readU32
import org.plos_clan.cpos.utils.readU64
import org.plos_clan.cpos.utils.readU8

private const val ELF64_HEADER_SIZE = 64uL
private const val ELF64_SECTION_HEADER_SIZE = 64uL
private const val ELF64_SYMBOL_SIZE = 24uL

private const val ELF_IDENT_CLASS_OFFSET = 4
private const val ELF_IDENT_DATA_OFFSET = 5
private const val ELF_CLASS_64 = 2u
private const val ELF_DATA_LITTLE_ENDIAN = 1u

private const val ELF_E_SHOFF_OFFSET = 40
private const val ELF_E_SHENTSIZE_OFFSET = 58
private const val ELF_E_SHNUM_OFFSET = 60

private const val ELF_SH_TYPE_OFFSET = 4
private const val ELF_SH_OFFSET_OFFSET = 24
private const val ELF_SH_SIZE_OFFSET = 32
private const val ELF_SH_LINK_OFFSET = 40
private const val ELF_SH_ENTSIZE_OFFSET = 56

private const val ELF_SYM_NAME_OFFSET = 0
private const val ELF_SYM_INFO_OFFSET = 4
private const val ELF_SYM_SHNDX_OFFSET = 6
private const val ELF_SYM_VALUE_OFFSET = 8
private const val ELF_SYM_SIZE_OFFSET = 16

private const val ELF_SHT_SYMTAB = 2u
private const val ELF_SHT_STRTAB = 3u
private const val ELF_SHT_DYNSYM = 11u

private const val ELF_STT_NOTYPE = 0u
private const val ELF_STT_FUNC = 2u
private const val ELF_SHN_UNDEF = 0

private const val MAX_SYMBOL_NAME_LENGTH = 512uL

private data class SymbolEntry(
    val address: ULong,
    val size: ULong,
    val nameOffset: Int,
    val maxNameLength: Int,
) {
    fun contains(targetAddress: ULong, nextAddress: ULong?): Boolean =
        when {
            targetAddress < address -> false
            nextAddress != null -> targetAddress < nextAddress
            size == 0uL -> false
            else -> (targetAddress - address) < size
        }
}

private data class SymbolTable(
    val imageBase: CPointer<UByteVar>?,
    val symbols: List<SymbolEntry>,
)

private data class ElfSection(
    val type: UInt,
    val offset: ULong,
    val size: ULong,
    val link: Int,
    val entrySize: ULong,
) {
    val isSymbolTable: Boolean get() = type == ELF_SHT_SYMTAB || type == ELF_SHT_DYNSYM
    val isStringTable: Boolean get() = type == ELF_SHT_STRTAB

    fun isIn(imageSize: ULong): Boolean = isInRange(offset, size, imageSize)

    fun symbolCount(imageSize: ULong, sectionCount: Int): Int? {
        if (!isSymbolTable || entrySize < ELF64_SYMBOL_SIZE || size < entrySize) {
            return null
        }
        if (!isIn(imageSize) || link !in 0 until sectionCount) {
            return null
        }
        return (size / entrySize).toIntOrNull()
    }

    fun canServeStrings(imageSize: ULong): Boolean =
        isStringTable && size != 0uL && isIn(imageSize)
}

object KernelSymbolizer {
    private val symbolTable: SymbolTable by lazy(LazyThreadSafetyMode.NONE) {
        loadSymbolTable()
    }

    fun initialize() {
        symbolTable
    }

    fun describe(address: ULong): String {
        val rawAddress = address.hex()
        val symbol = symbolize(address) ?: return rawAddress
        return "$rawAddress <$symbol>"
    }

    private fun loadSymbolTable(): SymbolTable {
        val executableFile = executable_file_request.response
            ?.pointed
            ?.executable_file
            ?.pointed

        val image = executableFile?.address?.reinterpret<UByteVar>()
        val imageSize = executableFile?.size

        return if (image != null && imageSize != null && isValidElf(image, imageSize)) {
            SymbolTable(imageBase = image, symbols = parseSymbols(image, imageSize))
        } else {
            SymbolTable(imageBase = null, symbols = emptyList())
        }
    }

    private fun symbolize(address: ULong): String? {
        val table = symbolTable
        val symbol = findSymbol(table.symbols, address) ?: return null
        val name = readSymbolName(table.imageBase, symbol) ?: return null
        val offset = address - symbol.address
        if (offset == 0uL) {
            return name
        }
        return "$name+0x${offset.toString(16)}"
    }

    private fun findSymbol(entries: List<SymbolEntry>, address: ULong): SymbolEntry? {
        var index = entries.binarySearch { entry -> entry.address.compareTo(address) }
            .let { result -> if (result >= 0) result else -result - 2 }
        while (entries.getOrNull(index + 1)?.address == address) {
            index += 1
        }
        val candidate = entries.getOrNull(index) ?: return null
        val nextAddress = entries.getOrNull(index + 1)?.address
        return candidate.takeIf { it.contains(address, nextAddress) }
    }

    private fun parseSymbols(image: CPointer<UByteVar>, imageSize: ULong): List<SymbolEntry> {
        val sectionTableOffset = image.readU64(ELF_E_SHOFF_OFFSET)
        val sectionHeaderSize = image.readU16(ELF_E_SHENTSIZE_OFFSET).toULong()
        val sectionCount = image.readU16(ELF_E_SHNUM_OFFSET).toInt()

        if (
            sectionTableOffset == 0uL ||
            sectionHeaderSize < ELF64_SECTION_HEADER_SIZE ||
            sectionCount <= 0
        ) {
            return emptyList()
        }

        val sectionTableBytes = sectionHeaderSize * sectionCount.toULong()
        if (!isInRange(sectionTableOffset, sectionTableBytes, imageSize)) {
            return emptyList()
        }

        return buildList {
            for (sectionIndex in 0 until sectionCount) {
                val symbolSection = readSection(
                    image = image,
                    sectionTableOffset = sectionTableOffset,
                    sectionHeaderSize = sectionHeaderSize,
                    sectionIndex = sectionIndex,
                ) ?: continue
                val symbolCount = symbolSection.symbolCount(imageSize, sectionCount) ?: continue

                val stringSection = readSection(
                    image = image,
                    sectionTableOffset = sectionTableOffset,
                    sectionHeaderSize = sectionHeaderSize,
                    sectionIndex = symbolSection.link,
                ) ?: continue
                if (!stringSection.canServeStrings(imageSize)) {
                    continue
                }

                for (symbolIndex in 0 until symbolCount) {
                    val symbolOffset = symbolSection.offset + symbolSection.entrySize * symbolIndex.toULong()
                    val symbolOffsetInt = symbolOffset.toIntOrNull() ?: continue
                    parseSymbol(image, symbolOffsetInt, stringSection)?.let(::add)
                }
            }
        }
            .distinctBy { it.address to it.nameOffset }
            .sortedBy { it.address }
    }

    private fun parseSymbol(
        image: CPointer<UByteVar>,
        symbolOffset: Int,
        stringSection: ElfSection,
    ): SymbolEntry? {
        val nameOffset = image.readU32(symbolOffset + ELF_SYM_NAME_OFFSET)
        val info = image.readU8(symbolOffset + ELF_SYM_INFO_OFFSET).toUInt()
        val type = info and 0xFu
        val sectionRef = image.readU16(symbolOffset + ELF_SYM_SHNDX_OFFSET)
        val address = image.readU64(symbolOffset + ELF_SYM_VALUE_OFFSET)
        val size = image.readU64(symbolOffset + ELF_SYM_SIZE_OFFSET)

        if (nameOffset == 0u || address == 0uL || sectionRef.toInt() == ELF_SHN_UNDEF) {
            return null
        }
        if (!address.isCanonicalKernelAddress() || (type != ELF_STT_FUNC && type != ELF_STT_NOTYPE)) {
            return null
        }

        val relativeNameOffset = nameOffset.toULong().takeIf { it < stringSection.size } ?: return null
        val nameStart = (stringSection.offset + relativeNameOffset).toIntOrNull() ?: return null
        val maxNameLength = minOf(
            stringSection.size - relativeNameOffset,
            MAX_SYMBOL_NAME_LENGTH,
        ).toIntOrNull() ?: return null
        if (maxNameLength <= 0) {
            return null
        }

        return SymbolEntry(
            address = address,
            size = size,
            nameOffset = nameStart,
            maxNameLength = maxNameLength,
        ).takeUnless { image.readU8(nameStart).isHiddenSymbolPrefix() }
    }

    private fun readSection(
        image: CPointer<UByteVar>,
        sectionTableOffset: ULong,
        sectionHeaderSize: ULong,
        sectionIndex: Int,
    ): ElfSection? {
        val sectionOffset = sectionTableOffset + sectionHeaderSize * sectionIndex.toULong()
        val offset = sectionOffset.toIntOrNull() ?: return null
        return ElfSection(
            type = image.readU32(offset + ELF_SH_TYPE_OFFSET),
            offset = image.readU64(offset + ELF_SH_OFFSET_OFFSET),
            size = image.readU64(offset + ELF_SH_SIZE_OFFSET),
            link = image.readU32(offset + ELF_SH_LINK_OFFSET).toInt(),
            entrySize = image.readU64(offset + ELF_SH_ENTSIZE_OFFSET),
        )
    }

    private fun readSymbolName(image: CPointer<UByteVar>?, symbol: SymbolEntry): String? {
        image ?: return null
        val length = (0 until symbol.maxNameLength)
            .firstOrNull { index -> image.readU8(symbol.nameOffset + index) == 0.toUByte() }
            ?: symbol.maxNameLength
        return length.takeIf { it > 0 }?.let { image.readAscii(symbol.nameOffset, it) }
    }

    private fun isValidElf(image: CPointer<UByteVar>, imageSize: ULong): Boolean {
        if (imageSize < ELF64_HEADER_SIZE) {
            return false
        }

        val hasMagic =
            image.readU8(0) == 0x7Fu.toUByte() &&
                image.readU8(1) == 'E'.code.toUByte() &&
                image.readU8(2) == 'L'.code.toUByte() &&
                image.readU8(3) == 'F'.code.toUByte()

        if (!hasMagic) {
            return false
        }

        return image.readU8(ELF_IDENT_CLASS_OFFSET).toUInt() == ELF_CLASS_64 &&
            image.readU8(ELF_IDENT_DATA_OFFSET).toUInt() == ELF_DATA_LITTLE_ENDIAN
    }
}

private fun isInRange(offset: ULong, size: ULong, limit: ULong): Boolean {
    return offset <= limit && size <= limit - offset
}

private fun ULong.toIntOrNull(): Int? =
    if (this <= Int.MAX_VALUE.toULong()) this.toInt() else null

private fun UByte.isHiddenSymbolPrefix(): Boolean =
    this == '.'.code.toUByte() || this == '$'.code.toUByte()
