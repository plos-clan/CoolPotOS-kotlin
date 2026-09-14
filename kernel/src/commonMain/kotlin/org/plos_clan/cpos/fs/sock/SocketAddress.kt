package org.plos_clan.cpos.fs.sock

private const val DEFAULT_SOCKET_BUFFER_SIZE = 212_992

internal enum class SocketDomain(val abiValue: Int) {
    UNIX(1),
    IPV4(2),
    NETLINK(16),
    PACKET(17),
    ;

    companion object {
        fun fromAbi(value: Int): SocketDomain? = entries.firstOrNull { it.abiValue == value }
    }
}

internal enum class SocketType(val abiValue: Int, val connectionOriented: Boolean) {
    STREAM(1, true),
    DATAGRAM(2, false),
    RAW(3, false),
    SEQUENCED_PACKET(5, true),
    ;

    companion object {
        fun fromAbi(value: Int): SocketType? = entries.firstOrNull { it.abiValue == value }
    }
}

internal interface SocketAddress {
    val domain: SocketDomain
}

internal data object UnspecifiedSocketAddress : SocketAddress {
    override val domain = SocketDomain.UNIX
}

internal enum class SocketShutdownMode(val reads: Boolean, val writes: Boolean) {
    READ(reads = true, writes = false),
    WRITE(reads = false, writes = true),
    BOTH(reads = true, writes = true),
}

internal data class SocketLinger(val enabled: Boolean = false, val seconds: Int = 0)

internal data class SocketOptions(
    val sendBufferSize: Int = DEFAULT_SOCKET_BUFFER_SIZE,
    val receiveBufferSize: Int = DEFAULT_SOCKET_BUFFER_SIZE,
    val passCredentials: Boolean = false,
    val receiveTimestamp: Boolean = false,
    val receiveLowWatermark: Int = 1,
    val sendTimeoutNanos: ULong? = null,
    val receiveTimeoutNanos: ULong? = null,
    val reuseAddress: Boolean = false,
    val broadcast: Boolean = false,
    val keepAlive: Boolean = false,
    val linger: SocketLinger = SocketLinger(),
    val boundInterfaceIndex: Int? = null,
)
