@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.plos_clan.cpos.drivers.acpi.aml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind

class AmlFirmwareTableTest {
    @Test
    fun loadsExportedFirmwareTablesToTheirDeclaredEnds() {
        val directory = platform.posix.getenv("ACPI_AML_TABLE_DIR")?.toKString() ?: return
        val files = listOf("dsdt.dat") + (1..17).map { "ssdt$it.dat" }
        val loader = AmlLoader(AmlNamespace())

        files.forEach { name ->
            val bytes = readFile("$directory/$name")
            val result = loader.load(name.substringBefore('.').uppercase(), AmlArraySource(bytes))
            assertTrue(result.success, "$name: ${result.status}")
            assertEquals(bytes.size, result.length, name)
            assertEquals(bytes.size, result.consumed, name)
        }
    }

    private fun readFile(path: String): ByteArray {
        val file = fopen(path, "rb") ?: error("cannot open $path")
        try {
            check(fseek(file, 0, SEEK_END) == 0)
            val size = ftell(file).toInt()
            check(size >= 0)
            rewind(file)
            return ByteArray(size).also { bytes ->
                val read = bytes.usePinned {
                    fread(it.addressOf(0), 1u, size.toULong(), file)
                }
                check(read == size.toULong()) { "short read $path" }
            }
        } finally {
            fclose(file)
        }
    }
}
