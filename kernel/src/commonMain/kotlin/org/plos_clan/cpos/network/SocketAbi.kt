package org.plos_clan.cpos.network

import org.plos_clan.cpos.drivers.net.MacAddress
import org.plos_clan.cpos.fs.sock.*
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsPathname
import org.plos_clan.cpos.fs.vfs.VfsResult
import org.plos_clan.cpos.utils.LittleEndianBuffer

internal object SocketConstants {
    const val AF_UNSPEC = 0
    const val AF_UNIX = 1
    const val AF_INET = 2
    const val AF_NETLINK = 16
    const val AF_PACKET = 17

    const val SOCK_TYPE_MASK = 0xF
    const val SOCK_NONBLOCK = 0x800
    const val SOCK_CLOEXEC = 0x8_0000
    const val SOCK_SUPPORTED_FLAGS = SOCK_NONBLOCK or SOCK_CLOEXEC

    const val SOL_SOCKET = 1
    const val SCM_RIGHTS = 1
    const val SCM_CREDENTIALS = 2
    const val SCM_TIMESTAMP = 29

    const val SO_REUSEADDR = 2
    const val SO_TYPE = 3
    const val SO_ERROR = 4
    const val SO_BROADCAST = 6
    const val SO_SNDBUF = 7
    const val SO_RCVBUF = 8
    const val SO_KEEPALIVE = 9
    const val SO_LINGER = 13
    const val SO_PASSCRED = 16
    const val SO_PEERCRED = 17
    const val SO_RCVLOWAT = 18
    const val SO_SNDLOWAT = 19
    const val SO_RCVTIMEO = 20
    const val SO_SNDTIMEO = 21
    const val SO_ATTACH_FILTER = 26
    const val SO_TIMESTAMP = SCM_TIMESTAMP
    const val SO_ACCEPTCONN = 30
    const val SO_PROTOCOL = 38
    const val SO_DOMAIN = 39
    const val SO_BINDTOIFINDEX = 62

    const val MSG_PEEK = 0x0002
    const val MSG_DONTROUTE = 0x0004
    const val MSG_CTRUNC = 0x0008
    const val MSG_TRUNC = 0x0020
    const val MSG_DONTWAIT = 0x0040
    const val MSG_EOR = 0x0080
    const val MSG_WAITALL = 0x0100
    const val MSG_NOSIGNAL = 0x4000
    const val MSG_MORE = 0x8000
    const val MSG_WAITFORONE = 0x1_0000
    const val MSG_CMSG_CLOEXEC = 0x4000_0000

    const val SEND_FLAGS = MSG_DONTROUTE or MSG_DONTWAIT or MSG_EOR or MSG_NOSIGNAL or MSG_MORE
    const val RECEIVE_FLAGS = MSG_PEEK or MSG_TRUNC or MSG_DONTWAIT or MSG_WAITALL or
        MSG_CMSG_CLOEXEC or MSG_WAITFORONE

    const val SOCKET_ADDRESS_SIZE = 128
    const val UNIX_ADDRESS_SIZE = 110
    const val SOCKET_PATH_SIZE = UNIX_ADDRESS_SIZE - UShort.SIZE_BYTES
    const val MESSAGE_HEADER_SIZE = 56
    const val MULTI_MESSAGE_HEADER_SIZE = 64
    const val CONTROL_HEADER_SIZE = SocketControlMessage.HEADER_SIZE
    const val CREDENTIAL_SIZE = Int.SIZE_BYTES * 3
    const val MAX_CONTROL_SIZE = 1024 * 1024
    const val MAX_RIGHTS = 253
}

internal data class PacketSocketAddress(
    val interfaceIndex: Int = 0,
    val protocol: UShort = 0u,
    val hardwareType: UShort = 0u,
    val packetType: UByte = 0u,
    val hardwareAddress: MacAddress? = null,
) : SocketAddress {
    override val domain = org.plos_clan.cpos.fs.sock.SocketDomain.PACKET
}

internal object SocketAddressAbi {
    fun decode(bytes: ByteArray, allowUnspec: Boolean = false): VfsResult<SocketAddress> {
        if (bytes.size !in UShort.SIZE_BYTES..SocketConstants.SOCKET_ADDRESS_SIZE) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val family = LittleEndianBuffer(bytes).readU16(0).toInt()
        if (family == SocketConstants.AF_UNSPEC && allowUnspec) {
            return VfsResult.Ok(UnspecifiedSocketAddress)
        }
        return when (family) {
            SocketConstants.AF_UNIX -> decodeUnix(bytes)
            SocketConstants.AF_INET -> decodeIpv4(bytes)
            SocketConstants.AF_NETLINK -> decodeNetlink(bytes)
            SocketConstants.AF_PACKET -> decodePacket(bytes)
            else -> VfsResult.Err(VfsError.ADDRESS_FAMILY_NOT_SUPPORTED)
        }
    }

    fun encode(address: SocketAddress): ByteArray = when (address) {
        is UnixSocketAddress -> encodeUnix(address)
        is Ipv4SocketAddress -> ByteArray(IPV4_ADDRESS_SIZE).also { bytes ->
            LittleEndianBuffer(bytes).writeU16(0, SocketConstants.AF_INET.toUShort())
            NetworkOrderBuffer(bytes).apply {
                writeU16(2, address.port)
                writeU32(4, address.address.value)
            }
        }
        is NetlinkSocketAddress -> ByteArray(NETLINK_ADDRESS_SIZE).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU16(0, SocketConstants.AF_NETLINK.toUShort())
                writeU16(2, 0u)
                writeU32(4, address.portId)
                writeU32(8, address.groups)
            }
        }
        is PacketSocketAddress -> ByteArray(PACKET_ADDRESS_SIZE).also { bytes ->
            LittleEndianBuffer(bytes).apply {
                writeU16(0, SocketConstants.AF_PACKET.toUShort())
                writeU32(4, address.interfaceIndex.toUInt())
                writeU16(8, address.hardwareType)
                writeU8(10, address.packetType)
                writeU8(
                    11,
                    if (address.hardwareAddress == null) 0.toUByte()
                    else MacAddress.SIZE_BYTES.toUByte(),
                )
            }
            NetworkOrderBuffer(bytes).writeU16(2, address.protocol)
            address.hardwareAddress?.copyTo(bytes, 12)
        }
        UnspecifiedSocketAddress -> ByteArray(UShort.SIZE_BYTES)
        else -> error("Unsupported socket address ${address::class.simpleName}")
    }

    private fun decodeUnix(bytes: ByteArray): VfsResult<SocketAddress> {
        if (bytes.size > SocketConstants.UNIX_ADDRESS_SIZE) {
            return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        }
        val path = bytes.copyOfRange(UShort.SIZE_BYTES, bytes.size)
        if (path.isEmpty()) return VfsResult.Ok(UnixSocketAddress.Unnamed)
        if (path[0] == 0.toByte()) {
            return VfsResult.Ok(
                UnixSocketAddress.Abstract(
                    UnixSocketName.fromBytes(path.copyOfRange(1, path.size)),
                ),
            )
        }
        val terminator = path.indexOf(0)
        val length = if (terminator < 0) path.size else terminator
        if (length == 0) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return VfsResult.Ok(
            UnixSocketAddress.Pathname(VfsPathname.fromBytes(path.copyOf(length))),
        )
    }

    private fun decodeIpv4(bytes: ByteArray): VfsResult<SocketAddress> {
        if (bytes.size < IPV4_ADDRESS_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val input = NetworkOrderBuffer(bytes)
        return VfsResult.Ok(
            Ipv4SocketAddress(
                Ipv4Address.fromBits(input.readU32(4)),
                input.readU16(2),
            ),
        )
    }

    private fun decodeNetlink(bytes: ByteArray): VfsResult<SocketAddress> {
        if (bytes.size < NETLINK_ADDRESS_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val input = LittleEndianBuffer(bytes)
        return VfsResult.Ok(NetlinkSocketAddress(input.readU32(4), input.readU32(8)))
    }

    private fun decodePacket(bytes: ByteArray): VfsResult<SocketAddress> {
        if (bytes.size < PACKET_ADDRESS_SIZE) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        val input = LittleEndianBuffer(bytes)
        val interfaceIndex = input.readU32(4)
        val addressLength = input.readU8(11).toInt()
        if (interfaceIndex > Int.MAX_VALUE.toUInt() ||
            addressLength != 0 && addressLength != MacAddress.SIZE_BYTES
        ) return VfsResult.Err(VfsError.INVALID_ARGUMENT)
        return VfsResult.Ok(
            PacketSocketAddress(
                interfaceIndex.toInt(),
                NetworkOrderBuffer(bytes).readU16(2),
                input.readU16(8),
                input.readU8(10),
                if (addressLength == 0) null else MacAddress.from(bytes, 12),
            ),
        )
    }

    private fun encodeUnix(address: UnixSocketAddress): ByteArray {
        val path = when (address) {
            UnixSocketAddress.Unnamed -> ByteArray(0)
            is UnixSocketAddress.Abstract -> byteArrayOf(0) + address.name.copyBytes()
            is UnixSocketAddress.Pathname -> address.pathname.copyBytes().let { bytes ->
                if (bytes.size < SocketConstants.SOCKET_PATH_SIZE) bytes + byteArrayOf(0) else bytes
            }
        }
        return ByteArray(UShort.SIZE_BYTES + path.size).also { bytes ->
            LittleEndianBuffer(bytes).writeU16(0, SocketConstants.AF_UNIX.toUShort())
            path.copyInto(bytes, UShort.SIZE_BYTES)
        }
    }

    private const val IPV4_ADDRESS_SIZE = 16
    private const val NETLINK_ADDRESS_SIZE = 12
    private const val PACKET_ADDRESS_SIZE = 20
}
