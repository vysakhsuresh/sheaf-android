package com.layerbit.sheaf.ops

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

    private fun plural(n: Int, one: String, many: String = one + "s") = if (n == 1) "1 $one" else "$n $many"

    fun suggest(files: List<Facts>, max: Int = 8): List<Suggestion> {
        val out = mutableListOf<Suggestion>()
        val pdfs = files.indices.filter { files[it].kind == Kind.PDF && !files[it].unreadable }
        val locked = pdfs.filter { files[it].locked }
        val images = files.indices.filter { files[it].kind == Kind.IMAGE }
        val office = files.indices.filter { files[it].kind == Kind.OFFICE }

        if (locked.isNotEmpty()) {
            out += Suggestion(
                ToolId.REMOVE_PASSWORD,
                if (locked.size == 1) "It asks for a password: unlock it, with the password, for good"
                else "${plural(locked.size, "file")} ask for a password: unlock them",
                locked, 100
            )
        }
        if (images.isNotEmpty()) {
            val n = images.size
            out += Suggestion(ToolId.IMAGES_TO_PDF, if (n == 1) "Turn the photo into a PDF" else "Make one PDF of these $n pictures", images, 90)
            out += Suggestion(
                ToolId.RESIZE_PHOTO,
                if (n == 1) "For an upload form? Get it under 20 KB, 50 KB or any size" else "For an upload form? Get all $n under 20 KB, 50 KB or any size",
                images, 89
            )
        }
        if (office.isNotEmpty()) {
            val n = office.size
            out += Suggestion(
                ToolId.OFFICE_TO_PDF,
                if (n == 1) "Turn the Word, Excel or text file into a PDF" else "Turn these $n Word, Excel or text files into PDFs",
                office, 90
            )
        }
        if (pdfs.size >= 2) {
            val n = pdfs.size
            val total = pdfs.sumOf { files[it].bytes }
            out += Suggestion(ToolId.MERGE, "Join these $n PDFs into one", pdfs, 88)
            out += Suggestion(ToolId.COMPRESS, "Make all $n smaller (${mb(total)} together)", pdfs, if (total > BIG) 80 else 40)
            if (n == 2) out += Suggestion(ToolId.COMPARE, "Two versions of one document? See what changed", pdfs, 55)
            out += Suggestion(ToolId.SET_PASSWORD, "Lock all $n with a password", pdfs, 35)
            out += Suggestion(ToolId.PDF_TO_OFFICE, "Turn all $n into Word files you can edit", pdfs, 30)
            out += Suggestion(ToolId.PAGE_NUMBERS, "Number the pages of all $n", pdfs, 25)
        } else if (pdfs.size == 1) {
            val i = pdfs.single()
            val f = files[i]
            val one = listOf(i)
            val pages = f.pages ?: 0
            if (f.scanned) {
                out += Suggestion(ToolId.OCR, "It is a scan: make its words searchable and copyable", one, 85)
                out += Suggestion(ToolId.EDIT_TEXT, "Change words on the scan: they are read first, on this phone", one, 50)
            }
            if (f.forms) out += Suggestion(ToolId.FORMS, "It has fields: fill them in and save", one, 84)
            if (f.amounts) out += Suggestion(ToolId.PDF_TO_OFFICE, "It is full of amounts: get them into a spreadsheet you can add up", one, 80, preset = PRESET_EXCEL)
            if (f.idNumber) out += Suggestion(ToolId.REDACT, "It shows an ID number (Aadhaar or PAN): hide it for good before you share it", one, 66)
            if (f.bytes > BIG) out += Suggestion(ToolId.COMPRESS, "It is ${mb(f.bytes)}: make it smaller", one, 82)
            out += Suggestion(ToolId.SIGN, "Sign it", one, if (pages == 1) 75 else 60)
            if (!f.scanned) out += Suggestion(ToolId.EDIT_TEXT, "Change words on it", one, 58)
            if (pages >= 2) {
                out += Suggestion(ToolId.ORGANISE, "Reorder, turn or delete its $pages pages", one, 57)
                out += Suggestion(ToolId.SPLIT, "Split its $pages pages into separate files", one, 52)
                if (!f.scanned) out += Suggestion(ToolId.SUMMARISE, "Pick out its key points", one, 47)
                out += Suggestion(ToolId.EXTRACT, "Keep only the pages you need", one, 45)
            }
            if (!f.scanned) out += Suggestion(ToolId.PDF_TO_OFFICE, "Turn it into a Word file you can edit", one, 48)
            out += Suggestion(ToolId.ANNOTATE, "Highlight, underline or add notes", one, 44)
            if (f.bytes <= BIG) out += Suggestion(ToolId.COMPRESS, "Make it smaller", one, 40)
            if (!f.locked) out += Suggestion(ToolId.SET_PASSWORD, "Lock it with a password", one, 38)
            out += Suggestion(ToolId.REDACT, "Hide private details for good (Aadhaar, PAN, account numbers)", one, 30)
            out += Suggestion(null, "Just read it", one, 20)
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
                "This PDF is damaged and couldn't be read. If it came by email or WhatsApp, download it again: it may have been cut short."
            } else {
                "These PDFs are damaged and couldn't be read. If they came by email or WhatsApp, download them again: they may have been cut short."
            }
        }
        val extensions = files.map { it.extension }
        if (extensions.any { it in setOf("ppt", "pptx", "key", "odp") }) {
            return "Slides cannot be turned into a PDF here yet. In PowerPoint, Keynote or Google Slides, choose Save as PDF, then open that PDF here."
        }
        if (extensions.any { it in setOf("doc", "xls", "odt", "ods", "rtf") }) {
            return "Only .docx, .xlsx, .txt, .md and .csv files can be turned into a PDF here. Save it as .docx or .xlsx (or straight as a PDF) and open that."
        }
        return "Nothing here is a PDF, a picture or a Word or Excel file. Pick a tool from the home screen instead."
    }

    const val PRESET_EXCEL = "excel"
}
