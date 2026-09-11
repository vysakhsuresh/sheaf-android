package com.layerbit.sheaf

import com.layerbit.sheaf.files.sanitisedForFilesystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A display name comes from a content provider, which means it is arbitrary text chosen by
 * another app. Writing it into a path unchecked is a directory traversal, so this is a
 * security test rather than a tidiness one.
 */
class FilenameSanitiseTest {

    @Test
    fun `a traversal attempt cannot escape the directory`() {
        val cleaned = "../../databases/sheaf.db".sanitisedForFilesystem()
        assertFalse(cleaned.contains("/"))
        assertFalse(cleaned.startsWith("."))
    }

    @Test
    fun `an ordinary name survives intact`() {
        assertEquals("Bank statement 2026.pdf", "Bank statement 2026.pdf".sanitisedForFilesystem())
    }

    @Test
    fun `path separators are replaced`() {
        val withSeparators = listOf("a/b.pdf", "a" + '\\' + "b.pdf")
        for (name in withSeparators) {
            val cleaned = name.sanitisedForFilesystem()
            assertFalse("$name survived as $cleaned", cleaned.contains("/"))
            assertFalse("$name survived as $cleaned", cleaned.contains('\\'))
        }
    }

    @Test
    fun `an empty or fully stripped name falls back to something usable`() {
        assertEquals("document.pdf", "".sanitisedForFilesystem())
        assertEquals("document.pdf", "...".sanitisedForFilesystem())
    }

    @Test
    fun `an absurdly long name is truncated below the filesystem limit`() {
        val cleaned = ("x".repeat(5000) + ".pdf").sanitisedForFilesystem()
        assertTrue("was ${cleaned.length} characters", cleaned.length <= 120)
    }
}
