package org.plos_clan.cpos.fs.vfs

interface FileSystemOptions

data object EmptyFileSystemOptions : FileSystemOptions

abstract class FileSystemParameter(open val key: String) {
    data class Flag(override val key: String) : FileSystemParameter(key)

    data class StringValue(
        override val key: String,
        val value: String,
    ) : FileSystemParameter(key)

    class BinaryValue(
        override val key: String,
        val value: ByteArray,
    ) : FileSystemParameter(key)

    internal open fun release() {}

}

class FileSystemParameters private constructor(
    private val values: List<FileSystemParameter>,
) : List<FileSystemParameter> by values {
    companion object {
        val EMPTY = FileSystemParameters(emptyList())

        fun fromMountData(data: ByteArray?): FileSystemParameters {
            if (data == null || data.isEmpty()) return EMPTY
            return FileSystemParameters(data.decodeToString().split(',').map { option ->
                val separator = option.indexOf('=')
                if (separator < 0) FileSystemParameter.Flag(option)
                else FileSystemParameter.StringValue(
                    option.substring(0, separator),
                    option.substring(separator + 1),
                )
            })
        }

        internal fun copyOf(values: List<FileSystemParameter>): FileSystemParameters =
            if (values.isEmpty()) EMPTY else FileSystemParameters(values.toList())
    }
}
