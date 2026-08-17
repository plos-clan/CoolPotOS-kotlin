@file:OptIn(ExperimentalForeignApi::class)

package org.plos_clan.cpos.utils

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString

data class Argument(
    val name: String,
    val value: String?,
) {
    val isFlag: Boolean
        get() = value == null
}

object Cmdline {

    var raw: String = ""
        private set

    var arguments: List<Argument> = emptyList()
        private set

    fun initialize(): Boolean {
        val response = bridge.cmdline_request.response?.pointed ?: run {
            println("error: cannot find kernel cmdline.")
            return false
        }
        val commandLine = response.cmdline?.toKString() ?: run {
            println("error: cmdline is null.")
            return false
        }

        parse(commandLine)
        println("Kernel command line: $raw")
        return true
    }

    fun parse(commandLine: String) {
        raw = commandLine
        arguments = tokenize(commandLine).mapNotNull(::parseArgument)
    }

    operator fun contains(name: String): Boolean =
        arguments.any { it.name == name }

    operator fun get(name: String): String? =
        arguments.lastOrNull { it.name == name }?.value

    fun getAll(name: String): List<Argument> =
        arguments.filter { it.name == name }

    fun isFlag(name: String): Boolean =
        arguments.any { it.name == name && it.isFlag }

    fun boolean(name: String): Boolean? {
        val argument = arguments.lastOrNull { it.name == name } ?: return null
        val value = argument.value ?: return true
        return when (value.lowercase()) {
            "1", "yes", "true", "on" -> true
            "0", "no", "false", "off" -> false
            else -> null
        }
    }

    fun int(name: String, radix: Int = 10): Int? =
        get(name)?.toIntOrNull(radix)

    fun uLong(name: String, radix: Int = 10): ULong? =
        get(name)?.toULongOrNull(radix)

    private fun parseArgument(token: String): Argument? {
        val separator = token.indexOf('=')
        val name = if (separator < 0) token else token.substring(0, separator)
        if (name.isEmpty()) {
            return null
        }
        val value = if (separator < 0) null else token.substring(separator + 1)
        return Argument(name, value)
    }

    private fun tokenize(commandLine: String): List<String> {
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var started = false

        fun finishToken() {
            if (started) {
                tokens += token.toString()
                token.clear()
                started = false
            }
        }

        for (character in commandLine) {
            when {
                escaped -> {
                    token.append(character)
                    escaped = false
                    started = true
                }

                character == '\\' -> {
                    escaped = true
                    started = true
                }

                quote != null -> {
                    if (character == quote) {
                        quote = null
                    } else {
                        token.append(character)
                    }
                    started = true
                }

                character == '\'' || character == '"' -> {
                    quote = character
                    started = true
                }

                character.isWhitespace() -> finishToken()
                else -> {
                    token.append(character)
                    started = true
                }
            }
        }

        if (escaped) {
            token.append('\\')
        }
        finishToken()
        return tokens
    }
}
