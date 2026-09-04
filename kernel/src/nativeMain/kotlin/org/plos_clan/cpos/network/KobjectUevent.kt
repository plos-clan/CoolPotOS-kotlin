package org.plos_clan.cpos.network

internal enum class KobjectAction(val wireName: String) {
    ADD("add"),
    REMOVE("remove"),
    CHANGE("change"),
    MOVE("move"),
    ONLINE("online"),
    OFFLINE("offline"),
    BIND("bind"),
    UNBIND("unbind"),
    ;
}

internal class KobjectUeventRequest private constructor(
    val action: KobjectAction,
    val environment: List<Pair<String, String>>,
) {
    companion object {
        private const val UUID_LENGTH = 36
        private const val SYNTH_UUID = "SYNTH_UUID"
        private const val SYNTH_ARG_PREFIX = "SYNTH_ARG_"
        private val NO_UUID_ENVIRONMENT = listOf(SYNTH_UUID to "0")

        fun parse(input: ByteArray): KobjectUeventRequest? {
            var end = input.size
            if (end != 0 &&
                (input[end - 1] == '\n'.code.toByte() || input[end - 1] == 0.toByte())
            ) {
                end--
            }
            if (end == 0) return null

            var actionEnd = 0
            while (actionEnd < end && input[actionEnd] != ' '.code.toByte()) actionEnd++
            val action = KobjectAction.entries.firstOrNull { candidate ->
                val name = candidate.wireName
                if (name.length != actionEnd) return@firstOrNull false
                var index = 0
                while (index < actionEnd && input[index].toInt() == name[index].code) index++
                index == actionEnd
            } ?: return null
            if (actionEnd == end) return KobjectUeventRequest(action, NO_UUID_ENVIRONMENT)

            var cursor = actionEnd + 1
            if (end - cursor < UUID_LENGTH || !isUuid(input, cursor)) return null
            val uuidEnd = cursor + UUID_LENGTH
            val environment = mutableListOf(
                SYNTH_UUID to input.decodeToString(cursor, uuidEnd),
            )
            cursor = uuidEnd

            while (cursor < end) {
                if (input[cursor++] != ' '.code.toByte()) return null
                val keyStart = cursor
                while (cursor < end && input[cursor] != '='.code.toByte()) {
                    if (!input[cursor].isAsciiAlphanumeric()) return null
                    cursor++
                }
                if (cursor == keyStart || cursor == end) return null
                val keyEnd = cursor++

                val valueStart = cursor
                while (cursor < end && input[cursor] != ' '.code.toByte()) {
                    if (!input[cursor].isAsciiAlphanumeric()) return null
                    cursor++
                }
                if (cursor == valueStart) return null
                val key = SYNTH_ARG_PREFIX + input.decodeToString(keyStart, keyEnd)
                val value = input.decodeToString(valueStart, cursor)
                environment += key to value
            }
            return KobjectUeventRequest(action, environment)
        }

        private fun isUuid(input: ByteArray, offset: Int): Boolean {
            repeat(UUID_LENGTH) { index ->
                val value = input[offset + index]
                if (index == 8 || index == 13 || index == 18 || index == 23) {
                    if (value != '-'.code.toByte()) return false
                } else if (!value.isAsciiHexDigit()) {
                    return false
                }
            }
            return true
        }

        private fun Byte.isAsciiHexDigit(): Boolean {
            val value = toInt()
            return value in '0'.code..'9'.code ||
                value in 'a'.code..'f'.code || value in 'A'.code..'F'.code
        }

        private fun Byte.isAsciiAlphanumeric(): Boolean {
            val value = toInt()
            return value in '0'.code..'9'.code ||
                value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code
        }
    }
}

internal data class KobjectUevent(
    val action: KobjectAction,
    val devicePath: String,
    val subsystem: String,
    val environment: List<Pair<String, String>> = emptyList(),
) {
    init {
        require(devicePath.startsWith('/') && '\u0000' !in devicePath)
        require(subsystem.isNotEmpty() && '\u0000' !in subsystem)
        require(environment.all { (key, value) ->
            key.isNotEmpty() && '=' !in key && '\u0000' !in key && '\u0000' !in value &&
                key !in RESERVED_KEYS
        })
    }

    fun encode(sequence: ULong): ByteArray = buildString {
        append(action.wireName).append('@').append(devicePath).append('\u0000')
        append("ACTION=").append(action.wireName).append('\u0000')
        append("DEVPATH=").append(devicePath).append('\u0000')
        append("SUBSYSTEM=").append(subsystem).append('\u0000')
        environment.forEach { (key, value) ->
            append(key).append('=').append(value).append('\u0000')
        }
        append("SEQNUM=").append(sequence).append('\u0000')
    }.encodeToByteArray()

    companion object {
        private val RESERVED_KEYS = setOf("ACTION", "DEVPATH", "SUBSYSTEM", "SEQNUM")
    }
}

internal fun interface KobjectUeventPublisher {
    fun publish(event: KobjectUevent)
}
