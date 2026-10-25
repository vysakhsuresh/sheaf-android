package com.layerbit.sheaf.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.layerbit.sheaf.R

/**
 * Which drawn, moving icon replaces each static one.
 *
 * Keyed by the vector drawable so every place that shows a tool's icon - the home cards, the
 * tool's own bar, Open a file's suggestions, the picker - gets the moving one without knowing
 * it. A drawable with no entry here stays the static vector.
 */
fun glyphFor(@DrawableRes icon: Int): Glyph? = when (icon) {
    R.drawable.ic_tool_scan, R.drawable.ic_shortcut_scan -> Glyph.SCAN
    R.drawable.ic_tool_ocr -> Glyph.OCR
    R.drawable.ic_tool_extract_text -> Glyph.EXTRACT_TEXT
    R.drawable.ic_tool_merge, R.drawable.ic_shortcut_merge -> Glyph.MERGE
    R.drawable.ic_tool_split -> Glyph.SPLIT
    R.drawable.ic_tool_extract -> Glyph.EXTRACT
    R.drawable.ic_tool_organise -> Glyph.ORGANISE
    R.drawable.ic_tool_images_to_pdf -> Glyph.IMAGES_TO_PDF
    R.drawable.ic_tool_pdf_to_images -> Glyph.PDF_TO_IMAGES
    R.drawable.ic_tool_compress, R.drawable.ic_shortcut_compress -> Glyph.COMPRESS
    R.drawable.ic_tool_pdf_to_office -> Glyph.PDF_TO_OFFICE
    R.drawable.ic_tool_office_to_pdf -> Glyph.OFFICE_TO_PDF
    R.drawable.ic_tool_watermark -> Glyph.WATERMARK
    R.drawable.ic_tool_page_numbers -> Glyph.PAGE_NUMBERS
    R.drawable.ic_tool_sign -> Glyph.SIGN
    R.drawable.ic_tool_add_text -> Glyph.ADD_TEXT
    R.drawable.ic_tool_add_image -> Glyph.ADD_IMAGE
    R.drawable.ic_tool_annotate -> Glyph.ANNOTATE
    R.drawable.ic_tool_forms -> Glyph.FORMS
    R.drawable.ic_tool_header_footer -> Glyph.HEADER_FOOTER
    R.drawable.ic_tool_insert -> Glyph.INSERT
    R.drawable.ic_tool_crop -> Glyph.CROP
    R.drawable.ic_tool_background -> Glyph.BACKGROUND
    R.drawable.ic_tool_page_labels -> Glyph.PAGE_LABELS
    R.drawable.ic_tool_bookmarks -> Glyph.BOOKMARKS
    R.drawable.ic_tool_attachments -> Glyph.ATTACHMENTS
    R.drawable.ic_tool_n_up -> Glyph.N_UP
    R.drawable.ic_tool_split_size -> Glyph.SPLIT_SIZE
    R.drawable.ic_tool_extract_images -> Glyph.EXTRACT_IMAGES
    R.drawable.ic_tool_redact -> Glyph.REDACT
    R.drawable.ic_tool_metadata -> Glyph.METADATA
    R.drawable.ic_tool_edit_text -> Glyph.EDIT_TEXT
    R.drawable.ic_tool_create_form -> Glyph.CREATE_FORM
    R.drawable.ic_tool_digital_sign -> Glyph.DIGITAL_SIGN
    R.drawable.ic_tool_archive -> Glyph.ARCHIVE
    R.drawable.ic_tool_compare -> Glyph.COMPARE
    R.drawable.ic_tool_receipt -> Glyph.RECEIPT
    R.drawable.ic_tool_lock -> Glyph.LOCK
    R.drawable.ic_tool_unlock -> Glyph.UNLOCK
    R.drawable.ic_tool_resize_photo -> Glyph.RESIZE_PHOTO
    R.drawable.ic_tool_edit_images -> Glyph.EDIT_IMAGES
    R.drawable.ic_tool_summarise -> Glyph.SUMMARISE
    R.drawable.ic_tool_recipe -> Glyph.RECIPE
    R.drawable.ic_tool_read -> Glyph.READ
    R.drawable.ic_plus -> Glyph.PLUS
    else -> null
}

/** The Material glyphs that have a drawn, moving counterpart. Arrows and crosses stay still. */
fun glyphFor(icon: ImageVector): Glyph? = when (icon.name.substringAfterLast('.')) {
    "Settings" -> Glyph.GEAR
    "Star" -> Glyph.STAR
    "Favorite" -> Glyph.HEART
    "Search" -> Glyph.SEARCH
    "Info" -> Glyph.INFO
    "Share" -> Glyph.SHARE
    "Send" -> Glyph.SEND
    "Email" -> Glyph.MAIL
    "Lock" -> Glyph.LOCK
    "Check" -> Glyph.CHECK
    "Add" -> Glyph.PLUS
    else -> null
}

/** A steady seed per icon, so each moves at its own moment in the lap. */
fun glyphSeed(key: Any): Int = (key.hashCode() and 0x7fffffff) % 89
