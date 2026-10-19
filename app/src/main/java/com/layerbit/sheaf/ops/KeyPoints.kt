package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The key points of a document, picked on the phone.
 *
 * Foolscap hands this to the AI built into a desktop browser, and does nothing where there is
 * none. A phone with no network permission has no such AI to hand it to, and the on-device
 * models that exist come through Play services on a handful of flagships. So this does what
 * works on every phone: it picks the document's own sentences that carry the most of what the
 * document keeps returning to, and lists the figures in it - amounts, dates, percentages. It
 * never writes a sentence the document did not, which for a lease or a statement is the
 * property worth having: every line can be found again in the original.
 */
class KeyPointsOp : Op {

    override val tool = ToolId.SUMMARISE
    override val title = "Key points"
    override val arity = Op.Arity.ExactlyOne

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        val input = inputs.firstOrNull() ?: return@withContext listOf(OpOutcome.Failed("", "Pick one PDF."))
        val pdf = PdfInput(input.file, context.passwords[input.file.path])
        onProgress(Progress(0, 2, "Reading the words…"))
        val pages = try {
            val count = context.surgeon.inspect(pdf).pageCount
            context.surgeon.extractText(pdf, (0 until min(count, MAX_PAGES)).toList()) to count
        } catch (e: PdfException) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, e.message ?: "This file couldn't be read."))
        }
        context.checkCancelled()
        val (byPage, total) = pages
        val text = (0 until min(total, MAX_PAGES)).joinToString("\n\n") { byPage[it].orEmpty() }.trim()
        if (text.isBlank()) {
            return@withContext listOf(
                OpOutcome.Failed(input.displayName, "This file has no words in it, only pictures of them. Run Make searchable first.")
            )
        }
        onProgress(Progress(1, 2, "Picking the key points…"))
        val summary = KeyPoints.of(text)
        if (summary.points.isEmpty()) {
            return@withContext listOf(OpOutcome.Failed(input.displayName, "There are too few full sentences in this document to pick from."))
        }
        val read = if (total > MAX_PAGES) "the first $MAX_PAGES of $total pages" else if (total == 1) "1 page" else "$total pages"
        val body = buildString {
            append("Key points of ").append(input.displayName).append(", from ").append(read).append(":\n\n")
            summary.points.forEach { append("• ").append(it).append("\n\n") }
            if (summary.figures.isNotEmpty()) {
                append("Figures in it:\n")
                summary.figures.forEach { append("• ").append(it).append('\n') }
            }
        }.trim() + "\n"
        val output = context.workspace.newOutput("${input.baseName} (key points)", "key-points", "txt")
        output.writeText(body)
        onProgress(Progress(2, 2, "Done"))
        listOf(
            OpOutcome.Produced(
                SheafFile(output, "${input.baseName} (key points).txt", SheafFile.Origin.Derived("key-points"), SheafFile.MIME_TEXT),
                "${summary.points.size} key points from $read, picked on this phone; each is a sentence from the document itself"
            )
        )
    }

    private companion object {
        const val MAX_PAGES = 300
    }
}

/** The arithmetic of picking. No Android, no PDF: tested on the JVM. */
object KeyPoints {

    class Summary(val points: List<String>, val figures: List<String>)

    // A title before a name, or Rs before an amount, is not the end of a sentence.
    private val SENTENCE_END = Regex(
        """(?<=[.!?।॥])(?<!\b(?:Mr|Ms|Dr|Rs|No|St|vs|Mrs|Smt|Sri|Shri|etc|viz|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Oct|Nov|Dec|Ltd|Pvt|Co)\.)(?<!\b\p{Lu}\.)\s+|\n{2,}|\n(?=\s*[•\-*\d]+[.)]?\s)"""
    )
    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val AMOUNT = Regex("(?:₹|Rs\\.?|INR|\\$|€|£)\\s?\\d[\\d,]*(?:\\.\\d{1,2})?(?:\\s?(?:lakh|crore|million|billion|/-))?", RegexOption.IGNORE_CASE)
    private val PERCENT = Regex("\\b\\d+(?:\\.\\d+)?\\s?%")
    private val DATE = Regex(
        "\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b|\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?,?\\s+\\d{4}\\b|\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s+\\d{1,2},?\\s+\\d{4}\\b",
        RegexOption.IGNORE_CASE
    )

    /** Words that say nothing about what a document is about, in English and Hindi. */
    private val STOP = setOf(
        "a", "an", "the", "and", "or", "but", "if", "then", "of", "to", "in", "on", "at", "by", "for", "with", "from", "as",
        "is", "are", "was", "were", "be", "been", "being", "it", "its", "this", "that", "these", "those", "there", "here",
        "he", "she", "they", "we", "you", "i", "his", "her", "their", "our", "your", "my", "me", "him", "them", "us",
        "not", "no", "so", "such", "can", "could", "will", "would", "shall", "should", "may", "might", "must", "do", "does",
        "did", "have", "has", "had", "which", "who", "whom", "what", "when", "where", "why", "how", "all", "any", "each",
        "other", "some", "more", "most", "also", "than", "into", "about", "over", "under", "upon", "per", "page", "said",
        "है", "हैं", "का", "की", "के", "में", "से", "को", "और", "पर", "यह", "वह", "था", "थे", "थी", "भी", "एक", "लिए", "तो", "ही"
    )

    fun of(text: String): Summary {
        val sentences = sentences(text)
        val figures = figures(text)
        if (sentences.isEmpty()) return Summary(emptyList(), figures)

        // How often each word turns up across the document, damped so one word repeated forty
        // times does not drown the rest.
        val counts = HashMap<String, Int>()
        val wordsOf = sentences.map { s -> contentWords(s).also { ws -> ws.forEach { counts[it] = (counts[it] ?: 0) + 1 } } }
        val weight = counts.mapValues { (_, n) -> ln(1.0 + n) }

        val scored = sentences.indices.map { i ->
            val words = wordsOf[i]
            val unique = words.toSet()
            var score = unique.sumOf { weight[it] ?: 0.0 } / sqrt(max(8, words.size).toDouble())
            // A document says what it is near its start.
            if (i < sentences.size * 0.15) score *= 1.25
            // Figures are why most documents are opened.
            if (AMOUNT.containsMatchIn(sentences[i]) || DATE.containsMatchIn(sentences[i])) score *= 1.15
            i to score
        }

        val want = (sqrt(sentences.size.toDouble()) * 1.5).roundToInt().coerceIn(3, 10).coerceAtMost(sentences.size)
        val chosen = mutableListOf<Int>()
        for ((i, _) in scored.sortedByDescending { it.second }) {
            if (chosen.size >= want) break
            val mine = wordsOf[i].toSet()
            // Two sentences saying the same thing are one point.
            val repeats = chosen.any { j ->
                val theirs = wordsOf[j].toSet()
                val overlap = mine.intersect(theirs).size.toDouble() / max(1, min(mine.size, theirs.size))
                overlap > 0.6
            }
            if (!repeats) chosen += i
        }
        return Summary(chosen.sorted().map { sentences[it] }, figures)
    }

    /** Whole sentences worth reading on their own: not headings, not table rows, not fragments. */
    fun sentences(text: String): List<String> =
        text.replace('\r', '\n')
            .replace(Regex("-\\n(?=\\p{Ll})"), "")
            .split(SENTENCE_END)
            .map { it.replace(Regex("\\s+"), " ").trim().trimStart('•', '-', '*', ' ') }
            .filter { s ->
                val words = WORD.findAll(s).count()
                val letters = s.count { it.isLetter() || Character.getType(it) == Character.NON_SPACING_MARK.toInt() || Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt() }
                words in 6..70 && letters >= s.length * 0.5
            }
            .distinct()

    private fun contentWords(sentence: String): List<String> =
        WORD.findAll(sentence.lowercase()).map { it.value }
            .filter { it.length > 2 && it !in STOP && !it.all { c -> c.isDigit() } }
            .toList()

    /** Amounts, percentages and dates, in the order they first appear, each once. */
    fun figures(text: String): List<String> {
        val found = mutableListOf<Pair<Int, String>>()
        for (pattern in listOf(AMOUNT, PERCENT, DATE)) {
            pattern.findAll(text).forEach { m -> found += m.range.first to m.value.replace(Regex("\\s+"), " ").trim() }
        }
        return found.sortedBy { it.first }.map { it.second }.distinctBy { it.lowercase() }.take(12)
    }
}
