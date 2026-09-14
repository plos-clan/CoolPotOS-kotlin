package org.plos_clan.cpos.fs.devpts

import org.plos_clan.cpos.fs.vfs.*

internal data class DevptsOptions(
    val uid: UInt? = null,
    val gid: UInt? = null,
    val mode: UInt = 0x180u,
    val ptmxMode: UInt = 0u,
    val maximum: Int = DeviceNumber.MAX_MINOR.toInt() + 1,
) : FileSystemOptions {
    companion object {
        fun parse(parameters: FileSystemParameters): VfsResult<DevptsOptions> {
            var options = DevptsOptions()
            for (parameter in parameters) {
                if (parameter.key == "newinstance" && parameter is FileSystemParameter.Flag) continue
                val value = (parameter as? FileSystemParameter.StringValue)?.value
                    ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                val number = value.toUIntOrNull(if (parameter.key == "mode" || parameter.key == "ptmxmode") 8 else 10)
                    ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
                options = when (parameter.key) {
                    "uid" -> if (number != UInt.MAX_VALUE) options.copy(uid = number) else null
                    "gid" -> if (number != UInt.MAX_VALUE) options.copy(gid = number) else null
                    "mode" -> options.copy(mode = number and 0x1FFu)
                    "ptmxmode" -> options.copy(ptmxMode = number and 0x1FFu)
                    "max" -> if (number <= DeviceNumber.MAX_MINOR + 1u) options.copy(maximum = number.toInt()) else null
                    else -> null
                } ?: return VfsResult.Err(VfsError.INVALID_ARGUMENT)
            }
            return VfsResult.Ok(options)
        }
    }
}
