package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.text.Strings
import kotlin.math.roundToInt

/**
 * What to do with the files someone opened, before they have said.
 *
 * Each file is looked at on the phone - how many pages, whether it is locked, a scan, a form,
 * a statement full of amounts, a page showing an Aadhaar or PAN number - and every tool that
 * fits is scored; the best eight are offered, each once, with the reason in words. A person
 * who does not know what "OCR" is still knows "It is a scan: make its words searchable".
 */
object Suggest {

    enum class Kind { PDF, IMAGE, OFFICE, OTHER }

    /** What was found out about one file. Nothing here is the file's name or its words. */
    data class Facts(
        val kind: Kind,
        val bytes: Long,
        val pages: Int? = null,
        val locked: Boolean = false,
        val unreadable: Boolean = false,
        val scanned: Boolean = false,
        val forms: Boolean = false,
        val amounts: Boolean = false,
        val idNumber: Boolean = false,
        /** The extension, lower case, for saying why nothing fits. */
        val extension: String = ""
    )

    /** A tool, why it fits, and which of the files it takes. [preset] tunes the tool's options. */
    data class Suggestion(val tool: ToolId?, val why: String, val takes: List<Int>, val score: Int, val preset: String? = null) {
        /** Reading has no tool of its own: it opens the viewer. */
        val isRead: Boolean get() = tool == null
    }

    private const val BIG = 2L * 1024 * 1024

    private val AMOUNT = Regex("""^[₹$]?\s?\(?-?\d{1,3}(?:,\d{2,3})*(?:\.\d{2})\)?(?:\s?(?:Cr|Dr))?$""")
    private val AADHAAR = Regex("""\b\d{4}\s\d{4}\s\d{4}\b""")
    private val PAN = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")

    /** Whether the words of a first page look like a statement, and whether they show an ID. */
    fun wordsSay(text: String): Pair<Boolean, Boolean> {
        // Two decimals required, Indian grouping and Cr or Dr allowed: what a statement prints.
        val amounts = text.split(Regex("\\s+")).count { AMOUNT.matches(it.trim()) }
        val id = AADHAAR.containsMatchIn(text) || PAN.containsMatchIn(text)
        return (amounts >= 8) to id
    }

    fun mb(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return when {
            mb >= 10 -> "${mb.roundToInt()} MB"
            mb >= 1 -> "%.1f MB".format(mb)
            else -> "${(bytes / 1024.0).roundToInt().coerceAtLeast(1)} KB"
        }
    }

    fun suggest(files: List<Facts>, max: Int = 8): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        val pdfs = files.indices.filter { files[it].kind == Kind.PDF && !files[it].unreadable }
        val locked = pdfs.filter { files[it].locked }
        val images = files.indices.filter { files[it].kind == Kind.IMAGE }
        val office = files.indices.filter { files[it].kind == Kind.OFFICE }

        if (locked.isNotEmpty()) {
            out += Suggestion(
                ToolId.REMOVE_PASSWORD,
                if (locked.size == 1) Strings.get(R.string.catalog_suggest_unlock_one)
                else Strings.plural(R.plurals.catalog_suggest_unlock_many, locked.size),
                locked, 100
            )
        }
        if (images.isNotEmpty()) {
            val n = images.size
            out += Suggestion(ToolId.IMAGES_TO_PDF, if (n == 1) Strings.get(R.string.catalog_suggest_photo_one) else Strings.plural(R.plurals.catalog_suggest_photos, n), images, 90)
            out += Suggestion(
                ToolId.RESIZE_PHOTO,
                if (n == 1) Strings.get(R.string.catalog_suggest_resize_one) else Strings.plural(R.plurals.catalog_suggest_resize_many, n),
                images, 89
            )
        }
        if (office.isNotEmpty()) {
            val n = office.size
            out += Suggestion(
                ToolId.OFFICE_TO_PDF,
                if (n == 1) Strings.get(R.string.catalog_suggest_office_one) else Strings.plural(R.plurals.catalog_suggest_office_many, n),
                office, 90
            )
        }
        if (pdfs.size >= 2) {
            val n = pdfs.size
            val total = pdfs.sumOf { files[it].bytes }
            out += Suggestion(ToolId.MERGE, Strings.plural(R.plurals.catalog_suggest_merge, n), pdfs, 88)
            out += Suggestion(ToolId.COMPRESS, Strings.plural(R.plurals.catalog_suggest_compress_all, n, n, mb(total)), pdfs, if (total > BIG) 80 else 40)
            if (n == 2) out += Suggestion(ToolId.COMPARE, Strings.get(R.string.catalog_suggest_compare), pdfs, 55)
            out += Suggestion(ToolId.SET_PASSWORD, Strings.plural(R.plurals.catalog_suggest_lock_all, n), pdfs, 35)
            out += Suggestion(ToolId.PDF_TO_OFFICE, Strings.plural(R.plurals.catalog_suggest_word_all, n), pdfs, 30)
            out += Suggestion(ToolId.PAGE_NUMBERS, Strings.plural(R.plurals.catalog_suggest_number_all, n), pdfs, 25)
        } else if (pdfs.size == 1) {
            val i = pdfs.single()
            val f = files[i]
            val one = listOf(i)
            val pages = f.pages ?: 0
            if (f.scanned) {
                out += Suggestion(ToolId.OCR, Strings.get(R.string.catalog_suggest_ocr), one, 85)
                out += Suggestion(ToolId.EDIT_TEXT, Strings.get(R.string.catalog_suggest_edit_scan), one, 50)
            }
            if (f.forms) out += Suggestion(ToolId.FORMS, Strings.get(R.string.catalog_suggest_forms), one, 84)
            if (f.amounts) out += Suggestion(ToolId.PDF_TO_OFFICE, Strings.get(R.string.catalog_suggest_amounts), one, 80, preset = PRESET_EXCEL)
            if (f.idNumber) out += Suggestion(ToolId.REDACT, Strings.get(R.string.catalog_suggest_id_number), one, 66)
            if (f.bytes > BIG) out += Suggestion(ToolId.COMPRESS, Strings.get(R.string.catalog_suggest_big, mb(f.bytes)), one, 82)
            out += Suggestion(ToolId.SIGN, Strings.get(R.string.catalog_suggest_sign), one, if (pages == 1) 75 else 60)
            if (!f.scanned) out += Suggestion(ToolId.EDIT_TEXT, Strings.get(R.string.catalog_suggest_edit), one, 58)
            if (pages >= 2) {
                out += Suggestion(ToolId.ORGANISE, Strings.plural(R.plurals.catalog_suggest_organise, pages), one, 57)
                out += Suggestion(ToolId.SPLIT, Strings.plural(R.plurals.catalog_suggest_split, pages), one, 52)
                if (!f.scanned) out += Suggestion(ToolId.SUMMARISE, Strings.get(R.string.catalog_suggest_key_points), one, 47)
                out += Suggestion(ToolId.EXTRACT, Strings.get(R.string.catalog_suggest_extract), one, 45)
            }
            if (!f.scanned) out += Suggestion(ToolId.PDF_TO_OFFICE, Strings.get(R.string.catalog_suggest_word), one, 48)
            out += Suggestion(ToolId.ANNOTATE, Strings.get(R.string.catalog_suggest_annotate), one, 44)
            if (f.bytes <= BIG) out += Suggestion(ToolId.COMPRESS, Strings.get(R.string.catalog_suggest_compress), one, 40)
            if (!f.locked) out += Suggestion(ToolId.SET_PASSWORD, Strings.get(R.string.catalog_suggest_lock), one, 38)
            out += Suggestion(ToolId.REDACT, Strings.get(R.string.catalog_suggest_redact), one, 30)
            out += Suggestion(null, Strings.get(R.string.catalog_suggest_read), one, 20)
        }
        return out.sortedByDescending { it.score }
            .distinctBy { it.tool?.name ?: "READ" }
            .take(max)
    }

    /** Why nothing was suggested, in words that say what to do instead. */
    fun noSuggestion(files: List<Facts>): String {
        val pdfs = files.filter { it.kind == Kind.PDF }
        if (pdfs.isNotEmpty() && pdfs.all { it.unreadable } && pdfs.size == files.size) {
            return if (pdfs.size == 1) {
                Strings.get(R.string.catalog_none_damaged_one)
            } else {
                Strings.get(R.string.catalog_none_damaged_many)
            }
        }
        val extensions = files.map { it.extension }
        if (extensions.any { it in setOf("ppt", "pptx", "key", "odp") }) {
            return Strings.get(R.string.catalog_none_slides)
        }
        if (extensions.any { it in setOf("doc", "xls", "odt", "ods", "rtf") }) {
            return Strings.get(R.string.catalog_none_old_office)
        }
        return Strings.get(R.string.catalog_none_nothing)
    }

    const val PRESET_EXCEL = "excel"
}
