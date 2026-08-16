@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.usb.adapt.hid

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.get

const val ITEM_TYPE_MAIN = 0u
const val ITEM_TYPE_GLOBAL = 1u
const val ITEM_TYPE_LOCAL = 2u

const val TAG_INPUT = 0b1000u
const val TAG_OUTPUT = 0b1001u
const val TAG_COLLECTION = 0b1010u
const val TAG_FEATURE = 0b1011u
const val TAG_END_COLLECTION = 0b1100u

const val TAG_USAGE_PAGE = 0b0000u
const val TAG_LOGICAL_MIN = 0b0001u
const val TAG_LOGICAL_MAX = 0b0010u
const val TAG_PHYSICAL_MIN = 0b0011u
const val TAG_PHYSICAL_MAX = 0b0100u
const val TAG_UNIT_EXP = 0b0101u
const val TAG_UNIT = 0b0110u
const val TAG_REPORT_SIZE = 0b0111u
const val TAG_REPORT_ID = 0b1000u
const val TAG_REPORT_COUNT = 0b1001u
const val TAG_PUSH = 0b1010u
const val TAG_POP = 0b1011u

const val TAG_USAGE = 0b0000u
const val TAG_USAGE_MIN = 0b0001u
const val TAG_USAGE_MAX = 0b0010u

const val FLAG_CONSTANT = 0x0000_0001u
const val FLAG_VARIABLE = 0x0000_0002u
const val FLAG_RELATIVE = 0x0000_0004u
const val FLAG_WRAP = 0x0000_0008u
const val FLAG_NONLINEAR = 0x0000_0010u
const val FLAG_NO_PREF = 0x0000_0020u
const val FLAG_NULL_STATE = 0x0000_0040u
const val FLAG_VOLATILE = 0x0000_0080u
const val FLAG_BUFFERED = 0x0000_0100u

enum class HidKind {
    INPUT,
    OUTPUT,
    FEATURE,
}

class HidField(
    val reportId: UByte,
    val kind: HidKind,
    val bitOffset: UInt,
    val bitSize: UInt,
    val reportCount: UInt,
    val logicalMin: Int,
    val logicalMax: Int,
    val physicalMin: Int,
    val physicalMax: Int,
    val flags: UInt,
    val usagePage: UShort,
    val usageMin: UInt,
    val usageMax: UInt,
) {
    fun isConst(): Boolean = flags and FLAG_CONSTANT != 0u

    fun isVariable(): Boolean = flags and FLAG_VARIABLE != 0u

    fun isArray(): Boolean = flags and FLAG_VARIABLE == 0u

    fun isRelative(): Boolean = flags and FLAG_RELATIVE != 0u

    fun isRange(): Boolean = usageMin != usageMax

    fun value(data: CPointer<UByteVar>, index: UInt): UInt {
        val offset = bitOffset + index * bitSize
        val byteIndex = (offset / 8u).toInt()
        val shift = (offset % 8u).toInt()

        val count = ((offset + bitSize + 7u) / 8u).toInt() - byteIndex
        var raw = 0uL
        for (i in 0 until count) {
            raw = raw or (data[byteIndex + i].toULong() shl (i * 8))
        }
        return ((raw shr shift) and ((1uL shl bitSize.toInt()) - 1uL)).toUInt()
    }

    fun valueSigned(data: CPointer<UByteVar>, index: UInt): Int {
        val value = value(data, index)

        if (bitSize >= 32u) {
            return value.toInt()
        }

        val shift = 32 - bitSize.toInt()
        return (value shl shift).toInt() shr shift
    }
}

class HidReport(
    val id: UByte,
) {
    val sizeBits = UIntArray(3)
    val fields = mutableListOf<HidField>()

    fun sizeBytes(kind: HidKind): UInt = (sizeBits[kind.ordinal] + 7u) / 8u
}

class HidDescriptor {
    val reports = mutableMapOf<UByte, HidReport>()

    fun free() {}
}

private class LocalItem(
    val isRange: Boolean,
    val min: UInt,
    var max: UInt,
)

private class LocalState {
    val items = mutableListOf<LocalItem>()
}

private data class GlobalState(
    var usagePage: UShort = 0u,
    var logicalMin: Int = 0,
    var logicalMax: Int = 0,
    var physicalMin: Int = 0,
    var physicalMax: Int = 0,
    var reportSize: UInt = 0u,
    var reportCount: UInt = 0u,
    var reportId: UByte = 0u,
)

class HidParser(
    private val data: CPointer<UByteVar>,
    private val length: UShort,
) {
    private var offset: UShort = 0u
    private var global = GlobalState()
    private val globalStack = mutableListOf<GlobalState>()
    private val local = LocalState()
    private val descriptor = HidDescriptor()

    fun parse(): HidDescriptor? {
        while (offset < length) {
            val header = data[offset.toInt()]
            offset++

            val sizeCode = header and 0x03u.toUByte()
            val dataLength = if (sizeCode == 3u.toUByte()) 4u.toUShort() else sizeCode.toUShort()

            val itemType = (header.toUInt() shr 2) and 0x03u
            val itemTag = (header.toUInt() shr 4) and 0x0fu

            if (offset.toUInt() + dataLength.toUInt() > length.toUInt()) {
                println("HID: Truncated at offset $offset")
                break
            }

            when (itemType) {
                ITEM_TYPE_MAIN -> {
                    val flags = readUnsigned(dataLength)
                    handleMain(itemTag, flags)
                }
                ITEM_TYPE_GLOBAL -> {
                    handleGlobal(itemTag, dataLength)
                }
                ITEM_TYPE_LOCAL -> {
                    val value = readUnsigned(dataLength)
                    handleLocal(itemTag, dataLength, value)
                }
                else -> {}
            }
        }

        return descriptor
    }

    private fun handleGlobal(tag: UInt, length: UShort) {
        when (tag) {
            TAG_USAGE_PAGE -> global.usagePage = readUnsigned(length).toUShort()
            TAG_LOGICAL_MIN -> global.logicalMin = readSigned(length)
            TAG_LOGICAL_MAX -> global.logicalMax = readSigned(length)
            TAG_PHYSICAL_MIN -> global.physicalMin = readSigned(length)
            TAG_PHYSICAL_MAX -> global.physicalMax = readSigned(length)
            TAG_REPORT_SIZE -> global.reportSize = readUnsigned(length)
            TAG_REPORT_COUNT -> global.reportCount = readUnsigned(length)
            TAG_REPORT_ID -> global.reportId = readUnsigned(length).toUByte()
            TAG_PUSH -> globalStack.add(global.copy())
            TAG_POP -> {
                if (globalStack.isNotEmpty()) {
                    global = globalStack.removeLast()
                } else {
                    println("HID: Global stack pop underflow")
                }
            }
            else -> offset = (offset + length).toUShort()
        }
    }

    private fun handleLocal(tag: UInt, dataLength: UShort, value: UInt) {
        val isExtended = dataLength == 4u.toUShort()

        val fullUsage = if (isExtended) {
            value
        } else {
            (global.usagePage.toUInt() shl 16) or value
        }

        when (tag) {
            TAG_USAGE -> local.items.add(LocalItem(isRange = false, min = fullUsage, max = fullUsage))
            TAG_USAGE_MIN -> local.items.add(LocalItem(isRange = true, min = fullUsage, max = 0u))
            TAG_USAGE_MAX -> local.items.lastOrNull()?.let { it.max = fullUsage }
            else -> {}
        }
    }

    private fun handleMain(tag: UInt, flags: UInt) {
        try {
            val kind = when (tag) {
                TAG_INPUT -> HidKind.INPUT
                TAG_OUTPUT -> HidKind.OUTPUT
                TAG_FEATURE -> HidKind.FEATURE
                else -> return
            }

            val kindIndex = kind.ordinal
            val reportId = global.reportId

            val layout = descriptor.reports.getOrPut(reportId) {
                HidReport(reportId).also { report ->
                    if (reportId != 0u.toUByte()) {
                        report.sizeBits[0] = 8u
                        report.sizeBits[1] = 8u
                        report.sizeBits[2] = 8u
                    }
                }
            }

            val reportCount = global.reportCount
            val reportSize = global.reportSize
            val isVariable = flags and FLAG_VARIABLE != 0u
            val isSingleRange = local.items.size == 1 && local.items[0].isRange

            if (!isVariable && isSingleRange) {
                val usageItem = local.items[0]
                layout.fields.add(
                    HidField(
                        reportId = reportId,
                        kind = kind,
                        bitOffset = layout.sizeBits[kindIndex],
                        bitSize = reportSize,
                        reportCount = reportCount,
                        logicalMin = global.logicalMin,
                        logicalMax = global.logicalMax,
                        physicalMin = global.physicalMin,
                        physicalMax = global.physicalMax,
                        flags = flags,
                        usagePage = global.usagePage,
                        usageMin = usageItem.min,
                        usageMax = usageItem.max,
                    ),
                )
                layout.sizeBits[kindIndex] += reportSize * reportCount
            } else {
                var itemIndex = 0
                var rangeOffset = 0u
                for (iteration in 0 until reportCount.toInt()) {
                    var currentUsage = 0u
                    local.items.getOrNull(itemIndex)?.let { item ->
                        currentUsage = item.min + rangeOffset
                        if (currentUsage < item.max) {
                            rangeOffset++
                        } else if (itemIndex < local.items.size - 1) {
                            itemIndex++
                            rangeOffset = 0u
                        }
                    }
                    layout.fields.add(
                        HidField(
                            reportId = reportId,
                            kind = kind,
                            bitOffset = layout.sizeBits[kindIndex],
                            bitSize = reportSize,
                            reportCount = 1u,
                            logicalMin = global.logicalMin,
                            logicalMax = global.logicalMax,
                            physicalMin = global.physicalMin,
                            physicalMax = global.physicalMax,
                            flags = flags,
                            usagePage = global.usagePage,
                            usageMin = currentUsage,
                            usageMax = currentUsage,
                        ),
                    )
                    layout.sizeBits[kindIndex] += reportSize
                }
            }
        } finally {
            local.items.clear()
        }
    }

    private fun readSigned(length: UShort): Int {
        val value = readUnsigned(length)
        return when (length) {
            1u.toUShort() -> value.toUByte().toByte().toInt()
            2u.toUShort() -> value.toUShort().toShort().toInt()
            4u.toUShort() -> value.toInt()
            else -> 0
        }
    }

    private fun readUnsigned(length: UShort): UInt {
        var value = 0u
        when (length) {
            1u.toUShort() -> value = data[offset.toInt()].toUInt()
            2u.toUShort() -> {
                val b0 = data[offset.toInt()].toUInt()
                val b1 = data[offset.toInt() + 1].toUInt()
                value = b0 or (b1 shl 8)
            }
            4u.toUShort() -> {
                val b0 = data[offset.toInt()].toUInt()
                val b1 = data[offset.toInt() + 1].toUInt()
                val b2 = data[offset.toInt() + 2].toUInt()
                val b3 = data[offset.toInt() + 3].toUInt()
                value = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            }
            else -> {}
        }

        offset = (offset + length).toUShort()
        return value
    }
}
