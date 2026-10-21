package com.roadconquest.app.export

import java.io.Writer

internal object CsvUtil {
    /** Keep large geometry fields out of a second, joined row string. */
    fun writeRow(writer: Writer, fields: Iterable<String>) {
        var first = true
        for (field in fields) {
            if (!first) writer.write(','.code)
            first = false
            // A long array with no quotes needs only its enclosing CSV quotes.
            // '[' is already spreadsheet-safe; keep all other values on escape().
            if (field.length >= 1024 && field[0] == '[' && field.last() == ']' &&
                ',' in field && '"' !in field
            ) {
                writer.write('"'.code)
                writer.write(field)
                writer.write('"'.code)
            } else writer.write(escape(field))
        }
        writer.write('\n'.code)
    }

    fun escape(value: String): String {
        // Road names come from a remote service. Keep spreadsheet applications from
        // interpreting those names as formulas when the CSV is opened.
        val first = value.firstOrNull {
            !it.isWhitespace() && !Character.isISOControl(it) &&
                Character.getType(it) != Character.FORMAT.toInt()
        }
        val leading = value.firstOrNull()
        // Escape a literal leading apostrophe too, so schema-10 backups can undo
        // spreadsheet protection without changing real road/place names.
        val safe = if (leading == '\'' || first == '=' || first == '+' || first == '-' || first == '@' ||
            leading == '\t' || leading == '\r' || leading == '\n'
        ) "'$value" else value
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"${safe.replace("\"", "\"\"")}\""
        } else safe
    }

    fun unescape(value: String, schema: Int): String {
        if (!value.startsWith('\'')) return value
        val rest = value.substring(1)
        if (schema >= 10) return rest
        // Legacy exports did not distinguish a literal apostrophe before a formula.
        val encoded = escape(rest)
        return if (encoded == value) rest else value
    }
}
