package com.layerbit.sheaf

import com.layerbit.sheaf.ops.Suggest
import com.layerbit.sheaf.ops.Suggest.Facts
import com.layerbit.sheaf.ops.Suggest.Kind
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.text.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestTest {
    @Test fun lockedComesFirst() {
        val s = Suggest.suggest(listOf(Facts(Kind.PDF, 1000, pages = 3, locked = true)))
        assertEquals(ToolId.REMOVE_PASSWORD, s.first().tool)
        assertTrue(s.none { it.tool == ToolId.SET_PASSWORD })
    }

    @Test fun scanSaysMakeSearchable() {
        val s = Suggest.suggest(listOf(Facts(Kind.PDF, 1000, pages = 1, scanned = true)))
        assertEquals(ToolId.OCR, s.first().tool)
    }

    @Test fun statementPutsExcelFirst() {
        val s = Suggest.suggest(listOf(Facts(Kind.PDF, 1000, pages = 2, amounts = true)))
        assertEquals(ToolId.PDF_TO_OFFICE, s.first().tool)
        assertEquals(Suggest.PRESET_EXCEL, s.first().preset)
    }

    @Test fun idNumberIsInTheTopThree() {
        val s = Suggest.suggest(listOf(Facts(Kind.PDF, 1000, pages = 1, idNumber = true)))
        assertTrue(s.take(3).any { it.tool == ToolId.REDACT })
    }

    @Test fun severalPdfsMergeAndEachToolOnce() {
        val s = Suggest.suggest(listOf(Facts(Kind.PDF, 3_000_000, pages = 2), Facts(Kind.PDF, 1000, pages = 1)))
        assertEquals(ToolId.MERGE, s.first().tool)
        assertEquals(s.size, s.map { it.tool }.distinct().size)
        assertTrue(s.any { it.tool == ToolId.COMPARE })
    }

    @Test fun mixedDrop() {
        val s = Suggest.suggest(listOf(Facts(Kind.IMAGE, 1000), Facts(Kind.PDF, 1000, pages = 1)))
        assertTrue(s.any { it.tool == ToolId.IMAGES_TO_PDF && it.takes == listOf(0) })
        assertTrue(s.any { it.tool == ToolId.SIGN && it.takes == listOf(1) })
        assertTrue(s.size <= 8)
    }

    @Test fun wordsOfAStatement() {
        val page = (1..9).joinToString("\n") { "0$it/04/2026 UPI transfer ${it},250.00 Dr 45,000.00 Cr" }
        assertEquals(true to false, Suggest.wordsSay(page))
        assertEquals(false to true, Suggest.wordsSay("Aadhaar 2345 6789 0123"))
    }

    @Test fun nothingFits() {
        assertEquals(Strings.get(R.string.catalog_none_slides), Suggest.noSuggestion(listOf(Facts(Kind.OTHER, 10, extension = "pptx"))))
        assertEquals(Strings.get(R.string.catalog_none_damaged_one), Suggest.noSuggestion(listOf(Facts(Kind.PDF, 10, unreadable = true))))
    }
}
