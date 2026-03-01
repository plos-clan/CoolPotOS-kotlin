@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.fault

import bridge.executable_file_request
import kotlinx.cinterop.*
import org.plos_clan.cpos.utils.*

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
    val strtabOffset: ULong,
    val strtabSize: ULong,
    val nameOffset: UInt,
)

object KernelSymbolizer {
    private var imageBase: CPointer<UByteVar>? = null
    private var symbols: List<SymbolEntry> = emptyList()
    private var initialized = false

    fun initialize() {
        if (initialized) {
            return
        }

        val executableFile = executable_file_request.response
            ?.pointed
            ?.executable_file
            ?.pointed

        val image = executableFile?.address?.reinterpret<UByteVar>()
        val imageSize = executableFile?.size

        if (image != null && imageSize != null && isValidElf(image, imageSize)) {
            imageBase = image
            symbols = parseSymbols(image, imageSize)
        }

        initialized = true
    }

    fun describe(address: ULong): String {
        val rawAddress = address.hex()
        val symbol = symbolize(address) ?: return rawAddress
        return "$rawAddress <$symbol>"
    }

    private fun symbolize(address: ULong): String? {
        if (!initialized) {
            initialize()
        }

        val symbol = findSymbol(address) ?: return null
        val name = readSymbolName(symbol) ?: return null
        val offset = address - symbol.address
        if (offset == 0uL) {
            return name
        }
        return "$name+0x${offset.toString(16)}"
    }

    private fun findSymbol(address: ULong): SymbolEntry? {
        val entries = symbols
        if (entries.isEmpty()) {
            return null
        }

        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (entries[mid].address <= address) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        val index = low - 1
        if (index < 0) {
            return null
        }

        val candidate = entries[index]
        if (address < candidate.address) {
            return null
        }

        val nextAddress = entries.getOrNull(index + 1)?.address
        if (nextAddress != null) {
            return if (address < nextAddress) candidate else null
        }

        if (candidate.size == 0uL) {
            return null
        }
        return if ((address - candidate.address) < candidate.size) candidate else null
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

        val result = mutableListOf<SymbolEntry>()
        for (sectionIndex in 0 until sectionCount) {
            val sectionOffset = sectionTableOffset + sectionHeaderSize * sectionIndex.toULong()
            val sectionOffsetInt = sectionOffset.toIntOrNull() ?: continue
            val sectionType = image.readU32(sectionOffsetInt + ELF_SH_TYPE_OFFSET)
            if (sectionType != ELF_SHT_SYMTAB && sectionType != ELF_SHT_DYNSYM) {
                continue
            }

            val symbolTableOffset = image.readU64(sectionOffsetInt + ELF_SH_OFFSET_OFFSET)
            val symbolTableSize = image.readU64(sectionOffsetInt + ELF_SH_SIZE_OFFSET)
            val symbolEntrySize = image.readU64(sectionOffsetInt + ELF_SH_ENTSIZE_OFFSET)
            val linkedStrtabIndex = image.readU32(sectionOffsetInt + ELF_SH_LINK_OFFSET).toInt()

            if (symbolEntrySize < ELF64_SYMBOL_SIZE || symbolTableSize < symbolEntrySize) {
                continue
            }
            if (!isInRange(symbolTableOffset, symbolTableSize, imageSize)) {
                continue
            }
            if (linkedStrtabIndex !in 0 until sectionCount) {
                continue
            }

            val strtabSectionOffset = sectionTableOffset + sectionHeaderSize * linkedStrtabIndex.toULong()
            val strtabSectionOffsetInt = strtabSectionOffset.toIntOrNull() ?: continue
            val strtabType = image.readU32(strtabSectionOffsetInt + ELF_SH_TYPE_OFFSET)
            if (strtabType != ELF_SHT_STRTAB) {
                continue
            }

            val strtabOffset = image.readU64(strtabSectionOffsetInt + ELF_SH_OFFSET_OFFSET)
            val strtabSize = image.readU64(strtabSectionOffsetInt + ELF_SH_SIZE_OFFSET)
            if (strtabSize == 0uL || !isInRange(strtabOffset, strtabSize, imageSize)) {
                continue
            }

            val symbolCount = (symbolTableSize / symbolEntrySize).toIntOrNull() ?: continue
            for (symbolIndex in 0 until symbolCount) {
                val symbolOffset = symbolTableOffset + symbolEntrySize * symbolIndex.toULong()
                val symbolOffsetInt = symbolOffset.toIntOrNull() ?: continue

                val nameOffset = image.readU32(symbolOffsetInt + ELF_SYM_NAME_OFFSET)
                val info = image.readU8(symbolOffsetInt + ELF_SYM_INFO_OFFSET).toUInt()
                val type = info and 0xFu
                val sectionRef = image.readU16(symbolOffsetInt + ELF_SYM_SHNDX_OFFSET)
                val value = image.readU64(symbolOffsetInt + ELF_SYM_VALUE_OFFSET)
                val size = image.readU64(symbolOffsetInt + ELF_SYM_SIZE_OFFSET)

                if (nameOffset == 0u || value == 0uL || sectionRef.toInt() == ELF_SHN_UNDEF) {
                    continue
                }
                if (!value.isCanonicalKernelAddress()) {
                    continue
                }
                if (type != ELF_STT_FUNC && type != ELF_STT_NOTYPE) {
                    continue
                }
                if (nameOffset.toULong() >= strtabSize) {
                    continue
                }
                val nameStart = (strtabOffset + nameOffset.toULong()).toIntOrNull() ?: continue
                val nameFirstByte = image.readU8(nameStart)
                if (nameFirstByte == '.'.code.toUByte() || nameFirstByte == '$'.code.toUByte()) {
                    continue
                }

                result += SymbolEntry(
                    address = value,
                    size = size,
                    strtabOffset = strtabOffset,
                    strtabSize = strtabSize,
                    nameOffset = nameOffset,
                )
            }
        }

        return result
            .distinctBy { Triple(it.address, it.strtabOffset, it.nameOffset) }
            .sortedBy { it.address }
    }

    private fun readSymbolName(symbol: SymbolEntry): String? {
        val image = imageBase ?: return null
        val relativeOffset = symbol.nameOffset.toULong()
        if (relativeOffset >= symbol.strtabSize) {
            return null
        }

        val start = symbol.strtabOffset + relativeOffset
        val startInt = start.toIntOrNull() ?: return null
        val available = symbol.strtabSize - relativeOffset
        val maxLength = minOf(available, MAX_SYMBOL_NAME_LENGTH).toIntOrNull() ?: return null
        if (maxLength <= 0) {
            return null
        }

        var length = 0
        while (length < maxLength && image.readU8(startInt + length) != 0.toUByte()) {
            length++
        }
        if (length <= 0) {
            return null
        }

        return image.readAscii(startInt, length)
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
    if (offset > limit) {
        return false
    }
    return size <= limit - offset
}

private fun ULong.toIntOrNull(): Int? =
    if (this <= Int.MAX_VALUE.toULong()) this.toInt() else null
