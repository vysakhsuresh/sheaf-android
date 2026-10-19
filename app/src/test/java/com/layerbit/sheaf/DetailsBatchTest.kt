package com.layerbit.sheaf

import com.layerbit.sheaf.pdf.DocumentMetadata
import com.layerbit.sheaf.ui.tools.ToolConfig
import com.layerbit.sheaf.ui.tools.forSeveral
import org.junit.Assert.assertEquals
import org.junit.Test

class DetailsBatchTest {
    private val seed = DocumentMetadata("Lease", "Ravi", "", "")

    @Test fun untouchedBoxesEmptyForABatch() {
        val c = ToolConfig(metaTitle = "Lease", metaAuthor = "Ravi").forSeveral(seed, 2)
        assertEquals("" to "", c.metaTitle to c.metaAuthor)
    }

    @Test fun typedBoxesStay() {
        val c = ToolConfig(metaTitle = "Lease", metaAuthor = "Anjali").forSeveral(seed, 2)
        assertEquals("Anjali", c.metaAuthor)
    }

    @Test fun oneFileKeepsItsDetails() {
        assertEquals("Lease", ToolConfig(metaTitle = "Lease", metaAuthor = "Ravi").forSeveral(seed, 1).metaTitle)
    }
}
