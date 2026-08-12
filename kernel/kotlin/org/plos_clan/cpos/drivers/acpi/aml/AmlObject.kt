package org.plos_clan.cpos.drivers.acpi.aml

import org.plos_clan.cpos.utils.LittleEndianBuffer

sealed class AmlObject

data object AmlUninitialized : AmlObject()

data class AmlInteger(val value: ULong) : AmlObject()

data class AmlString(val value: String) : AmlObject()

data class AmlBuffer(val bytes: ByteArray) : AmlObject() {
    override fun equals(other: Any?): Boolean =
        other is AmlBuffer && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

data class AmlPackage(val elements: MutableList<AmlObject>) : AmlObject()

data object AmlDevice : AmlObject()

data object AmlProcessor : AmlObject()

data object AmlThermalZone : AmlObject()

class AmlMethod internal constructor(
    val argumentCount: Int,
    val serialized: Boolean,
    val syncLevel: Int,
    internal val source: AmlByteSource,
    internal val bodyStart: Int,
    internal val bodyEnd: Int,
    internal val declarationScope: AmlName,
) : AmlObject()

data class AmlOperationRegion(
    val spaceId: UInt,
    val offset: ULong,
    val length: ULong,
    val declarationScope: AmlName,
) : AmlObject()

enum class AmlFieldUpdateRule {
    PRESERVE,
    WRITE_ONES,
    WRITE_ZEROES,
}

data class AmlFieldUnit(
    val regionName: AmlNamePath,
    val declarationScope: AmlName,
    val bitOffset: ULong,
    val bitLength: ULong,
    val accessType: UInt,
    val lockRule: Boolean,
    val updateRule: AmlFieldUpdateRule,
) : AmlObject()

data class AmlAlias(
    val target: AmlNamePath,
    val declarationScope: AmlName,
) : AmlObject()

data class AmlMutex(val syncLevel: UInt) : AmlObject()

data class AmlEvent(var signalled: Boolean = false) : AmlObject()

internal class AmlReference(
    val read: () -> AmlObject,
    val write: (AmlObject) -> Boolean,
) : AmlObject()

internal fun AmlObject.dereference(): AmlObject =
    if (this is AmlReference) read() else this

internal fun AmlObject.integerValue(): ULong? =
    when (val value = dereference()) {
        is AmlInteger -> value.value
        is AmlBuffer -> LittleEndianBuffer(value.bytes)
            .readUnsigned(0, minOf(value.bytes.size, ULong.SIZE_BYTES))
        is AmlString -> value.value.toULongOrNull()
        else -> null
    }
