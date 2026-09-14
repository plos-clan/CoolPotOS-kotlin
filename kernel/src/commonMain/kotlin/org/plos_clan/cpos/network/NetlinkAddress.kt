package org.plos_clan.cpos.network

import org.plos_clan.cpos.fs.sock.SocketAddress
import org.plos_clan.cpos.fs.sock.SocketDomain
import org.plos_clan.cpos.fs.vfs.VfsError

internal enum class NetlinkProtocolKind(val number: Int) {
    ROUTE(0),
    USERSOCK(2),
    KOBJECT_UEVENT(15),
    GENERIC(16),
    ;

    companion object {
        fun fromNumber(number: Int): NetlinkProtocolKind? = entries.firstOrNull {
            it.number == number
        }
    }
}

internal data class NetlinkSocketAddress(
    val portId: UInt,
    val groups: UInt,
) : SocketAddress {
    override val domain = SocketDomain.NETLINK
}

internal data class NetlinkReply(
    val type: Int,
    val payload: ByteArray = ByteArray(0),
    val flags: Int = 0,
)

internal sealed interface NetlinkResult {
    data class Success(
        val replies: List<NetlinkReply> = emptyList(),
        val multipart: Boolean = false,
    ) : NetlinkResult

    data class Failure(
        val error: VfsError,
        val message: String? = null,
        val offset: Int? = null,
    ) : NetlinkResult
}
