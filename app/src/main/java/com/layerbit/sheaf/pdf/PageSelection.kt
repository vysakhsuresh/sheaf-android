package com.layerbit.sheaf.pdf

/**
 * Turns what a person types into page indices.
 *
 * People write page ranges the way they write them on paper - "1-3, 7, 12-" - and they write
 * them wrong: backwards ranges, spaces everywhere, a trailing comma, a page past the end. All
 * of that is ordinary input rather than an error state, so this is forgiving by design and
 * only refuses input with nothing usable in it at all.
 *
 * ONE-BASED GOING IN, ZERO-BASED COMING OUT. Users count from one and PDF libraries count from
 * zero, and every off-by-one in an app like this comes from that boundary being crossed in
 * more than one place. It is crossed here and nowhere else.
 *
 * Pure Kotlin, no Android types, so it is covered by ordinary JVM unit tests.
 */
object PageSelection {

    /**
     * @param spec what the user typed, e.g. "1-3, 7, 12-".
     * @param pageCount how many pages the document has.
     * @return zero-based indices, ascending, no duplicates. Empty if nothing matched.
     */
    fun parse(spec: String, pageCount: Int): List<Int> {
        if (pageCount <= 0) return emptyList()
        val selected = sortedSetOf<Int>()

        for (rawPart in spec.split(',')) {
            val part = rawPart.trim()
            if (part.isEmpty()) continue

            when {
                // "12-" means twelve to the end, which is how people write "the rest".
                part.endsWith('-') -> {
                    val from = part.dropLast(1).trim().toIntOrNull() ?: continue
                    addRange(selected, from, pageCount, pageCount)
                }
                // "-5" means the start up to five.
                part.startsWith('-') -> {
                    val to = part.drop(1).trim().toIntOrNull() ?: continue
                    addRange(selected, 1, to, pageCount)
                }
                part.contains('-') -> {
                    val halves = part.split('-', limit = 2)
                    val from = halves[0].trim().toIntOrNull() ?: continue
                    val to = halves[1].trim().toIntOrNull() ?: continue
                    // "9-3" is a backwards range. Treating it as 3-9 is what the user meant;
                    // rejecting it would only make them retype it the other way round.
                    addRange(selected, minOf(from, to), maxOf(from, to), pageCount)
                }
                else -> {
                    val single = part.toIntOrNull() ?: continue
                    addRange(selected, single, single, pageCount)
                }
            }
        }
        return selected.toList()
    }

    /** Every page. */
    fun all(pageCount: Int): List<Int> = (0 until maxOf(0, pageCount)).toList()

    /**
     * Groups pages into chunks of [size], for "split every N pages".
     * A final short group is kept - a 10-page document split every 3 gives 3, 3, 3, 1.
     */
    fun chunks(pageCount: Int, size: Int): List<List<Int>> {
        if (pageCount <= 0 || size <= 0) return emptyList()
        return all(pageCount).chunked(size)
    }

    /**
     * The inverse: which pages are NOT selected. Used by delete, which is expressed as "keep
     * everything else" so it goes through the same extract path rather than its own.
     */
    fun invert(selected: Collection<Int>, pageCount: Int): List<Int> {
        val drop = selected.toSet()
        return (0 until maxOf(0, pageCount)).filterNot { it in drop }
    }

    /** Renders indices back as a spec a person would recognise, collapsing runs into ranges. */
    fun describe(indices: List<Int>): String {
        if (indices.isEmpty()) return ""
        val sorted = indices.distinct().sorted()
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var previous = start

        for (value in sorted.drop(1)) {
            if (value == previous + 1) {
                previous = value
            } else {
                parts += renderRun(start, previous)
                start = value
                previous = value
            }
        }
        parts += renderRun(start, previous)
        return parts.joinToString(", ")
    }

    private fun renderRun(start: Int, end: Int): String =
        if (start == end) "${start + 1}" else "${start + 1}-${end + 1}"

    /** [from] and [to] are one-based and inclusive; out-of-range ends are clipped, not rejected. */
    private fun addRange(into: MutableSet<Int>, from: Int, to: Int, pageCount: Int) {
        val first = maxOf(1, from)
        val last = minOf(pageCount, to)
        for (page in first..last) into += page - 1
    }
}
