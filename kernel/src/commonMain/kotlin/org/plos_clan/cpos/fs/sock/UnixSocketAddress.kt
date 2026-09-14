package org.plos_clan.cpos.fs.sock

import org.plos_clan.cpos.fs.vfs.VfsPathname

internal class UnixSocketName private constructor(private val bytes: ByteArray) {
    private val hash = bytes.contentHashCode()

    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is UnixSocketName && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = hash

    companion object {
        fun fromBytes(bytes: ByteArray): UnixSocketName = UnixSocketName(bytes.copyOf())

        fun fromHex(value: UInt, width: Int): UnixSocketName {
            require(width in 1..UInt.SIZE_BYTES * 2)
            val bytes = ByteArray(width)
            var remaining = value
            for (index in bytes.lastIndex downTo 0) {
                val digit = (remaining and 0xFu).toInt()
                bytes[index] = (if (digit < 10) '0'.code + digit
                else 'a'.code + digit - 10).toByte()
                remaining = remaining shr 4
            }
            return UnixSocketName(bytes)
        }
    }
}

internal sealed interface UnixSocketAddress : SocketAddress {
    override val domain: SocketDomain
        get() = SocketDomain.UNIX

    data object Unnamed : UnixSocketAddress
    data class Pathname(val pathname: VfsPathname) : UnixSocketAddress
    data class Abstract(val name: UnixSocketName) : UnixSocketAddress
}

internal data class UnixCredentials(
    val processId: Int,
    val userId: UInt,
    val groupId: UInt,
)
