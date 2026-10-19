package com.layerbit.sheaf.pdf

import com.tom_roush.fontbox.ttf.TrueTypeCollection
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File

/**
 * Fonts for text the built-in ones cannot set: Hindi, Tamil, Malayalam, Arabic, Chinese.
 *
 * Helvetica knows the Latin letters and nothing else. Android ships Noto fonts for every
 * script it displays, under /system/fonts, and PDFBox can embed one, so a Word file in
 * Marathi becomes a PDF in Marathi rather than a PDF of question marks. The fonts are the
 * phone's own: which scripts come out right depends on which fonts the phone has, and a
 * script with no font falls back to Helvetica with the characters it cannot set left out.
 *
 * One honest limit: PDFBox does not shape complex scripts, so conjuncts in Devanagari and
 * joined forms in Arabic are set letter by letter. Readable, not typeset.
 */
class SystemFonts(private val doc: PDDocument) {

    private val loaded = HashMap<String, PDFont?>()

    /** The font to set [text] in, bold or not. Helvetica when it can; a system font when it must. */
    fun fontFor(text: String, bold: Boolean, italic: Boolean): PDFont {
        val base = when {
            bold && italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
            bold -> PDType1Font.HELVETICA_BOLD
            italic -> PDType1Font.HELVETICA_OBLIQUE
            else -> PDType1Font.HELVETICA
        }
        val first = text.firstOrNull { it.code > 255 && !it.isWhitespace() } ?: return base
        val candidates = candidatesFor(first, bold)
        for (name in candidates) {
            val font = load(name) ?: continue
            if (canSet(font, text)) return font
        }
        // Nothing on the phone sets it, or only part of it: the first font that was found
        // sets what it can and the rest is left out, which beats a blank line.
        for (name in candidates) load(name)?.let { return it }
        return base
    }

    /** Whether every character of [text] has a glyph in [font]. */
    fun canSet(font: PDFont, text: String): Boolean =
        runCatching { font.encode(text); true }.getOrDefault(false)

    /** What [font] can set of [text]: the characters it has glyphs for. */
    fun settable(font: PDFont, text: String): String {
        if (canSet(font, text)) return text
        return buildString {
            for (ch in text) {
                val piece = ch.toString()
                if (runCatching { font.encode(piece); true }.getOrDefault(false)) append(piece)
            }
        }
    }

    private fun load(name: String): PDFont? = loaded.getOrPut(name) {
        val file = File(FONT_DIR, name)
        if (!file.isFile) return@getOrPut null
        runCatching {
            if (name.endsWith(".ttc")) {
                // A collection holds several faces; the first is the regular one.
                var found: PDFont? = null
                TrueTypeCollection(file).use { collection ->
                    collection.processAllFonts { ttf ->
                        if (found == null) found = PDType0Font.load(doc, ttf, true)
                    }
                }
                found
            } else {
                PDType0Font.load(doc, file)
            }
        }.getOrNull()
    }

    private fun candidatesFor(ch: Char, bold: Boolean): List<String> {
        val code = ch.code
        val families = when (code) {
            in 0x0900..0x097F -> listOf("NotoSansDevanagari", "NotoSansDevanagariUI", "NotoSerifDevanagari")
            in 0x0980..0x09FF -> listOf("NotoSansBengali", "NotoSansBengaliUI", "NotoSerifBengali")
            in 0x0A00..0x0A7F -> listOf("NotoSansGurmukhi", "NotoSansGurmukhiUI")
            in 0x0A80..0x0AFF -> listOf("NotoSansGujarati", "NotoSansGujaratiUI")
            in 0x0B00..0x0B7F -> listOf("NotoSansOriya", "NotoSansOriyaUI")
            in 0x0B80..0x0BFF -> listOf("NotoSansTamil", "NotoSansTamilUI", "NotoSerifTamil")
            in 0x0C00..0x0C7F -> listOf("NotoSansTelugu", "NotoSansTeluguUI")
            in 0x0C80..0x0CFF -> listOf("NotoSansKannada", "NotoSansKannadaUI")
            in 0x0D00..0x0D7F -> listOf("NotoSansMalayalam", "NotoSansMalayalamUI", "NotoSerifMalayalam")
            in 0x0D80..0x0DFF -> listOf("NotoSansSinhala", "NotoSansSinhalaUI")
            in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> listOf("NotoNaskhArabic", "NotoNaskhArabicUI", "NotoSansArabic")
            in 0x0590..0x05FF -> listOf("NotoSansHebrew")
            in 0x0E00..0x0E7F -> listOf("NotoSansThai", "NotoSansThaiUI")
            in 0x1000..0x109F -> listOf("NotoSansMyanmar", "NotoSansMyanmarUI")
            in 0x0E80..0x0EFF -> listOf("NotoSansLao")
            in 0x1780..0x17FF -> listOf("NotoSansKhmer", "NotoSansKhmerUI")
            in 0x10A0..0x10FF -> listOf("NotoSansGeorgian")
            in 0x0530..0x058F -> listOf("NotoSansArmenian")
            in 0x1200..0x137F -> listOf("NotoSansEthiopic")
            in 0x3040..0x30FF, in 0x3400..0x9FFF, in 0xAC00..0xD7AF, in 0xF900..0xFAFF -> return listOf("NotoSansCJK-Regular.ttc", "NotoSansSC-Regular.otf", "DroidSansFallback.ttf")
            else -> emptyList()
        }
        val weights = if (bold) listOf("Bold", "Regular") else listOf("Regular")
        val names = mutableListOf<String>()
        for (family in families) {
            for (weight in weights) names += "$family-$weight.ttf"
            // Android 13 and later ship one variable font per script in place of the
            // Regular and Bold files; its default instance is the regular weight.
            names += "$family-VF.ttf"
        }
        // Latin with accents, Greek, Cyrillic, symbols: the phone's UI font has them all.
        for (weight in weights) names += "Roboto-$weight.ttf"
        names += "RobotoStatic-Regular.ttf"
        names += "NotoSans-Regular.ttf"
        names += "DroidSans.ttf"
        names += "DroidSansFallback.ttf"
        return names
    }

    private companion object {
        const val FONT_DIR = "/system/fonts"
    }
}
