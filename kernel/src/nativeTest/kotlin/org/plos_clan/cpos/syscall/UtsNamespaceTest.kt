package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.mem.ByteArrayBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UtsNamespaceTest {
    @Test
    fun serializesLinuxUtsLayout() {
        val bytes = snapshot(namespace())
        val fields = listOf("kernel", "host", "release", "version", "machine", "domain")

        assertEquals(65, UtsNamespace.FIELD_SIZE)
        assertEquals(390, bytes.size)
        fields.forEachIndexed { index, value ->
            val offset = index * UtsNamespace.FIELD_SIZE
            val encoded = value.encodeToByteArray()
            assertContentEquals(encoded, bytes.copyOfRange(offset, offset + encoded.size))
            assertTrue(
                bytes.copyOfRange(offset + encoded.size, offset + UtsNamespace.FIELD_SIZE)
                    .all { it == 0.toByte() },
            )
        }
    }

    @Test
    fun replacesMutableNamesAsOpaqueBytesAndClearsTheirFields() {
        val namespace = namespace()
        val hostname = byteArrayOf('n'.code.toByte(), 0, 0xFF.toByte())
        val domainName = byteArrayOf('d'.code.toByte(), 0, 0xFE.toByte())

        namespace.setName(UtsNamespace.MutableField.NODE_NAME, hostname)
        namespace.setName(UtsNamespace.MutableField.DOMAIN_NAME, domainName)
        val bytes = snapshot(namespace)
        val hostnameOffset = UtsNamespace.MutableField.NODE_NAME.offset
        val domainNameOffset = UtsNamespace.MutableField.DOMAIN_NAME.offset

        assertContentEquals(
            hostname,
            bytes.copyOfRange(hostnameOffset, hostnameOffset + hostname.size),
        )
        assertTrue(
            bytes.copyOfRange(
                hostnameOffset + hostname.size,
                hostnameOffset + UtsNamespace.FIELD_SIZE,
            ).all { it == 0.toByte() },
        )
        assertContentEquals(
            domainName,
            bytes.copyOfRange(domainNameOffset, domainNameOffset + domainName.size),
        )
        assertTrue(
            bytes.copyOfRange(
                domainNameOffset + domainName.size,
                domainNameOffset + UtsNamespace.FIELD_SIZE,
            ).all { it == 0.toByte() },
        )
        assertContentEquals("kernel".encodeToByteArray(), bytes.copyOfRange(0, 6))
        assertContentEquals(
            "release".encodeToByteArray(),
            bytes.copyOfRange(UtsNamespace.FIELD_SIZE * 2, UtsNamespace.FIELD_SIZE * 2 + 7),
        )
    }

    @Test
    fun acceptsLinuxBoundaryLengthsAndRejectsOverflow() {
        val namespace = namespace()
        val maximum = ByteArray(UtsNamespace.MAX_NAME_LENGTH) { it.toByte() }

        namespace.setName(UtsNamespace.MutableField.DOMAIN_NAME, maximum)
        val bytes = snapshot(namespace)
        val offset = UtsNamespace.MutableField.DOMAIN_NAME.offset
        assertContentEquals(maximum, bytes.copyOfRange(offset, offset + maximum.size))
        assertEquals(0, bytes[offset + maximum.size])

        namespace.setName(UtsNamespace.MutableField.DOMAIN_NAME, ByteArray(0))
        assertTrue(
            snapshot(namespace)
                .copyOfRange(offset, offset + UtsNamespace.FIELD_SIZE)
                .all { it == 0.toByte() },
        )
        assertFailsWith<IllegalArgumentException> {
            namespace.setName(
                UtsNamespace.MutableField.DOMAIN_NAME,
                ByteArray(UtsNamespace.MAX_NAME_LENGTH + 1),
            )
        }
        assertTrue(
            snapshot(namespace)
                .copyOfRange(offset, offset + UtsNamespace.FIELD_SIZE)
                .all { it == 0.toByte() },
        )
    }

    private fun namespace() = UtsNamespace(
        sysName = "kernel",
        nodeName = "host",
        release = "release",
        version = "version",
        machine = "machine",
        domainName = "domain",
    )

    private fun snapshot(namespace: UtsNamespace): ByteArray =
        ByteArray(UtsNamespace.NATIVE_SIZE).also {
            assertTrue(namespace.copyTo(ByteArrayBuffer(it)))
        }
}
