package org.plos_clan.cpos.drivers.acpi.aml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AmlLoaderTest {
    @Test
    fun consumesNestedControlFlowAtPackageBoundary() {
        val nested = byteArrayOf(
            0xA0.toByte(), 0x05, 0x00, 0xA2.toByte(), 0x02, 0xA3.toByte(),
        )
        val result = load(nested)

        assertEquals(AmlLoadStatus.LOADED, result.status)
        assertEquals(36 + nested.size, result.consumed)
    }

    @Test
    fun rejectsTruncatedPackageWithoutCrossingTableEnd() {
        val result = load(byteArrayOf(0xA0.toByte(), 0x05, 0x00))

        assertEquals(AmlLoadStatus.MALFORMED_TERM, result.status)
        assertTrue(result.consumed <= result.length)
    }

    @Test
    fun preservesDynamicOperationRegionExpression() {
        val body = byteArrayOf(
            0x5B, 0x80.toByte(), 'T'.code.toByte(), 'E'.code.toByte(),
            'S'.code.toByte(), 'T'.code.toByte(), 0x00,
            0x72, 0x0A, 0x10, 0x0A, 0x20, 0x00,
            0x0A, 0x10,
        )
        val namespace = AmlNamespace()
        val result = AmlLoader(namespace).load("SSDT", source(body))

        assertEquals(AmlLoadStatus.LOADED, result.status)
        val region = assertIs<AmlOperationRegion>(namespace.find(AmlName.ROOT.child("TEST"))?.value)
        assertEquals(0x30uL, region.offset.staticInteger(namespace))
        assertEquals(0x10uL, region.length.staticInteger(namespace))
    }

    @Test
    fun loadsFieldLayoutAgainstOperationRegion() {
        val body = byteArrayOf(
            0x5B, 0x80.toByte(), 'R'.code.toByte(), 'E'.code.toByte(),
            'G'.code.toByte(), 'N'.code.toByte(), 0x00, 0x00, 0x0A, 0x10,
            0x5B, 0x81.toByte(), 0x0B, 'R'.code.toByte(), 'E'.code.toByte(),
            'G'.code.toByte(), 'N'.code.toByte(), 0x00,
            'F'.code.toByte(), 'L'.code.toByte(), 'D'.code.toByte(), '0'.code.toByte(), 0x08,
        )
        val namespace = AmlNamespace()
        val result = AmlLoader(namespace).load("SSDT", source(body))

        assertEquals(AmlLoadStatus.LOADED, result.status)
        val field = assertIs<AmlFieldUnit>(namespace.find(AmlName.ROOT.child("FLD0"))?.value)
        assertEquals(0uL, field.bitOffset)
        assertEquals(8uL, field.bitLength)
        assertIs<AmlFieldBinding.Region>(field.binding)
    }

    @Test
    fun stopsOnUnknownOpcode() {
        val result = load(byteArrayOf(0xFE.toByte()))

        assertEquals(AmlLoadStatus.MALFORMED_TERM, result.status)
        assertEquals(36, result.consumed)
    }

    @Test
    fun evaluatesRepeatedStaticNameReferences() {
        val body = byteArrayOf(
            0x08, 'B'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 0x0A, 0x10,
            0x5B, 0x80.toByte(), 'R'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte(), 'N'.code.toByte(), 0x00,
            0x72, 'B'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(),
            'B'.code.toByte(), 'A'.code.toByte(), 'S'.code.toByte(), 'E'.code.toByte(), 0x00,
            0x0A, 0x01,
        )
        val namespace = AmlNamespace()
        val result = AmlLoader(namespace).load("SSDT", source(body))

        assertEquals(AmlLoadStatus.LOADED, result.status)
        val region = assertIs<AmlOperationRegion>(namespace.find(AmlName.ROOT.child("REGN"))?.value)
        assertEquals(0x20uL, region.offset.staticInteger(namespace))
    }

    @Test
    fun consumesBothDivideTargetsInDeferredExpressions() {
        val body = byteArrayOf(
            0x5B, 0x80.toByte(), 'R'.code.toByte(), 'E'.code.toByte(), 'G'.code.toByte(), 'N'.code.toByte(), 0x00,
            0x78, 0x0A, 0x08, 0x0A, 0x02, 0x00, 0x00,
            0x0A, 0x01,
        )
        val namespace = AmlNamespace()
        val result = AmlLoader(namespace).load("SSDT", source(body))

        assertEquals(AmlLoadStatus.LOADED, result.status)
        val region = assertIs<AmlOperationRegion>(namespace.find(AmlName.ROOT.child("REGN"))?.value)
        assertEquals(0x04uL, region.offset.staticInteger(namespace))
        assertEquals(0x01uL, region.length.staticInteger(namespace))
    }

    private fun load(body: ByteArray): AmlLoadResult = AmlLoader(AmlNamespace()).load("SSDT", source(body))

    private fun source(body: ByteArray): AmlByteSource =
        AmlArraySource(ByteArray(36) + body)
}
