package org.plos_clan.cpos.network

internal enum class KobjectAction(val wireName: String) {
    ADD("add"),
    REMOVE("remove"),
    CHANGE("change"),
    ;
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

    fun encode(sequence: ULong): ByteArray = buildList {
        add("${action.wireName}@$devicePath")
        add("ACTION=${action.wireName}")
        add("DEVPATH=$devicePath")
        add("SUBSYSTEM=$subsystem")
        environment.forEach { (key, value) -> add("$key=$value") }
        add("SEQNUM=$sequence")
    }.joinToString(separator = "\u0000", postfix = "\u0000").encodeToByteArray()

    companion object {
        private val RESERVED_KEYS = setOf("ACTION", "DEVPATH", "SUBSYSTEM", "SEQNUM")
    }
}
