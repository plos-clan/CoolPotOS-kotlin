package org.plos_clan.cpos.drivers.acpi.aml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmlFirmwareTableTest {
    @Test
    fun loadsExportedFirmwareTablesToTheirDeclaredEnds() {
        val directory = System.getenv("ACPI_AML_TABLE_DIR")
        org.junit.Assume.assumeTrue("ACPI_AML_TABLE_DIR is not configured", directory != null)
        val files = listOf("dsdt.dat") + (1..17).map { "ssdt$it.dat" }
        val loader = AmlLoader(AmlNamespace())

        files.forEach { name ->
            val bytes = java.io.File(directory, name).readBytes()
            val result = loader.load(name.substringBefore('.').uppercase(), AmlArraySource(bytes))
            assertTrue(result.success, "$name: ${result.status}")
            assertEquals(bytes.size, result.length, name)
            assertEquals(bytes.size, result.consumed, name)
        }
    }
}
