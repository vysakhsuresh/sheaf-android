package com.layerbit.sheaf.pdf

import android.util.Base64
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.util.Matrix

/**
 * Words in any script as real, invisible text: what makes a Hindi or Malayalam scan
 * searchable.
 *
 * Helvetica covers Latin and nothing else, and the phone's own fonts differ from phone to
 * phone and cannot all be embedded. This font draws nothing at all - a few hundred bytes, one
 * blank glyph every character maps to - so it can carry any script. The text goes in as UTF-16
 * codes through Identity-H, and a ToUnicode map says each code is itself, so what a reader
 * copies out is exactly the words that were put in. Written in render mode 3 it never shows.
 * Characters outside the Basic Multilingual Plane (emoji) are left out.
 *
 * The same font Foolscap writes, byte for byte, so a page made searchable on either reads the
 * same in every reader.
 */
internal class InvisibleText(private val doc: PDDocument) {

    private val font: PDType0Font by lazy { PDType0Font(fontDictionary()) }

    /**
     * Writes [text] invisibly with its baseline starting at ([x], [y]), [size] points tall,
     * squeezed or stretched to come out [width] points wide so a selection covers the ink.
     * Call outside a text object, inside the stream's current transform.
     */
    fun write(stream: PDPageContentStream, text: String, x: Float, y: Float, width: Float, size: Float) {
        val hex = codes(text)
        if (hex.isEmpty() || width <= 0f || size <= 0f) return
        val natural = hex.length / 4 * WIDTH * size
        // Inside its own save and restore: render mode and squeeze outlive the text object,
        // and anything written after this must not come out invisible or squashed.
        stream.saveGraphicsState()
        stream.beginText()
        try {
            stream.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER)
            stream.setFont(font, size)
            stream.setHorizontalScaling((100f * width / natural).coerceIn(5f, 1000f))
            stream.setTextMatrix(Matrix.getTranslateInstance(x, y))
            @Suppress("DEPRECATION")
            stream.appendRawCommands("<$hex> Tj\n")
        } finally {
            stream.endText()
            stream.restoreGraphicsState()
        }
    }

    private fun fontDictionary(): COSDictionary {
        val ttf = Base64.decode(GLYPHLESS_TTF, Base64.DEFAULT)
        val fontFile = PDStream(doc, ttf.inputStream(), COSName.FLATE_DECODE)
        fontFile.cosObject.setInt(COSName.LENGTH1, ttf.size)

        val descriptor = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT_DESC)
            setName(COSName.FONT_NAME, NAME)
            setInt(COSName.FLAGS, 5)
            setItem(COSName.FONT_BBOX, ints(0, 0, 500, 1000))
            setInt(COSName.ITALIC_ANGLE, 0)
            setInt(COSName.ASCENT, 1000)
            setInt(COSName.DESCENT, 0)
            setInt(COSName.CAP_HEIGHT, 1000)
            setInt(COSName.STEM_V, 80)
            setItem(COSName.FONT_FILE2, fontFile)
        }

        // Every code to glyph 1, the blank one.
        val map = ByteArray(131072)
        for (i in 1 until map.size step 2) map[i] = 1
        val cidToGid = PDStream(doc, map.inputStream(), COSName.FLATE_DECODE)

        val cidFont = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2)
            setName(COSName.BASE_FONT, NAME)
            setItem(COSName.CIDSYSTEMINFO, COSDictionary().apply {
                setItem(COSName.REGISTRY, COSString("Adobe"))
                setItem(COSName.ORDERING, COSString("Identity"))
                setInt(COSName.SUPPLEMENT, 0)
            })
            setItem(COSName.FONT_DESC, descriptor)
            setInt(COSName.DW, 500)
            setItem(COSName.CID_TO_GID_MAP, cidToGid)
        }

        val toUnicode = PDStream(doc, toUnicodeCMap().toByteArray(Charsets.US_ASCII).inputStream(), COSName.FLATE_DECODE)
        return COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.TYPE0)
            setName(COSName.BASE_FONT, NAME)
            setItem(COSName.ENCODING, COSName.IDENTITY_H)
            setItem(COSName.DESCENDANT_FONTS, COSArray().apply { add(cidFont) })
            setItem(COSName.TO_UNICODE, toUnicode)
        }
    }

    private fun ints(vararg values: Int) = COSArray().apply { values.forEach { add(COSInteger.get(it.toLong())) } }

    companion object {
        private const val NAME = "GlyphLessFont"
        private const val WIDTH = 0.5f

        private const val GLYPHLESS_TTF =
            "AAEAAAAKAIAAAwAgT1MvMkT7RUAAAAEoAAAAYGNtYXAADABzAAABkAAAADRnbHlmAAAAAAAAAcwAAAABaGVhZC0K0lQAAACsAAAANmhoZWED6QH2AAAA5AAAACRobXR4AfQAAAAAAYgAAAAGbG9jYQAAAAAAAAHEAAAABm1heHAAAwACAAABCAAAACBuYW1lGZ8ZNAAAAdAAAABycG9zdAAHAAAAAAJEAAAAJgABAAAAAQAAgmg2ll8PPPUAAwPoAAAAAOb6SLoAAAAA5vpIugAAAAAAAAAAAAAAAwACAAAAAAAAAAEAAAPoAAAAAAH0AAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAEAAAACAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAwH0AZAABQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAPz8/PwAAACAAIAPoAAAAAAPoAAAAAAAAAAAAAAAAAAAAAAAgAAAB9AAAAAAAAAAAAAIAAAADAAAAFAADAAEAAAAUAAQAIAAAAAQABAABAAAAIP//AAAAIP///+EAAQAAAAAAAAAAAAAAAAAAAAAAAAAEADYAAQAAAAAAAQANAAAAAQAAAAAAAgAHAA0AAwABBAkAAQAaABQAAwABBAkAAgAOAC5HbHlwaExlc3NGb250UmVndWxhcgBHAGwAeQBwAGgATABlAHMAcwBGAG8AbgB0AFIAZQBnAHUAbABhAHIAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAAADAAA="

        private val RTL = Regex("[\\u0590-\\u08ff\\ufb1d-\\ufdff\\ufe70-\\ufeff]")
        private val LTR = Regex("[A-Za-z0-9\\u00c0-\\u024f\\u0900-\\u0dff]")

        /**
         * The text as UTF-16 hex codes, emoji and controls left out. A right-to-left word is
         * stored in the order it is drawn, the way PDF makers store it; readers turn it back.
         */
        fun codes(text: String): String {
            val chars = text.toList().let { if (RTL.containsMatchIn(text) && !LTR.containsMatchIn(text)) it.reversed() else it }
            val out = StringBuilder()
            for (ch in chars) {
                val cp = ch.code
                if (ch.isSurrogate() || cp < 0x20 || cp in 0x7F until 0xA0) continue
                out.append(String.format("%04X", cp))
            }
            return out.toString()
        }

        private fun toUnicodeCMap(): String {
            val ranges = (0 until 256).map { hi -> "%02X".format(hi).let { "<${it}00> <${it}FF> <${it}00>" } }
            val blocks = ranges.chunked(100).joinToString("\n") { part ->
                "${part.size} beginbfrange\n${part.joinToString("\n")}\nendbfrange"
            }
            return listOf(
                "/CIDInit /ProcSet findresource begin",
                "12 dict begin",
                "begincmap",
                "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def",
                "/CMapName /Adobe-Identity-UCS def",
                "/CMapType 2 def",
                "1 begincodespacerange",
                "<0000> <FFFF>",
                "endcodespacerange",
                blocks,
                "endcmap",
                "CMapName currentdict /CMap defineresource pop",
                "end",
                "end"
            ).joinToString("\n")
        }
    }
}
