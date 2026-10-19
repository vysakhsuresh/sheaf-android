package com.layerbit.sheaf

import com.layerbit.sheaf.ops.TextEditPlans
import com.layerbit.sheaf.pdf.EditableLine
import com.layerbit.sheaf.pdf.OcrLine
import com.layerbit.sheaf.pdf.PageArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditPlansTest {

    /** "Total due Rs 5,000 only" on a scan: five words, each 0.1 wide with 0.02 between. */
    private fun scanLine(): EditableLine {
        val words = listOf("Total", "due", "Rs", "5,000", "only").mapIndexed { i, w -> w to PageArea(0.1f + i * 0.12f, 0.5f, 0.1f, 0.02f) }
        return EditableLine("Total due Rs 5,000 only", PageArea(0.1f, 0.495f, 0.58f, 0.025f), 0.52f, 12f, roomLeft = 0.05f, roomRight = 0.95f, words = words)
    }

    private val measure: (String) -> Float = { it.length * 0.02f }

    @Test fun onlyTheChangedWordIsRepainted() {
        val plan = TextEditPlans.planScan(scanLine(), "Total due Rs 6,500 only", 0.02f, measure)!!
        assertEquals("6,500", plan.text)
        assertEquals(0.46f, plan.paintFrom, 1e-4f)
        assertEquals(0.56f, plan.paintTo, 1e-4f)
    }

    @Test fun aLongerChangeRewritesToTheEnd() {
        val plan = TextEditPlans.planScan(scanLine(), "Total due Rs 5,00,000.00 only, by cheque", 0.02f, measure)!!
        assertEquals("5,00,000.00 only, by cheque", plan.text)
        assertEquals(0.95f, plan.right, 1e-4f)
    }

    @Test fun unchangedIsNoPlan() {
        assertNull(TextEditPlans.planScan(scanLine(), "Total due Rs 5,000 only", 0.02f, measure))
    }

    @Test fun insertionShiftsTheRest() {
        val plan = TextEditPlans.planScan(scanLine(), "Total amount due Rs 5,000 only", 0.02f, measure)!!
        assertEquals("amount due Rs 5,000 only", plan.text)
    }

    @Test fun wholeWordsOnly() {
        assertEquals("Rs 15,000.00 and 6,000.00", TextEditPlans.replaceIn("Rs 15,000.00 and 5,000.00", "5,000.00", "6,000.00", false))
        assertEquals("Paid by Anjali", TextEditPlans.replaceIn("Paid by ANJU", "anju", "Anjali", false))
        assertEquals("Paid by ANJU", TextEditPlans.replaceIn("Paid by ANJU", "anju", "Anjali", true))
        assertEquals("a b", TextEditPlans.replaceIn("a old b", "old", "", false))
    }

    @Test fun paperAndInkFromPixels() {
        val w = 20; val h = 10
        val cream = 0xFFFFF5E0.toInt()
        val px = IntArray(w * h) { cream }
        px[5 * w + 5] = 0xFF303030.toInt()
        val (paper, ink) = TextEditPlans.colours(px, w, h, PageArea(0f, 0f, 1f, 1f))
        assertEquals(0xFFFFF0E0.toInt() and 0xFFF8F8F8.toInt(), paper and 0xFFF8F8F8.toInt())
        assertEquals(0xFF303030.toInt(), ink)
    }

    @Test fun blueInkIsPushedToFullDepth() {
        val ink = TextEditPlans.trueInk(TextEditPlans.WHITE, 0xFF8080FF.toInt())
        assertTrue((ink shr 16 and 0xFF) < 0x40 && (ink and 0xFF) > 0xF0)
    }

    @Test fun scanLinesFromOcr() {
        val ocr = listOf(OcrLine("Invoice no 42", 100f, 200f, 400f, 30f, listOf(
            OcrLine("Invoice", 100f, 200f, 200f, 30f), OcrLine("no", 310f, 205f, 50f, 25f), OcrLine("42", 370f, 200f, 130f, 30f)
        )))
        val lines = TextEditPlans.scanLines(ocr, 1000, 1414, 842f)
        assertEquals(1, lines.size)
        assertEquals("Invoice no 42", lines[0].text)
        assertEquals(3, lines[0].words.size)
        assertTrue(lines[0].sizePoints in 15f..30f)
    }
}
