package com.layerbit.sheaf

import com.layerbit.sheaf.ops.KeyPoints
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPointsTest {
    private val lease = """
        LEAVE AND LICENCE AGREEMENT

        This agreement is made on 1st April 2026 between Mr. Ravi Kumar, the licensor, and Ms. Anjali Menon, the licensee, for the flat at Kakkanad, Kochi.
        The monthly licence fee is Rs. 18,000 payable on or before the fifth day of every month by bank transfer.
        The licensee has paid a refundable security deposit of Rs. 1,00,000 which the licensor shall return within thirty days of the end of the licence.
        The licence is for a period of eleven months starting 1st April 2026 and may be renewed by mutual consent in writing.
        The licence fee shall increase by 5% on renewal of the agreement for a further period.
        The licensee shall not sublet the flat or any part of it to any other person.
        Either party may end the licence by giving one month's notice in writing to the other party.
        The licensee shall pay the electricity and water charges for the flat directly to the authorities.
        Pets are allowed in the flat.
        The licensor shall carry out major repairs to the flat at his own cost within a reasonable time.
    """.trimIndent()

    @Test fun picksSentencesFromTheDocument() {
        val s = KeyPoints.of(lease)
        assertTrue(s.points.size in 3..10)
        s.points.forEach { p -> assertTrue("verbatim: $p", lease.replace(Regex("\\s+"), " ").contains(p)) }
        assertTrue("fee is a key point", s.points.any { it.contains("18,000") })
    }

    @Test fun listsFigures() {
        val f = KeyPoints.of(lease).figures
        assertTrue(f.toString(), f.contains("Rs. 18,000") && f.contains("Rs. 1,00,000") && f.contains("5%") && f.any { it.startsWith("1st April 2026") })
    }

    @Test fun headingsAndFragmentsAreNotPoints() {
        assertTrue(KeyPoints.sentences(lease).none { it.startsWith("LEAVE AND") })
        assertTrue(KeyPoints.sentences("Pets are allowed.").isEmpty())
    }

    @Test fun hindiSentencesSplitOnDanda() {
        val hi = "यह समझौता एक अप्रैल को किया गया है और ग्यारह महीने तक चलेगा। मासिक किराया अठारह हज़ार रुपये है जो हर महीने की पाँच तारीख तक देना होगा।"
        assertTrue(KeyPoints.sentences(hi).size == 2)
    }
}
