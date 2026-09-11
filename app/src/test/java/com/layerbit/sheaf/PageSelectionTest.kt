package com.layerbit.sheaf

import com.layerbit.sheaf.pdf.PageSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page ranges are where an app like this silently produces the wrong document.
 *
 * Nothing here throws, which is the design: people type ranges the way they write them on
 * paper, and that includes backwards ranges, stray spaces and a number past the end. The tests
 * that matter most are the ones asserting that malformed input is interpreted the way the
 * person meant rather than rejected or, worse, quietly turned into different pages.
 *
 * One-based going in, zero-based coming out - every assertion below is written to make that
 * boundary visible, because every off-by-one in this app would come from it.
 */
class PageSelectionTest {

    @Test
    fun `a single page is one index, counted from zero`() {
        assertEquals(listOf(0), PageSelection.parse("1", 10))
        assertEquals(listOf(9), PageSelection.parse("10", 10))
    }

    @Test
    fun `a range includes both ends`() {
        assertEquals(listOf(0, 1, 2), PageSelection.parse("1-3", 10))
    }

    @Test
    fun `a comma list combines and sorts`() {
        assertEquals(listOf(0, 1, 2, 6), PageSelection.parse("7, 1-3", 10))
    }

    @Test
    fun `an open range runs to the end of the document`() {
        assertEquals(listOf(7, 8, 9), PageSelection.parse("8-", 10))
    }

    @Test
    fun `a leading dash runs from the start`() {
        assertEquals(listOf(0, 1, 2), PageSelection.parse("-3", 10))
    }

    @Test
    fun `a backwards range is read the way it was meant`() {
        // Someone typing "9-3" meant pages three to nine. Rejecting it only makes them retype
        // the same intention the other way round.
        assertEquals(PageSelection.parse("3-9", 10), PageSelection.parse("9-3", 10))
    }

    @Test
    fun `pages past the end are clipped rather than invented`() {
        assertEquals(listOf(8, 9), PageSelection.parse("9-500", 10))
        assertTrue(PageSelection.parse("50", 10).isEmpty())
    }

    @Test
    fun `page zero and negative numbers cannot select anything`() {
        assertTrue(PageSelection.parse("0", 10).isEmpty())
        // "-2" is a leading-dash range meaning "up to page 2", not page minus two.
        assertEquals(listOf(0, 1), PageSelection.parse("-2", 10))
    }

    @Test
    fun `duplicates collapse`() {
        assertEquals(listOf(0, 1, 2), PageSelection.parse("1-3, 2, 1-2, 3", 10))
    }

    @Test
    fun `whitespace and trailing commas are tolerated`() {
        assertEquals(listOf(0, 4), PageSelection.parse("  1 ,  5 ,  ", 10))
    }

    @Test
    fun `nonsense yields nothing rather than something wrong`() {
        assertTrue(PageSelection.parse("", 10).isEmpty())
        assertTrue(PageSelection.parse("abc", 10).isEmpty())
        assertTrue(PageSelection.parse("!!,??", 10).isEmpty())
    }

    @Test
    fun `an empty document selects nothing whatever is asked for`() {
        assertTrue(PageSelection.parse("1-5", 0).isEmpty())
        assertTrue(PageSelection.all(0).isEmpty())
    }

    @Test
    fun `chunks keep a short final group`() {
        val groups = PageSelection.chunks(pageCount = 10, size = 3)
        assertEquals(4, groups.size)
        assertEquals(listOf(9), groups.last())
        assertEquals(10, groups.sumOf { it.size })
    }

    @Test
    fun `a chunk size larger than the document gives one group`() {
        assertEquals(listOf(PageSelection.all(4)), PageSelection.chunks(4, 99))
    }

    @Test
    fun `a chunk size of zero cannot loop forever`() {
        assertTrue(PageSelection.chunks(10, 0).isEmpty())
    }

    @Test
    fun `invert is what delete is built on`() {
        assertEquals(listOf(0, 3, 4), PageSelection.invert(listOf(1, 2), 5))
        assertTrue(PageSelection.invert(PageSelection.all(5), 5).isEmpty())
    }

    @Test
    fun `describe renders runs back the way a person would write them`() {
        assertEquals("1-3", PageSelection.describe(listOf(0, 1, 2)))
        assertEquals("1, 5", PageSelection.describe(listOf(0, 4)))
        assertEquals("1-2, 7-9", PageSelection.describe(listOf(0, 1, 6, 7, 8)))
        assertEquals("", PageSelection.describe(emptyList()))
    }

    @Test
    fun `describe round-trips through parse`() {
        val original = PageSelection.parse("2-4, 9, 12-14", 20)
        assertEquals(original, PageSelection.parse(PageSelection.describe(original), 20))
    }
}
