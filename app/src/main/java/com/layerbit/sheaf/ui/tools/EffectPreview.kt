package com.layerbit.sheaf.ui.tools

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.pdf.ImageStamp
import com.layerbit.sheaf.pdf.PageNumberSpec
import com.layerbit.sheaf.pdf.PageSize
import com.layerbit.sheaf.pdf.WatermarkSpec
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * The first page with the tool's effect drawn over it.
 *
 * Every tool here changes how a page looks, and until now the only way to find out how was to
 * run it and open the result. Seeing it beforehand is the difference between choosing a
 * setting and guessing at one - a 10% trim means nothing as a number and everything as a
 * picture of your own page.
 *
 * It is an approximation, drawn in Compose rather than by the PDF engine, and it says so. The
 * geometry matches what the operation does; the type rendering will differ slightly, because
 * the real one uses Helvetica inside the document.
 */
@Composable
fun EffectPreview(
    tool: ToolId,
    config: ToolConfig,
    page: Bitmap?,
    signature: Bitmap?,
    modifier: Modifier = Modifier,
    /** The page's size in points, when known, so millimetres can be drawn to scale. */
    pageSize: PageSize? = null,
    /**
     * The document is password-protected and has not been unlocked yet. The page cannot be
     * drawn until it is, and nothing will retry on its own, so the preview has to say so.
     */
    locked: Boolean = false,
    /** The engine was asked for the page and could not produce it. Also never retried. */
    previewFailed: Boolean = false,
    /**
     * Where the user tapped, as a fraction of the page. Only tools that place something at a
     * point pass this; for the rest the preview is something to look at rather than to touch.
     */
    onPlace: ((Float, Float) -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeading(if (onPlace != null) "Tap to place · page shown" else "Preview · first page")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .aspectRatio(if (page != null && page.height > 0) page.width.toFloat() / page.height else 0.707f)
                .clip(MaterialTheme.shapes.small)
                .background(SheafColors.Paper)
                .border(1.dp, SheafColors.Border, MaterialTheme.shapes.small)
                .then(
                    if (onPlace == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(onPlace) {
                            detectTapGestures { offset ->
                                if (size.width > 0 && size.height > 0) {
                                    onPlace?.invoke(
                                        (offset.x / size.width).coerceIn(0f, 1f),
                                        (offset.y / size.height).coerceIn(0f, 1f)
                                    )
                                }
                            }
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (page == null) {
                // A spinner means "wait". Neither of these two is a wait: the request has
                // already been made and already finished without a page, and the key it was
                // made under will not change on its own, so nothing is coming. Spinning here
                // is simply untrue, and the user sits in front of it.
                val stalled = when {
                    locked -> "Unlock the document above to see the preview."
                    previewFailed ->
                        "This page could not be drawn, so there is nothing to preview. " +
                            "The tool will still run."
                    else -> null
                }
                if (stalled == null) {
                    CircularProgressIndicator(color = SheafColors.Band, strokeWidth = 2.dp)
                } else {
                    Text(
                        text = stalled,
                        style = MaterialTheme.typography.bodySmall,
                        color = SheafColors.Dim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                Image(
                    bitmap = page.asImageBitmap(),
                    contentDescription = "Preview of the first page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                when (tool) {
                    ToolId.CROP -> CropOverlay(config, pageSize)
                    ToolId.BACKGROUND -> BackgroundOverlay(config)
                    ToolId.WATERMARK -> WatermarkOverlay(config)
                    ToolId.PAGE_NUMBERS -> PageNumberOverlay(config)
                    ToolId.SIGN, ToolId.ADD_IMAGE -> SignatureOverlay(config, signature)
                    ToolId.ADD_TEXT -> NoteOverlay(config)
                    else -> Unit
                }
            }
        }

        Text(
            text = when (tool) {
                ToolId.CROP -> if (config.cropAuto) {
                    "White margins are found page by page when it runs; the preview cannot show them."
                } else {
                    "The shaded edges are what gets trimmed away, to scale."
                }
                ToolId.BACKGROUND -> "Close to how it will look. Scanned pages are solid pictures and will not tint."
                ToolId.WATERMARK -> "Close to how it will look. The document uses Helvetica."
                ToolId.PAGE_NUMBERS -> "Shown on the first page that gets a number."
                ToolId.SIGN -> "Where your signature will sit on the page you chose."
                ToolId.ADD_IMAGE -> "Where the image will sit on the page you chose."
                ToolId.ADD_TEXT -> "Tap the page to move the text. Helvetica, as the document uses."
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = SheafColors.Dim,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** Shades the margins that cropping will hide, so the kept area is the bright part. */
@Composable
private fun CropOverlay(config: ToolConfig, pageSize: PageSize?) {
    if (config.cropAuto) return
    val widthPoints = pageSize?.widthPoints?.takeIf { it > 0f } ?: A4_WIDTH_POINTS
    val heightPoints = pageSize?.heightPoints?.takeIf { it > 0f } ?: A4_HEIGHT_POINTS
    val top = (config.cropTopMm * MM_POINTS / heightPoints).coerceIn(0f, 0.49f)
    val bottom = (config.cropBottomMm * MM_POINTS / heightPoints).coerceIn(0f, 0.49f)
    val left = (config.cropLeftMm * MM_POINTS / widthPoints).coerceIn(0f, 0.49f)
    val right = (config.cropRightMm * MM_POINTS / widthPoints).coerceIn(0f, 0.49f)
    if (top == 0f && bottom == 0f && left == 0f && right == 0f) return
    val shade = Color.Black.copy(alpha = 0.55f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        if (top > 0f) Box(Modifier.fillMaxWidth().height(height * top).background(shade))
        if (bottom > 0f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height * bottom)
                    .align(Alignment.BottomStart)
                    .background(shade)
            )
        }
        if (left > 0f) {
            Box(
                Modifier
                    .width(width * left)
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .background(shade)
            )
        }
        if (right > 0f) {
            Box(
                Modifier
                    .width(width * right)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
                    .background(shade)
            )
        }
    }
}

/** Tints the page the chosen colour, under the page image's own ink. */
@Composable
private fun BackgroundOverlay(config: ToolConfig) {
    // Multiply would be exact; a translucent wash over a rendered page is close enough to
    // judge the colour by, and white paper takes it the same way.
    Box(Modifier.fillMaxSize().background(Color(config.backgroundColour).copy(alpha = 0.55f)))
}

@Composable
private fun WatermarkOverlay(config: ToolConfig) {
    if (config.watermarkText.isBlank()) return
    val ink = Color(config.watermarkColour).copy(alpha = config.watermarkOpacity.coerceIn(0.05f, 1f))

    when (config.watermarkLayout) {
        WatermarkSpec.Layout.TILED -> {
            // Three rows is enough to show what tiling looks like without pretending to be
            // the exact spacing the engine uses, which depends on the real font metrics.
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(4) { row ->
                    Text(
                        text = if (row % 2 == 0) "${config.watermarkText}    ${config.watermarkText}" else "  ${config.watermarkText}    ${config.watermarkText}  ",
                        color = ink,
                        fontSize = 13.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().rotate(-30f)
                    )
                }
            }
        }
        else -> BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // The engine sizes the words to cross the page; the preview does the same from
            // the box's own width, so DRAFT is big and a sentence is small.
            val size = (maxWidth.value * 0.7f / maxOf(4f, config.watermarkText.length * 0.6f))
                .coerceAtMost(maxHeight.value * 0.2f)
            Text(
                text = config.watermarkText,
                color = ink,
                fontSize = size.sp,
                maxLines = 1,
                modifier = Modifier.rotate(if (config.watermarkLayout == WatermarkSpec.Layout.ONCE_DIAGONAL) -45f else 0f)
            )
        }
    }
}

@Composable
private fun PageNumberOverlay(config: ToolConfig) {
    val label = if (config.bates) {
        config.batesPrefix + config.numberStartAt.toString().padStart(config.batesDigits, '0')
    } else {
        config.numberFormat
            .replace("{n}", config.numberStartAt.toString())
            .replace("{total}", "9")
    }

    val alignment = when (config.numberPosition) {
        PageNumberSpec.Position.TOP_LEFT -> Alignment.TopStart
        PageNumberSpec.Position.TOP_CENTRE -> Alignment.TopCenter
        PageNumberSpec.Position.TOP_RIGHT -> Alignment.TopEnd
        PageNumberSpec.Position.BOTTOM_LEFT -> Alignment.BottomStart
        PageNumberSpec.Position.BOTTOM_CENTRE -> Alignment.BottomCenter
        PageNumberSpec.Position.BOTTOM_RIGHT -> Alignment.BottomEnd
    }

    Box(modifier = Modifier.fillMaxSize().padding(10.dp)) {
        Text(
            text = label,
            color = Color.Black,
            fontSize = 10.sp,
            modifier = Modifier.align(alignment)
        )
    }
}

@Composable
private fun SignatureOverlay(config: ToolConfig, signature: Bitmap?) {
    if (signature == null) return

    val alignment = when (config.signAnchor) {
        ImageStamp.Anchor.TOP_LEFT -> Alignment.TopStart
        ImageStamp.Anchor.TOP_CENTRE -> Alignment.TopCenter
        ImageStamp.Anchor.TOP_RIGHT -> Alignment.TopEnd
        ImageStamp.Anchor.MIDDLE_LEFT -> Alignment.CenterStart
        ImageStamp.Anchor.MIDDLE_CENTRE -> Alignment.Center
        ImageStamp.Anchor.MIDDLE_RIGHT -> Alignment.CenterEnd
        ImageStamp.Anchor.BOTTOM_LEFT -> Alignment.BottomStart
        ImageStamp.Anchor.BOTTOM_CENTRE -> Alignment.BottomCenter
        ImageStamp.Anchor.BOTTOM_RIGHT -> Alignment.BottomEnd
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Image(
            bitmap = signature.asImageBitmap(),
            contentDescription = "Signature placement",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(maxWidth * config.signWidth)
                .align(alignment)
        )
    }
}

/**
 * The note where it will land, drawn at roughly the size it will be.
 *
 * Roughly, because the size is in PDF points and the preview is in pixels, and the ratio
 * between them depends on the page's own dimensions. A4 is assumed, which is a few percent out
 * on Letter - close enough to judge placement by, which is what the preview is for.
 */
@Composable
private fun NoteOverlay(config: ToolConfig) {
    if (config.noteText.isBlank()) return
    val ink = Color(config.noteColour)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The preview is as many dp tall as the page is points, so this is the one ratio
        // that turns a type size in the document into a type size on screen.
        val dpPerPoint = maxHeight.value / A4_HEIGHT_POINTS
        Text(
            text = config.noteText,
            color = ink,
            fontSize = (config.noteSize * dpPerPoint).sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(x = maxWidth * config.noteLeft, y = maxHeight * config.noteTop)
                .then(if (config.noteCover) Modifier.background(Color.White) else Modifier)
        )
    }
}

/** The page the preview's type size is reckoned against when the real one is not known. */
private const val A4_HEIGHT_POINTS = 841.89f
private const val A4_WIDTH_POINTS = 595.28f

/** A millimetre, in points. */
private const val MM_POINTS = 72f / 25.4f
