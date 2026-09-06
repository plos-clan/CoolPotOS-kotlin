package org.plos_clan.cpos.fs.tmpfs

import org.plos_clan.cpos.fs.vfs.FileMode
import org.plos_clan.cpos.fs.vfs.FileSystemOptions
import org.plos_clan.cpos.fs.vfs.VfsError
import org.plos_clan.cpos.fs.vfs.VfsResult

data class TmpfsOptions(
    val sizeLimit: ULong? = null,
    val inodeLimit: ULong = 0uL,
    val rootMode: FileMode = FileMode(0x1EDu),
    val rootUid: UInt = 0u,
    val rootGid: UInt = 0u,
) : FileSystemOptions {
    companion object {
        internal fun parse(data: ByteArray?, totalBytes: ULong): VfsResult<TmpfsOptions> {
            if (data == null || data.isEmpty()) return VfsResult.Ok(TmpfsOptions())

            var sizeLimit: ULong? = null
            var inodeLimit = 0uL
            var mode = 0x1EDu
            var uid = 0u
            var gid = 0u
            for (option in data.decodeToString().split(',')) {
                val separator = option.indexOf('=')
                if (separator <= 0 || separator == option.lastIndex) {
                    return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
                val value = option.substring(separator + 1)
                when (option.substring(0, separator)) {
                    "size" -> sizeLimit = parseLimit(value, totalBytes)
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "nr_inodes" -> inodeLimit = parseLimit(value)
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "mode" -> mode = value.toUIntOrNull(8)?.takeIf { it <= 0xFFFu }
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "uid" -> uid = value.toUIntOrNull()
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    "gid" -> gid = value.toUIntOrNull()
                        ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                    else -> return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                }
            }
            return VfsResult.Ok(
                TmpfsOptions(
                    sizeLimit = sizeLimit,
                    inodeLimit = inodeLimit,
                    rootMode = FileMode(mode),
                    rootUid = uid,
                    rootGid = gid,
                ),
            )
        }

        private fun parseLimit(value: String, totalBytes: ULong? = null): ULong? {
            if (value.endsWith('%')) {
                val total = totalBytes ?: return null
                val percentage = value.dropLast(1).toUIntOrNull()?.takeIf { it <= 100u }
                    ?: return null
                val share = percentage.toULong()
                return total / 100uL * share + total % 100uL * share / 100uL
            }
            val shift = when (value.lastOrNull()?.lowercaseChar()) {
                'k' -> 10
                'm' -> 20
                'g' -> 30
                't' -> 40
                'p' -> 50
                'e' -> 60
                else -> 0
            }
            val digits = if (shift == 0) value else value.dropLast(1)
            val radix = when {
                digits.startsWith("0x", ignoreCase = true) -> 16
                digits.startsWith('0') -> 8
                else -> 10
            }
            val number = if (radix == 16) digits.drop(2) else digits
            if (number.firstOrNull()?.digitToIntOrNull(radix) == null) return null
            val units = number.toULongOrNull(radix) ?: return null
            return units.takeIf { it <= ULong.MAX_VALUE shr shift }?.shl(shift)
        }
    }
}
