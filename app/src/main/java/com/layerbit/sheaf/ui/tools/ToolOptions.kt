package com.layerbit.sheaf.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.pdf.CompressionLevel
import com.layerbit.sheaf.pdf.ImageFormat
import com.layerbit.sheaf.pdf.ImageStamp
import com.layerbit.sheaf.ops.OfficeTarget
import com.layerbit.sheaf.pdf.NewField
import com.layerbit.sheaf.pdf.OcrScript
import com.layerbit.sheaf.pdf.PageLabelSpec
import com.layerbit.sheaf.pdf.PageNumberSpec
import com.layerbit.sheaf.pdf.WatermarkSpec
import com.layerbit.sheaf.pdf.PageSpec
import com.layerbit.sheaf.pdf.StampSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Info
import com.layerbit.sheaf.ui.components.ChipRow
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.RoundIconButton
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.theme.GroupAccents
import com.layerbit.sheaf.ui.components.ToggleRow
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * The settings each tool takes.
 *
 * One composable per tool, all of them writing into the same [ToolConfig].
 *
 * That is a little loose, since a config field only one tool reads is still on the type. It
 * keeps the whole options layer in one file, though, and the alternative is a dozen parallel
 * state classes for what amounts to nine text fields and five choosers.
 */
@Composable
fun ToolOptions(
    tool: ToolId,
    config: ToolConfig,
    pageCount: Int?,
    onChange: ((ToolConfig) -> ToolConfig) -> Unit,
    modifier: Modifier = Modifier,
    sizeCheck: SizeCheck = SizeCheck.Idle,
    onCheckSize: () -> Unit = {},
    /** How many files the options apply to; some say something different for a batch. */
    fileCount: Int = 1
) {
    // These have their own canvases on the tool screen rather than plain settings.
    if (tool == ToolId.SCAN || tool == ToolId.ORGANISE) return
    val hintOnly = tool in HINT_ONLY
    Panel(modifier = modifier.fillMaxWidth()) {
        SectionHeading(
            if (hintOnly) stringResource(R.string.options_about_this_tool) else stringResource(R.string.options_heading),
            accent = GroupAccents.of(tool.group),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (tool) {
            ToolId.EXTRACT -> {
                ChoiceRow(
                    options = listOf(false to stringResource(R.string.options_extract_keep), true to stringResource(R.string.options_extract_remove)),
                    selected = config.removePages,
                    onSelect = { value -> onChange { it.copy(removePages = value) } }
                )
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = if (config.removePages) stringResource(R.string.options_extract_pages_remove) else stringResource(R.string.options_extract_pages_keep),
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
            }

            ToolId.SPLIT -> {
                SectionHeading(stringResource(R.string.options_split_how))
                ChoiceRow(
                    options = listOf(
                        ToolConfig.SplitMode.EVERY_N to stringResource(R.string.options_split_every_n),
                        ToolConfig.SplitMode.EACH_PAGE to stringResource(R.string.options_split_single),
                        ToolConfig.SplitMode.RANGES to stringResource(R.string.options_split_ranges),
                        ToolConfig.SplitMode.AT_BOOKMARKS to stringResource(R.string.options_split_bookmarks)
                    ),
                    selected = config.splitMode,
                    onSelect = { mode -> onChange { it.copy(splitMode = mode) } }
                )
                when (config.splitMode) {
                    ToolConfig.SplitMode.EVERY_N -> NumberField(
                        value = config.splitSize,
                        label = stringResource(R.string.options_split_pages_per_file),
                        min = 1,
                        max = pageCount ?: 9999,
                        onChange = { size -> onChange { it.copy(splitSize = size) } }
                    )
                    ToolConfig.SplitMode.RANGES -> PageSpecField(
                        value = config.pageSpec,
                        pageCount = pageCount,
                        label = stringResource(R.string.options_split_one_per_range),
                        hint = stringResource(R.string.options_split_ranges_hint),
                        onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                    )
                    ToolConfig.SplitMode.EACH_PAGE -> Unit
                    ToolConfig.SplitMode.AT_BOOKMARKS -> Hint(
                        stringResource(R.string.options_split_bookmarks_hint)
                    )
                }
            }

            ToolId.PDF_TO_IMAGES -> {
                SectionHeading(stringResource(R.string.options_format))
                ChoiceRow(
                    options = listOf(
                        ImageFormat.PNG to "PNG",
                        ImageFormat.JPEG to "JPEG"
                    ),
                    selected = config.imageFormat,
                    onSelect = { format -> onChange { it.copy(imageFormat = format) } }
                )
                SectionHeading(stringResource(R.string.options_resolution))
                ChoiceRow(
                    options = listOf(
                        96 to stringResource(R.string.options_dpi_screen),
                        200 to stringResource(R.string.options_dpi_good),
                        300 to stringResource(R.string.options_dpi_print)
                    ),
                    selected = config.dpi,
                    onSelect = { dpi -> onChange { it.copy(dpi = dpi) } }
                )
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = stringResource(R.string.options_pages),
                    hint = stringResource(R.string.options_empty_every_page),
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
            }

            ToolId.IMAGES_TO_PDF -> {
                SectionHeading(stringResource(R.string.options_page_size))
                ChoiceRow(
                    options = listOf(
                        PageSpec.Size.A4 to "A4",
                        PageSpec.Size.LETTER to stringResource(R.string.options_size_letter),
                        PageSpec.Size.A5 to "A5",
                        PageSpec.Size.FIT_IMAGE to stringResource(R.string.options_size_fit)
                    ),
                    selected = config.pageSize,
                    onSelect = { size -> onChange { it.copy(pageSize = size) } }
                )
                SectionHeading(stringResource(R.string.options_output))
                ChoiceRow(
                    options = listOf(false to stringResource(R.string.options_one_pdf), true to stringResource(R.string.options_pdf_per_image)),
                    selected = config.separatePdfs,
                    onSelect = { value -> onChange { it.copy(separatePdfs = value) } }
                )
                if (config.pageSize != PageSpec.Size.FIT_IMAGE) {
                    SectionHeading(stringResource(R.string.options_margin))
                    ChoiceRow(
                        options = listOf(
                            0f to stringResource(R.string.options_none),
                            18f to stringResource(R.string.options_narrow),
                            36f to stringResource(R.string.options_normal),
                            72f to stringResource(R.string.options_wide)
                        ),
                        selected = config.marginPoints,
                        onSelect = { margin -> onChange { it.copy(marginPoints = margin) } }
                    )
                }
            }

            ToolId.PDF_TO_OFFICE -> {
                SectionHeading(stringResource(R.string.options_make_a))
                ChoiceRow(
                    options = OfficeTarget.entries.map { it to it.label },
                    selected = config.officeTarget,
                    onSelect = { value -> onChange { it.copy(officeTarget = value) } }
                )
                if (config.officeTarget == OfficeTarget.POWERPOINT) {
                    ChoiceRow(
                        options = listOf(true to stringResource(R.string.options_office_pictures), false to stringResource(R.string.options_office_editable)),
                        selected = config.convertPictures,
                        onSelect = { value -> onChange { it.copy(convertPictures = value) } }
                    )
                } else if (config.officeTarget != OfficeTarget.EXCEL) {
                    ToggleRow(
                        title = stringResource(R.string.options_office_bring_pictures),
                        checked = config.convertPictures,
                        onChange = { on -> onChange { it.copy(convertPictures = on) } }
                    )
                }
                Hint(
                    when (config.officeTarget) {
                        OfficeTarget.WORD -> stringResource(R.string.options_office_word_hint)
                        OfficeTarget.EXCEL -> stringResource(R.string.options_office_excel_hint)
                        OfficeTarget.EPUB -> stringResource(R.string.options_office_epub_hint)
                        OfficeTarget.HTML -> stringResource(R.string.options_office_html_hint)
                        OfficeTarget.POWERPOINT -> stringResource(R.string.options_office_pptx_hint)
                    } + " " + stringResource(R.string.options_office_scanned_hint)
                )
            }

            ToolId.OFFICE_TO_PDF -> Hint(
                stringResource(R.string.options_office_to_pdf_hint)
            )

            ToolId.COMPRESS -> {
                ChoiceRow(
                    options = listOf(false to stringResource(R.string.options_compress_level), true to stringResource(R.string.options_compress_size)),
                    selected = config.compressToSize,
                    onSelect = { value -> onChange { it.copy(compressToSize = value) } }
                )
                if (config.compressToSize) {
                    SectionHeading(stringResource(R.string.options_under))
                    ChoiceRow(
                        options = listOf(1 to "1 MB", 2 to "2 MB", 5 to "5 MB", 10 to "10 MB", 25 to "25 MB"),
                        selected = config.compressTargetMb,
                        onSelect = { value -> onChange { it.copy(compressTargetMb = value) } }
                    )
                    NumberField(
                        value = config.compressTargetMb,
                        label = stringResource(R.string.options_any_size_mb),
                        min = 1,
                        max = 2000,
                        onChange = { value -> onChange { it.copy(compressTargetMb = value) } }
                    )
                    Hint(
                        stringResource(R.string.options_compress_size_hint)
                    )
                    ToggleRow(
                        title = stringResource(R.string.options_compress_pictures),
                        checked = config.compressPictures,
                        onChange = { on -> onChange { it.copy(compressPictures = on) } }
                    )
                    Hint(
                        stringResource(R.string.options_compress_pictures_hint)
                    )
                } else {
                    SectionHeading(stringResource(R.string.options_compress_how_hard))
                    ChoiceRow(
                        options = CompressionLevel.entries.map { it to it.label },
                        selected = config.compression,
                        onSelect = { level -> onChange { it.copy(compression = level) } }
                    )
                }
                Hint(
                    stringResource(R.string.options_compress_hint)
                )

                // The real operation on the real file, thrown away afterwards. An estimate
                // from image sizes would be wrong in exactly the cases that matter, and the
                // question being asked is whether this will fit under an attachment limit.
                when (sizeCheck) {
                    SizeCheck.Idle -> SecondaryButton(text = stringResource(R.string.options_check_size), onClick = onCheckSize, tall = false)
                    SizeCheck.Working -> Text(
                        stringResource(R.string.options_check_trying),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SheafColors.Muted
                    )
                    SizeCheck.Failed -> Text(
                        stringResource(R.string.options_check_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = SheafColors.Skipped
                    )
                    is SizeCheck.Done -> Text(
                        text = if (sizeCheck.compressedBytes >= sizeCheck.originalBytes) {
                            stringResource(R.string.options_check_same, formatBytes(sizeCheck.originalBytes))
                        } else {
                            stringResource(
                                R.string.options_check_result,
                                formatBytes(sizeCheck.originalBytes),
                                formatBytes(sizeCheck.compressedBytes),
                                (sizeCheck.savedFraction * 100).toInt()
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sizeCheck.compressedBytes >= sizeCheck.originalBytes) {
                            SheafColors.Skipped
                        } else {
                            SheafColors.Done
                        }
                    )
                }
            }

            ToolId.SET_PASSWORD -> {
                ChoiceRow(
                    options = listOf(false to stringResource(R.string.options_pw_open), true to stringResource(R.string.options_pw_restrict)),
                    selected = config.restrictOnly,
                    onSelect = { value -> onChange { it.copy(restrictOnly = value) } }
                )
                if (!config.restrictOnly) {
                    PasswordField(
                        value = config.newPassword,
                        label = stringResource(R.string.options_password),
                        onChange = { value -> onChange { it.copy(newPassword = value) } }
                    )
                    PasswordField(
                        value = config.confirmPassword,
                        label = stringResource(R.string.options_pw_again),
                        onChange = { value -> onChange { it.copy(confirmPassword = value) } }
                    )
                    Hint(
                        stringResource(R.string.options_pw_hint)
                    )
                } else {
                    Hint(
                        stringResource(R.string.options_pw_restrict_hint)
                    )
                }
                SectionHeading(stringResource(R.string.options_pw_reader_may))
                ToggleRow(
                    title = stringResource(R.string.options_allow_printing),
                    checked = config.allowPrinting,
                    onChange = { on -> onChange { it.copy(allowPrinting = on) } }
                )
                ToggleRow(
                    title = stringResource(R.string.options_allow_copying),
                    checked = config.allowCopying,
                    onChange = { on -> onChange { it.copy(allowCopying = on) } }
                )
                ToggleRow(
                    title = stringResource(R.string.options_allow_changes),
                    checked = config.allowChanges,
                    onChange = { on -> onChange { it.copy(allowChanges = on) } }
                )
                Hint(
                    stringResource(
                        if (config.restrictOnly) R.string.options_pw_request_restrict else R.string.options_pw_request_lock
                    )
                )
            }

            ToolId.MERGE -> Hint(stringResource(R.string.options_merge_hint))

            ToolId.REMOVE_PASSWORD -> Hint(
                stringResource(R.string.options_remove_pw_hint)
            )

            ToolId.EXTRACT_TEXT -> PageSpecField(
                value = config.pageSpec,
                pageCount = pageCount,
                label = stringResource(R.string.options_pages),
                hint = stringResource(R.string.options_empty_whole_doc),
                onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
            )

            ToolId.OCR -> {
                SectionHeading(stringResource(R.string.options_ocr_language))
                ChoiceRow(
                    options = OcrScript.entries.map { it to it.label },
                    selected = config.ocrScript,
                    onSelect = { value -> onChange { it.copy(ocrScript = value) } }
                )
                Hint(
                    stringResource(R.string.options_ocr_hint)
                )
            }

            ToolId.RESIZE_PHOTO -> {
                SectionHeading(stringResource(R.string.options_under))
                ChoiceRow(
                    options = listOf(20 to "20 KB", 50 to "50 KB", 100 to "100 KB", 200 to "200 KB", 500 to "500 KB"),
                    selected = config.photoKb,
                    onSelect = { value -> onChange { it.copy(photoKb = value) } }
                )
                NumberField(
                    value = config.photoKb,
                    label = stringResource(R.string.options_any_size_kb),
                    min = 2,
                    max = 100000,
                    onChange = { value -> onChange { it.copy(photoKb = value) } }
                )
                Hint(stringResource(R.string.options_photo_min_hint))
                SectionHeading(stringResource(R.string.options_pixels))
                ChoiceRow(
                    options = ToolConfig.PhotoMode.entries.map { it to it.label },
                    selected = config.photoMode,
                    onSelect = { value -> onChange { it.copy(photoMode = value) } }
                )
                when (config.photoMode) {
                    ToolConfig.PhotoMode.KEEP -> Hint(stringResource(R.string.options_photo_keep_hint))
                    ToolConfig.PhotoMode.PASSPORT -> Hint(stringResource(R.string.options_photo_passport_hint))
                    ToolConfig.PhotoMode.EXACT -> {
                        NumberField(
                            value = config.photoWidth,
                            label = stringResource(R.string.options_width_px),
                            min = 16,
                            max = 4096,
                            onChange = { value -> onChange { it.copy(photoWidth = value) } }
                        )
                        NumberField(
                            value = config.photoHeight,
                            label = stringResource(R.string.options_height_px),
                            min = 16,
                            max = 4096,
                            onChange = { value -> onChange { it.copy(photoHeight = value) } }
                        )
                        Hint(stringResource(R.string.options_photo_exact_hint))
                    }
                }
                Hint(
                    stringResource(R.string.options_photo_hint)
                )
            }

            ToolId.EDIT_TEXT -> Hint(
                stringResource(R.string.options_edit_text_hint)
            )

            ToolId.CREATE_FORM -> {
                SectionHeading(stringResource(R.string.options_form_next_box))
                ChoiceRow(
                    options = NewField.Kind.entries.map { it to it.label },
                    selected = config.fieldKind,
                    onSelect = { value -> onChange { it.copy(fieldKind = value) } }
                )
                Hint(
                    stringResource(R.string.options_create_form_hint)
                )
            }

            ToolId.DIGITAL_SIGN -> {
                PasswordField(
                    value = config.certPassword,
                    label = stringResource(R.string.options_cert_password),
                    onChange = { value -> onChange { it.copy(certPassword = value) } }
                )
                Field(
                    value = config.signReason,
                    label = stringResource(R.string.options_sign_reason),
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(signReason = value) } }
                )
                Field(
                    value = config.signLocation,
                    label = stringResource(R.string.options_sign_place),
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(signLocation = value) } }
                )
                SectionHeading(stringResource(R.string.options_on_the_page))
                ChoiceRow(
                    options = listOf(true to stringResource(R.string.options_sign_show_box), false to stringResource(R.string.options_sign_invisible)),
                    selected = config.signVisible,
                    onSelect = { value -> onChange { it.copy(signVisible = value) } }
                )
                if (config.signVisible) {
                    ChoiceRow(
                        options = listOf(false to stringResource(R.string.options_sign_last), true to stringResource(R.string.options_sign_first)),
                        selected = config.signOnFirstPage,
                        onSelect = { value -> onChange { it.copy(signOnFirstPage = value) } }
                    )
                }
                Hint(
                    stringResource(R.string.options_digital_sign_hint)
                )
            }

            ToolId.PDF_A -> Hint(
                stringResource(R.string.options_pdfa_hint)
            )

            ToolId.COMPARE -> Hint(
                stringResource(R.string.options_compare_hint)
            )

            ToolId.RENT_RECEIPTS -> {
                Field(
                    value = config.tenant,
                    label = stringResource(R.string.options_rent_tenant),
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(tenant = value) } }
                )
                Field(
                    value = config.landlord,
                    label = stringResource(R.string.options_rent_owner),
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(landlord = value) } }
                )
                Field(
                    value = config.rentAddress,
                    label = stringResource(R.string.options_rent_address),
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(rentAddress = value) } }
                )
                Field(
                    value = if (config.rent > 0) config.rent.toString() else "",
                    label = stringResource(R.string.options_rent_monthly),
                    keyboardType = KeyboardType.Number,
                    onChange = { value -> onChange { it.copy(rent = value.filter { c -> c.isDigit() }.take(9).toLongOrNull() ?: 0L) } }
                )
                SectionHeading(stringResource(R.string.options_rent_first_month))
                ChoiceRow(
                    // The chips follow the screen's language; the receipts themselves keep the op's own month names.
                    options = java.text.DateFormatSymbols
                        .getInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
                        .shortMonths
                        .take(12)
                        .mapIndexed { i, name -> i + 1 to name },
                    selected = config.rentMonth,
                    onSelect = { value -> onChange { it.copy(rentMonth = value) } }
                )
                NumberField(
                    value = config.rentYear,
                    label = stringResource(R.string.options_year),
                    min = 2000,
                    max = 2100,
                    onChange = { value -> onChange { it.copy(rentYear = value) } }
                )
                SectionHeading(stringResource(R.string.options_rent_how_many))
                ChoiceRow(
                    options = listOf(1 to "1", 3 to "3", 6 to "6", 12 to "12"),
                    selected = config.rentMonths,
                    onSelect = { value -> onChange { it.copy(rentMonths = value) } }
                )
                SectionHeading(stringResource(R.string.options_rent_paid_by))
                ChoiceRow(
                    // The value is what the receipt prints, so it stays as written; only the chip is translated.
                    options = listOf(
                        "Bank transfer" to stringResource(R.string.options_rent_bank),
                        "UPI" to "UPI",
                        "Cash" to stringResource(R.string.options_rent_cash),
                        "Cheque" to stringResource(R.string.options_rent_cheque)
                    ),
                    selected = config.rentMode,
                    onSelect = { value -> onChange { it.copy(rentMode = value) } }
                )
                Field(
                    value = config.landlordPan,
                    label = stringResource(R.string.options_rent_pan),
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(landlordPan = value.uppercase().take(10)) } }
                )
                Hint(
                    stringResource(R.string.options_rent_hint)
                )
            }

            ToolId.WATERMARK -> {
                Field(
                    value = config.watermarkText,
                    label = stringResource(R.string.options_watermark_text),
                    keyboardType = KeyboardType.Text,
                    onChange = { text -> onChange { it.copy(watermarkText = text) } }
                )
                SectionHeading(stringResource(R.string.options_layout))
                ChoiceRow(
                    options = WatermarkSpec.Layout.entries.map { it to it.label },
                    selected = config.watermarkLayout,
                    onSelect = { value -> onChange { it.copy(watermarkLayout = value) } }
                )
                SectionHeading(stringResource(R.string.options_strength))
                ChoiceRow(
                    options = listOf(0.12f to stringResource(R.string.options_light), 0.22f to stringResource(R.string.options_medium), 0.38f to stringResource(R.string.options_strong)),
                    selected = config.watermarkOpacity,
                    onSelect = { value -> onChange { it.copy(watermarkOpacity = value) } }
                )
                SectionHeading(stringResource(R.string.options_colour))
                ChoiceRow(
                    options = listOf(WATERMARK_GREY to stringResource(R.string.options_grey), WATERMARK_RED to stringResource(R.string.options_red), WATERMARK_BLUE to stringResource(R.string.options_blue)),
                    selected = config.watermarkColour,
                    onSelect = { value -> onChange { it.copy(watermarkColour = value) } }
                )
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = stringResource(R.string.options_pages),
                    hint = stringResource(R.string.options_empty_every_page),
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
                Hint(stringResource(R.string.options_watermark_hint))
            }

            ToolId.PAGE_NUMBERS -> {
                ChoiceRow(
                    options = listOf(false to stringResource(R.string.options_page_numbers), true to stringResource(R.string.options_bates_numbers)),
                    selected = config.bates,
                    onSelect = { value -> onChange { it.copy(bates = value) } }
                )
                SectionHeading(stringResource(R.string.options_where))
                ChoiceRow(
                    options = listOf(
                        PageNumberSpec.Position.BOTTOM_CENTRE to stringResource(R.string.options_bottom),
                        PageNumberSpec.Position.BOTTOM_RIGHT to stringResource(R.string.options_bottom_right),
                        PageNumberSpec.Position.BOTTOM_LEFT to stringResource(R.string.options_bottom_left),
                        PageNumberSpec.Position.TOP_CENTRE to stringResource(R.string.options_top),
                        PageNumberSpec.Position.TOP_RIGHT to stringResource(R.string.options_top_right),
                        PageNumberSpec.Position.TOP_LEFT to stringResource(R.string.options_top_left)
                    ),
                    selected = config.numberPosition,
                    onSelect = { value -> onChange { it.copy(numberPosition = value) } }
                )
                if (config.bates) {
                    Field(
                        value = config.batesPrefix,
                        label = stringResource(R.string.options_bates_prefix),
                        keyboardType = KeyboardType.Text,
                        onChange = { value -> onChange { it.copy(batesPrefix = value) } }
                    )
                    SectionHeading(stringResource(R.string.options_digits))
                    ChoiceRow(
                        options = listOf(4 to "0001", 6 to "000001", 8 to "00000001"),
                        selected = config.batesDigits,
                        onSelect = { value -> onChange { it.copy(batesDigits = value) } }
                    )
                    NumberField(
                        value = config.numberStartAt,
                        label = stringResource(R.string.options_start_at),
                        min = 1,
                        max = 99_999_999,
                        onChange = { value -> onChange { it.copy(numberStartAt = value) } }
                    )
                    Hint(
                        stringResource(R.string.options_bates_hint)
                    )
                } else {
                    SectionHeading(stringResource(R.string.options_format))
                    ChoiceRow(
                        options = listOf(
                            "{n}" to "1",
                            "Page {n}" to "Page 1",
                            "{n} of {total}" to "1 of 9",
                            "Page {n} of {total}" to "Page 1 of 9",
                            "- {n} -" to "- 1 -"
                        ),
                        selected = config.numberFormat,
                        onSelect = { value -> onChange { it.copy(numberFormat = value) } }
                    )
                    NumberField(
                        value = config.numberStartAt,
                        label = stringResource(R.string.options_start_at),
                        min = 1,
                        max = 99_999,
                        onChange = { value -> onChange { it.copy(numberStartAt = value) } }
                    )
                }
                NumberField(
                    value = config.numberSkipFirst,
                    label = stringResource(R.string.options_leave_unnumbered),
                    min = 0,
                    max = (pageCount ?: 9999) - 1,
                    onChange = { value -> onChange { it.copy(numberSkipFirst = value) } }
                )
            }

            ToolId.CROP -> {
                ChoiceRow(
                    options = listOf(false to stringResource(R.string.options_crop_mm), true to stringResource(R.string.options_crop_auto)),
                    selected = config.cropAuto,
                    onSelect = { value -> onChange { it.copy(cropAuto = value) } }
                )
                if (config.cropAuto) {
                    Hint(
                        stringResource(R.string.options_crop_auto_hint)
                    )
                } else {
                    SectionHeading(stringResource(R.string.options_crop_mm_each))
                    ChoiceRow(
                        options = listOf(0f to stringResource(R.string.options_none), 5f to "5 mm", 10f to "10 mm", 15f to "15 mm", 20f to "20 mm"),
                        selected = if (config.cropTopMm == config.cropRightMm && config.cropTopMm == config.cropBottomMm && config.cropTopMm == config.cropLeftMm) config.cropTopMm else -1f,
                        onSelect = { mm ->
                            onChange { it.copy(cropTopMm = mm, cropRightMm = mm, cropBottomMm = mm, cropLeftMm = mm) }
                        }
                    )
                    MillimetreField(stringResource(R.string.options_mm_top), config.cropTopMm) { mm -> onChange { it.copy(cropTopMm = mm) } }
                    MillimetreField(stringResource(R.string.options_mm_right), config.cropRightMm) { mm -> onChange { it.copy(cropRightMm = mm) } }
                    MillimetreField(stringResource(R.string.options_mm_bottom), config.cropBottomMm) { mm -> onChange { it.copy(cropBottomMm = mm) } }
                    MillimetreField(stringResource(R.string.options_mm_left), config.cropLeftMm) { mm -> onChange { it.copy(cropLeftMm = mm) } }
                    Hint(stringResource(R.string.options_crop_measured_hint))
                }
                SectionHeading(stringResource(R.string.options_crop_then))
                ChoiceRow(
                    options = listOf(
                        null to stringResource(R.string.options_leave_as_is),
                        PageSpec.Size.A4 to "A4",
                        PageSpec.Size.LETTER to stringResource(R.string.options_size_letter),
                        PageSpec.Size.A5 to "A5",
                        PageSpec.Size.LEGAL to stringResource(R.string.options_size_legal),
                        PageSpec.Size.A3 to "A3"
                    ),
                    selected = config.resizeTo,
                    onSelect = { value -> onChange { it.copy(resizeTo = value) } }
                )
                if (config.resizeTo != null) {
                    ChoiceRow(
                        options = listOf(true to stringResource(R.string.options_crop_scale_up), false to stringResource(R.string.options_crop_keep_size)),
                        selected = config.cropEnlarge,
                        onSelect = { value -> onChange { it.copy(cropEnlarge = value) } }
                    )
                }
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = stringResource(R.string.options_pages),
                    hint = stringResource(R.string.options_empty_every_page),
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
                Hint(
                    stringResource(R.string.options_crop_hint)
                )
            }

            ToolId.BACKGROUND -> {
                SectionHeading(stringResource(R.string.options_colour))
                ChoiceRow(
                    options = listOf(
                        BACKGROUND_CREAM to stringResource(R.string.options_cream),
                        BACKGROUND_SEPIA to stringResource(R.string.options_sepia),
                        BACKGROUND_GREY to stringResource(R.string.options_grey),
                        BACKGROUND_BLUE to stringResource(R.string.options_blue),
                        BACKGROUND_GREEN to stringResource(R.string.options_green),
                        BACKGROUND_PINK to stringResource(R.string.options_pink),
                        BACKGROUND_YELLOW to stringResource(R.string.options_yellow),
                        BACKGROUND_DARK to stringResource(R.string.options_dark)
                    ),
                    selected = config.backgroundColour,
                    onSelect = { value -> onChange { it.copy(backgroundColour = value) } }
                )
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = stringResource(R.string.options_pages),
                    hint = stringResource(R.string.options_empty_every_page),
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
                Hint(
                    stringResource(R.string.options_background_hint)
                )
            }

            ToolId.PAGE_LABELS -> {
                ChoiceRow(
                    options = listOf(false to stringResource(R.string.options_labels_set), true to stringResource(R.string.options_labels_remove)),
                    selected = config.removeLabels,
                    onSelect = { value -> onChange { it.copy(removeLabels = value) } }
                )
                if (config.removeLabels) {
                    Hint(stringResource(R.string.options_labels_remove_hint))
                } else {
                    NumberField(
                        value = config.labelFrontPages,
                        label = stringResource(R.string.options_labels_front),
                        min = 0,
                        max = (pageCount ?: 9999) - 1,
                        onChange = { value -> onChange { it.copy(labelFrontPages = value) } }
                    )
                    if (config.labelFrontPages > 0) {
                        SectionHeading(stringResource(R.string.options_labels_front_style))
                        ChoiceRow(
                            options = PageLabelSpec.Style.entries.map { it to it.label },
                            selected = config.labelFrontStyle,
                            onSelect = { value -> onChange { it.copy(labelFrontStyle = value) } }
                        )
                    }
                    SectionHeading(stringResource(R.string.options_labels_rest))
                    ChoiceRow(
                        options = PageLabelSpec.Style.entries.map { it to it.label },
                        selected = config.labelStyle,
                        onSelect = { value -> onChange { it.copy(labelStyle = value) } }
                    )
                    NumberField(
                        value = config.labelStartAt,
                        label = stringResource(R.string.options_starting_at),
                        min = 1,
                        max = 99_999,
                        onChange = { value -> onChange { it.copy(labelStartAt = value) } }
                    )
                    Field(
                        value = config.labelPrefix,
                        label = stringResource(R.string.options_labels_prefix),
                        keyboardType = KeyboardType.Text,
                        onChange = { value -> onChange { it.copy(labelPrefix = value) } }
                    )
                    Hint(
                        stringResource(R.string.options_labels_hint)
                    )
                }
            }

            ToolId.BOOKMARKS -> Hint(
                stringResource(R.string.options_bookmarks_hint)
            )

            ToolId.ATTACHMENTS -> {
                ChoiceRow(
                    options = listOf(false to stringResource(R.string.options_attach_put), true to stringResource(R.string.options_attach_take)),
                    selected = config.extractAttachments,
                    onSelect = { value -> onChange { it.copy(extractAttachments = value) } }
                )
                Hint(
                    if (config.extractAttachments) {
                        stringResource(R.string.options_attach_take_hint)
                    } else {
                        stringResource(R.string.options_attach_put_hint)
                    }
                )
            }

            ToolId.SIGN -> {
                SectionHeading(stringResource(R.string.options_where_on_page))
                ChoiceRow(
                    options = listOf(
                        ImageStamp.Anchor.BOTTOM_RIGHT to stringResource(R.string.options_bottom_right),
                        ImageStamp.Anchor.BOTTOM_LEFT to stringResource(R.string.options_bottom_left),
                        ImageStamp.Anchor.BOTTOM_CENTRE to stringResource(R.string.options_bottom_centre)
                    ),
                    selected = config.signAnchor,
                    onSelect = { value -> onChange { it.copy(signAnchor = value) } }
                )
                SectionHeading(stringResource(R.string.options_size))
                ChoiceRow(
                    options = listOf(0.2f to stringResource(R.string.options_small), 0.3f to stringResource(R.string.options_medium), 0.45f to stringResource(R.string.options_large)),
                    selected = config.signWidth,
                    onSelect = { value -> onChange { it.copy(signWidth = value) } }
                )
                NumberField(
                    value = config.markPage,
                    label = stringResource(R.string.options_page),
                    min = 1,
                    max = pageCount ?: 9999,
                    onChange = { value -> onChange { it.copy(markPage = value) } }
                )
                Hint(
                    stringResource(R.string.options_sign_hint)
                )
            }

            ToolId.ADD_IMAGE -> {
                SectionHeading(stringResource(R.string.options_where_on_page))
                ChoiceRow(
                    options = listOf(
                        ImageStamp.Anchor.TOP_LEFT to stringResource(R.string.options_top_left),
                        ImageStamp.Anchor.TOP_CENTRE to stringResource(R.string.options_top),
                        ImageStamp.Anchor.TOP_RIGHT to stringResource(R.string.options_top_right),
                        ImageStamp.Anchor.MIDDLE_LEFT to stringResource(R.string.options_left),
                        ImageStamp.Anchor.MIDDLE_CENTRE to stringResource(R.string.options_middle),
                        ImageStamp.Anchor.MIDDLE_RIGHT to stringResource(R.string.options_right),
                        ImageStamp.Anchor.BOTTOM_LEFT to stringResource(R.string.options_bottom_left),
                        ImageStamp.Anchor.BOTTOM_CENTRE to stringResource(R.string.options_bottom),
                        ImageStamp.Anchor.BOTTOM_RIGHT to stringResource(R.string.options_bottom_right)
                    ),
                    selected = config.signAnchor,
                    onSelect = { value -> onChange { it.copy(signAnchor = value) } }
                )
                SectionHeading(stringResource(R.string.options_width_share))
                ChoiceRow(
                    options = listOf(0.15f to stringResource(R.string.options_small), 0.3f to stringResource(R.string.options_medium), 0.5f to stringResource(R.string.options_large), 0.9f to stringResource(R.string.options_full_width)),
                    selected = config.signWidth,
                    onSelect = { value -> onChange { it.copy(signWidth = value) } }
                )
                NumberField(
                    value = config.markPage,
                    label = stringResource(R.string.options_page),
                    min = 1,
                    max = pageCount ?: 9999,
                    onChange = { value -> onChange { it.copy(markPage = value) } }
                )
                Hint(
                    stringResource(R.string.options_add_image_hint)
                )
            }

            ToolId.ADD_TEXT -> {
                Field(
                    value = config.noteText,
                    label = stringResource(R.string.options_what_to_write),
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(noteText = value) } }
                )
                SectionHeading(stringResource(R.string.options_size))
                ChoiceRow(
                    options = listOf(9f to stringResource(R.string.options_small), 12f to stringResource(R.string.options_normal), 16f to stringResource(R.string.options_large), 24f to stringResource(R.string.options_big)),
                    selected = config.noteSize,
                    onSelect = { value -> onChange { it.copy(noteSize = value) } }
                )
                SectionHeading(stringResource(R.string.options_ink))
                ChoiceRow(
                    options = listOf(
                        NOTE_BLACK to stringResource(R.string.options_black),
                        NOTE_BLUE to stringResource(R.string.options_blue),
                        NOTE_RED to stringResource(R.string.options_red)
                    ),
                    selected = config.noteColour,
                    onSelect = { value -> onChange { it.copy(noteColour = value) } }
                )
                SectionHeading(stringResource(R.string.options_already_there))
                ChoiceRow(
                    options = listOf(
                        false to stringResource(R.string.options_write_over),
                        true to stringResource(R.string.options_cover_first)
                    ),
                    selected = config.noteCover,
                    onSelect = { value -> onChange { it.copy(noteCover = value) } }
                )
                NumberField(
                    value = config.markPage,
                    label = stringResource(R.string.options_page),
                    min = 1,
                    max = pageCount ?: 9999,
                    onChange = { value -> onChange { it.copy(markPage = value) } }
                )
                Hint(
                    stringResource(R.string.options_add_text_hint)
                )
            }

            ToolId.HEADER_FOOTER -> {
                Field(
                    value = config.headerText,
                    label = stringResource(R.string.options_along_top),
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(headerText = value) } }
                )
                Field(
                    value = config.footerText,
                    label = stringResource(R.string.options_along_bottom),
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(footerText = value) } }
                )
                Hint(
                    stringResource(R.string.options_header_hint)
                )
                SectionHeading(stringResource(R.string.options_where))
                ChoiceRow(
                    options = listOf(
                        StampSpec.Align.LEFT to stringResource(R.string.options_left),
                        StampSpec.Align.CENTRE to stringResource(R.string.options_centre),
                        StampSpec.Align.RIGHT to stringResource(R.string.options_right)
                    ),
                    selected = config.stampAlign,
                    onSelect = { value -> onChange { it.copy(stampAlign = value) } }
                )
                SectionHeading(stringResource(R.string.options_size))
                ChoiceRow(
                    options = listOf(8f to stringResource(R.string.options_small), 9f to stringResource(R.string.options_normal), 11f to stringResource(R.string.options_large)),
                    selected = config.stampSize,
                    onSelect = { value -> onChange { it.copy(stampSize = value) } }
                )
                NumberField(
                    value = config.stampSkipFirst,
                    label = stringResource(R.string.options_leave_unstamped),
                    min = 0,
                    max = (pageCount ?: 9999) - 1,
                    onChange = { value -> onChange { it.copy(stampSkipFirst = value) } }
                )
            }

            ToolId.INSERT -> {
                Hint(
                    stringResource(R.string.options_insert_hint)
                )
                if (!config.replacePages) {
                    NumberField(
                        value = config.insertAt,
                        label = stringResource(R.string.options_insert_at),
                        min = 1,
                        max = (pageCount ?: 9999) + 1,
                        onChange = { value -> onChange { it.copy(insertAt = value) } }
                    )
                }
                SectionHeading(stringResource(R.string.options_insert_blank_heading))
                ChoiceRow(
                    options = listOf(0 to stringResource(R.string.options_none), 1 to "1", 2 to "2", 4 to "4"),
                    selected = config.blankPages,
                    onSelect = { value -> onChange { it.copy(blankPages = value) } }
                )
                SectionHeading(stringResource(R.string.options_insert_or_replace))
                ChoiceRow(
                    options = listOf(false to stringResource(R.string.options_insert_beside), true to stringResource(R.string.options_insert_replace)),
                    selected = config.replacePages,
                    onSelect = { value -> onChange { it.copy(replacePages = value) } }
                )
                if (config.replacePages) {
                    PageSpecField(
                        value = config.replaceSpec,
                        pageCount = pageCount,
                        label = stringResource(R.string.options_insert_pages_replace),
                        hint = stringResource(R.string.options_insert_replace_hint),
                        onChange = { spec -> onChange { it.copy(replaceSpec = spec) } }
                    )
                }
            }

            ToolId.ANNOTATE -> {
                SectionHeading(stringResource(R.string.options_finger))
                ChoiceRow(
                    options = ToolConfig.MarkupTool.entries.map { it to it.label },
                    selected = config.markupTool,
                    onSelect = { tool ->
                        onChange {
                            // The two sets of colours are not interchangeable - a yellow pen
                            // is invisible and a black highlight is a redaction - so
                            // switching between them picks that set's first colour.
                            val inkNow = tool.usesInk
                            val inkBefore = it.markupTool.usesInk
                            it.copy(
                                markupTool = tool,
                                markupColour = when {
                                    inkNow == inkBefore -> it.markupColour
                                    inkNow -> if (tool == ToolConfig.MarkupTool.STAMP) NOTE_RED else NOTE_BLACK
                                    else -> HIGHLIGHT_YELLOW
                                }
                            )
                        }
                    }
                )
                when (config.markupTool) {
                    ToolConfig.MarkupTool.NOTE -> {
                        Field(
                            value = config.markupText,
                            label = stringResource(R.string.options_note_says),
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(markupText = value) } }
                        )
                        SectionHeading(stringResource(R.string.options_size))
                        ChoiceRow(
                            options = listOf(8f to stringResource(R.string.options_small), 10f to stringResource(R.string.options_normal), 14f to stringResource(R.string.options_large)),
                            selected = config.noteSize,
                            onSelect = { value -> onChange { it.copy(noteSize = value) } }
                        )
                    }
                    ToolConfig.MarkupTool.STAMP -> {
                        SectionHeading(stringResource(R.string.options_stamp_says))
                        ChoiceRow(
                            options = STAMP_WORDS.map { it to it },
                            selected = config.stampText,
                            onSelect = { value -> onChange { it.copy(stampText = value) } }
                        )
                        Field(
                            value = config.stampText,
                            label = stringResource(R.string.options_own_word),
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(stampText = value) } }
                        )
                    }
                    else -> Unit
                }
                SectionHeading(stringResource(R.string.options_colour))
                if (config.markupTool.usesInk) {
                    ChoiceRow(
                        options = listOf(
                            NOTE_BLACK to stringResource(R.string.options_black),
                            NOTE_BLUE to stringResource(R.string.options_blue),
                            NOTE_RED to stringResource(R.string.options_red),
                            INK_GREEN to stringResource(R.string.options_green)
                        ),
                        selected = config.markupColour,
                        onSelect = { value -> onChange { it.copy(markupColour = value) } }
                    )
                    if (config.markupTool != ToolConfig.MarkupTool.NOTE && config.markupTool != ToolConfig.MarkupTool.STAMP) {
                        SectionHeading(stringResource(R.string.options_thickness))
                        ChoiceRow(
                            options = listOf(1.2f to stringResource(R.string.options_thin), 2.5f to stringResource(R.string.options_medium), 5f to stringResource(R.string.options_thick)),
                            selected = config.penWidth,
                            onSelect = { value -> onChange { it.copy(penWidth = value) } }
                        )
                    }
                } else {
                    ChoiceRow(
                        options = listOf(
                            HIGHLIGHT_YELLOW to stringResource(R.string.options_yellow),
                            HIGHLIGHT_GREEN to stringResource(R.string.options_green),
                            HIGHLIGHT_PINK to stringResource(R.string.options_pink),
                            HIGHLIGHT_BLUE to stringResource(R.string.options_blue)
                        ),
                        selected = config.markupColour,
                        onSelect = { value -> onChange { it.copy(markupColour = value) } }
                    )
                }
                Hint(
                    when {
                        config.markupTool.taps -> stringResource(R.string.options_tap_page)
                        config.markupTool.drags -> stringResource(R.string.options_drag_page)
                        else -> stringResource(R.string.options_draw_finger)
                    } + " " + stringResource(R.string.options_annotate_hint)
                )
            }

            ToolId.FORMS -> {
                ChoiceRow(
                    options = listOf(
                        false to stringResource(R.string.options_form_editable),
                        true to stringResource(R.string.options_form_seal)
                    ),
                    selected = config.flattenForm,
                    onSelect = { value -> onChange { it.copy(flattenForm = value) } }
                )
                Hint(
                    if (config.flattenForm) {
                        stringResource(R.string.options_form_seal_hint)
                    } else {
                        stringResource(R.string.options_form_editable_hint)
                    }
                )
            }

            ToolId.N_UP -> {
                SectionHeading(stringResource(R.string.options_per_sheet))
                ChoiceRow(
                    options = listOf(2 to "2", 4 to "4", 6 to "6", 8 to "8", 9 to "9", 16 to "16"),
                    selected = config.perSheet,
                    onSelect = { value -> onChange { it.copy(perSheet = value) } }
                )
                SectionHeading(stringResource(R.string.options_sheet))
                ChoiceRow(
                    options = listOf(
                        PageSpec.Size.A4 to "A4",
                        PageSpec.Size.LETTER to stringResource(R.string.options_size_letter),
                        PageSpec.Size.A3 to "A3",
                        PageSpec.Size.LEGAL to stringResource(R.string.options_size_legal)
                    ),
                    selected = config.nUpPaper,
                    onSelect = { value -> onChange { it.copy(nUpPaper = value) } }
                )
                Hint(
                    stringResource(R.string.options_nup_hint)
                )
            }

            ToolId.SPLIT_BY_SIZE -> {
                SectionHeading(stringResource(R.string.options_each_part_under))
                ChoiceRow(
                    options = listOf(2 to "2 MB", 5 to "5 MB", 10 to "10 MB", 25 to "25 MB"),
                    selected = config.partMb,
                    onSelect = { value -> onChange { it.copy(partMb = value) } }
                )
                NumberField(
                    value = config.partMb,
                    label = stringResource(R.string.options_any_size_mb),
                    min = 1,
                    max = 2000,
                    onChange = { value -> onChange { it.copy(partMb = value) } }
                )
                Hint(
                    stringResource(R.string.options_split_size_hint)
                )
            }

            ToolId.METADATA -> {
                ChoiceRow(
                    options = listOf(
                        ToolConfig.MetaMode.EDIT to stringResource(R.string.options_meta_edit),
                        ToolConfig.MetaMode.CLEAN to stringResource(R.string.options_meta_clean),
                        ToolConfig.MetaMode.STRIP to stringResource(R.string.options_meta_blank)
                    ),
                    selected = config.metaMode,
                    onSelect = { value -> onChange { it.copy(metaMode = value) } }
                )
                when (config.metaMode) {
                    ToolConfig.MetaMode.STRIP -> Hint(
                        stringResource(R.string.options_meta_strip_hint)
                    )
                    ToolConfig.MetaMode.CLEAN -> {
                        Hint(
                            stringResource(R.string.options_meta_clean_hint)
                        )
                        ToggleRow(
                            title = stringResource(R.string.options_clean_scripts),
                            checked = config.cleanScripts,
                            onChange = { on -> onChange { it.copy(cleanScripts = on) } }
                        )
                        ToggleRow(
                            title = stringResource(R.string.options_clean_attachments),
                            checked = config.cleanAttachments,
                            onChange = { on -> onChange { it.copy(cleanAttachments = on) } }
                        )
                        ToggleRow(
                            title = stringResource(R.string.options_clean_comments),
                            checked = config.cleanComments,
                            onChange = { on -> onChange { it.copy(cleanComments = on) } }
                        )
                    }
                    ToolConfig.MetaMode.EDIT -> {
                        Hint(
                            if (fileCount > 1) {
                                pluralStringResource(R.plurals.options_meta_batch_hint, fileCount, fileCount)
                            } else {
                                stringResource(R.string.options_meta_single_hint)
                            }
                        )
                        Field(
                            value = config.metaTitle,
                            label = stringResource(R.string.options_title),
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(metaTitle = value) } }
                        )
                        Field(
                            value = config.metaAuthor,
                            label = stringResource(R.string.options_author),
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(metaAuthor = value) } }
                        )
                        Field(
                            value = config.metaSubject,
                            label = stringResource(R.string.options_subject),
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(metaSubject = value) } }
                        )
                        Field(
                            value = config.metaKeywords,
                            label = stringResource(R.string.options_keywords),
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(metaKeywords = value) } }
                        )
                    }
                }
            }

            ToolId.EXTRACT_IMAGES -> Hint(
                stringResource(R.string.options_extract_images_hint)
            )

            ToolId.REDACT -> Hint(
                stringResource(R.string.options_redact_hint)
            )

            // These have their own canvases on the tool screen rather than plain settings.
            ToolId.SCAN, ToolId.EDIT_IMAGES, ToolId.RECIPES -> Unit
            ToolId.SUMMARISE -> Hint(
                stringResource(R.string.options_summarise_hint)
            )

            ToolId.ORGANISE -> Unit
        }
        }
    }
}

/** Tools whose options panel only explains; the controls are on the screen, or there are none. */
private val HINT_ONLY = setOf(
    ToolId.MERGE, ToolId.REMOVE_PASSWORD, ToolId.EXTRACT_IMAGES, ToolId.REDACT, ToolId.BOOKMARKS,
    ToolId.COMPARE, ToolId.OFFICE_TO_PDF, ToolId.PDF_A
)

@Composable
private fun PageSpecField(
    value: String,
    pageCount: Int?,
    label: String,
    hint: String = stringResource(R.string.options_page_spec_hint),
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Field(
            value = value,
            label = label,
            keyboardType = KeyboardType.Text,
            onChange = onChange
        )
        Hint(if (pageCount != null) pluralStringResource(R.plurals.options_page_spec_with_count, pageCount, hint, pageCount) else hint)
    }
}

@Composable
private fun NumberField(value: Int, label: String, min: Int, max: Int, onChange: (Int) -> Unit) {
    val top = maxOf(min, max)
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = SheafColors.Muted,
            modifier = Modifier.weight(1f).padding(end = 10.dp)
        )
        // Nudged a step at a time, or typed: a page number is usually one off, a Bates start
        // is usually six digits.
        RoundIconButton(iconRes = com.layerbit.sheaf.R.drawable.ic_minus, contentDescription = stringResource(R.string.options_less), onClick = { onChange((value - 1).coerceIn(min, top)) }, size = 36.dp)
        androidx.compose.foundation.layout.Box(modifier = Modifier.width(88.dp).padding(horizontal = 6.dp)) {
            Field(
                value = value.toString(),
                label = "",
                keyboardType = KeyboardType.Number,
                onChange = { text ->
                    // An empty field while typing must not snap back to a number, so blank
                    // maps to the minimum rather than being rejected outright.
                    val parsed = text.filter { it.isDigit() }.toIntOrNull() ?: min
                    onChange(parsed.coerceIn(min, top))
                },
                centred = true
            )
        }
        RoundIconButton(iconRes = com.layerbit.sheaf.R.drawable.ic_plus, contentDescription = stringResource(R.string.options_more), onClick = { onChange((value + 1).coerceIn(min, top)) }, size = 36.dp)
    }
}

/** A length in millimetres, typed. Blank reads as nothing off that edge. */
@Composable
private fun MillimetreField(label: String, value: Float, onChange: (Float) -> Unit) {
    Field(
        value = if (value == 0f) "" else if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString(),
        label = label,
        keyboardType = KeyboardType.Decimal,
        onChange = { text ->
            val parsed = text.replace(',', '.').filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f
            onChange(parsed.coerceIn(0f, 500f))
        }
    )
}

/** Also used by the per-document unlock prompt on the tool screen. */
@Composable
fun PasswordField(value: String, label: String = stringResource(R.string.options_password), onChange: (String) -> Unit) {
    Field(
        value = value,
        label = label,
        keyboardType = KeyboardType.Password,
        password = true,
        onChange = onChange
    )
}

@Composable
internal fun Field(
    value: String,
    label: String,
    keyboardType: KeyboardType,
    password: Boolean = false,
    onChange: (String) -> Unit,
    centred: Boolean = false
) {
    // The field keeps what is typed itself and tells the model, rather than showing the
    // model's copy back: that copy arrives a frame later, and a keyboard that sends edits
    // faster than frames - or replaces a whole word at once - had characters dropped.
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(value) }
    // What this field has sent and not yet seen come back. A value that is one of them is
    // its own echo, late; anything else was set from outside - Replace everywhere, a kept
    // answer - and is taken.
    val sent = androidx.compose.runtime.remember { mutableListOf<String>() }
    androidx.compose.runtime.LaunchedEffect(value) {
        val echo = sent.indexOf(value)
        if (echo >= 0) {
            repeat(echo + 1) { sent.removeAt(0) }
        } else if (value != text) {
            sent.clear()
            text = value
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            sent += it
            if (sent.size > 64) sent.removeAt(0)
            onChange(it)
        },
        label = if (label.isEmpty()) null else ({ Text(label, style = MaterialTheme.typography.bodySmall) }),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = if (centred) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SheafColors.Band,
            unfocusedBorderColor = SheafColors.Border,
            focusedTextColor = SheafColors.Text,
            unfocusedTextColor = SheafColors.Text,
            cursorColor = SheafColors.Band,
            focusedLabelColor = SheafColors.Band,
            unfocusedLabelColor = SheafColors.Dim,
            focusedContainerColor = SheafColors.Surface,
            unfocusedContainerColor = SheafColors.Surface
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * A row of mutually exclusive choices. Wraps rather than scrolling, so nothing hides offscreen.
 *
 * The drawing lives in components, because the settings screen asks the same kind of question
 * and two copies of a control drift apart the first time one is adjusted.
 */
@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) =
    ChipRow(options = options, selected = selected, onSelect = onSelect)

/** A sentence under a control saying what it does, or what it costs. Shared with the screen. */
@Composable
internal fun Hint(text: String) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SheafColors.SurfaceDim, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Top
    ) {
        androidx.compose.material3.Icon(
            imageVector = androidx.compose.material.icons.Icons.Filled.Info,
            contentDescription = null,
            tint = SheafColors.Dim,
            modifier = Modifier.size(16.dp).padding(top = 1.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = SheafColors.Muted,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}
