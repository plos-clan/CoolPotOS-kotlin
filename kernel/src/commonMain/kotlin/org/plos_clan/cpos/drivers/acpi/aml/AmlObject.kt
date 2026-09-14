package org.plos_clan.cpos.drivers.acpi.aml

import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_ADD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_AND_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_DIVIDE_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_MOD_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_MULTIPLY_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_NAND_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_NOR_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_OR_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_SHIFT_LEFT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_SHIFT_RIGHT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_SUBTRACT_OP
import org.plos_clan.cpos.drivers.acpi.aml.evaluator.AML_XOR_OP
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

internal sealed class AmlTermArg(
    open val start: Int,
    open val end: Int,
) {
    abstract fun staticInteger(
        namespace: AmlNamespace,
        visited: MutableSet<AmlName> = mutableSetOf(),
    ): ULong?

    data class Integer(
        val value: ULong,
        override val start: Int,
        override val end: Int,
    ) : AmlTermArg(start, end) {
        override fun staticInteger(namespace: AmlNamespace, visited: MutableSet<AmlName>): ULong = value
    }

    data class Name(
        val path: AmlNamePath,
        val declarationScope: AmlName,
        override val start: Int,
        override val end: Int,
    ) : AmlTermArg(start, end) {
        override fun staticInteger(
            namespace: AmlNamespace,
            visited: MutableSet<AmlName>,
        ): ULong? {
            val node = namespace.resolve(declarationScope, path) ?: return null
            if (!visited.add(node.name)) return null
            return try {
                AmlTermResolver.staticInteger(node.value, namespace, visited)
            } finally {
                visited.remove(node.name)
            }
        }
    }

    data class Operation(
        val opcode: UInt,
        val operands: List<AmlTermArg>,
        override val start: Int,
        override val end: Int,
    ) : AmlTermArg(start, end) {
        override fun staticInteger(
            namespace: AmlNamespace,
            visited: MutableSet<AmlName>,
        ): ULong? {
            val values = ArrayList<ULong>(operands.size)
            operands.forEach { operand ->
                values += operand.staticInteger(namespace, visited) ?: return null
            }
            return AmlTermResolver.staticOperation(opcode, values)
        }
    }

    data class Deferred(
        val opcode: UInt?,
        override val start: Int,
        override val end: Int,
    ) : AmlTermArg(start, end) {
        override fun staticInteger(namespace: AmlNamespace, visited: MutableSet<AmlName>): ULong? = null
    }

    internal fun asObject(namespace: AmlNamespace): AmlObject = when (this) {
        is Integer -> AmlInteger(value)
        is Name -> AmlAlias(path, declarationScope)
        else -> AmlDeferredValue(this, namespace)
    }
}

internal object AmlTermResolver {
    fun staticInteger(
        value: AmlObject,
        namespace: AmlNamespace,
        visited: MutableSet<AmlName>,
    ): ULong? = when (value) {
        is AmlInteger -> value.value
        is AmlBuffer, is AmlString -> value.integerValue()
        is AmlAlias -> {
            val node = namespace.resolve(value.declarationScope, value.target) ?: return null
            if (!visited.add(node.name)) return null
            try {
                staticInteger(node.value, namespace, visited)
            } finally {
                visited.remove(node.name)
            }
        }
        is AmlDeferredValue -> value.expression.staticInteger(namespace, visited)
        else -> null
    }

    fun staticOperation(opcode: UInt, values: List<ULong>): ULong? {
        if (values.size < 2) return null
        val left = values[0]
        val right = values[1]
        return when (opcode) {
            AML_ADD_OP -> left + right
            AML_SUBTRACT_OP -> left - right
            AML_MULTIPLY_OP -> left * right
            AML_DIVIDE_OP -> right.takeIf { it != 0uL }?.let { left / it }
            AML_MOD_OP -> right.takeIf { it != 0uL }?.let { left % it }
            AML_SHIFT_LEFT_OP -> if (right >= ULong.SIZE_BITS.toULong()) 0uL else left shl right.toInt()
            AML_SHIFT_RIGHT_OP -> if (right >= ULong.SIZE_BITS.toULong()) 0uL else left shr right.toInt()
            AML_AND_OP -> left and right
            AML_NAND_OP -> (left and right).inv()
            AML_OR_OP -> left or right
            AML_NOR_OP -> (left or right).inv()
            AML_XOR_OP -> left xor right
            else -> null
        }
    }
}

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

internal data class AmlExternalMethod(
    val argumentCount: Int,
    val declarationScope: AmlName,
) : AmlObject() {
    override val objectType: ULong
        get() = 8uL
}

internal data class AmlOperationRegion(
    val spaceId: UInt,
    val offset: AmlTermArg,
    val length: AmlTermArg,
    val declarationScope: AmlName,
) : AmlObject() {
    override val objectType: ULong
        get() = 10uL

    internal fun addressAt(namespace: AmlNamespace, relativeOffset: ULong): ULong? {
        val base = offset.staticInteger(namespace) ?: return null
        val size = length.staticInteger(namespace) ?: return null
        if (size == 0uL || relativeOffset >= size || relativeOffset > ULong.MAX_VALUE - base) {
            return null
        }
        return base + relativeOffset
    }
}

enum class AmlFieldUpdateRule {
    PRESERVE,
    WRITE_ONES,
    WRITE_ZEROES,
}

internal sealed class AmlFieldBinding {
    data class Region(val name: AmlNamePath) : AmlFieldBinding()

    data class Index(
        val indexName: AmlNamePath,
        val dataName: AmlNamePath,
    ) : AmlFieldBinding()

    data class Bank(
        val regionName: AmlNamePath,
        val bankName: AmlNamePath,
        val bankValue: AmlTermArg,
    ) : AmlFieldBinding()
}

internal data class AmlFieldUnit(
    val binding: AmlFieldBinding,
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

internal class AmlDeferredValue(
    val expression: AmlTermArg,
    private val namespace: AmlNamespace,
) : AmlObject() {
    override fun integerValue(): ULong? = expression.staticInteger(namespace)

    override fun dereference(): AmlObject = this
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
