package org.plos_clan.cpos.drivers.acpi.aml

import org.plos_clan.cpos.utils.LittleEndianBuffer

sealed class AmlObject {
    internal open fun dereference(): AmlObject = this

    internal open fun integerValue(): ULong? =
        dereference().takeIf { it !== this }?.integerValue()

    internal open fun toBytes(): ByteArray = ByteArray(0)

    internal open val objectType: ULong
        get() = 0uL

    internal open fun runtimeCopy(): AmlObject = this
}

data object AmlUninitialized : AmlObject()

data class AmlInteger(val value: ULong) : AmlObject() {
    override fun integerValue(): ULong = value

    override fun toBytes(): ByteArray = ByteArray(ULong.SIZE_BYTES) { index ->
        (value shr (index * 8)).toByte()
    }

    override val objectType: ULong
        get() = 1uL
}

data class AmlString(val value: String) : AmlObject() {
    override fun integerValue(): ULong? = value.toULongOrNull()

    override fun toBytes(): ByteArray = value.encodeToByteArray() + byteArrayOf(0)

    override val objectType: ULong
        get() = 2uL
}

data class AmlBuffer(val bytes: ByteArray) : AmlObject() {
    override fun integerValue(): ULong =
        LittleEndianBuffer(bytes).readUnsigned(0, minOf(bytes.size, ULong.SIZE_BYTES))

    override fun toBytes(): ByteArray = bytes.copyOf()

    override val objectType: ULong
        get() = 3uL

    override fun runtimeCopy(): AmlObject = AmlBuffer(bytes.copyOf())

    override fun equals(other: Any?): Boolean =
        other is AmlBuffer && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

data class AmlPackage(val elements: MutableList<AmlObject>) : AmlObject() {
    override val objectType: ULong
        get() = 4uL

    override fun runtimeCopy(): AmlObject = AmlPackage(elements.toMutableList())
}

data object AmlDevice : AmlObject() {
    override val objectType: ULong
        get() = 6uL
}

data object AmlProcessor : AmlObject() {
    override val objectType: ULong
        get() = 12uL
}

data object AmlThermalZone : AmlObject() {
    override val objectType: ULong
        get() = 13uL
}

class AmlMethod internal constructor(
    val argumentCount: Int,
    val serialized: Boolean,
    val syncLevel: Int,
    internal val source: AmlByteSource,
    internal val bodyStart: Int,
    internal val bodyEnd: Int,
    internal val declarationScope: AmlName,
) : AmlObject() {
    override val objectType: ULong
        get() = 8uL
}

data class AmlOperationRegion(
    val spaceId: UInt,
    val offset: ULong,
    val length: ULong,
    val declarationScope: AmlName,
) : AmlObject() {
    override val objectType: ULong
        get() = 10uL
}

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
) : AmlObject() {
    override val objectType: ULong
        get() = 5uL
}

data class AmlAlias(
    val target: AmlNamePath,
    val declarationScope: AmlName,
) : AmlObject()

data class AmlMutex(val syncLevel: UInt) : AmlObject() {
    override val objectType: ULong
        get() = 9uL
}

data class AmlEvent(var signalled: Boolean = false) : AmlObject() {
    override val objectType: ULong
        get() = 7uL
}

internal class AmlReference(
    val read: () -> AmlObject,
    val write: (AmlObject) -> Boolean,
) : AmlObject() {
    override fun dereference(): AmlObject = read()

    override fun toBytes(): ByteArray = dereference().toBytes()
}
