package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageMark
import com.layerbit.sheaf.pdf.PagePoint
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.PlacedWord
import com.layerbit.sheaf.pdf.ReceiptSpec
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Documents made from what is typed, and the two tools that read a document's words as
 * words: comparing two versions, and changing a line.
 */

/** What a year of rent receipts needs to say. */
data class RentReceipts(
    val tenant: String,
    val landlord: String,
    val address: String,
    /** Monthly rent in rupees. */
    val rent: Long,
    /** The first month: year and month, one-based. */
    val startYear: Int,
    val startMonth: Int,
    val months: Int,
    val mode: String,
    /** The owner's PAN, which employers ask for when the rent is over a lakh a year. */
    val pan: String
)

/**
 * Rent receipts for an HRA claim: one per month, two to a page, ready to print and sign.
 *
 * The one document Indian salaried people need every year and nobody has a template for.
 * The amount is written in words as a receipt or a cheque writes it, with lakhs and crores.
 */
class RentReceiptsOp(private val spec: RentReceipts) : Op {

    override val tool = ToolId.RENT_RECEIPTS
    override val title = Strings.get(R.string.catalog_tool_rent_receipts_title)
    override val arity = Op.Arity.Together(min = 0)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val missing = when {
            spec.tenant.isBlank() -> R.string.catalog_rent_need_tenant
            spec.landlord.isBlank() -> R.string.catalog_rent_need_owner
            spec.address.isBlank() -> R.string.catalog_rent_need_address
            else -> null
        }
        if (missing != null) return@withContext listOf(OpOutcome.Failed("", Strings.get(missing)))
        if (spec.rent <= 0 || spec.months !in 1..24) {
            return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_rent_need_amount)))
        }

        onProgress(Progress(0, 1, Strings.get(R.string.catalog_progress_receipts)))
        val amount = "Rs. ${indianGrouping(spec.rent)} (${amountInWords(spec.rent)})"
        val receipts = (0 until spec.months).map { i ->
            val month = monthName(spec.startYear, spec.startMonth, i)
            val rows = buildList {
                add("Received from" to spec.tenant.trim())
                add("The sum of" to amount)
                add("Towards rent for" to "$month, for the property at ${spec.address.trim()}")
                add("Paid by" to spec.mode)
                add("Received by (owner)" to spec.landlord.trim())
                if (spec.pan.isNotBlank()) add("Owner's PAN" to spec.pan.trim().uppercase())
            }
            val footer = buildList {
                add("Signature of the owner: ______________________          Date: ____________")
                if (spec.mode == "Cash" && spec.rent > 5000) {
                    add("Affix a revenue stamp here: a cash payment over Rs. 5,000 needs one.")
                }
            }
            ReceiptSpec("RENT RECEIPT", month, rows, footer)
        }

        val first = monthName(spec.startYear, spec.startMonth, 0)
        val last = monthName(spec.startYear, spec.startMonth, spec.months - 1)
        val name = if (spec.months == 1) "Rent receipt $first" else "Rent receipts $first to $last"
        val output = context.workspace.newOutput(name, "receipts")
        val pages = try {
            context.surgeon.writeReceipts(receipts, output)
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed("", e.message ?: Strings.get(R.string.catalog_rent_failed)))
        }
        onProgress(Progress(1, 1, Strings.get(R.string.catalog_done)))

        // Employers ask for the owner's PAN once the rent passes a lakh a year (the HRA rule).
        val askPan = spec.pan.isBlank() && spec.rent * 12 > 100_000
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "$name.pdf", SheafFile.Origin.Derived("receipts")),
                Strings.get(
                    if (askPan) R.string.catalog_rent_done_pan else R.string.catalog_rent_done,
                    Strings.plural(R.plurals.catalog_rent_count, spec.months, spec.months, first, last),
                    Strings.plural(R.plurals.catalog_rent_sheets, pages)
                )
            )
        )
    }

    companion object {
        private val ONES = listOf(
            "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
            "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"
        )
        private val TENS = listOf("", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety")
        private val MONTHS = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
        )

        private fun belowHundred(n: Int): String {
            if (n < 20) return ONES[n]
            val one = ONES[n % 10]
            return if (one.isEmpty()) TENS[n / 10] else "${TENS[n / 10]} $one"
        }

        private fun belowThousand(n: Int): String = listOf(
            if (n / 100 > 0) "${ONES[n / 100]} Hundred" else "",
            if (n % 100 > 0) belowHundred(n % 100) else ""
        ).filter { it.isNotEmpty() }.joinToString(" ")

        /** 1234567 becomes "Twelve Lakh Thirty Four Thousand Five Hundred Sixty Seven". */
        fun rupeesInWords(amount: Long): String {
            var n = kotlin.math.abs(amount)
            if (n == 0L) return "Zero"
            val crore = (n / 10_000_000).toInt()
            n %= 10_000_000
            val lakh = (n / 100_000).toInt()
            n %= 100_000
            val thousand = (n / 1000).toInt()
            n %= 1000
            return listOf(
                if (crore > 0) "${if (crore > 999) rupeesInWords(crore.toLong()) else belowThousand(crore)} Crore" else "",
                if (lakh > 0) "${belowHundred(lakh)} Lakh" else "",
                if (thousand > 0) "${belowHundred(thousand)} Thousand" else "",
                if (n > 0) belowThousand(n.toInt()) else ""
            ).filter { it.isNotEmpty() }.joinToString(" ")
        }

        /** 15000 becomes "Rupees Fifteen Thousand only", as a receipt or a cheque writes it. */
        fun amountInWords(amount: Long): String = "Rupees ${rupeesInWords(amount)} only"

        /** 1234567 becomes "12,34,567": Indian grouping. */
        fun indianGrouping(amount: Long): String {
            val whole = kotlin.math.abs(amount).toString()
            if (whole.length <= 3) return whole
            val last3 = whole.takeLast(3)
            val rest = whole.dropLast(3)
            val grouped = StringBuilder()
            for ((i, ch) in rest.reversed().withIndex()) {
                if (i > 0 && i % 2 == 0) grouped.append(',')
                grouped.append(ch)
            }
            return "${grouped.reverse()},$last3"
        }

        /** April 2026 plus `i` months, as "May 2026". */
        fun monthName(year: Int, month: Int, i: Int): String {
            val index = (month - 1).coerceIn(0, 11) + i
            return "${MONTHS[((index % 12) + 12) % 12]} ${year + Math.floorDiv(index, 12)}"
        }

        /** This month, for the fields to start from. */
        fun thisMonth(): Pair<Int, Int> {
            val now = Calendar.getInstance()
            return now.get(Calendar.YEAR) to now.get(Calendar.MONTH) + 1
        }
    }
}

/**
 * Changes a line of text wherever it appears: a date, a name, a number.
 *
 * The nearest thing to editing a PDF that a PDF honestly allows. The old words are covered
 * and the new ones written over them in Helvetica, sized to fit the same space - so the font
 * will differ from the document's own, and the line is patched rather than reflowed. The
 * UI says so.
 */
class ReplaceTextOp(
    private val find: String,
    private val replacement: String,
    private val matchCase: Boolean = false
) : Op {

    override val tool = ToolId.EDIT_TEXT
    override val title = Strings.get(R.string.catalog_replace_title, find)
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull()
            ?: return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_no_document)))
        val needle = find.trim()
        if (needle.isEmpty()) return@withContext listOf(OpOutcome.Failed(input.displayName, Strings.get(R.string.catalog_replace_need_words)))

        val pattern = if (matchCase) Regex(Regex.escape(needle)) else Regex(Regex.escape(needle), RegexOption.IGNORE_CASE)
        val pdfInput = PdfInput(input.file, context.passwords[input.file.path])
        val output = context.workspace.newOutput(input.baseName, "edited")
        val changed = try {
            context.surgeon.replaceText(pdfInput, pattern, replacement.trim(), output) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total, Strings.get(R.string.catalog_progress_page_of, done, total)))
            }
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.catalog_failed)))
        }
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} edited.pdf", SheafFile.Origin.Derived("edited")),
                if (replacement.isBlank()) {
                    Strings.plural(R.plurals.catalog_replace_removed, changed, needle, changed)
                } else {
                    Strings.plural(R.plurals.catalog_replace_changed, changed, needle, replacement.trim(), changed)
                }
            )
        )
    }
}

/**
 * What changed between two versions of a document.
 *
 * The newer one comes back marked: words that are new are highlighted green, and where words
 * were taken out a note says what they were. Nothing else on its pages changes, so it can be
 * read and sent as it is. Words only: a change in a picture or in layout is not seen.
 */
class CompareOp : Op {

    override val tool = ToolId.COMPARE
    override val title = Strings.get(R.string.catalog_compare_title)
    override val arity = Op.Arity.Together(min = 2)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        if (inputs.size != 2) {
            return@withContext listOf(OpOutcome.Failed("", Strings.get(R.string.catalog_compare_need_two)))
        }
        val (older, newer) = inputs
        val olderInput = PdfInput(older.file, context.passwords[older.file.path])
        val newerInput = PdfInput(newer.file, context.passwords[newer.file.path])

        val a: List<PlacedWord>
        val b: List<PlacedWord>
        try {
            a = context.surgeon.placedWords(olderInput) { done, total ->
                context.checkCancelled()
                onProgress(Progress(done, total * 2, Strings.get(R.string.catalog_progress_reading_older, done)))
            }
            b = context.surgeon.placedWords(newerInput) { done, total ->
                context.checkCancelled()
                onProgress(Progress(total + done, total * 2, Strings.get(R.string.catalog_progress_reading_newer, done)))
            }
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(newer.displayName, e.message ?: Strings.get(R.string.catalog_unreadable)))
        }
        if (a.isEmpty() || b.isEmpty()) {
            return@withContext listOf(
                OpOutcome.Failed(
                    newer.displayName,
                    Strings.get(R.string.catalog_compare_no_words)
                )
            )
        }

        onProgress(Progress(0, 1, Strings.get(R.string.catalog_progress_comparing)))
        val changes = WordDiff.diff(a.map { it.text }, b.map { it.text })

        val added = mutableListOf<PlacedWord>()
        val notes = mutableListOf<Pair<PlacedWord, String>>()
        var removed = 0
        val pending = mutableListOf<String>()
        var lastB: PlacedWord? = null
        fun flush(next: PlacedWord?) {
            if (pending.isEmpty()) return
            // The note sits where the words used to be: just before the word that now follows.
            val anchor = next ?: lastB ?: b.last()
            val text = pending.joinToString(" ")
            notes += anchor to "Removed: " + (if (text.length > 600) text.take(600) + "…" else text)
            pending.clear()
        }
        for (change in changes) {
            when (change) {
                is WordDiff.Change.Removed -> {
                    pending += a[change.a].text
                    removed += 1
                }
                is WordDiff.Change.Added -> {
                    val word = b[change.b]
                    flush(word)
                    added += word
                    lastB = word
                }
                is WordDiff.Change.Same -> {
                    val word = b[change.b]
                    flush(word)
                    lastB = word
                }
            }
        }
        flush(null)

        if (added.isEmpty() && removed == 0) {
            return@withContext listOf(
                OpOutcome.Skipped(
                    newer.displayName,
                    Strings.get(R.string.catalog_compare_same)
                )
            )
        }

        val marks = mutableMapOf<Int, MutableList<PageMark>>()
        for (area in mergeNeighbours(added)) {
            marks.getOrPut(area.first) { mutableListOf() } += PageMark.Highlight(area.second, ADDED_GREEN)
        }
        for ((at, text) in notes) {
            marks.getOrPut(at.pageIndex) { mutableListOf() } +=
                PageMark.Note(PagePoint((at.area.left - 0.03f).coerceAtLeast(0f), at.area.top), text, NOTE_RED, 7f)
        }

        val output = context.workspace.newOutput(newer.baseName, "compared")
        try {
            context.surgeon.annotate(newerInput, marks, output) { _, _ -> context.checkCancelled() }
        } catch (e: PdfException) {
            output.delete()
            return@withContext listOf(OpOutcome.Failed(newer.displayName, e.message ?: Strings.get(R.string.catalog_failed)))
        }
        val pages = marks.keys.size
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${newer.baseName} compared.pdf", SheafFile.Origin.Derived("compared")),
                Strings.get(
                    R.string.catalog_compare_done,
                    Strings.plural(R.plurals.catalog_compare_words, added.size, added.size, removed),
                    Strings.plural(R.plurals.catalog_compare_pages, pages)
                )
            )
        )
    }

    /** Neighbouring words on one line, merged into one box, so a new sentence is one highlight, not twelve. */
    private fun mergeNeighbours(words: List<PlacedWord>): List<Pair<Int, PageArea>> {
        val out = mutableListOf<Pair<Int, PageArea>>()
        for (word in words) {
            val last = out.lastOrNull()
            val a = word.area
            if (last != null && last.first == word.pageIndex) {
                val l = last.second
                val sameLine = kotlin.math.abs(l.top - a.top) < a.height * 0.5f
                val adjacent = a.left - (l.left + l.width) < a.height * 1.5f && a.left >= l.left
                if (sameLine && adjacent) {
                    out[out.lastIndex] = word.pageIndex to PageArea(
                        left = l.left,
                        top = minOf(l.top, a.top),
                        width = a.left + a.width - l.left,
                        height = maxOf(l.height, a.height)
                    )
                    continue
                }
            }
            out += word.pageIndex to a
        }
        return out
    }

    companion object {
        val ADDED_GREEN: Int = 0xFF73EB73.toInt()
        val NOTE_RED: Int = 0xFFD92626.toInt()
    }
}

/**
 * Which words changed between two versions: a patience diff.
 *
 * Words that appear exactly once in both versions anchor the comparison, so a paragraph
 * moved or a page inserted does not make everything after it look different; between
 * anchors, a plain longest-common-run comparison works out the rest while it is small enough
 * to be quick.
 */
object WordDiff {

    sealed interface Change {
        data class Same(val a: Int, val b: Int) : Change
        data class Removed(val a: Int) : Change
        data class Added(val b: Int) : Change
    }

    /** Gaps bigger than this many word pairs are not compared word by word: they changed. */
    private const val DP_LIMIT = 1_000_000L

    fun diff(a: List<String>, b: List<String>): List<Change> {
        val out = mutableListOf<Change>()
        val ka = a.map { key(it) }
        val kb = b.map { key(it) }

        fun lcs(aLo: Int, aHi: Int, bLo: Int, bHi: Int) {
            val n = aHi - aLo
            val m = bHi - bLo
            val table = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) {
                for (j in m - 1 downTo 0) {
                    table[i][j] = if (ka[aLo + i] == kb[bLo + j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
                }
            }
            var i = 0
            var j = 0
            while (i < n && j < m) {
                when {
                    ka[aLo + i] == kb[bLo + j] -> { out += Change.Same(aLo + i, bLo + j); i++; j++ }
                    table[i + 1][j] >= table[i][j + 1] -> { out += Change.Removed(aLo + i); i++ }
                    else -> { out += Change.Added(bLo + j); j++ }
                }
            }
            while (i < n) { out += Change.Removed(aLo + i); i++ }
            while (j < m) { out += Change.Added(bLo + j); j++ }
        }

        fun uniqueAnchors(aLo: Int, aHi: Int, bLo: Int, bHi: Int): List<Pair<Int, Int>> {
            val countA = HashMap<String, Int>()
            val countB = HashMap<String, Int>()
            val whereB = HashMap<String, Int>()
            for (i in aLo until aHi) countA[ka[i]] = (countA[ka[i]] ?: 0) + 1
            for (j in bLo until bHi) {
                countB[kb[j]] = (countB[kb[j]] ?: 0) + 1
                whereB[kb[j]] = j
            }
            val pairs = mutableListOf<Pair<Int, Int>>()
            for (i in aLo until aHi) {
                val w = ka[i]
                if (countA[w] == 1 && countB[w] == 1) pairs += i to whereB.getValue(w)
            }
            // Longest increasing run of b positions, by patience sorting.
            val tops = mutableListOf<Int>()
            val topAt = mutableListOf<Int>()
            val back = IntArray(pairs.size) { -1 }
            pairs.forEachIndexed { k, (_, j) ->
                var lo = 0
                var hi = tops.size
                while (lo < hi) {
                    val mid = (lo + hi) ushr 1
                    if (tops[mid] < j) lo = mid + 1 else hi = mid
                }
                if (lo > 0) back[k] = topAt[lo - 1]
                if (lo == tops.size) { tops += j; topAt += k } else { tops[lo] = j; topAt[lo] = k }
            }
            val run = ArrayDeque<Pair<Int, Int>>()
            var k = topAt.lastOrNull() ?: -1
            while (k >= 0) {
                run.addFirst(pairs[k])
                k = back[k]
            }
            return run.toList()
        }

        fun range(aLo0: Int, aHi0: Int, bLo0: Int, bHi0: Int) {
            var aLo = aLo0; var aHi = aHi0; var bLo = bLo0; var bHi = bHi0
            // Shared start and end first: the common case is a small change in the middle.
            while (aLo < aHi && bLo < bHi && ka[aLo] == kb[bLo]) { out += Change.Same(aLo, bLo); aLo++; bLo++ }
            val tail = ArrayDeque<Change>()
            while (aHi > aLo && bHi > bLo && ka[aHi - 1] == kb[bHi - 1]) { aHi--; bHi--; tail.addFirst(Change.Same(aHi, bHi)) }
            if (aLo == aHi || bLo == bHi) {
                for (i in aLo until aHi) out += Change.Removed(i)
                for (j in bLo until bHi) out += Change.Added(j)
                out += tail
                return
            }
            val anchors = uniqueAnchors(aLo, aHi, bLo, bHi)
            if (anchors.isNotEmpty()) {
                var pa = aLo
                var pb = bLo
                for ((i, j) in anchors) {
                    range(pa, i, pb, j)
                    out += Change.Same(i, j)
                    pa = i + 1
                    pb = j + 1
                }
                range(pa, aHi, pb, bHi)
            } else if ((aHi - aLo).toLong() * (bHi - bLo) <= DP_LIMIT) {
                lcs(aLo, aHi, bLo, bHi)
            } else {
                for (i in aLo until aHi) out += Change.Removed(i)
                for (j in bLo until bHi) out += Change.Added(j)
            }
            out += tail
        }

        range(0, ka.size, 0, kb.size)
        return out
    }

    private fun key(word: String): String = word.lowercase().replace(Regex("[“”\"’'‘]"), "'")
}
