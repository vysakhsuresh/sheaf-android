package com.layerbit.sheaf.ui.tools

import androidx.annotation.DrawableRes
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ops.ToolId

/**
 * The drawing for each tool.
 *
 * Kept in the UI layer rather than on [ToolId] itself, so the ops package stays free of
 * Android resource ids and can be unit-tested without a resource table.
 *
 * The drawings are stroked outlines on a shared 24dp grid, tinted at draw time, which is what
 * makes nine icons by different metaphors still read as one set. The pairs are deliberately
 * mirror images of each other - merge against split, images-to-PDF against PDF-to-images,
 * locked against unlocked - because those are the pairs someone will confuse in a hurry, and
 * the difference between them should be the shape rather than the caption.
 */
@DrawableRes
fun iconFor(tool: ToolId): Int = when (tool) {
    ToolId.SCAN -> R.drawable.ic_tool_scan
    ToolId.OCR -> R.drawable.ic_tool_ocr
    ToolId.EXTRACT_TEXT -> R.drawable.ic_tool_extract_text
    ToolId.MERGE -> R.drawable.ic_tool_merge
    ToolId.SPLIT -> R.drawable.ic_tool_split
    ToolId.EXTRACT -> R.drawable.ic_tool_extract
    ToolId.ORGANISE -> R.drawable.ic_tool_organise
    ToolId.IMAGES_TO_PDF -> R.drawable.ic_tool_images_to_pdf
    ToolId.PDF_TO_IMAGES -> R.drawable.ic_tool_pdf_to_images
    ToolId.COMPRESS -> R.drawable.ic_tool_compress
    ToolId.SET_PASSWORD -> R.drawable.ic_tool_lock
    ToolId.REMOVE_PASSWORD -> R.drawable.ic_tool_unlock
}
