package com.layerbit.sheaf

import com.layerbit.sheaf.pdf.LinkTarget
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageLink
import com.layerbit.sheaf.pdf.PageWord
import com.layerbit.sheaf.ui.viewer.MIN_BRIGHTNESS
import com.layerbit.sheaf.ui.viewer.PaperTint
import com.layerbit.sheaf.ui.viewer.ReadingMode
import com.layerbit.sheaf.ui.viewer.SelectionMath
import com.layerbit.sheaf.ui.viewer.Spreads
import com.layerbit.sheaf.ui.viewer.TextSelection
import com.layerbit.sheaf.ui.viewer.linkAt
import com.layerbit.sheaf.ui.viewer.marginCrop
import com.layerbit.sheaf.ui.viewer.pageColourMatrix
import com.layerbit.sheaf.ui.viewer.pagePoint
import com.layerbit.sheaf.ui.viewer.steppedPage
import com.layerbit.sheaf.ui.viewer.within
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's reading sums: which word a finger means, which pages share a spread, which
 * link a tap lands on, what colour the paper comes out, and where the margins go.
 */
class ReadingLogicTest {

    /** Two lines of three words each, the second line a line-height below the first. */
    private val words = listOf(
        PageWord("The", PageArea(0.10f, 0.10f, 0.05f, 0.02f)),
        PageWord("quick", PageArea(0.16f, 0.10f, 0.08f, 0.02f)),
        PageWord("fox", PageArea(0.25f, 0.10f, 0.05f, 0.02f)),
        PageWord("jumps", PageArea(0.10f, 0.13f, 0.08f, 0.02f)),
        PageWord("over", PageArea(0.19f, 0.13f, 0.06f, 0.02f)),
        PageWord("dogs", PageArea(0.26f, 0.13f, 0.06f, 0.02f))
    )

    // ---- selection ----

    @Test
    fun aPressInsideAWordSelectsThatWord() {
        assertEquals(1, SelectionMath.wordAt(words, 0.20f, 0.11f))
    }

    @Test
    fun aPressJustOffAWordStillFindsTheNearestOne() {
        // A fingertip lands a little below the glyphs more often than on them.
        assertEquals(4, SelectionMath.wordAt(words, 0.21f, 0.155f))
    }

    @Test
    fun aPressInTheMarginSelectsNothing() {
        assertNull(SelectionMath.wordAt(words, 0.8f, 0.8f))
        assertNull(SelectionMath.wordAt(emptyList(), 0.2f, 0.1f))
    }

    @Test
    fun aHandleDraggedIntoTheMarginSnapsToTheLineBesideIt() {
        // Far to the right of the second line: the end of that line, not nothing.
        assertEquals(5, SelectionMath.nearestWord(words, 0.9f, 0.14f))
    }

    @Test
    fun selectionRunsTheSameWhicheverWayItWasDragged() {
        val forwards = TextSelection(0, words, 1, 4)
        val backwards = TextSelection(0, words, 4, 1)
        assertEquals(1, backwards.first)
        assertEquals(4, backwards.last)
        assertEquals(forwards.text, backwards.text)
    }

    @Test
    fun copiedTextKeepsItsLineBreaks() {
        assertEquals("quick fox\njumps over", SelectionMath.textOf(words, 1, 4))
        assertEquals("fox", SelectionMath.textOf(words, 2, 2))
    }

    @Test
    fun theHighlightIsOneBandPerLine() {
        val bands = SelectionMath.lineBoxes(words, 0, 5)
        assertEquals(2, bands.size)
        // The first band runs from the left of "The" to the right of "fox".
        assertEquals(0.10f, bands[0].left, 1e-4f)
        assertEquals(0.30f, bands[0].left + bands[0].width, 1e-4f)
        assertEquals(0.13f, bands[1].top, 1e-4f)
    }

    // ---- spreads ----

    @Test
    fun pagesPairFromTheStartWithTheOddOneLast() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), Spreads.pairs(5))
        assertEquals(listOf(listOf(0, 1)), Spreads.pairs(2))
        assertEquals(listOf(listOf(0)), Spreads.pairs(1))
        assertEquals(emptyList<List<Int>>(), Spreads.pairs(0))
    }

    @Test
    fun aBookOpensWithItsCoverAlone() {
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), Spreads.pairs(5, coverAlone = true))
        assertEquals(0, Spreads.spreadOf(0, 5, coverAlone = true))
        assertEquals(1, Spreads.spreadOf(2, 5, coverAlone = true))
        assertEquals(2, Spreads.spreadOf(3, 5, coverAlone = true))
    }

    @Test
    fun everyPageIsFoundInTheSpreadThatHoldsIt() {
        for (count in 1..9) {
            val spreads = Spreads.pairs(count)
            for (page in 0 until count) {
                val s = Spreads.spreadOf(page, count)
                assertTrue("page $page of $count is not in spread $s", page in spreads[s])
            }
        }
    }

    @Test
    fun keyboardStepsAWholeSpreadInTwoUp() {
        assertEquals(2, steppedPage(0, true, ReadingMode.TWO_UP, 10))
        assertEquals(2, steppedPage(1, true, ReadingMode.TWO_UP, 10))
        assertEquals(0, steppedPage(3, false, ReadingMode.TWO_UP, 10))
        assertEquals(4, steppedPage(3, true, ReadingMode.SINGLE, 10))
        assertEquals(9, steppedPage(9, true, ReadingMode.CONTINUOUS, 10))
        assertEquals(0, steppedPage(0, false, ReadingMode.CONTINUOUS, 10))
    }

    // ---- links ----

    private val links = listOf(
        PageLink(PageArea(0.1f, 0.5f, 0.2f, 0.02f), LinkTarget.Page(7)),
        PageLink(PageArea(0.1f, 0.53f, 0.2f, 0.02f), LinkTarget.Web("https://example.org"))
    )

    @Test
    fun aTapInsideALinkFollowsIt() {
        assertEquals(LinkTarget.Page(7), linkAt(links, 0.2f, 0.51f)?.target)
        assertEquals(LinkTarget.Web("https://example.org"), linkAt(links, 0.2f, 0.54f)?.target)
    }

    @Test
    fun aTapBetweenTwoLinksTakesTheCloser() {
        // 0.522 is 0.002 below the first link and 0.008 above the second.
        assertEquals(LinkTarget.Page(7), linkAt(links, 0.2f, 0.522f)?.target)
    }

    @Test
    fun aTapAwayFromAnyLinkIsJustATap() {
        assertNull(linkAt(links, 0.8f, 0.9f))
        assertNull(linkAt(emptyList(), 0.2f, 0.51f))
    }

    // ---- paper ----

    @Test
    fun dayAtFullBrightnessIsDrawnUntouched() {
        assertNull(pageColourMatrix(PaperTint.DAY, 1f))
    }

    @Test
    fun nightTurnsWhitePaperBlackAndBlackInkWhite() {
        val m = pageColourMatrix(PaperTint.NIGHT, 1f)!!
        assertEquals(0f, red(m, 255f), 0.5f)
        assertEquals(255f, red(m, 0f), 0.5f)
    }

    @Test
    fun sepiaWarmsThePaperAndLeavesTheInk() {
        val m = pageColourMatrix(PaperTint.SEPIA, 1f)!!
        assertEquals(0f, red(m, 0f), 0.5f)
        assertTrue("sepia paper should be warmer than it is blue", red(m, 255f) > blue(m, 255f))
    }

    @Test
    fun darkPaperIsDarkWithLightInk() {
        val m = pageColourMatrix(PaperTint.DARK_PAPER, 1f)!!
        assertTrue("dark paper should be dark", red(m, 255f) < 60f)
        assertTrue("ink on dark paper should be light", red(m, 0f) > 180f)
    }

    @Test
    fun dimmingScalesThePageTowardsBlack() {
        val m = pageColourMatrix(PaperTint.DAY, 0.5f)!!
        assertEquals(127.5f, red(m, 255f), 0.5f)
        // Never darker than the floor, however far the slider is pushed.
        val floor = pageColourMatrix(PaperTint.DAY, 0f)!!
        assertEquals(255f * MIN_BRIGHTNESS, red(floor, 255f), 0.5f)
    }

    /** The red a grey of [level] comes out as through [m]. */
    private fun red(m: FloatArray, level: Float) = m[0] * level + m[1] * level + m[2] * level + m[4]
    private fun blue(m: FloatArray, level: Float) = m[10] * level + m[11] * level + m[12] * level + m[14]

    // ---- margins ----

    @Test
    fun theCropKeepsALittleWhiteRoundTheInk() {
        val crop = marginCrop(floatArrayOf(0.2f, 0.1f, 0.8f, 0.9f))
        assertNotNull(crop)
        assertEquals(0.18f, crop!!.left, 1e-4f)
        assertEquals(0.08f, crop.top, 1e-4f)
        assertEquals(0.64f, crop.width, 1e-4f)
        assertEquals(0.84f, crop.height, 1e-4f)
    }

    @Test
    fun aBlankPageOrAFullOneIsNotCropped() {
        assertNull(marginCrop(null))
        assertNull(marginCrop(floatArrayOf(0.01f, 0.01f, 0.99f, 0.99f)))
        // A speck of dust is not a page's worth of ink.
        assertNull(marginCrop(floatArrayOf(0.5f, 0.5f, 0.505f, 0.505f)))
    }

    @Test
    fun marksAndTapsAgreeAboutTheCroppedPage() {
        val crop = PageArea(0.2f, 0.1f, 0.6f, 0.8f)
        val word = PageArea(0.5f, 0.5f, 0.06f, 0.08f)
        val shown = word.within(crop)
        assertEquals(0.5f, shown.left, 1e-4f)
        assertEquals(0.5f, shown.top, 1e-4f)
        assertEquals(0.1f, shown.width, 1e-4f)
        // A tap where the word is drawn lands back on the word.
        val (x, y) = pagePoint(shown.left, shown.top, crop)
        assertEquals(word.left, x, 1e-4f)
        assertEquals(word.top, y, 1e-4f)
    }
}
