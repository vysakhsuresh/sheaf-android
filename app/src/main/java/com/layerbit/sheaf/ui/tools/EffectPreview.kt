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
                .clip(RoundedCornerShape(6.dp))
                .background(SheafColors.Paper)
                .border(1.dp, SheafColors.Border, RoundedCornerShape(6.dp))
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
                CircularProgressIndicator(color = SheafColors.Band, strokeWidth = 2.dp)
            } else {
                Image(
                    bitmap = page.asImageBitmap(),
                    contentDescription = "Preview of the first page",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                when (tool) {
                    ToolId.CROP -> CropOverlay(config)
                    ToolId.WATERMARK -> WatermarkOverlay(config)
                    ToolId.PAGE_NUMBERS -> PageNumberOverlay(config)
                    ToolId.SIGN -> SignatureOverlay(config, signature)
                    ToolId.ADD_TEXT -> NoteOverlay(config)
                    else -> Unit
                }
            }
        }

        Text(
            text = when (tool) {
                ToolId.CROP -> "The shaded edges are what gets trimmed away."
                ToolId.WATERMARK -> "Close to how it will look. The document uses Helvetica."
                ToolId.PAGE_NUMBERS -> "Shown on the first page that gets a number."
                ToolId.SIGN -> "Where your signature will sit on the page you chose."
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
private fun CropOverlay(config: ToolConfig) {
    val trim = config.trim
    if (trim <= 0f) return
    val shade = Color.Black.copy(alpha = 0.55f)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        Box(Modifier.fillMaxWidth().height(height * trim).background(shade))
        Box(
            Modifier
                .fillMaxWidth()
                .height(height * trim)
                .align(Alignment.BottomStart)
                .background(shade)
        )
        Box(
            Modifier
                .width(width * trim)
                .fillMaxHeight()
                .align(Alignment.CenterStart)
                .background(shade)
        )
        Box(
            Modifier
                .width(width * trim)
                .fillMaxHeight()
                .align(Alignment.CenterEnd)
                .background(shade)
        )
    }
}

@Composable
private fun WatermarkOverlay(config: ToolConfig) {
    if (config.watermarkText.isBlank()) return
    val ink = Color(0xFF595959).copy(alpha = config.watermarkOpacity.coerceIn(0.05f, 1f))
    val rotation = if (config.watermarkDiagonal) -45f else 0f

    if (config.watermarkTiled) {
        // Three rows is enough to show what tiling looks like without pretending to be the
        // exact spacing the engine uses, which depends on the real font metrics.
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(3) {
                Text(
                    text = "${config.watermarkText}    ${config.watermarkText}",
                    color = ink,
                    fontSize = 15.sp,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().rotate(rotation)
                )
            }
        }
    } else {
        Text(
            text = config.watermarkText,
            color = ink,
            fontSize = 22.sp,
            maxLines = 1,
            modifier = Modifier.rotate(rotation)
        )
    }
}

@Composable
private fun PageNumberOverlay(config: ToolConfig) {
    val label = config.numberFormat
        .replace("{n}", if (config.bates) config.numberStartAt.toString().padStart(6, '0') else config.numberStartAt.toString())
        .replace("{total}", "9")

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
