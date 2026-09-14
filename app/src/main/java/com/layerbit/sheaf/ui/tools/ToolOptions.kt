package com.layerbit.sheaf.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.layerbit.sheaf.pdf.PageNumberSpec
import com.layerbit.sheaf.pdf.PageSpec
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
            ToolId.EXTRACT -> PageSpecField(
                value = config.pageSpec,
                pageCount = pageCount,
                label = "Pages to keep",
                onChange = { spec -> onChange { it.copy(pageSpec = spec) } }
            )

            ToolId.SPLIT -> {
                SectionHeading("How to split")
                ChoiceRow(
                    options = listOf(
                        ToolConfig.SplitMode.EVERY_N to "Every N pages",
                        ToolConfig.SplitMode.EACH_PAGE to "Single pages",
                        ToolConfig.SplitMode.RANGES to "By ranges"
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
                        PageSpec.Size.FIT_IMAGE to "Fit the image"
                    ),
                    selected = config.pageSize,
                    onSelect = { size -> onChange { it.copy(pageSize = size) } }
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
                SectionHeading("Strength")
                ChoiceRow(
                    options = listOf(0.10f to "Faint", 0.18f to "Normal", 0.30f to "Strong"),
                    selected = config.watermarkOpacity,
                    onSelect = { value -> onChange { it.copy(watermarkOpacity = value) } }
                )
                SectionHeading("Layout")
                ChoiceRow(
                    options = listOf(true to "Repeated", false to "Once, in the middle"),
                    selected = config.watermarkTiled,
                    onSelect = { value -> onChange { it.copy(watermarkTiled = value) } }
                )
                ChoiceRow(
                    options = listOf(true to "Diagonal", false to "Straight"),
                    selected = config.watermarkDiagonal,
                    onSelect = { value -> onChange { it.copy(watermarkDiagonal = value) } }
                )
            }

            ToolId.PAGE_NUMBERS -> {
                SectionHeading("Where")
                ChoiceRow(
                    options = listOf(
                        PageNumberSpec.Position.BOTTOM_CENTRE to "Bottom",
                        PageNumberSpec.Position.BOTTOM_RIGHT to "Bottom right",
                        PageNumberSpec.Position.TOP_RIGHT to "Top right"
                    ),
                    selected = config.numberPosition,
                    onSelect = { value -> onChange { it.copy(numberPosition = value) } }
                )
                SectionHeading("Format")
                ChoiceRow(
                    options = listOf(
                        "{n}" to "1",
                        "Page {n}" to "Page 1",
                        "{n} of {total}" to "1 of 9"
                    ),
                    selected = config.numberFormat,
                    onSelect = { value -> onChange { it.copy(numberFormat = value) } }
                )
                NumberField(
                    value = config.numberSkipFirst,
                    label = "Leave this many pages unnumbered",
                    min = 0,
                    max = (pageCount ?: 9999) - 1,
                    onChange = { value -> onChange { it.copy(numberSkipFirst = value) } }
                )
                ChoiceRow(
                    options = listOf(false to "Plain numbers", true to "Bates (000001)"),
                    selected = config.bates,
                    onSelect = { value -> onChange { it.copy(bates = value) } }
                )
            }

            ToolId.CROP -> {
                SectionHeading("Trim from every edge")
                ChoiceRow(
                    options = listOf(
                        0f to "None",
                        0.03f to "A little",
                        0.06f to "More",
                        0.10f to "A lot"
                    ),
                    selected = config.trim,
                    onSelect = { value -> onChange { it.copy(trim = value) } }
                )
                SectionHeading("Page size")
                ChoiceRow(
                    options = listOf(
                        null to "Leave as it is",
                        PageSpec.Size.A4 to "A4",
                        PageSpec.Size.LETTER to "Letter"
                    ),
                    selected = config.resizeTo,
                    onSelect = { value -> onChange { it.copy(resizeTo = value) } }
                )
                Hint(
                    "Cropping hides the margins rather than deleting them, which is how every " +
                        "PDF reader works. To take something out of a page for good, use " +
                        "Remove areas."
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

            ToolId.N_UP -> {
                SectionHeading("Pages per sheet")
                ChoiceRow(
                    options = listOf(2 to "2", 4 to "4"),
                    selected = config.perSheet,
                    onSelect = { value -> onChange { it.copy(perSheet = value) } }
                )
                Hint("Useful for printing a long document on less paper, or making a booklet.")
            }

            ToolId.SPLIT_BY_SIZE -> {
                SectionHeading("Each part under")
                ChoiceRow(
                    options = listOf(
                        5L * 1024 * 1024 to "5 MB",
                        10L * 1024 * 1024 to "10 MB",
                        25L * 1024 * 1024 to "25 MB"
                    ),
                    selected = config.maxPartBytes,
                    onSelect = { value -> onChange { it.copy(maxPartBytes = value) } }
                )
                Hint(
                    "Sizes are worked out from the average page, so a part can come out a " +
                        "little over. The result screen shows what each one actually is."
                )
            }

            ToolId.METADATA -> {
                ChoiceRow(
                    options = listOf(false to "Edit the details", true to "Strip everything"),
                    selected = config.stripMetadata,
                    onSelect = { value -> onChange { it.copy(stripMetadata = value) } }
                )
                if (config.stripMetadata) {
                    Hint(
                        "Clears the title, author, subject, keywords, the app that made it and " +
                            "the dates. A scanner writes its make and model into a PDF; a word " +
                            "processor writes the name it is licensed to."
                    )
                } else {
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

/** A row of mutually exclusive choices. Wraps rather than scrolling, so nothing hides offscreen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val shape = RoundedCornerShape(999.dp)
            Row(
                modifier = Modifier
                    .background(if (active) SheafColors.BandDim else SheafColors.SurfaceDim, shape)
                    .border(1.dp, if (active) SheafColors.Band else SheafColors.Border, shape)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) SheafColors.BandBright else SheafColors.Muted
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = SheafColors.Dim
    )
}
