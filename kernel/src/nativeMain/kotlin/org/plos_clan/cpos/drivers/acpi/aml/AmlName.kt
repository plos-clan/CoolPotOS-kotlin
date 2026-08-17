package org.plos_clan.cpos.drivers.acpi.aml

private const val AML_ROOT_CHAR = 0x5Cu
private const val AML_PARENT_PREFIX_CHAR = 0x5Eu
private const val AML_DUAL_NAME_PREFIX = 0x2Eu
private const val AML_MULTI_NAME_PREFIX = 0x2Fu
private const val AML_NULL_NAME = 0x00u

data class AmlName(
    val segments: List<String>,
) {
    val isRoot: Boolean
        get() = segments.isEmpty()

    val parent: AmlName
        get() = if (segments.isEmpty()) this else AmlName(segments.dropLast(1))

    fun child(segment: String): AmlName {
        require(isValidNameSegment(segment)) { "invalid AML NameSeg '$segment'" }
        return AmlName(segments + segment)
    }

    fun startsWith(other: AmlName): Boolean =
        segments.size >= other.segments.size &&
            segments.subList(0, other.segments.size) == other.segments

    override fun toString(): String =
        if (segments.isEmpty()) "\\" else "\\${segments.joinToString(".")}"

    companion object {
        val ROOT = AmlName(emptyList())
    }
}

data class AmlNamePath(
    val absolute: Boolean,
    val parentPrefixCount: Int,
    val segments: List<String>,
) {
    fun resolveFrom(scope: AmlName): AmlName {
        val base = if (absolute) {
            AmlName.ROOT
        } else {
            AmlName(scope.segments.dropLast(minOf(parentPrefixCount, scope.segments.size)))
        }
        return AmlName(base.segments + segments)
    }
}

internal fun AmlByteReader.readNamePath(): AmlNamePath? {
    var absolute = false
    var parentPrefixes = 0

    if (peek() == AML_ROOT_CHAR) {
        readU8()
        absolute = true
    } else {
        while (peek() == AML_PARENT_PREFIX_CHAR) {
            readU8()
            parentPrefixes++
        }
    }

    val segments = when (peek()) {
        AML_NULL_NAME -> {
            readU8()
            emptyList()
        }
        AML_DUAL_NAME_PREFIX -> {
            readU8()
            listOfNotNull(readNameSegment(), readNameSegment()).takeIf { it.size == 2 }
                ?: return null
        }
        AML_MULTI_NAME_PREFIX -> {
            readU8()
            val count = readU8()?.toInt() ?: return null
            buildList {
                repeat(count) { add(readNameSegment() ?: return null) }
            }
        }
        else -> listOf(readNameSegment() ?: return null)
    }

    return AmlNamePath(
        absolute = absolute,
        parentPrefixCount = parentPrefixes,
        segments = segments,
    )
}

internal fun AmlByteReader.readNameSegment(): String? {
    val probe = copy()
    val segment = probe.readAscii(4) ?: return null
    if (!isValidNameSegment(segment)) {
        return null
    }
    skip(4)
    return segment
}

internal fun isNameStringLead(value: UInt): Boolean =
    value == AML_ROOT_CHAR ||
        value == AML_PARENT_PREFIX_CHAR ||
        value == AML_DUAL_NAME_PREFIX ||
        value == AML_MULTI_NAME_PREFIX ||
        value == AML_NULL_NAME ||
        value == '_'.code.toUInt() ||
        value in 'A'.code.toUInt()..'Z'.code.toUInt()

private fun isValidNameSegment(segment: String): Boolean =
    segment.length == 4 &&
        (segment[0] == '_' || segment[0] in 'A'..'Z') &&
        segment.drop(1).all { it == '_' || it in 'A'..'Z' || it in '0'..'9' }
