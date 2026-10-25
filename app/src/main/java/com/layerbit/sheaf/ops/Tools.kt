package com.layerbit.sheaf.ops

import androidx.annotation.StringRes
import com.layerbit.sheaf.R
import com.layerbit.sheaf.text.Strings

/**
 * Every tool the app offers, and what the home screen needs to draw it.
 *
 * Adding a tool is one entry here and one [Op] implementation. If it ever costs more than
 * that, the seam is wrong and the fix is to widen [Op] or [com.layerbit.sheaf.pdf.PdfSurgeon]
 * rather than to special-case the new tool in the UI.
 *
 * [group] exists because a flat list of a dozen tools stops being a menu and becomes a wall.
 * The groups are by what someone is trying to do, not by which library implements them.
 */
enum class ToolId(
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val input: InputKind,
    val group: Group
) {
    SCAN(
        R.string.catalog_tool_scan_title,
        R.string.catalog_tool_scan_summary,
        InputKind.CAMERA,
        Group.READ
    ),
    OCR(
        R.string.catalog_tool_ocr_title,
        R.string.catalog_tool_ocr_summary,
        InputKind.SEVERAL_PDFS,
        Group.READ
    ),
    EXTRACT_TEXT(
        R.string.catalog_tool_extract_text_title,
        R.string.catalog_tool_extract_text_summary,
        InputKind.ONE_PDF,
        Group.READ
    ),

    MERGE(
        R.string.catalog_tool_merge_title,
        R.string.catalog_tool_merge_summary,
        InputKind.SEVERAL_PDFS,
        Group.PAGES
    ),
    SPLIT(
        R.string.catalog_tool_split_title,
        R.string.catalog_tool_split_summary,
        InputKind.ONE_PDF,
        Group.PAGES
    ),
    EXTRACT(
        R.string.catalog_tool_extract_title,
        R.string.catalog_tool_extract_summary,
        InputKind.ONE_PDF,
        Group.PAGES
    ),
    INSERT(
        R.string.catalog_tool_insert_title,
        R.string.catalog_tool_insert_summary,
        InputKind.SEVERAL_PDFS,
        Group.PAGES
    ),
    ORGANISE(
        R.string.catalog_tool_organise_title,
        R.string.catalog_tool_organise_summary,
        InputKind.ONE_PDF,
        Group.PAGES
    ),

    IMAGES_TO_PDF(
        R.string.catalog_tool_images_to_pdf_title,
        R.string.catalog_tool_images_to_pdf_summary,
        InputKind.IMAGES,
        Group.CONVERT
    ),
    PDF_TO_IMAGES(
        R.string.catalog_tool_pdf_to_images_title,
        R.string.catalog_tool_pdf_to_images_summary,
        InputKind.ONE_PDF,
        Group.CONVERT
    ),
    PDF_TO_OFFICE(
        R.string.catalog_tool_pdf_to_office_title,
        R.string.catalog_tool_pdf_to_office_summary,
        InputKind.SEVERAL_PDFS,
        Group.CONVERT
    ),
    OFFICE_TO_PDF(
        R.string.catalog_tool_office_to_pdf_title,
        R.string.catalog_tool_office_to_pdf_summary,
        InputKind.OFFICE,
        Group.CONVERT
    ),
    COMPRESS(
        R.string.catalog_tool_compress_title,
        R.string.catalog_tool_compress_summary,
        InputKind.SEVERAL_PDFS,
        Group.CONVERT
    ),

    WATERMARK(
        R.string.catalog_tool_watermark_title,
        R.string.catalog_tool_watermark_summary,
        InputKind.SEVERAL_PDFS,
        Group.MARK
    ),
    PAGE_NUMBERS(
        R.string.catalog_tool_page_numbers_title,
        R.string.catalog_tool_page_numbers_summary,
        InputKind.SEVERAL_PDFS,
        Group.MARK
    ),
    SIGN(
        R.string.catalog_tool_sign_title,
        R.string.catalog_tool_sign_summary,
        InputKind.ONE_PDF,
        Group.MARK
    ),
    ANNOTATE(
        R.string.catalog_tool_annotate_title,
        R.string.catalog_tool_annotate_summary,
        InputKind.ONE_PDF,
        Group.MARK
    ),
    FORMS(
        R.string.catalog_tool_forms_title,
        R.string.catalog_tool_forms_summary,
        InputKind.ONE_PDF,
        Group.MARK
    ),
    HEADER_FOOTER(
        R.string.catalog_tool_header_footer_title,
        R.string.catalog_tool_header_footer_summary,
        InputKind.SEVERAL_PDFS,
        Group.MARK
    ),
    ADD_TEXT(
        R.string.catalog_tool_add_text_title,
        R.string.catalog_tool_add_text_summary,
        InputKind.ONE_PDF,
        Group.MARK
    ),
    ADD_IMAGE(
        R.string.catalog_tool_add_image_title,
        R.string.catalog_tool_add_image_summary,
        InputKind.PDF_AND_IMAGE,
        Group.MARK
    ),
    CROP(
        R.string.catalog_tool_crop_title,
        R.string.catalog_tool_crop_summary,
        InputKind.SEVERAL_PDFS,
        Group.MARK
    ),
    BACKGROUND(
        R.string.catalog_tool_background_title,
        R.string.catalog_tool_background_summary,
        InputKind.SEVERAL_PDFS,
        Group.MARK
    ),

    N_UP(
        R.string.catalog_tool_n_up_title,
        R.string.catalog_tool_n_up_summary,
        InputKind.SEVERAL_PDFS,
        Group.CONVERT
    ),
    SPLIT_BY_SIZE(
        R.string.catalog_tool_split_by_size_title,
        R.string.catalog_tool_split_by_size_summary,
        InputKind.ONE_PDF,
        Group.PAGES
    ),
    EXTRACT_IMAGES(
        R.string.catalog_tool_extract_images_title,
        R.string.catalog_tool_extract_images_summary,
        InputKind.ONE_PDF,
        Group.CONVERT
    ),

    PAGE_LABELS(
        R.string.catalog_tool_page_labels_title,
        R.string.catalog_tool_page_labels_summary,
        InputKind.SEVERAL_PDFS,
        Group.PAGES
    ),
    BOOKMARKS(
        R.string.catalog_tool_bookmarks_title,
        R.string.catalog_tool_bookmarks_summary,
        InputKind.ONE_PDF,
        Group.PAGES
    ),
    ATTACHMENTS(
        R.string.catalog_tool_attachments_title,
        R.string.catalog_tool_attachments_summary,
        InputKind.PDF_AND_FILES,
        Group.CONVERT
    ),

    REDACT(
        R.string.catalog_tool_redact_title,
        R.string.catalog_tool_redact_summary,
        InputKind.ONE_PDF,
        Group.PROTECT
    ),
    METADATA(
        R.string.catalog_tool_metadata_title,
        R.string.catalog_tool_metadata_summary,
        InputKind.SEVERAL_PDFS,
        Group.PROTECT
    ),

    SET_PASSWORD(
        R.string.catalog_tool_set_password_title,
        R.string.catalog_tool_set_password_summary,
        InputKind.SEVERAL_PDFS,
        Group.PROTECT
    ),
    REMOVE_PASSWORD(
        R.string.catalog_tool_remove_password_title,
        R.string.catalog_tool_remove_password_summary,
        InputKind.SEVERAL_PDFS,
        Group.PROTECT
    ),

    CREATE_FORM(
        R.string.catalog_tool_create_form_title,
        R.string.catalog_tool_create_form_summary,
        InputKind.ONE_PDF,
        Group.MARK
    ),
    DIGITAL_SIGN(
        R.string.catalog_tool_digital_sign_title,
        R.string.catalog_tool_digital_sign_summary,
        InputKind.PDF_AND_CERT,
        Group.PROTECT
    ),
    PDF_A(
        R.string.catalog_tool_pdf_a_title,
        R.string.catalog_tool_pdf_a_summary,
        InputKind.SEVERAL_PDFS,
        Group.PROTECT
    ),
    EDIT_TEXT(
        R.string.catalog_tool_edit_text_title,
        R.string.catalog_tool_edit_text_summary,
        InputKind.ONE_PDF,
        Group.MARK
    ),
    COMPARE(
        R.string.catalog_tool_compare_title,
        R.string.catalog_tool_compare_summary,
        InputKind.SEVERAL_PDFS,
        Group.READ
    ),
    RESIZE_PHOTO(
        R.string.catalog_tool_resize_photo_title,
        R.string.catalog_tool_resize_photo_summary,
        InputKind.IMAGES,
        Group.CONVERT
    ),
    EDIT_IMAGES(
        R.string.catalog_tool_edit_images_title,
        R.string.catalog_tool_edit_images_summary,
        InputKind.ONE_PDF,
        Group.MARK
    ),
    SUMMARISE(
        R.string.catalog_tool_summarise_title,
        R.string.catalog_tool_summarise_summary,
        InputKind.ONE_PDF,
        Group.READ
    ),
    RECIPES(
        R.string.catalog_tool_recipes_title,
        R.string.catalog_tool_recipes_summary,
        InputKind.SEVERAL_PDFS,
        Group.CONVERT
    ),
    RENT_RECEIPTS(
        R.string.catalog_tool_rent_receipts_title,
        R.string.catalog_tool_rent_receipts_summary,
        InputKind.NONE,
        Group.MAKE
    );

    /** Read when asked, so a change of language shows on the next screen drawn. */
    val title: String get() = Strings.get(titleRes)

    val summary: String get() = Strings.get(summaryRes)

    enum class InputKind {
        ONE_PDF,
        SEVERAL_PDFS,
        IMAGES,

        /** One PDF first, then any files at all. */
        PDF_AND_FILES,

        /** One PDF and one image. */
        PDF_AND_IMAGE,

        /** Word files, spreadsheets and text files, any number. */
        OFFICE,

        /** One PDF and one certificate file. */
        PDF_AND_CERT,

        /** Takes no file at all - it makes one with the camera. */
        CAMERA,

        /** Takes no file at all - it makes one from what is typed. */
        NONE
    }

    /** How the home screen groups the tools. Order here is the order on screen. */
    enum class Group(@StringRes val titleRes: Int) {
        READ(R.string.catalog_group_read),
        PAGES(R.string.catalog_group_pages),
        MARK(R.string.catalog_group_mark),
        CONVERT(R.string.catalog_group_convert),
        PROTECT(R.string.catalog_group_protect),
        MAKE(R.string.catalog_group_make);

        val title: String get() = Strings.get(titleRes)
    }

    /**
     * What the button says.
     *
     * A button repeating the screen's own title tells nobody what pressing it does - "Document
     * details" on the Document details screen was the clearest case. Every one of these is a
     * verb.
     */
    val action: String
        get() = Strings.get(actionRes)

    @get:StringRes
    val actionRes: Int
        get() = when (this) {
            SCAN -> R.string.catalog_tool_scan_action
            OCR -> R.string.catalog_tool_ocr_action
            EXTRACT_TEXT -> R.string.catalog_tool_extract_text_action
            MERGE -> R.string.catalog_tool_merge_action
            SPLIT -> R.string.catalog_tool_split_action
            EXTRACT -> R.string.catalog_tool_extract_action
            ORGANISE -> R.string.catalog_tool_organise_action
            IMAGES_TO_PDF -> R.string.catalog_tool_images_to_pdf_action
            PDF_TO_IMAGES -> R.string.catalog_tool_pdf_to_images_action
            COMPRESS -> R.string.catalog_tool_compress_action
            PDF_TO_OFFICE -> R.string.catalog_tool_pdf_to_office_action
            OFFICE_TO_PDF -> R.string.catalog_tool_office_to_pdf_action
            WATERMARK -> R.string.catalog_tool_watermark_action
            PAGE_NUMBERS -> R.string.catalog_tool_page_numbers_action
            SIGN -> R.string.catalog_tool_sign_action
            ADD_TEXT -> R.string.catalog_tool_add_text_action
            ADD_IMAGE -> R.string.catalog_tool_add_image_action
            ANNOTATE -> R.string.catalog_tool_annotate_action
            FORMS -> R.string.catalog_tool_forms_action
            HEADER_FOOTER -> R.string.catalog_tool_header_footer_action
            INSERT -> R.string.catalog_tool_insert_action
            CROP -> R.string.catalog_tool_crop_action
            BACKGROUND -> R.string.catalog_tool_background_action
            PAGE_LABELS -> R.string.catalog_tool_page_labels_action
            BOOKMARKS -> R.string.catalog_tool_bookmarks_action
            ATTACHMENTS -> R.string.catalog_tool_attachments_action
            N_UP -> R.string.catalog_tool_n_up_action
            SPLIT_BY_SIZE -> R.string.catalog_tool_split_by_size_action
            EXTRACT_IMAGES -> R.string.catalog_tool_extract_images_action
            REDACT -> R.string.catalog_tool_redact_action
            METADATA -> R.string.catalog_tool_metadata_action
            SET_PASSWORD -> R.string.catalog_tool_set_password_action
            REMOVE_PASSWORD -> R.string.catalog_tool_remove_password_action
            CREATE_FORM -> R.string.catalog_tool_create_form_action
            DIGITAL_SIGN -> R.string.catalog_tool_digital_sign_action
            PDF_A -> R.string.catalog_tool_pdf_a_action
            EDIT_TEXT -> R.string.catalog_action_save_changes
            RESIZE_PHOTO -> R.string.catalog_tool_resize_photo_action
            EDIT_IMAGES -> R.string.catalog_action_save_changes
            SUMMARISE -> R.string.catalog_tool_summarise_action
            RECIPES -> R.string.catalog_tool_recipes_action
            COMPARE -> R.string.catalog_tool_compare_action
            RENT_RECEIPTS -> R.string.catalog_tool_rent_receipts_action
        }

    val acceptsMultiple: Boolean
        get() = input == InputKind.SEVERAL_PDFS || input == InputKind.IMAGES ||
            input == InputKind.PDF_AND_FILES || input == InputKind.PDF_AND_IMAGE || input == InputKind.OFFICE ||
            input == InputKind.PDF_AND_CERT

    val mimeFilter: Array<String>
        get() = when (input) {
            InputKind.IMAGES -> arrayOf("image/*")
            InputKind.PDF_AND_FILES -> arrayOf("*/*")
            InputKind.PDF_AND_IMAGE -> arrayOf("application/pdf", "image/*")
            InputKind.PDF_AND_CERT -> arrayOf("*/*")
            InputKind.OFFICE -> arrayOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/plain",
                "text/markdown",
                "text/csv",
                "text/comma-separated-values",
                "application/octet-stream"
            )
            else -> arrayOf("application/pdf")
        }
}
