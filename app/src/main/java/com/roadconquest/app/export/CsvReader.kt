package com.roadconquest.app.export

import java.io.PushbackReader
import java.io.Reader

/** Strict, bounded RFC-4180 rows, including quoted commas, quotes and newlines. */
internal class CsvReader(reader: Reader, private val maxRowChars: Int = 1_048_576) {
    private val input = PushbackReader(reader, 1)

    fun next(): List<String>? {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var closed = false
        var started = false
        var chars = 0
        while (true) {
            val c = input.read()
            if (c == -1) {
                require(!quoted) { "Unclosed CSV quote" }
                if (!started) return null
                fields += field.toString()
                return fields
            }
            started = true
            require(++chars <= maxRowChars && fields.size < 20) { "Backup CSV row is too large" }
            if (quoted) {
                if (c == '"'.code) {
                    val after = input.read()
                    if (after == '"'.code) field.append('"') else {
                        quoted = false
                        closed = true
                        if (after != -1) input.unread(after)
                    }
                } else field.append(c.toChar())
            } else when (c) {
                ','.code -> { fields += field.toString(); field.setLength(0); closed = false }
                '\n'.code, '\r'.code -> {
                    if (c == '\r'.code) {
                        val after = input.read()
                        if (after != '\n'.code && after != -1) input.unread(after)
                    }
                    fields += field.toString()
                    return fields
                }
                '"'.code -> {
                    require(field.isEmpty() && !closed) { "Unexpected CSV quote" }
                    quoted = true
                }
                else -> { require(!closed) { "Data after CSV closing quote" }; field.append(c.toChar()) }
            }
        }
    }
}
