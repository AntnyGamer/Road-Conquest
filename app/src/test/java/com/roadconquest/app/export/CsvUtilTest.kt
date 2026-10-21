package com.roadconquest.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvUtilTest {
    @Test fun neutralizesFormulasIncludingLeadingWhitespace() {
        listOf("=1+1", "+1", "-1", "@SUM(A1)", "  =1", "\tvalue").forEach {
            assertEquals("'$it", CsvUtil.escape(it))
        }
    }

    @Test fun preservesNormalNamesAndEscapesQuotesCommasAndNewlines() {
        assertEquals("Main Street", CsvUtil.escape("Main Street"))
        assertEquals("\"Main, \"\"North\"\"\nStreet\"", CsvUtil.escape("Main, \"North\"\nStreet"))
        assertEquals("", CsvUtil.escape(""))
    }

    @Test fun newBackupEscapingPreservesLiteralApostrophesAndFormulaNames() {
        listOf("'=1+1", "'Main", "=1+1", "-74.0", "Main").forEach {
            assertEquals(it, CsvUtil.unescape(CsvUtil.escape(it), 10))
        }
        val reader = CsvReader(java.io.StringReader("name,value\n\"Main, \"\"North\"\"\nRoad\",5\n"))
        assertEquals(listOf("name", "value"), reader.next())
        assertEquals(listOf("Main, \"North\"\nRoad", "5"), reader.next())
        assertEquals(null, reader.next())
    }

    @Test fun neutralizesFormulasHiddenAfterUnicodeFormatOrControlCharacters() {
        listOf("\uFEFF=1+1", "\u200B@SUM(A1)", "\u0000+1", "\u200B  -1").forEach {
            assertEquals("'$it", CsvUtil.escape(it))
        }
    }

    @Test fun streamedRowsKeepOriginalUtf8BytesAndSpreadsheetRoundTrips() {
        val special = listOf("", "Main Street", "'=1+1", "'Main", "=1+1", "-74.0",
            "\uFEFF=1+1", "\u200B@SUM(A1)", "\u0000+1", "\tvalue", "\rvalue", "\nvalue",
            "Main, \"North\"\r\nRoad", "Straße 🛣️", "  -1")
        val rows = listOf(listOf(""), special) + (0 until 200).map { index ->
            listOf(index.toString(), special[index % special.size], "40.${index}00",
                "\"quoted,$index\"", "line\n$index")
        }
        val original = rows.joinToString("") { row ->
            row.joinToString(",") { CsvUtil.escape(it) } + "\n"
        }
        val output = java.io.StringWriter()
        rows.forEach { CsvUtil.writeRow(output, it) }
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), output.toString().toByteArray(Charsets.UTF_8))
        val reader = CsvReader(java.io.StringReader(output.toString()))
        rows.forEach { row ->
            assertEquals(row, requireNotNull(reader.next()).map { CsvUtil.unescape(it, 12) })
        }
        assertEquals(null, reader.next())
        val empty = java.io.StringWriter()
        CsvUtil.writeRow(empty, emptyList())
        assertEquals("\n", empty.toString())
    }

    @Test fun largeGeometryIsWrittenWithoutPassingAnAssembledRowToTheWriter() {
        val geometry = (0 until 5_000).joinToString(",", "[", "]") { "[-74.000000001,40.$it]" }
        val fields = listOf("road-id", "Main, \"North\" Road", "2026-10-19T00:00:00Z", geometry)
        val expected = fields.joinToString(",") { CsvUtil.escape(it) } + "\n"
        val output = StringBuilder()
        var largestWrite = 0
        val writer = object : java.io.Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {
                largestWrite = maxOf(largestWrite, length)
                output.append(chars, offset, length)
            }
            override fun write(value: String, offset: Int, length: Int) {
                largestWrite = maxOf(largestWrite, length)
                output.append(value, offset, offset + length)
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        CsvUtil.writeRow(writer, fields)
        assertEquals(expected, output.toString())
        assertEquals(geometry.length, largestWrite)
        assertTrue(largestWrite < expected.length)
        assertEquals(fields, requireNotNull(CsvReader(java.io.StringReader(output.toString())).next())
            .map { CsvUtil.unescape(it, 12) })
    }

    @Test fun longArrayFastPathKeepsExactEscapingAcrossThresholdsAndFallbacks() {
        val random = java.util.Random(804412)
        val fields = ArrayList<String>()
        for (size in listOf(0, 1, 1023, 1024, 1025, 8192)) {
            val text = "x".repeat(size)
            fields += listOf(text, "[$text]", "[$text,1]", "[$text,\"quoted\"]",
                "[$text,\r\n1]", "=[$text,1]", "'[$text,1]", " [$text,1]",
                "[$text,1", "[$text,🛣️\u0000]", "[\uD800$text,\uDC00]")
        }
        val alphabet = charArrayOf('[', ']', ',', '\"', '\r', '\n', '\t', '\'', '=', '+', '-', '@',
            '\uFEFF', '\u200B', ' ', 'x', '\u0000', '\uD800', '\uDC00', '雪')
        repeat(1000) {
            val text = buildString { repeat(1000 + random.nextInt(80)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            fields += "[$text,1]"
            fields += text
        }
        for (field in fields) {
            val output = java.io.StringWriter()
            CsvUtil.writeRow(output, listOf(field))
            assertEquals(CsvUtil.escape(field) + "\n", output.toString())
            assertEquals(listOf(field), requireNotNull(CsvReader(java.io.StringReader(output.toString())).next())
                .map { CsvUtil.unescape(it, 12) })
        }
    }

}
