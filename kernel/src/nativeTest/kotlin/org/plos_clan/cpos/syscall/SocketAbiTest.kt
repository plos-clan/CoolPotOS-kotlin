package org.plos_clan.cpos.syscall

import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.UnspecifiedSocketAddress
import org.plos_clan.cpos.fs.sock.UnixSocketAddress
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.network.Ipv4Address
import org.plos_clan.cpos.network.Ipv4SocketAddress
import org.plos_clan.cpos.network.NetlinkSocketAddress
import org.plos_clan.cpos.network.NetworkOrderBuffer
import org.plos_clan.cpos.network.SocketAddressAbi
import org.plos_clan.cpos.network.SocketConstants
import org.plos_clan.cpos.utils.LittleEndianBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SocketAbiTest {
    @Test
    fun decodesPathnameAndIgnoresBytesAfterTerminator() {
        val native = nativeAddress(byteArrayOf('r'.code.toByte(), 'u'.code.toByte(), 0, 7))
        val pathname = assertIs<UnixSocketAddress.Pathname>(
            assertIs<VfsResult.Ok<SocketAddress>>(SocketAddressAbi.decode(native)).value,
        )

        assertContentEquals("ru".encodeToByteArray(), pathname.pathname.copyBytes())
        assertContentEquals(nativeAddress("ru".encodeToByteArray() + byteArrayOf(0)),
            SocketAddressAbi.encode(pathname))
    }

    @Test
    fun preservesAbstractNamesIncludingEmbeddedNulls() {
        val nativeName = byteArrayOf(0, 1, 0, 2)
        val address = assertIs<UnixSocketAddress.Abstract>(
            assertIs<VfsResult.Ok<SocketAddress>>(
                SocketAddressAbi.decode(nativeAddress(nativeName)),
            ).value,
        )

        assertContentEquals(byteArrayOf(1, 0, 2), address.name.copyBytes())
        assertContentEquals(nativeAddress(nativeName), SocketAddressAbi.encode(address))
    }

    @Test
    fun distinguishesUnnamedAndUnspecAddresses() {
        assertEquals(
            UnixSocketAddress.Unnamed,
            assertIs<VfsResult.Ok<SocketAddress>>(
                SocketAddressAbi.decode(nativeAddress(ByteArray(0))),
            ).value,
        )

        val unspecified = ByteArray(UShort.SIZE_BYTES)
        assertIs<UnspecifiedSocketAddress>(
            assertIs<VfsResult.Ok<SocketAddress>>(
                SocketAddressAbi.decode(unspecified, allowUnspec = true),
            ).value,
        )
    }

    @Test
    fun roundTripsIpv4AddressInNetworkByteOrder() {
        val address = Ipv4SocketAddress(checkNotNull(Ipv4Address.parse("192.0.2.7")), 8080u)
        val encoded = SocketAddressAbi.encode(address)

        assertEquals(SocketConstants.AF_INET.toUShort(), LittleEndianBuffer(encoded).readU16(0))
        assertEquals(8080u.toUShort(), NetworkOrderBuffer(encoded).readU16(2))
        assertEquals(address, assertIs<VfsResult.Ok<SocketAddress>>(
            SocketAddressAbi.decode(encoded),
        ).value)
    }

    @Test
    fun roundTripsNetlinkAddressInNativeByteOrder() {
        val address = NetlinkSocketAddress(42u, 0x51u)
        val encoded = SocketAddressAbi.encode(address)

        assertEquals(SocketConstants.AF_NETLINK.toUShort(), LittleEndianBuffer(encoded).readU16(0))
        assertEquals(address, assertIs<VfsResult.Ok<SocketAddress>>(
            SocketAddressAbi.decode(encoded),
        ).value)
    }

    private fun nativeAddress(path: ByteArray): ByteArray =
        ByteArray(UShort.SIZE_BYTES + path.size).also { bytes ->
            LittleEndianBuffer(bytes).writeU16(0, SocketConstants.AF_UNIX.toUShort())
            path.copyInto(bytes, UShort.SIZE_BYTES)
        }
}
