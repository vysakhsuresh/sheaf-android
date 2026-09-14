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
 * makes a dozen icons built on different metaphors still read as one set.
 *
 * Some pairs are deliberately mirror images: merge against split, images-to-PDF against
 * PDF-to-images, locked against unlocked. Those are the ones someone confuses in a hurry, and
 * the difference should be the shape rather than the caption.
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
    ToolId.WATERMARK -> R.drawable.ic_tool_watermark
    ToolId.PAGE_NUMBERS -> R.drawable.ic_tool_page_numbers
    ToolId.SIGN -> R.drawable.ic_tool_sign
    ToolId.ADD_TEXT -> R.drawable.ic_tool_add_text
    ToolId.CROP -> R.drawable.ic_tool_crop
    ToolId.N_UP -> R.drawable.ic_tool_n_up
    ToolId.SPLIT_BY_SIZE -> R.drawable.ic_tool_split_size
    ToolId.EXTRACT_IMAGES -> R.drawable.ic_tool_extract_images
    ToolId.REDACT -> R.drawable.ic_tool_redact
    ToolId.METADATA -> R.drawable.ic_tool_metadata
    ToolId.SET_PASSWORD -> R.drawable.ic_tool_lock
    ToolId.REMOVE_PASSWORD -> R.drawable.ic_tool_unlock
}
