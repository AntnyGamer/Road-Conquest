package com.roadconquest.app.export

import org.junit.Assert.assertEquals
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
}
