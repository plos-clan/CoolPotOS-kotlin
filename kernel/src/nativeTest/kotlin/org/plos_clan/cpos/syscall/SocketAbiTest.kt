package org.plos_clan.cpos.syscall

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.plos_clan.cpos.fs.sock.UnixSocketAddress
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.LittleEndianBuffer

class SocketAbiTest {
    @Test
    fun decodesPathnameAndIgnoresBytesAfterTerminator() {
        val native = nativeAddress(byteArrayOf('r'.code.toByte(), 'u'.code.toByte(), 0, 7))
        val decoded = assertIs<VfsResult.Ok<DecodedSocketAddress>>(
            UnixSocketAddressAbi.decode(native),
        ).value
        val address = assertIs<DecodedSocketAddress.Unix>(decoded).address
        val pathname = assertIs<UnixSocketAddress.Pathname>(address)

        assertContentEquals("ru".encodeToByteArray(), pathname.pathname.copyBytes())
        assertContentEquals(nativeAddress("ru".encodeToByteArray() + byteArrayOf(0)),
            UnixSocketAddressAbi.encode(pathname))
    }

    @Test
    fun preservesAbstractNamesIncludingEmbeddedNulls() {
        val nativeName = byteArrayOf(0, 1, 0, 2)
        val decoded = assertIs<VfsResult.Ok<DecodedSocketAddress>>(
            UnixSocketAddressAbi.decode(nativeAddress(nativeName)),
        ).value
        val address = assertIs<UnixSocketAddress.Abstract>(
            assertIs<DecodedSocketAddress.Unix>(decoded).address,
        )

        assertContentEquals(byteArrayOf(1, 0, 2), address.name.copyBytes())
        assertContentEquals(nativeAddress(nativeName), UnixSocketAddressAbi.encode(address))
    }

    @Test
    fun distinguishesUnnamedAndUnspecAddresses() {
        val unnamed = assertIs<VfsResult.Ok<DecodedSocketAddress>>(
            UnixSocketAddressAbi.decode(nativeAddress(ByteArray(0))),
        ).value
        assertEquals(
            UnixSocketAddress.Unnamed,
            assertIs<DecodedSocketAddress.Unix>(unnamed).address,
        )

        val unspecified = ByteArray(UShort.SIZE_BYTES)
        assertIs<DecodedSocketAddress.Unspec>(
            assertIs<VfsResult.Ok<DecodedSocketAddress>>(
                UnixSocketAddressAbi.decode(unspecified, allowUnspec = true),
            ).value,
        )
    }

    private fun nativeAddress(path: ByteArray): ByteArray =
        ByteArray(UShort.SIZE_BYTES + path.size).also { bytes ->
            LittleEndianBuffer(bytes).writeU16(0, SocketConstants.AF_UNIX.toUShort())
            path.copyInto(bytes, UShort.SIZE_BYTES)
        }
}
