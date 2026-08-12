@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml

import bridge.io_in8
import bridge.io_out8
import org.plos_clan.cpos.drivers.pcie.Pcie
import org.plos_clan.cpos.mem.CachedMmioRegion
import org.plos_clan.cpos.utils.IrqSpinLock

private const val REGION_SYSTEM_MEMORY = 0u
private const val REGION_SYSTEM_IO = 1u
private const val REGION_PCI_CONFIG = 2u

internal class AmlRegionManager(
    private val namespace: AmlNamespace,
) {
    private val lock = IrqSpinLock()
    private val memory = CachedMmioRegion()

    fun read(field: AmlFieldUnit): AmlObject? {
        if (field.bitLength == 0uL) {
            return AmlInteger(0uL)
        }
        if (field.bitLength > ULong.MAX_VALUE - field.bitOffset) {
            return null
        }
        if (field.bitLength > ULong.SIZE_BITS.toULong()) {
            return readBuffer(field)
        }

        val operationRegion = resolveRegion(field) ?: return null
        val value = lock.withLock {
            var result = 0uL
            var cachedByteOffset = ULong.MAX_VALUE
            var cachedByte = 0u
            var bit = 0uL
            while (bit < field.bitLength) {
                val sourceBit = field.bitOffset + bit
                val byteOffset = sourceBit / 8uL
                if (byteOffset != cachedByteOffset) {
                    cachedByte = readByte(operationRegion, byteOffset) ?: return@withLock null
                    cachedByteOffset = byteOffset
                }
                if ((cachedByte and (1u shl (sourceBit % 8uL).toInt())) != 0u) {
                    result = result or (1uL shl bit.toInt())
                }
                bit++
            }
            result
        } ?: return null
        return AmlInteger(value)
    }

    fun write(field: AmlFieldUnit, value: AmlObject): Boolean {
        val source = value.integerValue() ?: return false
        if (field.bitLength == 0uL || field.bitLength > ULong.SIZE_BITS.toULong()) {
            return false
        }
        if (field.bitLength > ULong.MAX_VALUE - field.bitOffset) {
            return false
        }
        val operationRegion = resolveRegion(field) ?: return false

        return lock.withLock {
            val firstByte = field.bitOffset / 8uL
            val lastByte = (field.bitOffset + field.bitLength - 1uL) / 8uL
            var byteOffset = firstByte
            while (byteOffset <= lastByte) {
                val original = when (field.updateRule) {
                    AmlFieldUpdateRule.PRESERVE -> readByte(operationRegion, byteOffset) ?: return@withLock false
                    AmlFieldUpdateRule.WRITE_ONES -> 0xFFu
                    AmlFieldUpdateRule.WRITE_ZEROES -> 0u
                }
                var updated = original
                var bitInByte = 0
                while (bitInByte < 8) {
                    val absoluteBit = byteOffset * 8uL + bitInByte.toULong()
                    if (absoluteBit >= field.bitOffset &&
                        absoluteBit < field.bitOffset + field.bitLength
                    ) {
                        val sourceBit = (absoluteBit - field.bitOffset).toInt()
                        val mask = 1u shl bitInByte
                        updated = if ((source and (1uL shl sourceBit)) != 0uL) {
                            updated or mask
                        } else {
                            updated and mask.inv()
                        }
                    }
                    bitInByte++
                }
                if (!writeByte(operationRegion, byteOffset, updated.toUByte())) {
                    return@withLock false
                }
                byteOffset++
            }
            true
        }
    }

    private fun readBuffer(field: AmlFieldUnit): AmlObject? {
        if (field.bitOffset % 8uL != 0uL || field.bitLength % 8uL != 0uL) {
            return null
        }
        if (field.bitLength > ULong.MAX_VALUE - field.bitOffset) {
            return null
        }
        val byteCount = field.bitLength / 8uL
        if (byteCount > Int.MAX_VALUE.toULong()) {
            return null
        }
        val operationRegion = resolveRegion(field) ?: return null
        return lock.withLock {
            val bytes = ByteArray(byteCount.toInt())
            repeat(bytes.size) { index ->
                bytes[index] = (readByte(
                    operationRegion,
                    field.bitOffset / 8uL + index.toULong(),
                ) ?: return@withLock null).toByte()
            }
            AmlBuffer(bytes)
        }
    }

    private fun resolveRegion(field: AmlFieldUnit): AmlOperationRegion? {
        val node = namespace.resolve(field.declarationScope, field.regionName) ?: return null
        return resolveAlias(node, mutableSetOf())?.value as? AmlOperationRegion
    }

    private fun resolveAlias(
        node: AmlNamespaceNode,
        visited: MutableSet<AmlName>,
    ): AmlNamespaceNode? {
        if (!visited.add(node.name)) {
            return null
        }
        val alias = node.value as? AmlAlias ?: return node
        val target = namespace.resolve(alias.declarationScope, alias.target) ?: return null
        return resolveAlias(target, visited)
    }

    private fun readByte(region: AmlOperationRegion, relativeOffset: ULong): UInt? {
        if (relativeOffset >= region.length) {
            return null
        }
        if (relativeOffset > ULong.MAX_VALUE - region.offset) {
            return null
        }
        val address = region.offset + relativeOffset
        return when (region.spaceId) {
            REGION_SYSTEM_MEMORY -> {
                memory.addressAt(address)?.readU8()?.toUInt()
            }
            REGION_SYSTEM_IO -> {
                if (address > 0xFFFFuL) null else io_in8(address.toUShort()).toUInt()
            }
            REGION_PCI_CONFIG -> {
                val pci = pciAddress(region.declarationScope) ?: return null
                Pcie.readConfig(
                    segment = pci.segment,
                    bus = pci.bus,
                    device = pci.device,
                    function = pci.function,
                    offset = address.toUInt(),
                    byteCount = 1,
                )?.toUInt()
            }
            else -> null
        }
    }

    private fun writeByte(
        region: AmlOperationRegion,
        relativeOffset: ULong,
        value: UByte,
    ): Boolean {
        if (relativeOffset >= region.length) {
            return false
        }
        if (relativeOffset > ULong.MAX_VALUE - region.offset) {
            return false
        }
        val address = region.offset + relativeOffset
        return when (region.spaceId) {
            REGION_SYSTEM_MEMORY -> {
                memory.addressAt(address)?.writeU8(value) ?: return false
                true
            }
            REGION_SYSTEM_IO -> {
                if (address > 0xFFFFuL) {
                    false
                } else {
                    io_out8(address.toUShort(), value)
                    true
                }
            }
            REGION_PCI_CONFIG -> {
                val pci = pciAddress(region.declarationScope) ?: return false
                Pcie.writeConfig(
                    segment = pci.segment,
                    bus = pci.bus,
                    device = pci.device,
                    function = pci.function,
                    offset = address.toUInt(),
                    byteCount = 1,
                    value = value.toULong(),
                )
            }
            else -> false
        }
    }

    private fun pciAddress(scope: AmlName): AmlPciAddress? {
        val segment = findAncestorInteger(scope, "_SEG") ?: 0uL
        val bus = findAncestorInteger(scope, "_BBN") ?: 0uL
        val adr = findAncestorInteger(scope, "_ADR") ?: return null
        return AmlPciAddress(
            segment = segment.toUInt(),
            bus = bus.toUInt(),
            device = ((adr shr 16) and 0xFFFFuL).toUInt(),
            function = (adr and 0xFFFFuL).toUInt(),
        )
    }

    private fun findAncestorInteger(scope: AmlName, segment: String): ULong? {
        var current = scope
        while (true) {
            val value = namespace.find(current.child(segment))?.value
            when (value) {
                is AmlInteger -> return value.value
                is AmlAlias -> {
                    val node = namespace.resolve(current, value.target)
                    (node?.value as? AmlInteger)?.let { return it.value }
                }
                else -> Unit
            }
            if (current.isRoot) {
                return null
            }
            current = current.parent
        }
    }
}

private data class AmlPciAddress(
    val segment: UInt,
    val bus: UInt,
    val device: UInt,
    val function: UInt,
)
