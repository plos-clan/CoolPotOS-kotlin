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

    companion object {
        fun parse(input: ByteArray): KobjectAction? {
            val terminator = input.lastOrNull()
            val terminated = terminator == '\n'.code.toByte() || terminator == 0.toByte()
            val length = input.size - if (terminated) 1 else 0

            for (action in entries) {
                val name = action.wireName
                if (name.length != length) continue
                var index = 0
                while (index < length && input[index].toInt() == name[index].code) index++
                if (index == length) return action
            }
            return null
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
