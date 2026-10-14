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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.pdf.CompressionLevel
import com.layerbit.sheaf.pdf.ImageFormat
import com.layerbit.sheaf.pdf.ImageStamp
import com.layerbit.sheaf.pdf.PageLabelSpec
import com.layerbit.sheaf.pdf.PageNumberSpec
import com.layerbit.sheaf.pdf.WatermarkSpec
import com.layerbit.sheaf.pdf.PageSpec
import com.layerbit.sheaf.pdf.StampSpec
import com.layerbit.sheaf.ui.components.ChipRow
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
    onCheckSize: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (tool) {
            ToolId.EXTRACT -> {
                ChoiceRow(
                    options = listOf(false to "Keep these pages", true to "Remove these pages"),
                    selected = config.removePages,
                    onSelect = { value -> onChange { it.copy(removePages = value) } }
                )
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = if (config.removePages) "Pages to remove" else "Pages to keep",
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
            }

            ToolId.SPLIT -> {
                SectionHeading("How to split")
                ChoiceRow(
                    options = listOf(
                        ToolConfig.SplitMode.EVERY_N to "Every N pages",
                        ToolConfig.SplitMode.EACH_PAGE to "Single pages",
                        ToolConfig.SplitMode.RANGES to "By ranges",
                        ToolConfig.SplitMode.AT_BOOKMARKS to "At the bookmarks"
                    ),
                    selected = config.splitMode,
                    onSelect = { mode -> onChange { it.copy(splitMode = mode) } }
                )
                when (config.splitMode) {
                    ToolConfig.SplitMode.EVERY_N -> NumberField(
                        value = config.splitSize,
                        label = "Pages per file",
                        min = 1,
                        max = pageCount ?: 9999,
                        onChange = { size -> onChange { it.copy(splitSize = size) } }
                    )
                    ToolConfig.SplitMode.RANGES -> PageSpecField(
                        value = config.pageSpec,
                        pageCount = pageCount,
                        label = "One file per range",
                        hint = "Separate the files with commas: 1-3, 4-8, 9-",
                        onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                    )
                    ToolConfig.SplitMode.EACH_PAGE -> Unit
                    ToolConfig.SplitMode.AT_BOOKMARKS -> Hint(
                        "One file per top-level entry in the document's own table of contents, " +
                            "which is the natural way to break up a scanned book or a bundle " +
                            "of statements. Documents without one say so rather than guessing."
                    )
                }
            }

            ToolId.PDF_TO_IMAGES -> {
                SectionHeading("Format")
                ChoiceRow(
                    options = listOf(
                        ImageFormat.PNG to "PNG",
                        ImageFormat.JPEG to "JPEG"
                    ),
                    selected = config.imageFormat,
                    onSelect = { format -> onChange { it.copy(imageFormat = format) } }
                )
                SectionHeading("Resolution")
                ChoiceRow(
                    options = listOf(
                        96 to "96 · screen",
                        200 to "200 · good",
                        300 to "300 · print"
                    ),
                    selected = config.dpi,
                    onSelect = { dpi -> onChange { it.copy(dpi = dpi) } }
                )
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = "Pages",
                    hint = "Leave empty for every page",
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
            }

            ToolId.IMAGES_TO_PDF -> {
                SectionHeading("Page size")
                ChoiceRow(
                    options = listOf(
                        PageSpec.Size.A4 to "A4",
                        PageSpec.Size.LETTER to "Letter",
                        PageSpec.Size.A5 to "A5",
                        PageSpec.Size.FIT_IMAGE to "Fit the image"
                    ),
                    selected = config.pageSize,
                    onSelect = { size -> onChange { it.copy(pageSize = size) } }
                )
                SectionHeading("Output")
                ChoiceRow(
                    options = listOf(false to "One PDF, a page per image", true to "A PDF per image"),
                    selected = config.separatePdfs,
                    onSelect = { value -> onChange { it.copy(separatePdfs = value) } }
                )
                if (config.pageSize != PageSpec.Size.FIT_IMAGE) {
                    SectionHeading("Margin")
                    ChoiceRow(
                        options = listOf(
                            0f to "None",
                            18f to "Narrow",
                            36f to "Normal",
                            72f to "Wide"
                        ),
                        selected = config.marginPoints,
                        onSelect = { margin -> onChange { it.copy(marginPoints = margin) } }
                    )
                }
            }

            ToolId.COMPRESS -> {
                SectionHeading("How hard to squeeze")
                ChoiceRow(
                    options = CompressionLevel.entries.map { it to it.label },
                    selected = config.compression,
                    onSelect = { level -> onChange { it.copy(compression = level) } }
                )
                Hint(
                    "Sheaf shrinks the images inside a document and leaves text untouched, so " +
                        "text stays sharp and selectable. A document with no scanned pages will " +
                        "barely change."
                )

                // The real operation on the real file, thrown away afterwards. An estimate
                // from image sizes would be wrong in exactly the cases that matter, and the
                // question being asked is whether this will fit under an attachment limit.
                when (sizeCheck) {
                    SizeCheck.Idle -> TextButton(onClick = onCheckSize) {
                        Text("Check the size first", color = SheafColors.Band)
                    }
                    SizeCheck.Working -> Text(
                        "Trying it…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SheafColors.Muted
                    )
                    SizeCheck.Failed -> Text(
                        "That could not be measured. Running it will still tell you.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SheafColors.Skipped
                    )
                    is SizeCheck.Done -> Text(
                        text = if (sizeCheck.compressedBytes >= sizeCheck.originalBytes) {
                            "${formatBytes(sizeCheck.originalBytes)} - already as small as it goes " +
                                "at this level, so the original would be kept."
                        } else {
                            "${formatBytes(sizeCheck.originalBytes)} to " +
                                "${formatBytes(sizeCheck.compressedBytes)} - " +
                                "${(sizeCheck.savedFraction * 100).toInt()}% smaller."
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
                    options = listOf(false to "Password to open", true to "Restrict only, no password"),
                    selected = config.restrictOnly,
                    onSelect = { value -> onChange { it.copy(restrictOnly = value) } }
                )
                if (!config.restrictOnly) {
                    PasswordField(
                        value = config.newPassword,
                        label = "Password",
                        onChange = { value -> onChange { it.copy(newPassword = value) } }
                    )
                    PasswordField(
                        value = config.confirmPassword,
                        label = "Type it again",
                        onChange = { value -> onChange { it.copy(confirmPassword = value) } }
                    )
                    Hint(
                        "Sheaf encrypts with AES-256 and does not keep the password anywhere. If you " +
                            "forget it, the document cannot be opened again - not by us either."
                    )
                } else {
                    Hint(
                        "The file opens without a password. It is encrypted with a key nobody is " +
                            "given, so the restrictions below cannot be lifted by typing one."
                    )
                }
                SectionHeading("What a reader may do")
                ToggleRow(
                    title = "Allow printing",
                    checked = config.allowPrinting,
                    onChange = { on -> onChange { it.copy(allowPrinting = on) } }
                )
                ToggleRow(
                    title = "Allow copying text",
                    checked = config.allowCopying,
                    onChange = { on -> onChange { it.copy(allowCopying = on) } }
                )
                ToggleRow(
                    title = "Allow changes",
                    checked = config.allowChanges,
                    onChange = { on -> onChange { it.copy(allowChanges = on) } }
                )
                Hint(
                    "These three are a request, not a lock. The PDF standard asks readers to " +
                        "honour them and most do, but a reader that ignores them can. " +
                        if (config.restrictOnly) "Anyone who can open the file can print it to a new one." else "The encryption is the part that actually holds."
                )
            }

            ToolId.MERGE -> Hint("Files are joined top to bottom. Use the arrows to reorder them.")

            ToolId.REMOVE_PASSWORD -> Hint(
                "You need the document's password. Sheaf opens it the way any reader would and " +
                    "saves an unlocked copy - it cannot recover a password you do not have."
            )

            ToolId.EXTRACT_TEXT -> PageSpecField(
                value = config.pageSpec,
                pageCount = pageCount,
                label = "Pages",
                hint = "Leave empty for the whole document",
                onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
            )

            ToolId.OCR -> Hint(
                "Sheaf reads the words in a scan and lays them invisibly over the page, so the " +
                    "document looks exactly the same but you can search, select and copy it. " +
                    "This is the slowest tool here - expect around a second a page - and it " +
                    "keeps running if you switch to another app."
            )

            ToolId.WATERMARK -> {
                Field(
                    value = config.watermarkText,
                    label = "Watermark text",
                    keyboardType = KeyboardType.Text,
                    onChange = { text -> onChange { it.copy(watermarkText = text) } }
                )
                SectionHeading("Layout")
                ChoiceRow(
                    options = WatermarkSpec.Layout.entries.map { it to it.label },
                    selected = config.watermarkLayout,
                    onSelect = { value -> onChange { it.copy(watermarkLayout = value) } }
                )
                SectionHeading("Strength")
                ChoiceRow(
                    options = listOf(0.12f to "Light", 0.22f to "Medium", 0.38f to "Strong"),
                    selected = config.watermarkOpacity,
                    onSelect = { value -> onChange { it.copy(watermarkOpacity = value) } }
                )
                SectionHeading("Colour")
                ChoiceRow(
                    options = listOf(WATERMARK_GREY to "Grey", WATERMARK_RED to "Red", WATERMARK_BLUE to "Blue"),
                    selected = config.watermarkColour,
                    onSelect = { value -> onChange { it.copy(watermarkColour = value) } }
                )
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = "Pages",
                    hint = "Leave empty for every page",
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
                Hint("Sized to the page, so DRAFT and CONFIDENTIAL both cross it; longer text is shrunk to fit.")
            }

            ToolId.PAGE_NUMBERS -> {
                ChoiceRow(
                    options = listOf(false to "Page numbers", true to "Bates numbers"),
                    selected = config.bates,
                    onSelect = { value -> onChange { it.copy(bates = value) } }
                )
                SectionHeading("Where")
                ChoiceRow(
                    options = listOf(
                        PageNumberSpec.Position.BOTTOM_CENTRE to "Bottom",
                        PageNumberSpec.Position.BOTTOM_RIGHT to "Bottom right",
                        PageNumberSpec.Position.BOTTOM_LEFT to "Bottom left",
                        PageNumberSpec.Position.TOP_CENTRE to "Top",
                        PageNumberSpec.Position.TOP_RIGHT to "Top right",
                        PageNumberSpec.Position.TOP_LEFT to "Top left"
                    ),
                    selected = config.numberPosition,
                    onSelect = { value -> onChange { it.copy(numberPosition = value) } }
                )
                if (config.bates) {
                    Field(
                        value = config.batesPrefix,
                        label = "Prefix, such as ACME-",
                        keyboardType = KeyboardType.Text,
                        onChange = { value -> onChange { it.copy(batesPrefix = value) } }
                    )
                    SectionHeading("Digits")
                    ChoiceRow(
                        options = listOf(4 to "0001", 6 to "000001", 8 to "00000001"),
                        selected = config.batesDigits,
                        onSelect = { value -> onChange { it.copy(batesDigits = value) } }
                    )
                    NumberField(
                        value = config.numberStartAt,
                        label = "Start at",
                        min = 1,
                        max = 99_999_999,
                        onChange = { value -> onChange { it.copy(numberStartAt = value) } }
                    )
                    Hint(
                        "One unbroken run across every file given, in the order they are listed: " +
                            "the second file carries on from where the first ended."
                    )
                } else {
                    SectionHeading("Format")
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
                        label = "Start at",
                        min = 1,
                        max = 99_999,
                        onChange = { value -> onChange { it.copy(numberStartAt = value) } }
                    )
                }
                NumberField(
                    value = config.numberSkipFirst,
                    label = "Leave this many pages unnumbered",
                    min = 0,
                    max = (pageCount ?: 9999) - 1,
                    onChange = { value -> onChange { it.copy(numberSkipFirst = value) } }
                )
            }

            ToolId.CROP -> {
                ChoiceRow(
                    options = listOf(false to "Trim by millimetres", true to "Remove white margins"),
                    selected = config.cropAuto,
                    onSelect = { value -> onChange { it.copy(cropAuto = value) } }
                )
                if (config.cropAuto) {
                    Hint(
                        "Each page is trimmed to just outside its words and pictures, with 2 mm " +
                            "of white kept. A page with nothing to find is left alone."
                    )
                } else {
                    SectionHeading("Millimetres off each edge")
                    ChoiceRow(
                        options = listOf(0f to "None", 5f to "5 mm", 10f to "10 mm", 15f to "15 mm", 20f to "20 mm"),
                        selected = if (config.cropTopMm == config.cropRightMm && config.cropTopMm == config.cropBottomMm && config.cropTopMm == config.cropLeftMm) config.cropTopMm else -1f,
                        onSelect = { mm ->
                            onChange { it.copy(cropTopMm = mm, cropRightMm = mm, cropBottomMm = mm, cropLeftMm = mm) }
                        }
                    )
                    MillimetreField("Top", config.cropTopMm) { mm -> onChange { it.copy(cropTopMm = mm) } }
                    MillimetreField("Right", config.cropRightMm) { mm -> onChange { it.copy(cropRightMm = mm) } }
                    MillimetreField("Bottom", config.cropBottomMm) { mm -> onChange { it.copy(cropBottomMm = mm) } }
                    MillimetreField("Left", config.cropLeftMm) { mm -> onChange { it.copy(cropLeftMm = mm) } }
                    Hint("Measured on the page as you read it, whichever way it is stored.")
                }
                SectionHeading("Then put every page on")
                ChoiceRow(
                    options = listOf(
                        null to "Leave as it is",
                        PageSpec.Size.A4 to "A4",
                        PageSpec.Size.LETTER to "Letter",
                        PageSpec.Size.A5 to "A5",
                        PageSpec.Size.LEGAL to "Legal",
                        PageSpec.Size.A3 to "A3"
                    ),
                    selected = config.resizeTo,
                    onSelect = { value -> onChange { it.copy(resizeTo = value) } }
                )
                if (config.resizeTo != null) {
                    ChoiceRow(
                        options = listOf(true to "Scale small pages up to fit", false to "Keep small pages their real size"),
                        selected = config.cropEnlarge,
                        onSelect = { value -> onChange { it.copy(cropEnlarge = value) } }
                    )
                }
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = "Pages",
                    hint = "Leave empty for every page",
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
                Hint(
                    "Cropping hides the margins rather than deleting them, which is how every " +
                        "PDF reader works. To take something out of a page for good, use " +
                        "Remove areas."
                )
            }

            ToolId.BACKGROUND -> {
                SectionHeading("Colour")
                ChoiceRow(
                    options = listOf(
                        BACKGROUND_CREAM to "Cream",
                        BACKGROUND_SEPIA to "Sepia",
                        BACKGROUND_GREY to "Grey",
                        BACKGROUND_BLUE to "Blue",
                        BACKGROUND_GREEN to "Green",
                        BACKGROUND_PINK to "Pink",
                        BACKGROUND_YELLOW to "Yellow",
                        BACKGROUND_DARK to "Dark"
                    ),
                    selected = config.backgroundColour,
                    onSelect = { value -> onChange { it.copy(backgroundColour = value) } }
                )
                PageSpecField(
                    value = config.pageSpec,
                    pageCount = pageCount,
                    label = "Pages",
                    hint = "Leave empty for every page",
                    onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
                )
                Hint(
                    "Drawn behind what is already on the page, so the text stays on top. A " +
                        "scanned page is one solid picture, so a colour will not show through it. " +
                        "Dark leaves black type unreadable; it is for pages of white type or pictures."
                )
            }

            ToolId.PAGE_LABELS -> {
                ChoiceRow(
                    options = listOf(false to "Set the labels", true to "Remove the labels"),
                    selected = config.removeLabels,
                    onSelect = { value -> onChange { it.copy(removeLabels = value) } }
                )
                if (config.removeLabels) {
                    Hint("Readers go back to counting from the first page.")
                } else {
                    NumberField(
                        value = config.labelFrontPages,
                        label = "Front matter pages (cover, contents)",
                        min = 0,
                        max = (pageCount ?: 9999) - 1,
                        onChange = { value -> onChange { it.copy(labelFrontPages = value) } }
                    )
                    if (config.labelFrontPages > 0) {
                        SectionHeading("Front matter style")
                        ChoiceRow(
                            options = PageLabelSpec.Style.entries.map { it to it.label },
                            selected = config.labelFrontStyle,
                            onSelect = { value -> onChange { it.copy(labelFrontStyle = value) } }
                        )
                    }
                    SectionHeading("Then the rest")
                    ChoiceRow(
                        options = PageLabelSpec.Style.entries.map { it to it.label },
                        selected = config.labelStyle,
                        onSelect = { value -> onChange { it.copy(labelStyle = value) } }
                    )
                    NumberField(
                        value = config.labelStartAt,
                        label = "Starting at",
                        min = 1,
                        max = 99_999,
                        onChange = { value -> onChange { it.copy(labelStartAt = value) } }
                    )
                    Field(
                        value = config.labelPrefix,
                        label = "Prefix, such as A- (optional)",
                        keyboardType = KeyboardType.Text,
                        onChange = { value -> onChange { it.copy(labelPrefix = value) } }
                    )
                    Hint(
                        "Labels change what a reader shows in its page box, not what is printed " +
                            "on the page. A report whose page 1 is the ninth sheet is the reason: " +
                            "with labels, typing 57 goes to 57. For numbers on the page itself, use Page numbers."
                    )
                }
            }

            ToolId.BOOKMARKS -> Hint(
                "Bookmarks are the table of contents a reader shows in its side panel. Edit " +
                    "the list above: a title and the page it opens. Indent an entry to nest it " +
                    "under the one before."
            )

            ToolId.ATTACHMENTS -> {
                ChoiceRow(
                    options = listOf(false to "Put files inside", true to "Take the files out"),
                    selected = config.extractAttachments,
                    onSelect = { value -> onChange { it.copy(extractAttachments = value) } }
                )
                Hint(
                    if (config.extractAttachments) {
                        "Every file inside the document comes out as its own result, as it was."
                    } else {
                        "The first file is the PDF. Everything added after it is carried inside " +
                            "the copy byte for byte and never opened - a spreadsheet, a photo, " +
                            "another PDF. Readers list them in their attachments panel."
                    }
                )
            }

            ToolId.SIGN -> {
                SectionHeading("Where on the page")
                ChoiceRow(
                    options = listOf(
                        ImageStamp.Anchor.BOTTOM_RIGHT to "Bottom right",
                        ImageStamp.Anchor.BOTTOM_LEFT to "Bottom left",
                        ImageStamp.Anchor.BOTTOM_CENTRE to "Bottom centre"
                    ),
                    selected = config.signAnchor,
                    onSelect = { value -> onChange { it.copy(signAnchor = value) } }
                )
                SectionHeading("Size")
                ChoiceRow(
                    options = listOf(0.2f to "Small", 0.3f to "Medium", 0.45f to "Large"),
                    selected = config.signWidth,
                    onSelect = { value -> onChange { it.copy(signWidth = value) } }
                )
                NumberField(
                    value = config.markPage,
                    label = "Page",
                    min = 1,
                    max = pageCount ?: 9999,
                    onChange = { value -> onChange { it.copy(markPage = value) } }
                )
                Hint(
                    "This draws your signature into the page, the same as printing, signing " +
                        "and scanning it back. It is not a cryptographic digital signature."
                )
            }

            ToolId.ADD_TEXT -> {
                Field(
                    value = config.noteText,
                    label = "What to write",
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(noteText = value) } }
                )
                SectionHeading("Size")
                ChoiceRow(
                    options = listOf(9f to "Small", 12f to "Normal", 16f to "Large", 24f to "Big"),
                    selected = config.noteSize,
                    onSelect = { value -> onChange { it.copy(noteSize = value) } }
                )
                SectionHeading("Ink")
                ChoiceRow(
                    options = listOf(
                        NOTE_BLACK to "Black",
                        NOTE_BLUE to "Blue",
                        NOTE_RED to "Red"
                    ),
                    selected = config.noteColour,
                    onSelect = { value -> onChange { it.copy(noteColour = value) } }
                )
                SectionHeading("What is already there")
                ChoiceRow(
                    options = listOf(
                        false to "Write over it",
                        true to "Cover it first"
                    ),
                    selected = config.noteCover,
                    onSelect = { value -> onChange { it.copy(noteCover = value) } }
                )
                NumberField(
                    value = config.markPage,
                    label = "Page",
                    min = 1,
                    max = pageCount ?: 9999,
                    onChange = { value -> onChange { it.copy(markPage = value) } }
                )
                Hint(
                    "Tap the page below to choose where the text goes. Covering paints white " +
                        "over the old text rather than deleting it, so it is a correction, not " +
                        "a redaction - use Remove areas when something has to be gone for good."
                )
            }

            ToolId.HEADER_FOOTER -> {
                Field(
                    value = config.headerText,
                    label = "Along the top",
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(headerText = value) } }
                )
                Field(
                    value = config.footerText,
                    label = "Along the bottom",
                    keyboardType = KeyboardType.Text,
                    onChange = { value -> onChange { it.copy(footerText = value) } }
                )
                Hint(
                    "Four things are filled in as each page is stamped: {page} the page " +
                        "number, {total} how many there are, {date} today, and {name} the " +
                        "document's name. \"Confidential - {date}\" works, so does " +
                        "\"{name} - page {page} of {total}\"."
                )
                SectionHeading("Where")
                ChoiceRow(
                    options = listOf(
                        StampSpec.Align.LEFT to "Left",
                        StampSpec.Align.CENTRE to "Centre",
                        StampSpec.Align.RIGHT to "Right"
                    ),
                    selected = config.stampAlign,
                    onSelect = { value -> onChange { it.copy(stampAlign = value) } }
                )
                SectionHeading("Size")
                ChoiceRow(
                    options = listOf(8f to "Small", 9f to "Normal", 11f to "Large"),
                    selected = config.stampSize,
                    onSelect = { value -> onChange { it.copy(stampSize = value) } }
                )
                NumberField(
                    value = config.stampSkipFirst,
                    label = "Leave this many pages unstamped",
                    min = 0,
                    max = (pageCount ?: 9999) - 1,
                    onChange = { value -> onChange { it.copy(stampSkipFirst = value) } }
                )
            }

            ToolId.INSERT -> {
                Hint(
                    "The first file is the document being added to. Add a second file and its " +
                        "pages go in; add nothing and you get blank pages instead."
                )
                if (!config.replacePages) {
                    NumberField(
                        value = config.insertAt,
                        label = "Insert at page",
                        min = 1,
                        max = (pageCount ?: 9999) + 1,
                        onChange = { value -> onChange { it.copy(insertAt = value) } }
                    )
                }
                SectionHeading("Blank pages, if no second file")
                ChoiceRow(
                    options = listOf(0 to "None", 1 to "1", 2 to "2", 4 to "4"),
                    selected = config.blankPages,
                    onSelect = { value -> onChange { it.copy(blankPages = value) } }
                )
                SectionHeading("Or replace pages")
                ChoiceRow(
                    options = listOf(false to "Insert beside", true to "Replace these pages"),
                    selected = config.replacePages,
                    onSelect = { value -> onChange { it.copy(replacePages = value) } }
                )
                if (config.replacePages) {
                    PageSpecField(
                        value = config.replaceSpec,
                        pageCount = pageCount,
                        label = "Pages to replace",
                        hint = "The new pages go in where the first of these was",
                        onChange = { spec -> onChange { it.copy(replaceSpec = spec) } }
                    )
                }
            }

            ToolId.ANNOTATE -> {
                SectionHeading("What your finger does")
                ChoiceRow(
                    options = listOf(false to "Highlight", true to "Draw"),
                    selected = config.markupPen,
                    onSelect = { usePen ->
                        onChange {
                            // The two sets of colours are not interchangeable - a yellow pen
                            // is invisible and a black highlight is a redaction - so
                            // switching tool picks that tool's first colour.
                            it.copy(
                                markupPen = usePen,
                                markupColour = if (usePen) NOTE_BLACK else HIGHLIGHT_YELLOW
                            )
                        }
                    }
                )
                SectionHeading("Colour")
                if (config.markupPen) {
                    ChoiceRow(
                        options = listOf(
                            NOTE_BLACK to "Black",
                            NOTE_BLUE to "Blue",
                            NOTE_RED to "Red"
                        ),
                        selected = config.markupColour,
                        onSelect = { value -> onChange { it.copy(markupColour = value) } }
                    )
                    SectionHeading("Thickness")
                    ChoiceRow(
                        options = listOf(1.2f to "Thin", 2.5f to "Medium", 5f to "Thick"),
                        selected = config.penWidth,
                        onSelect = { value -> onChange { it.copy(penWidth = value) } }
                    )
                } else {
                    ChoiceRow(
                        options = listOf(
                            HIGHLIGHT_YELLOW to "Yellow",
                            HIGHLIGHT_GREEN to "Green",
                            HIGHLIGHT_PINK to "Pink",
                            HIGHLIGHT_BLUE to "Blue"
                        ),
                        selected = config.markupColour,
                        onSelect = { value -> onChange { it.copy(markupColour = value) } }
                    )
                }
                Hint(
                    "Marks are drawn into the page rather than added as notes, so they are " +
                        "still there in any other reader - and cannot be peeled off again. " +
                        "The document you chose is never changed either way."
                )
            }

            ToolId.FORMS -> {
                ChoiceRow(
                    options = listOf(
                        false to "Leave it editable",
                        true to "Seal the answers in"
                    ),
                    selected = config.flattenForm,
                    onSelect = { value -> onChange { it.copy(flattenForm = value) } }
                )
                Hint(
                    if (config.flattenForm) {
                        "Sealing draws the answers into the page and removes the fields, so " +
                            "nobody can retype them. It cannot be undone, and the editable " +
                            "original is untouched - Sheaf never writes to the file you chose."
                    } else {
                        "The result stays a form, so the answers can be changed later by " +
                            "anyone who opens it."
                    }
                )
            }

            ToolId.N_UP -> {
                SectionHeading("Pages per sheet")
                ChoiceRow(
                    options = listOf(2 to "2", 4 to "4", 6 to "6", 8 to "8", 9 to "9", 16 to "16"),
                    selected = config.perSheet,
                    onSelect = { value -> onChange { it.copy(perSheet = value) } }
                )
                SectionHeading("Sheet")
                ChoiceRow(
                    options = listOf(
                        PageSpec.Size.A4 to "A4",
                        PageSpec.Size.LETTER to "Letter",
                        PageSpec.Size.A3 to "A3",
                        PageSpec.Size.LEGAL to "Legal"
                    ),
                    selected = config.nUpPaper,
                    onSelect = { value -> onChange { it.copy(nUpPaper = value) } }
                )
                Hint(
                    "The sheet is turned whichever way shows the pages biggest: two upright " +
                        "pages side by side on a sideways sheet, two wide slides one above the other."
                )
            }

            ToolId.SPLIT_BY_SIZE -> {
                SectionHeading("Each part under")
                ChoiceRow(
                    options = listOf(2 to "2 MB", 5 to "5 MB", 10 to "10 MB", 25 to "25 MB"),
                    selected = config.partMb,
                    onSelect = { value -> onChange { it.copy(partMb = value) } }
                )
                NumberField(
                    value = config.partMb,
                    label = "Or any size, in MB",
                    min = 1,
                    max = 2000,
                    onChange = { value -> onChange { it.copy(partMb = value) } }
                )
                Hint(
                    "Each part is measured by saving it, so none comes out over the limit - " +
                        "except a single page that is bigger than the limit on its own, which " +
                        "becomes its own part and is marked."
                )
            }

            ToolId.METADATA -> {
                ChoiceRow(
                    options = listOf(
                        ToolConfig.MetaMode.EDIT to "Edit the details",
                        ToolConfig.MetaMode.CLEAN to "Clean hidden data",
                        ToolConfig.MetaMode.STRIP to "Blank the details"
                    ),
                    selected = config.metaMode,
                    onSelect = { value -> onChange { it.copy(metaMode = value) } }
                )
                when (config.metaMode) {
                    ToolConfig.MetaMode.STRIP -> Hint(
                        "Clears the title, author, subject, keywords, the app that made it and " +
                            "the dates. A scanner writes its make and model into a PDF; a word " +
                            "processor writes the name it is licensed to."
                    )
                    ToolConfig.MetaMode.CLEAN -> {
                        Hint(
                            "Takes out what a file gives away before it is sent: the details and " +
                                "their hidden XMP copy, page thumbnails and private editing data, and " +
                                "whichever of these you choose. The result says what was found."
                        )
                        ToggleRow(
                            title = "Scripts and open actions",
                            checked = config.cleanScripts,
                            onChange = { on -> onChange { it.copy(cleanScripts = on) } }
                        )
                        ToggleRow(
                            title = "Attached files",
                            checked = config.cleanAttachments,
                            onChange = { on -> onChange { it.copy(cleanAttachments = on) } }
                        )
                        ToggleRow(
                            title = "Comments and notes",
                            checked = config.cleanComments,
                            onChange = { on -> onChange { it.copy(cleanComments = on) } }
                        )
                    }
                    ToolConfig.MetaMode.EDIT -> {
                        Hint("These are the document's current details. Edit whichever you want to change.")
                        Field(
                            value = config.metaTitle,
                            label = "Title",
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(metaTitle = value) } }
                        )
                        Field(
                            value = config.metaAuthor,
                            label = "Author",
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(metaAuthor = value) } }
                        )
                        Field(
                            value = config.metaSubject,
                            label = "Subject",
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(metaSubject = value) } }
                        )
                        Field(
                            value = config.metaKeywords,
                            label = "Keywords",
                            keyboardType = KeyboardType.Text,
                            onChange = { value -> onChange { it.copy(metaKeywords = value) } }
                        )
                    }
                }
            }

            ToolId.EXTRACT_IMAGES -> Hint(
                "This pulls out the pictures already inside the document at their original " +
                    "size. For pictures of the pages themselves, use PDF to images."
            )

            ToolId.REDACT -> Hint(
                "Draw a box over anything that should go. The marked pages are rebuilt as " +
                    "images, so what was underneath is genuinely gone rather than covered - " +
                    "and those pages stop being selectable text. Other pages are untouched."
            )

            // These have their own canvases on the tool screen rather than plain settings.
            ToolId.SCAN -> Unit

            ToolId.ORGANISE -> Unit
        }
    }
}

@Composable
private fun PageSpecField(
    value: String,
    pageCount: Int?,
    label: String,
    hint: String = "Type page numbers like 1-3, 7, 12-",
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Field(
            value = value,
            label = label,
            keyboardType = KeyboardType.Text,
            onChange = onChange
        )
        Hint(if (pageCount != null) "$hint. This document has $pageCount pages." else hint)
    }
}

@Composable
private fun NumberField(value: Int, label: String, min: Int, max: Int, onChange: (Int) -> Unit) {
    Field(
        value = value.toString(),
        label = label,
        keyboardType = KeyboardType.Number,
        onChange = { text ->
            // An empty field while typing must not snap back to a number, so blank maps to the
            // minimum rather than being rejected outright.
            val parsed = text.filter { it.isDigit() }.toIntOrNull() ?: min
            onChange(parsed.coerceIn(min, maxOf(min, max)))
        }
    )
}

/** A length in millimetres, typed. Blank reads as nothing off that edge. */
@Composable
private fun MillimetreField(edge: String, value: Float, onChange: (Float) -> Unit) {
    Field(
        value = if (value == 0f) "" else if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString(),
        label = "$edge, mm",
        keyboardType = KeyboardType.Decimal,
        onChange = { text ->
            val parsed = text.replace(',', '.').filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 0f
            onChange(parsed.coerceIn(0f, 500f))
        }
    )
}

/** Also used by the per-document unlock prompt on the tool screen. */
@Composable
fun PasswordField(value: String, label: String = "Password", onChange: (String) -> Unit) {
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
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = SheafColors.Band,
            unfocusedBorderColor = SheafColors.Border,
            focusedTextColor = SheafColors.Text,
            unfocusedTextColor = SheafColors.Text,
            cursorColor = SheafColors.Band,
            focusedLabelColor = SheafColors.Band,
            unfocusedLabelColor = SheafColors.Dim
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
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SheafColors.Dim
    )
}
