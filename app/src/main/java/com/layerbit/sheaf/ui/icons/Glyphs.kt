package com.layerbit.sheaf.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.layerbit.sheaf.ui.components.LocalLivelyClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Sheaf's icons, drawn by hand so each can act out what its tool does.
 *
 * Kshanam's sun shines and its eye blinks; here the scanner's beam runs down the page, Merge
 * brings two sheets together, the pen writes a signature, the highlighter crosses a line, the
 * lock's shackle snaps shut, a receipt prints, a recipe's steps light up one after another.
 * Each drawing lives on a unit square and is told [t], 0 to 1 over the shared lap; it plays
 * its action a few times a lap, phase-shifted by the icon's seed so a screen of tools takes
 * turns rather than moving in step.
 *
 * With motion off (Android's animations off, or Lively icons switched off in Settings), every
 * drawing is shown at [REST], a frame chosen so the icon still says what the tool does.
 */
enum class Glyph {
    // Tools
    SCAN, OCR, EXTRACT_TEXT, MERGE, SPLIT, EXTRACT, ORGANISE, IMAGES_TO_PDF, PDF_TO_IMAGES,
    COMPRESS, PDF_TO_OFFICE, OFFICE_TO_PDF, WATERMARK, PAGE_NUMBERS, SIGN, ADD_TEXT, ADD_IMAGE,
    ANNOTATE, FORMS, HEADER_FOOTER, INSERT, CROP, BACKGROUND, PAGE_LABELS, BOOKMARKS,
    ATTACHMENTS, N_UP, SPLIT_SIZE, EXTRACT_IMAGES, REDACT, METADATA, EDIT_TEXT, CREATE_FORM,
    DIGITAL_SIGN, ARCHIVE, COMPARE, RECEIPT, LOCK, UNLOCK, RESIZE_PHOTO, EDIT_IMAGES, SUMMARISE,
    RECIPE, READ,
    // Everyday
    GEAR, STAR, SEARCH, INFO, SHARE, SEND, MAIL, HEART, CHECK, PLUS
}

/** How many times a lap each drawing plays its action: about every three seconds. */
private const val PLAYS = 4f

/** The frame shown when nothing moves. */
private const val REST = 0.72f

/**
 * One drawn icon. [seed] shifts its timing; the same tool always gets the same one, so it
 * moves at the same moment wherever it appears.
 */
@Composable
fun AnimatedGlyph(glyph: Glyph, color: Color, modifier: Modifier = Modifier, seed: Int = 0) {
    val clock = LocalLivelyClock.current
    Canvas(modifier) {
        // Read inside the draw lambda only: the clock ticking redraws the picture and never
        // recomposes whatever holds it.
        val x = if (clock == null) REST else frac((clock.value + seed * 0.0613f) * PLAYS)
        val lap = if (clock == null) REST else frac(clock.value + seed * 0.0613f)
        drawGlyph(glyph, color, x, lap)
    }
}

/** Draws [glyph] at phase [x] (0..1 through one play of its action); [lap] is the slow clock. */
fun DrawScope.drawGlyph(glyph: Glyph, c: Color, x: Float, lap: Float = x) {
    when (glyph) {
        Glyph.SCAN -> scan(c, x)
        Glyph.OCR -> ocr(c, x)
        Glyph.EXTRACT_TEXT -> extractText(c, x)
        Glyph.MERGE -> merge(c, x)
        Glyph.SPLIT -> split(c, x)
        Glyph.EXTRACT -> extract(c, x)
        Glyph.ORGANISE -> organise(c, x)
        Glyph.IMAGES_TO_PDF -> imagesToPdf(c, x)
        Glyph.PDF_TO_IMAGES -> pdfToImages(c, x)
        Glyph.COMPRESS -> compress(c, x)
        Glyph.PDF_TO_OFFICE -> pdfToOffice(c, x)
        Glyph.OFFICE_TO_PDF -> officeToPdf(c, x)
        Glyph.WATERMARK -> watermark(c, x)
        Glyph.PAGE_NUMBERS -> pageNumbers(c, x)
        Glyph.SIGN -> sign(c, x)
        Glyph.ADD_TEXT -> addText(c, x)
        Glyph.ADD_IMAGE -> addImage(c, x)
        Glyph.ANNOTATE -> annotate(c, x)
        Glyph.FORMS -> forms(c, x)
        Glyph.HEADER_FOOTER -> headerFooter(c, x)
        Glyph.INSERT -> insert(c, x)
        Glyph.CROP -> crop(c, x)
        Glyph.BACKGROUND -> background(c, x)
        Glyph.PAGE_LABELS -> pageLabels(c, x)
        Glyph.BOOKMARKS -> bookmarks(c, x)
        Glyph.ATTACHMENTS -> attachments(c, x)
        Glyph.N_UP -> nUp(c, x)
        Glyph.SPLIT_SIZE -> splitSize(c, x)
        Glyph.EXTRACT_IMAGES -> extractImages(c, x)
        Glyph.REDACT -> redact(c, x)
        Glyph.METADATA -> metadata(c, x, lap)
        Glyph.EDIT_TEXT -> editText(c, x)
        Glyph.CREATE_FORM -> createForm(c, x)
        Glyph.DIGITAL_SIGN -> digitalSign(c, x, lap)
        Glyph.ARCHIVE -> archive(c, x)
        Glyph.COMPARE -> compare(c, x)
        Glyph.RECEIPT -> receipt(c, x)
        Glyph.LOCK -> lock(c, x)
        Glyph.UNLOCK -> unlock(c, x)
        Glyph.RESIZE_PHOTO -> resizePhoto(c, x)
        Glyph.EDIT_IMAGES -> editImages(c, x)
        Glyph.SUMMARISE -> summarise(c, x)
        Glyph.RECIPE -> recipe(c, x)
        Glyph.READ -> read(c, x)
        Glyph.GEAR -> gear(c, lap)
        Glyph.STAR -> star(c, x, lap)
        Glyph.SEARCH -> search(c, x)
        Glyph.INFO -> info(c, x)
        Glyph.SHARE -> share(c, x)
        Glyph.SEND -> send(c, x)
        Glyph.MAIL -> mail(c, x)
        Glyph.HEART -> heart(c, x)
        Glyph.CHECK -> check(c, x)
        Glyph.PLUS -> plus(c, x)
    }
}

// ---------------------------------------------------------------- timing and drawing helpers

private const val TAU = (2 * PI).toFloat()
private fun frac(v: Float) = v - floor(v)

/** 0 → 1 between [a] and [b] of the play, eased at both ends. */
private fun seg(x: Float, a: Float, b: Float): Float {
    val c = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return c * c * (3 - 2 * c)
}

/** Up between [a] and [b], held, then down between [c] and [d]. */
private fun hold(x: Float, a: Float, b: Float, c: Float, d: Float) = seg(x, a, b) * (1f - seg(x, c, d))
private fun bump(v: Float) = sin(v.coerceIn(0f, 1f) * PI.toFloat())
private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

private fun DrawScope.s() = size.minDimension
private fun DrawScope.p(x: Float, y: Float) = Offset(x * s(), y * s())
private fun DrawScope.sz(w: Float, h: Float) = Size(w * s(), h * s())
private fun DrawScope.line() = s() * 0.075f
private fun DrawScope.thin() = s() * 0.055f
private fun DrawScope.stroke(w: Float = line()) = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** A sheet of paper: outline, a faint fill, and the folded corner. */
private fun DrawScope.page(c: Color, l: Float, t: Float, w: Float, h: Float, alpha: Float = 1f, fill: Float = 0.16f) {
    if (alpha <= 0f) return
    val fold = minOf(w, h) * 0.28f
    val path = Path().apply {
        moveTo(l * s(), t * s())
        lineTo((l + w - fold) * s(), t * s())
        lineTo((l + w) * s(), (t + fold) * s())
        lineTo((l + w) * s(), (t + h) * s())
        lineTo(l * s(), (t + h) * s())
        close()
    }
    drawPath(path, c.copy(alpha = fill * alpha))
    drawPath(path, c.copy(alpha = alpha), style = stroke())
    val corner = Path().apply {
        moveTo((l + w - fold) * s(), t * s())
        lineTo((l + w - fold) * s(), (t + fold) * s())
        lineTo((l + w) * s(), (t + fold) * s())
    }
    drawPath(corner, c.copy(alpha = alpha), style = stroke(thin()))
}

/** Lines of text on a page. */
private fun DrawScope.textLines(c: Color, l: Float, t: Float, w: Float, rows: Int, gap: Float = 0.11f, alpha: Float = 0.9f) {
    for (i in 0 until rows) {
        val len = if (i == rows - 1) w * 0.6f else w
        drawLine(c.copy(alpha = alpha), p(l, t + gap * i), p(l + len, t + gap * i), thin(), StrokeCap.Round)
    }
}

/** A small photograph: frame, a hill and a sun. */
private fun DrawScope.photo(c: Color, l: Float, t: Float, w: Float, h: Float, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawRoundRect(c.copy(alpha = 0.2f * alpha), p(l, t), sz(w, h), CornerRadius(s() * 0.04f))
    drawRoundRect(c.copy(alpha = alpha), p(l, t), sz(w, h), CornerRadius(s() * 0.04f), style = stroke(thin()))
    val hill = Path().apply {
        moveTo(l * s(), (t + h) * s())
        lineTo((l + w * 0.38f) * s(), (t + h * 0.45f) * s())
        lineTo((l + w * 0.62f) * s(), (t + h * 0.72f) * s())
        lineTo((l + w * 0.78f) * s(), (t + h * 0.58f) * s())
        lineTo((l + w) * s(), (t + h) * s())
        close()
    }
    drawPath(hill, c.copy(alpha = alpha))
    drawCircle(c.copy(alpha = alpha), w * 0.11f * s(), p(l + w * 0.72f, t + h * 0.28f))
}

/** A four-pointed sparkle, for the moment something is done. */
private fun DrawScope.sparkle(c: Color, at: Offset, r: Float) {
    if (r <= 0f) return
    val path = Path().apply {
        moveTo(at.x, at.y - r)
        quadraticBezierTo(at.x, at.y, at.x + r, at.y)
        quadraticBezierTo(at.x, at.y, at.x, at.y + r)
        quadraticBezierTo(at.x, at.y, at.x - r, at.y)
        quadraticBezierTo(at.x, at.y, at.x, at.y - r)
        close()
    }
    drawPath(path, c)
}

/** A soft round glow. */
private fun DrawScope.glow(c: Color, at: Offset, r: Float, alpha: Float) {
    if (alpha <= 0f || r <= 0f) return
    drawCircle(Brush.radialGradient(listOf(c.copy(alpha = alpha), Color.Transparent), at, r), r, at)
}

/** Text drawn in the icon's colour: digits, a letter, a currency sign. */
private fun DrawScope.label(text: String, c: Color, at: Offset, size: Float, bold: Boolean = true, alpha: Float = 1f) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = c.copy(alpha = alpha * c.alpha).toArgb()
        textSize = size
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
    }
    drawContext.canvas.nativeCanvas.drawText(text, at.x, at.y + size * 0.36f, paint)
}

/** The part of [path] from 0 to [f] of its length: how a pen line is drawn as it goes. */
private fun partial(path: Path, f: Float): Path {
    val m = PathMeasure()
    m.setPath(path, false)
    val out = Path()
    m.getSegment(0f, m.length * f.coerceIn(0f, 1f), out, true)
    return out
}

private fun DrawScope.arrow(c: Color, from: Offset, to: Offset, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawLine(c.copy(alpha = alpha), from, to, thin(), StrokeCap.Round)
    val a = kotlin.math.atan2(to.y - from.y, to.x - from.x)
    val h = s() * 0.09f
    for (d in listOf(0.45f, -0.45f)) {
        val back = a + PI.toFloat() + d
        drawLine(c.copy(alpha = alpha), to, Offset(to.x + h * cos(back), to.y + h * sin(back)), thin(), StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------- the tools

/** A page in a viewfinder; a beam of light sweeps down it, lighting the lines it passes. */
private fun DrawScope.scan(c: Color, x: Float) {
    val k = s() * 0.16f
    val inset = 0.06f + 0.02f * bump(seg(x, 0f, 0.15f))
    val corners = listOf(inset to inset, 1 - inset to inset, inset to 1 - inset, 1 - inset to 1 - inset)
    for ((cx, cy) in corners) {
        val dx = if (cx < 0.5f) k else -k
        val dy = if (cy < 0.5f) k else -k
        drawLine(c, p(cx, cy), Offset(cx * s() + dx, cy * s()), line(), StrokeCap.Round)
        drawLine(c, p(cx, cy), Offset(cx * s(), cy * s() + dy), line(), StrokeCap.Round)
    }
    page(c, 0.26f, 0.2f, 0.48f, 0.6f, alpha = 0.7f, fill = 0.1f)
    val beam = lerp(0.2f, 0.8f, seg(x, 0.1f, 0.8f))
    val fade = 1f - seg(x, 0.82f, 1f)
    for (i in 0 until 4) {
        val y = 0.36f + 0.11f * i
        val lit = y < beam
        drawLine(c.copy(alpha = if (lit) 0.95f else 0.3f), p(0.33f, y), p(if (i == 3) 0.52f else 0.67f, y), thin(), StrokeCap.Round)
    }
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, c.copy(alpha = 0.35f * fade)), (beam - 0.18f) * s(), beam * s()),
        p(0.16f, beam - 0.18f), sz(0.68f, 0.18f)
    )
    drawLine(c.copy(alpha = fade), p(0.14f, beam), p(0.86f, beam), line(), StrokeCap.Round)
}

/** A magnifier reads along the lines; each line it has read stands out. */
private fun DrawScope.ocr(c: Color, x: Float) {
    page(c, 0.16f, 0.1f, 0.6f, 0.78f)
    val rows = 3
    val pos = seg(x, 0.05f, 0.85f) * rows
    val row = pos.toInt().coerceAtMost(rows - 1)
    val along = pos - row
    for (i in 0 until rows) {
        val y = 0.36f + 0.15f * i
        val f = if (i < row) 1f else if (i == row) along else 0f
        drawLine(c.copy(alpha = 0.35f), p(0.26f, y), p(0.62f, y), thin(), StrokeCap.Round)
        if (f > 0f) drawLine(c, p(0.26f, y), p(lerp(0.26f, 0.62f, f), y), line(), StrokeCap.Round)
    }
    val y = 0.36f + 0.15f * row
    val mx = lerp(0.3f, 0.62f, along.coerceIn(0f, 1f))
    val center = p(mx, y - 0.02f)
    val r = s() * 0.13f
    glow(c, center, r * 1.4f, 0.3f)
    drawCircle(c.copy(alpha = 0.15f), r, center)
    drawCircle(c, r, center, style = stroke())
    drawLine(c, Offset(center.x + r * 0.7f, center.y + r * 0.7f), Offset(center.x + r * 1.5f, center.y + r * 1.5f), line() * 1.3f, StrokeCap.Round)
}

/** Lines of text lift off the page one by one and float away to the right. */
private fun DrawScope.extractText(c: Color, x: Float) {
    page(c, 0.1f, 0.12f, 0.5f, 0.76f)
    for (i in 0 until 3) {
        val y = 0.36f + 0.14f * i
        val start = 0.08f + i * 0.22f
        val f = seg(x, start, start + 0.3f)
        val back = seg(x, 0.88f, 1f)
        drawLine(c.copy(alpha = 0.3f), p(0.18f, y), p(0.5f, y), thin(), StrokeCap.Round)
        val dx = 0.4f * f * (1f - back)
        val dy = -0.06f * bump(f) * (1f - back)
        val a = if (back > 0f) back else 1f - 0.4f * f
        drawLine(c.copy(alpha = a), p(0.18f + dx, y + dy), p(0.5f + dx, y + dy), line(), StrokeCap.Round)
    }
}

/** Two sheets slide together and become one; a spark marks the join. */
private fun DrawScope.merge(c: Color, x: Float) {
    val f = seg(x, 0.05f, 0.55f)
    val apart = 1f - f
    val back = seg(x, 0.85f, 1f)
    val offset = 0.17f * (apart + back * 0f)
    rotate(-14f * apart, p(0.3f, 0.5f)) { page(c, 0.26f - offset, 0.18f, 0.4f, 0.56f, alpha = 0.85f) }
    rotate(14f * apart, p(0.7f, 0.5f)) { page(c, 0.34f + offset, 0.26f, 0.4f, 0.56f) }
    val spark = hold(x, 0.5f, 0.6f, 0.7f, 0.85f)
    sparkle(c, p(0.8f, 0.2f), s() * 0.12f * spark)
    glow(c, p(0.54f, 0.54f), s() * 0.4f, 0.25f * spark)
}

/** A dashed cut runs down a page and the halves part. */
private fun DrawScope.split(c: Color, x: Float) {
    val cut = seg(x, 0.05f, 0.4f)
    val part = hold(x, 0.42f, 0.62f, 0.85f, 1f)
    val gap = 0.1f * part
    clipRect(right = (0.5f - gap / 2) * s() + 0.001f) { translate(left = -gap / 2 * s()) { page(c, 0.2f, 0.12f, 0.6f, 0.76f) } }
    clipRect(left = (0.5f + gap / 2) * s()) { translate(left = gap / 2 * s()) { page(c, 0.2f, 0.12f, 0.6f, 0.76f) } }
    if (part < 0.05f && cut > 0f) {
        drawLine(
            c, p(0.5f, 0.05f), p(0.5f, lerp(0.05f, 0.95f, cut)), thin(), StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(s() * 0.06f, s() * 0.05f))
        )
    }
}

/** From a stack of pages, the top one lifts out and away. */
private fun DrawScope.extract(c: Color, x: Float) {
    page(c, 0.3f, 0.22f, 0.5f, 0.64f, alpha = 0.5f)
    page(c, 0.24f, 0.18f, 0.5f, 0.64f, alpha = 0.75f)
    val f = seg(x, 0.1f, 0.6f)
    val back = seg(x, 0.8f, 1f)
    val lift = f * (1f - back)
    val a = if (back > 0f) back else 1f - 0.5f * f
    translate(left = 0.16f * lift * s(), top = -0.12f * lift * s()) {
        rotate(10f * lift, p(0.43f, 0.46f)) { page(c, 0.18f, 0.14f, 0.5f, 0.64f, alpha = a) }
    }
    arrow(c, p(0.72f, 0.3f), p(0.9f, 0.1f), alpha = lift)
}

/** Four pages in a grid; two of them swap places. */
private fun DrawScope.organise(c: Color, x: Float) {
    val cells = listOf(0.1f to 0.1f, 0.54f to 0.1f, 0.1f to 0.54f, 0.54f to 0.54f)
    val f = seg(x, 0.15f, 0.65f)
    val back = seg(x, 0.82f, 1f)
    val swap = f * (1f - back)
    for ((i, cell) in cells.withIndex()) {
        if (i == 0 || i == 3) continue
        page(c, cell.first, cell.second, 0.36f, 0.36f, alpha = 0.6f)
    }
    val a = cells[0]; val b = cells[3]
    val arc = bump(swap) * 0.14f
    val ax = lerp(a.first, b.first, swap) + arc; val ay = lerp(a.second, b.second, swap) - arc
    val bx = lerp(b.first, a.first, swap) - arc; val by = lerp(b.second, a.second, swap) + arc
    page(c, bx, by, 0.36f, 0.36f)
    page(c, ax, ay, 0.36f, 0.36f, fill = 0.4f)
}

/** A photograph flies into a page and becomes part of it. */
private fun DrawScope.imagesToPdf(c: Color, x: Float) {
    page(c, 0.42f, 0.14f, 0.5f, 0.72f)
    val f = seg(x, 0.1f, 0.6f)
    val out = seg(x, 0.85f, 1f)
    val k = lerp(1f, 0.62f, f)
    val l = lerp(0.04f, 0.5f, f); val t = lerp(0.36f, 0.42f, f)
    photo(c, l, t, 0.38f * k, 0.3f * k, alpha = 1f - out)
    sparkle(c, p(0.86f, 0.24f), s() * 0.09f * hold(x, 0.55f, 0.65f, 0.75f, 0.85f))
}

/** A page sends out its pictures, fanned like prints. */
private fun DrawScope.pdfToImages(c: Color, x: Float) {
    page(c, 0.06f, 0.14f, 0.48f, 0.72f)
    textLines(c, 0.14f, 0.42f, 0.3f, 3, alpha = 0.5f)
    for (i in 0 until 2) {
        val f = seg(x, 0.1f + 0.2f * i, 0.5f + 0.2f * i) * (1f - seg(x, 0.85f, 1f))
        val l = lerp(0.16f, 0.56f, f); val t = lerp(0.3f, 0.12f + 0.38f * i, f)
        rotate(lerp(0f, -10f + 20f * i, f), p(l + 0.18f, t + 0.14f)) { photo(c, l, t, 0.36f, 0.28f, alpha = 0.3f + 0.7f * f) }
    }
}

/** Two arrows press a page; it squashes smaller, then springs back. */
private fun DrawScope.compress(c: Color, x: Float) {
    val f = hold(x, 0.1f, 0.4f, 0.7f, 0.95f)
    val h = lerp(0.56f, 0.34f, f)
    val top = 0.5f - h / 2
    page(c, 0.26f, top, 0.48f, h)
    arrow(c, p(0.5f, 0.02f), p(0.5f, top - 0.05f + 0.02f))
    arrow(c, p(0.5f, 0.98f), p(0.5f, top + h + 0.05f - 0.02f))
    textLines(c, 0.34f, top + h * 0.38f, 0.3f, 2, gap = h * 0.28f, alpha = 0.6f)
}

/** A page turns over and comes back as a Word page. */
private fun DrawScope.pdfToOffice(c: Color, x: Float) = flipBetween(c, x, front = { pdfFace(c) }, back = { letterFace(c, "W") })

/** A spreadsheet turns over and comes back as a PDF page. */
private fun DrawScope.officeToPdf(c: Color, x: Float) = flipBetween(c, x, front = { sheetFace(c) }, back = { pdfFace(c) })

private fun DrawScope.flipBetween(c: Color, x: Float, front: DrawScope.() -> Unit, back: DrawScope.() -> Unit) {
    val turn = seg(x, 0.2f, 0.6f) // 0 = front, 1 = back
    val ret = seg(x, 0.85f, 1f)
    val angle = (turn * (1f - ret)) * PI.toFloat()
    val k = abs(cos(angle)).coerceAtLeast(0.02f)
    scale(k, 1f, p(0.5f, 0.5f)) { if (angle < PI / 2) front() else back() }
}

private fun DrawScope.pdfFace(c: Color) {
    page(c, 0.2f, 0.1f, 0.6f, 0.8f)
    textLines(c, 0.3f, 0.42f, 0.4f, 3, alpha = 0.7f)
}

private fun DrawScope.letterFace(c: Color, letter: String) {
    page(c, 0.2f, 0.1f, 0.6f, 0.8f, fill = 0.3f)
    label(letter, c, p(0.5f, 0.54f), s() * 0.4f)
}

private fun DrawScope.sheetFace(c: Color) {
    drawRoundRect(c.copy(alpha = 0.2f), p(0.16f, 0.16f), sz(0.68f, 0.68f), CornerRadius(s() * 0.06f))
    drawRoundRect(c, p(0.16f, 0.16f), sz(0.68f, 0.68f), CornerRadius(s() * 0.06f), style = stroke())
    for (i in 1..2) {
        drawLine(c, p(0.16f + 0.227f * i, 0.16f), p(0.16f + 0.227f * i, 0.84f), thin())
        drawLine(c, p(0.16f, 0.16f + 0.227f * i), p(0.84f, 0.16f + 0.227f * i), thin())
    }
}

/** A faint stamp fades in across the page, and back out. */
private fun DrawScope.watermark(c: Color, x: Float) {
    page(c, 0.18f, 0.1f, 0.64f, 0.8f)
    textLines(c, 0.28f, 0.34f, 0.44f, 4, alpha = 0.35f)
    val f = hold(x, 0.1f, 0.4f, 0.7f, 0.95f)
    rotate(-32f, p(0.5f, 0.52f)) {
        drawRoundRect(c.copy(alpha = 0.8f * f), p(0.14f, 0.44f), sz(0.72f, 0.16f), CornerRadius(s() * 0.08f), style = stroke(thin()))
        label("DRAFT", c, p(0.5f, 0.52f), s() * 0.12f, alpha = 0.85f * f)
    }
}

/** The number at the foot of the page counts up as the pages go by. */
private fun DrawScope.pageNumbers(c: Color, x: Float) {
    val step = (x * 3f).toInt().coerceIn(0, 2)
    val within = frac(x * 3f)
    val turn = seg(within, 0.75f, 1f)
    page(c, 0.18f, 0.08f, 0.64f, 0.84f)
    textLines(c, 0.28f, 0.28f, 0.44f, 3, alpha = 0.4f)
    drawRoundRect(c, p(0.36f, 0.66f), sz(0.28f, 0.18f), CornerRadius(s() * 0.09f))
    clipRect(top = 0.66f * s(), bottom = 0.84f * s()) {
        label("${step + 1}", Color.White, p(0.5f, 0.75f - 0.18f * turn), s() * 0.15f)
        label("${(step + 1) % 3 + 1}", Color.White, p(0.5f, 0.93f - 0.18f * turn), s() * 0.15f)
    }
}

/** A pen writes a signature along the line. */
private fun DrawScope.sign(c: Color, x: Float) {
    drawLine(c.copy(alpha = 0.5f), p(0.08f, 0.78f), p(0.92f, 0.78f), thin(), StrokeCap.Round)
    val sig = Path().apply {
        moveTo(0.12f * s(), 0.62f * s())
        cubicTo(0.18f * s(), 0.28f * s(), 0.28f * s(), 0.3f * s(), 0.24f * s(), 0.62f * s())
        cubicTo(0.22f * s(), 0.72f * s(), 0.38f * s(), 0.4f * s(), 0.44f * s(), 0.56f * s())
        cubicTo(0.48f * s(), 0.68f * s(), 0.56f * s(), 0.38f * s(), 0.62f * s(), 0.54f * s())
        cubicTo(0.66f * s(), 0.64f * s(), 0.74f * s(), 0.5f * s(), 0.86f * s(), 0.46f * s())
    }
    val f = seg(x, 0.05f, 0.7f)
    val fade = 1f - seg(x, 0.88f, 1f)
    val written = partial(sig, f)
    drawPath(written, c.copy(alpha = fade), style = stroke())
    val m = PathMeasure().apply { setPath(sig, false) }
    val tip = m.getPosition(m.length * f)
    // The pen, tilted, its nib at the end of the ink.
    rotate(35f, tip) {
        drawRoundRect(c, Offset(tip.x - s() * 0.05f, tip.y - s() * 0.3f), sz(0.1f, 0.22f), CornerRadius(s() * 0.04f))
        val nib = Path().apply {
            moveTo(tip.x - s() * 0.05f, tip.y - s() * 0.08f)
            lineTo(tip.x + s() * 0.05f, tip.y - s() * 0.08f)
            lineTo(tip.x, tip.y)
            close()
        }
        drawPath(nib, c)
    }
}

/** A text box: a T, the cursor blinking, and words appearing after it. */
private fun DrawScope.addText(c: Color, x: Float) {
    drawRoundRect(
        c, p(0.08f, 0.22f), sz(0.84f, 0.56f), CornerRadius(s() * 0.06f),
        style = Stroke(thin(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(s() * 0.07f, s() * 0.05f)))
    )
    label("T", c, p(0.26f, 0.5f), s() * 0.34f)
    val typed = seg(x, 0.1f, 0.7f) * (1f - seg(x, 0.88f, 1f))
    val end = lerp(0.44f, 0.82f, typed)
    if (typed > 0f) drawLine(c, p(0.44f, 0.4f), p(end, 0.4f), thin(), StrokeCap.Round)
    val second = seg(x, 0.45f, 0.75f) * (1f - seg(x, 0.88f, 1f))
    if (second > 0f) drawLine(c, p(0.44f, 0.6f), p(lerp(0.44f, 0.7f, second), 0.6f), thin(), StrokeCap.Round)
    val cursorX = if (second > 0f) lerp(0.44f, 0.7f, second) + 0.04f else end + 0.04f
    val cursorY = if (second > 0f) 0.6f else 0.4f
    if (frac(x * 6f) < 0.6f) drawLine(c, p(cursorX, cursorY - 0.08f), p(cursorX, cursorY + 0.08f), thin(), StrokeCap.Round)
}

/** A picture drops onto the page and settles with a little bounce. */
private fun DrawScope.addImage(c: Color, x: Float) {
    page(c, 0.14f, 0.16f, 0.6f, 0.76f)
    val fall = seg(x, 0.05f, 0.4f)
    val bounce = 0.05f * bump(seg(x, 0.4f, 0.55f))
    val gone = seg(x, 0.85f, 1f)
    val t = lerp(-0.3f, 0.4f, fall) - bounce
    photo(c, 0.32f, t, 0.44f, 0.34f, alpha = 1f - gone)
    val plus = hold(x, 0.5f, 0.6f, 0.8f, 0.9f)
    if (plus > 0f) {
        drawCircle(c, s() * 0.12f * plus, p(0.8f, 0.24f))
        drawLine(Color.White, p(0.8f - 0.06f * plus, 0.24f), p(0.8f + 0.06f * plus, 0.24f), thin(), StrokeCap.Round)
        drawLine(Color.White, p(0.8f, 0.24f - 0.06f * plus), p(0.8f, 0.24f + 0.06f * plus), thin(), StrokeCap.Round)
    }
}

/** A highlighter crosses a line of text, leaving colour behind it. */
private fun DrawScope.annotate(c: Color, x: Float) {
    page(c, 0.12f, 0.08f, 0.66f, 0.84f)
    textLines(c, 0.22f, 0.3f, 0.46f, 4, gap = 0.14f, alpha = 0.55f)
    val f = seg(x, 0.1f, 0.65f)
    val fade = 1f - seg(x, 0.85f, 1f)
    val y = 0.44f
    drawRect(c.copy(alpha = 0.6f * fade), p(0.2f, y - 0.06f), sz(0.5f * f, 0.12f))
    val tipX = 0.2f + 0.5f * f
    rotate(-40f, p(tipX, y)) {
        drawRoundRect(c, Offset(tipX * s() - s() * 0.06f, (y - 0.3f) * s()), sz(0.12f, 0.22f), CornerRadius(s() * 0.03f))
        drawRect(c.copy(alpha = 0.6f), Offset(tipX * s() - s() * 0.05f, (y - 0.1f) * s()), sz(0.1f, 0.1f))
    }
}

/** A box is ticked, then a field fills in. */
private fun DrawScope.forms(c: Color, x: Float) {
    page(c, 0.12f, 0.08f, 0.7f, 0.84f)
    drawRoundRect(c, p(0.22f, 0.24f), sz(0.18f, 0.18f), CornerRadius(s() * 0.04f), style = stroke(thin()))
    val tick = Path().apply {
        moveTo(0.25f * s(), 0.33f * s()); lineTo(0.3f * s(), 0.39f * s()); lineTo(0.38f * s(), 0.26f * s())
    }
    val f = seg(x, 0.08f, 0.35f) * (1f - seg(x, 0.88f, 1f))
    if (f > 0f) drawPath(partial(tick, f), c, style = stroke())
    drawLine(c.copy(alpha = 0.5f), p(0.46f, 0.33f), p(0.7f, 0.33f), thin(), StrokeCap.Round)
    drawRoundRect(c, p(0.22f, 0.54f), sz(0.5f, 0.18f), CornerRadius(s() * 0.04f), style = stroke(thin()))
    val fill = seg(x, 0.4f, 0.75f) * (1f - seg(x, 0.88f, 1f))
    if (fill > 0f) drawLine(c, p(0.28f, 0.63f), p(lerp(0.28f, 0.64f, fill), 0.63f), thin(), StrokeCap.Round)
}

/** A band slides in along the top of the page, then one along the bottom. */
private fun DrawScope.headerFooter(c: Color, x: Float) {
    page(c, 0.16f, 0.08f, 0.68f, 0.84f)
    textLines(c, 0.26f, 0.4f, 0.48f, 2, gap = 0.12f, alpha = 0.4f)
    val back = 1f - seg(x, 0.88f, 1f)
    val top = seg(x, 0.05f, 0.35f) * back
    val bottom = seg(x, 0.35f, 0.65f) * back
    clipRect(left = 0.16f * s(), right = 0.84f * s()) {
        drawRoundRect(c, p(lerp(-0.6f, 0.24f, top), 0.2f), sz(0.52f, 0.09f), CornerRadius(s() * 0.04f))
        drawRoundRect(c, p(lerp(0.9f, 0.24f, bottom), 0.74f), sz(0.52f, 0.09f), CornerRadius(s() * 0.04f))
    }
}

/** Two pages make room, and a new one drops in between them. */
private fun DrawScope.insert(c: Color, x: Float) {
    val open = hold(x, 0.05f, 0.3f, 0.75f, 0.95f)
    page(c, 0.02f - 0.04f * open, 0.3f, 0.3f, 0.44f, alpha = 0.7f)
    page(c, 0.68f + 0.04f * open, 0.3f, 0.3f, 0.44f, alpha = 0.7f)
    val drop = seg(x, 0.25f, 0.6f)
    val shown = 1f - seg(x, 0.8f, 0.95f)
    val t = lerp(-0.5f, 0.28f, drop)
    page(c, 0.33f, t, 0.34f, 0.48f, fill = 0.4f, alpha = shown)
    arrow(c, p(0.5f, 0.02f), p(0.5f, 0.2f), alpha = (1f - drop) * open * shown)
}

/** The crop frame closes in on the page; what falls outside dims away. */
private fun DrawScope.crop(c: Color, x: Float) {
    val f = hold(x, 0.1f, 0.45f, 0.75f, 0.98f)
    val inset = lerp(0.08f, 0.24f, f)
    page(c, 0.12f, 0.1f, 0.76f, 0.8f, alpha = 1f - 0.5f * f)
    drawRect(c.copy(alpha = 0.25f), p(inset, inset), sz(1 - 2 * inset, 1 - 2 * inset))
    val k = 0.16f
    val corners = listOf(inset to inset, 1 - inset to inset, inset to 1 - inset, 1 - inset to 1 - inset)
    for ((cx, cy) in corners) {
        val dx = if (cx < 0.5f) k else -k; val dy = if (cy < 0.5f) k else -k
        drawLine(c, p(cx, cy), p(cx + dx, cy), line() * 1.2f, StrokeCap.Round)
        drawLine(c, p(cx, cy), p(cx, cy + dy), line() * 1.2f, StrokeCap.Round)
    }
}

/** Colour pours into the page from the bottom up, then drains. */
private fun DrawScope.background(c: Color, x: Float) {
    val f = hold(x, 0.05f, 0.5f, 0.75f, 0.98f)
    page(c, 0.18f, 0.1f, 0.64f, 0.8f, fill = 0.1f)
    val level = lerp(0.9f, 0.1f, f)
    clipRect(top = level * s(), left = 0.18f * s(), right = 0.82f * s(), bottom = 0.9f * s()) {
        drawRect(c.copy(alpha = 0.55f), p(0.18f, 0.1f), sz(0.64f, 0.8f))
        val wave = Path().apply {
            moveTo(0.18f * s(), level * s())
            for (i in 0..8) {
                val xx = 0.18f + 0.08f * i
                lineTo(xx * s(), (level + 0.02f * sin(TAU * (i / 4f + x * 2f))) * s())
            }
        }
        drawPath(wave, c, style = stroke(thin()))
    }
}

/** A tag on the page swings and turns over: i, ii, iii. */
private fun DrawScope.pageLabels(c: Color, x: Float) {
    page(c, 0.08f, 0.1f, 0.6f, 0.8f)
    textLines(c, 0.16f, 0.36f, 0.4f, 3, alpha = 0.4f)
    val pin = p(0.66f, 0.3f)
    val swing = 22f * sin(TAU * x * 2f) * (1f - 0.5f * x)
    rotate(swing, pin) {
        drawLine(c, pin, p(0.72f, 0.46f), thin(), StrokeCap.Round)
        val tag = Path().apply {
            moveTo(0.64f * s(), 0.5f * s()); lineTo(0.72f * s(), 0.44f * s()); lineTo(0.92f * s(), 0.44f * s())
            lineTo(0.92f * s(), 0.68f * s()); lineTo(0.72f * s(), 0.68f * s()); close()
        }
        drawPath(tag, c)
        val numeral = listOf("i", "ii", "iii")[(x * 3f).toInt().coerceIn(0, 2)]
        label(numeral, Color.White, p(0.8f, 0.56f), s() * 0.13f)
    }
    drawCircle(c, s() * 0.03f, pin)
}

/** A ribbon drops down over the page edge and sways. */
private fun DrawScope.bookmarks(c: Color, x: Float) {
    page(c, 0.12f, 0.1f, 0.64f, 0.8f)
    textLines(c, 0.2f, 0.36f, 0.34f, 4, alpha = 0.35f)
    val drop = seg(x, 0.05f, 0.35f) * (1f - seg(x, 0.88f, 1f))
    val len = lerp(0.04f, 0.42f, drop)
    val sway = 6f * sin(TAU * x * 3f) * drop
    rotate(sway, p(0.62f, 0.06f)) {
        val r = Path().apply {
            moveTo(0.54f * s(), 0.06f * s()); lineTo(0.7f * s(), 0.06f * s())
            lineTo(0.7f * s(), (0.06f + len) * s()); lineTo(0.62f * s(), (0.06f + len - 0.07f) * s())
            lineTo(0.54f * s(), (0.06f + len) * s()); close()
        }
        drawPath(r, c)
    }
}

/** A paper clip slides onto the page edge and gives a little wiggle. */
private fun DrawScope.attachments(c: Color, x: Float) {
    page(c, 0.14f, 0.16f, 0.62f, 0.76f)
    val slide = seg(x, 0.05f, 0.4f) * (1f - seg(x, 0.88f, 1f))
    val wiggle = 8f * sin(TAU * x * 4f) * bump(seg(x, 0.4f, 0.8f))
    val top = lerp(-0.5f, 0.04f, slide)
    rotate(wiggle + 12f, p(0.62f, top + 0.3f)) {
        val clip = Path().apply {
            moveTo(0.58f * s(), (top + 0.18f) * s())
            lineTo(0.58f * s(), (top + 0.52f) * s())
            cubicTo(0.58f * s(), (top + 0.62f) * s(), 0.72f * s(), (top + 0.62f) * s(), 0.72f * s(), (top + 0.52f) * s())
            lineTo(0.72f * s(), (top + 0.1f) * s())
            cubicTo(0.72f * s(), (top + 0.02f) * s(), 0.64f * s(), (top + 0.02f) * s(), 0.64f * s(), (top + 0.1f) * s())
            lineTo(0.64f * s(), (top + 0.46f) * s())
        }
        drawPath(clip, c, style = stroke())
    }
}

/** Four small pages fly in from the corners and settle as a grid on one sheet. */
private fun DrawScope.nUp(c: Color, x: Float) {
    page(c, 0.1f, 0.08f, 0.8f, 0.84f, fill = 0.08f)
    val targets = listOf(0.2f to 0.2f, 0.52f to 0.2f, 0.2f to 0.54f, 0.52f to 0.54f)
    val starts = listOf(-0.3f to -0.3f, 1.1f to -0.3f, -0.3f to 1.1f, 1.1f to 1.1f)
    for (i in 0 until 4) {
        val f = seg(x, 0.05f + i * 0.12f, 0.35f + i * 0.12f) * (1f - seg(x, 0.88f, 1f))
        val l = lerp(starts[i].first, targets[i].first, f)
        val t = lerp(starts[i].second, targets[i].second, f)
        drawRoundRect(c.copy(alpha = 0.35f * f), p(l, t), sz(0.28f, 0.28f), CornerRadius(s() * 0.03f))
        drawRoundRect(c.copy(alpha = f), p(l, t), sz(0.28f, 0.28f), CornerRadius(s() * 0.03f), style = stroke(thin()))
    }
}

/** A gauge fills; when it is full, the page splits into two smaller files. */
private fun DrawScope.splitSize(c: Color, x: Float) {
    val fill = seg(x, 0.05f, 0.4f)
    val split = hold(x, 0.42f, 0.6f, 0.85f, 1f)
    if (split < 0.02f) {
        page(c, 0.24f, 0.08f, 0.52f, 0.62f)
    } else {
        page(c, lerp(0.24f, 0.06f, split), 0.2f, 0.4f, 0.48f)
        page(c, lerp(0.24f, 0.54f, split), 0.2f, 0.4f, 0.48f)
    }
    drawRoundRect(c, p(0.16f, 0.8f), sz(0.68f, 0.12f), CornerRadius(s() * 0.06f), style = stroke(thin()))
    drawRoundRect(c, p(0.16f, 0.8f), sz(0.68f * fill * (1f - split * 0.5f), 0.12f), CornerRadius(s() * 0.06f))
}

/** The picture in a page lifts out of it, with a glint. */
private fun DrawScope.extractImages(c: Color, x: Float) {
    page(c, 0.08f, 0.2f, 0.62f, 0.74f)
    textLines(c, 0.16f, 0.74f, 0.4f, 2, alpha = 0.4f)
    val lift = seg(x, 0.1f, 0.55f) * (1f - seg(x, 0.85f, 1f))
    drawRoundRect(c.copy(alpha = 0.3f), p(0.16f, 0.36f), sz(0.4f, 0.3f), CornerRadius(s() * 0.03f), style = Stroke(thin(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(s() * 0.04f, s() * 0.04f))))
    photo(c, lerp(0.16f, 0.5f, lift), lerp(0.36f, 0.06f, lift), 0.4f, 0.3f)
    sparkle(c, p(0.92f, 0.08f), s() * 0.08f * hold(x, 0.5f, 0.6f, 0.72f, 0.84f))
}

/** A black bar sweeps across one line, then the next: gone for good. */
private fun DrawScope.redact(c: Color, x: Float) {
    page(c, 0.14f, 0.08f, 0.68f, 0.84f)
    val clear = 1f - seg(x, 0.88f, 1f)
    for (i in 0 until 3) {
        val y = 0.32f + 0.17f * i
        drawLine(c.copy(alpha = 0.45f), p(0.24f, y), p(0.7f, y), thin(), StrokeCap.Round)
        if (i == 1) continue
        val f = seg(x, 0.08f + 0.3f * (i / 2), 0.38f + 0.3f * (i / 2)) * clear
        if (f > 0f) drawRoundRect(Color.Black.copy(alpha = 0.85f), p(0.21f, y - 0.06f), sz(0.53f * f, 0.12f), CornerRadius(s() * 0.02f))
    }
}

/** An information tag turns slowly while its lines are read off. */
private fun DrawScope.metadata(c: Color, x: Float, lap: Float) {
    page(c, 0.08f, 0.08f, 0.6f, 0.78f)
    for (i in 0 until 3) {
        val f = seg(x, 0.08f + 0.18f * i, 0.3f + 0.18f * i) * (1f - seg(x, 0.88f, 1f))
        val y = 0.3f + 0.13f * i
        drawCircle(c, s() * 0.025f, p(0.18f, y))
        drawLine(c.copy(alpha = 0.3f), p(0.24f, y), p(0.56f, y), thin(), StrokeCap.Round)
        if (f > 0f) drawLine(c, p(0.24f, y), p(lerp(0.24f, 0.56f, f), y), thin(), StrokeCap.Round)
    }
    val o = p(0.74f, 0.74f)
    rotate(lap * 360f, o) { gearShape(c, o, s() * 0.19f) }
    drawCircle(c.copy(alpha = 0.2f), s() * 0.06f, o)
}

/** A word is rubbed out and written again, cursor following. */
private fun DrawScope.editText(c: Color, x: Float) {
    page(c, 0.1f, 0.08f, 0.72f, 0.84f)
    textLines(c, 0.2f, 0.28f, 0.5f, 1, alpha = 0.5f)
    textLines(c, 0.2f, 0.7f, 0.5f, 1, alpha = 0.5f)
    val erase = seg(x, 0.1f, 0.35f)
    val write = seg(x, 0.42f, 0.75f)
    val y = 0.49f
    drawRoundRect(c.copy(alpha = 0.22f), p(0.18f, y - 0.08f), sz(0.56f, 0.16f), CornerRadius(s() * 0.03f))
    val oldEnd = lerp(0.7f, 0.22f, erase)
    if (write <= 0f && erase < 1f) drawLine(c.copy(alpha = 0.5f), p(0.22f, y), p(oldEnd, y), line(), StrokeCap.Round)
    val newEnd = lerp(0.22f, 0.62f, write)
    if (write > 0f) drawLine(c, p(0.22f, y), p(newEnd, y), line(), StrokeCap.Round)
    val cx = if (write > 0f) newEnd + 0.03f else oldEnd + 0.03f
    if (frac(x * 6f) < 0.6f) drawLine(c, p(cx, y - 0.09f), p(cx, y + 0.09f), thin(), StrokeCap.Round)
}

/** Field boxes are drawn onto a blank page, one after another. */
private fun DrawScope.createForm(c: Color, x: Float) {
    page(c, 0.1f, 0.08f, 0.72f, 0.84f)
    val boxes = listOf(floatArrayOf(0.2f, 0.26f, 0.52f, 0.13f), floatArrayOf(0.2f, 0.46f, 0.52f, 0.13f), floatArrayOf(0.2f, 0.66f, 0.13f, 0.13f))
    for ((i, b) in boxes.withIndex()) {
        val f = seg(x, 0.06f + i * 0.22f, 0.28f + i * 0.22f) * (1f - seg(x, 0.88f, 1f))
        if (f <= 0f) continue
        val r = Path().apply { addRect(androidx.compose.ui.geometry.Rect(p(b[0], b[1]), sz(b[2], b[3]))) }
        drawPath(partial(r, f), c, style = stroke(thin()))
    }
    drawLine(c.copy(alpha = 0.4f), p(0.4f, 0.725f), p(0.68f, 0.725f), thin(), StrokeCap.Round)
}

/** A rosette turns, its tick is drawn, and light glances over it. */
private fun DrawScope.digitalSign(c: Color, x: Float, lap: Float) {
    val o = p(0.5f, 0.42f)
    val r = s() * 0.3f
    // Ribbons.
    for (d in listOf(-1f, 1f)) {
        val tail = Path().apply {
            moveTo(o.x + d * r * 0.4f, o.y + r * 0.6f)
            lineTo(o.x + d * r * 0.7f, o.y + r * 1.7f)
            lineTo(o.x + d * r * 0.35f, o.y + r * 1.45f)
            lineTo(o.x + d * r * 0.1f, o.y + r * 1.7f)
            close()
        }
        drawPath(tail, c.copy(alpha = 0.8f))
    }
    rotate(lap * 90f, o) {
        val rosette = Path()
        for (i in 0 until 24) {
            val a = i / 24f * TAU
            val rr = if (i % 2 == 0) r else r * 0.86f
            val px = o.x + cos(a) * rr; val py = o.y + sin(a) * rr
            if (i == 0) rosette.moveTo(px, py) else rosette.lineTo(px, py)
        }
        rosette.close()
        drawPath(rosette, c)
    }
    drawCircle(Color.White.copy(alpha = 0.25f), r * 0.7f, o, style = stroke(thin()))
    val tick = Path().apply {
        moveTo(o.x - r * 0.38f, o.y); lineTo(o.x - r * 0.08f, o.y + r * 0.3f); lineTo(o.x + r * 0.42f, o.y - r * 0.3f)
    }
    drawPath(partial(tick, seg(x, 0.1f, 0.4f) * (1f - seg(x, 0.9f, 1f))), Color.White, style = stroke())
    val shine = seg(x, 0.5f, 0.8f)
    if (shine > 0f && shine < 1f) {
        clipRect(left = o.x - r, top = o.y - r, right = o.x + r, bottom = o.y + r) {
            val sx = o.x - r + 2 * r * shine
            drawLine(Color.White.copy(alpha = 0.5f), Offset(sx - r * 0.3f, o.y + r), Offset(sx + r * 0.3f, o.y - r), r * 0.25f)
        }
    }
}

/** A page drops into the archive box and the lid closes over it. */
private fun DrawScope.archive(c: Color, x: Float) {
    val drop = seg(x, 0.05f, 0.4f)
    val lid = seg(x, 0.42f, 0.6f) * (1f - seg(x, 0.85f, 1f))
    clipRect(bottom = 0.6f * s()) { page(c, 0.3f, lerp(-0.4f, 0.3f, drop), 0.4f, 0.5f, alpha = 1f - lid * 0.6f) }
    drawRoundRect(c.copy(alpha = 0.25f), p(0.14f, 0.5f), sz(0.72f, 0.4f), CornerRadius(s() * 0.05f))
    drawRoundRect(c, p(0.14f, 0.5f), sz(0.72f, 0.4f), CornerRadius(s() * 0.05f), style = stroke())
    drawLine(c, p(0.4f, 0.64f), p(0.6f, 0.64f), line(), StrokeCap.Round)
    rotate(-70f * (1f - lid), p(0.1f, 0.48f)) {
        drawRoundRect(c, p(0.1f, 0.4f), sz(0.8f, 0.1f), CornerRadius(s() * 0.04f))
    }
}

/** Two versions side by side; the same line lights up in both, and the difference blinks. */
private fun DrawScope.compare(c: Color, x: Float) {
    page(c, 0.04f, 0.14f, 0.42f, 0.72f)
    page(c, 0.54f, 0.14f, 0.42f, 0.72f)
    for (side in 0..1) {
        val l = 0.1f + side * 0.5f
        for (i in 0 until 3) drawLine(c.copy(alpha = 0.4f), p(l, 0.4f + 0.12f * i), p(l + 0.26f, 0.4f + 0.12f * i), thin(), StrokeCap.Round)
    }
    val row = (x * 3f).toInt().coerceIn(0, 2)
    val within = frac(x * 3f)
    val y = 0.4f + 0.12f * row
    val on = bump(within)
    for (side in 0..1) {
        val l = 0.08f + side * 0.5f
        drawRoundRect(c.copy(alpha = 0.75f * on), p(l, y - 0.05f), sz(0.32f, 0.1f), CornerRadius(s() * 0.02f))
    }
    if (row == 1) sparkle(c, p(0.5f, y - 0.16f), s() * 0.08f * on)
}

/** A receipt prints out of the slot, with its rupee sign and a torn edge. */
private fun DrawScope.receipt(c: Color, x: Float) {
    drawRoundRect(c, p(0.12f, 0.08f), sz(0.76f, 0.14f), CornerRadius(s() * 0.06f))
    val out = seg(x, 0.08f, 0.65f) * (1f - seg(x, 0.88f, 1f))
    val len = lerp(0.04f, 0.68f, out)
    clipRect(top = 0.2f * s()) {
        val bottom = 0.18f + len
        val paper = Path().apply {
            moveTo(0.24f * s(), 0.18f * s())
            lineTo(0.76f * s(), 0.18f * s())
            lineTo(0.76f * s(), bottom * s())
            for (i in 0 until 6) {
                val xx = 0.76f - (i + 0.5f) * (0.52f / 6)
                lineTo(xx * s(), (bottom - (if (i % 2 == 0) 0.04f else 0f)) * s())
            }
            lineTo(0.24f * s(), bottom * s())
            close()
        }
        drawPath(paper, c.copy(alpha = 0.2f))
        drawPath(paper, c, style = stroke(thin()))
        label("₹", c, p(0.5f, bottom - 0.24f), s() * 0.2f)
        drawLine(c.copy(alpha = 0.6f), p(0.32f, bottom - 0.1f), p(0.68f, bottom - 0.1f), thin(), StrokeCap.Round)
    }
}

/** The shackle drops and snaps shut; the lock shivers, and a spark says it held. */
private fun DrawScope.lock(c: Color, x: Float) {
    val close = seg(x, 0.1f, 0.3f) * (1f - seg(x, 0.85f, 1f))
    val shiver = 3f * sin(TAU * x * 10f) * bump(seg(x, 0.3f, 0.5f))
    rotate(shiver, p(0.5f, 0.7f)) {
        val lift = 0.12f * (1f - close)
        drawArc(c, 180f, 180f, false, p(0.3f, 0.12f - lift), sz(0.4f, 0.44f), style = stroke(line() * 1.2f))
        drawLine(c, p(0.3f, 0.34f - lift), p(0.3f, 0.48f), line() * 1.2f, StrokeCap.Round)
        drawLine(c, p(0.7f, 0.34f - lift), p(0.7f, 0.48f - 0.14f * (1f - close)), line() * 1.2f, StrokeCap.Round)
        drawRoundRect(c, p(0.2f, 0.46f), sz(0.6f, 0.44f), CornerRadius(s() * 0.08f))
        drawCircle(Color.White, s() * 0.06f, p(0.5f, 0.64f))
        drawLine(Color.White, p(0.5f, 0.66f), p(0.5f, 0.76f), thin(), StrokeCap.Round)
    }
    sparkle(c, p(0.86f, 0.18f), s() * 0.1f * hold(x, 0.3f, 0.4f, 0.55f, 0.7f))
}

/** A key slides in and turns, and the shackle springs open. */
private fun DrawScope.unlock(c: Color, x: Float) {
    val open = seg(x, 0.45f, 0.6f) * (1f - seg(x, 0.88f, 1f))
    val lift = 0.14f * open
    drawArc(c, 180f, 180f, false, p(0.3f, 0.12f - lift), sz(0.4f, 0.44f), style = stroke(line() * 1.2f))
    drawLine(c, p(0.3f, 0.34f - lift), p(0.3f, 0.48f - lift), line() * 1.2f, StrokeCap.Round)
    drawLine(c, p(0.7f, 0.34f - lift), p(0.7f, 0.48f), line() * 1.2f, StrokeCap.Round)
    drawRoundRect(c, p(0.2f, 0.46f), sz(0.6f, 0.44f), CornerRadius(s() * 0.08f))
    val inKey = seg(x, 0.05f, 0.3f) * (1f - seg(x, 0.82f, 0.95f))
    val turn = seg(x, 0.3f, 0.45f) * (1f - seg(x, 0.82f, 0.95f))
    val kx = lerp(1.1f, 0.5f, inKey)
    rotate(90f * turn, p(kx, 0.66f)) {
        drawCircle(Color.White, s() * 0.08f, p(kx + 0.2f, 0.66f), style = stroke(thin()))
        drawLine(Color.White, p(kx, 0.66f), p(kx + 0.12f, 0.66f), thin(), StrokeCap.Round)
        drawLine(Color.White, p(kx + 0.03f, 0.66f), p(kx + 0.03f, 0.72f), thin(), StrokeCap.Round)
    }
}

/** A photo's corner is dragged in, and the frame follows it down to size. */
private fun DrawScope.resizePhoto(c: Color, x: Float) {
    val f = hold(x, 0.1f, 0.45f, 0.7f, 0.95f)
    val w = lerp(0.76f, 0.46f, f)
    val h = w * 0.78f
    photo(c, 0.12f, 0.12f, w, h)
    val corner = p(0.12f + w, 0.12f + h)
    drawCircle(c, s() * 0.05f, corner)
    drawCircle(Color.White, s() * 0.025f, corner)
    arrow(c, Offset(corner.x + s() * 0.12f, corner.y + s() * 0.12f), Offset(corner.x + s() * 0.03f, corner.y + s() * 0.03f), alpha = bump(seg(x, 0.05f, 0.5f)))
    label("KB", c, p(0.78f, 0.88f), s() * 0.13f, alpha = f)
}

/** A picture on the page is picked up, moved, and put down with its handles showing. */
private fun DrawScope.editImages(c: Color, x: Float) {
    page(c, 0.08f, 0.08f, 0.76f, 0.84f)
    textLines(c, 0.18f, 0.74f, 0.5f, 2, alpha = 0.35f)
    val move = hold(x, 0.15f, 0.5f, 0.75f, 1f)
    val l = lerp(0.18f, 0.4f, move); val t = lerp(0.22f, 0.3f, move)
    photo(c, l, t, 0.36f, 0.28f)
    val sel = hold(x, 0.05f, 0.12f, 0.6f, 0.7f)
    if (sel > 0f) {
        drawRect(c.copy(alpha = sel), p(l - 0.03f, t - 0.03f), sz(0.42f, 0.34f), style = Stroke(s() * 0.025f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s() * 0.04f, s() * 0.03f))))
        for ((hx, hy) in listOf(l - 0.03f to t - 0.03f, l + 0.39f to t - 0.03f, l - 0.03f to t + 0.31f, l + 0.39f to t + 0.31f)) {
            drawCircle(c.copy(alpha = sel), s() * 0.035f, p(hx, hy))
        }
    }
}

/** Many lines draw together into a few key points, and a star marks them. */
private fun DrawScope.summarise(c: Color, x: Float) {
    page(c, 0.1f, 0.08f, 0.72f, 0.84f)
    val f = hold(x, 0.15f, 0.5f, 0.78f, 1f)
    for (i in 0 until 5) {
        val y0 = 0.26f + 0.11f * i
        val target = if (i < 2) 0.38f + 0.2f * i else 0.48f
        val y = lerp(y0, target, f)
        val a = if (i < 2) 1f else 0.6f * (1f - f)
        if (a <= 0.01f) continue
        val l = if (i < 2) lerp(0.2f, 0.32f, f) else 0.2f
        drawLine(c.copy(alpha = a), p(l, y), p(0.66f, y), thin(), StrokeCap.Round)
        if (i < 2) drawCircle(c.copy(alpha = f), s() * 0.03f, p(0.23f, y))
    }
    sparkle(c, p(0.76f, 0.2f), s() * 0.1f * f)
}

/** Three steps joined by a line; a light runs along it, lighting each step in turn. */
private fun DrawScope.recipe(c: Color, x: Float) {
    val steps = listOf(p(0.18f, 0.72f), p(0.5f, 0.3f), p(0.82f, 0.72f))
    val path = Path().apply {
        moveTo(steps[0].x, steps[0].y)
        quadraticBezierTo(0.26f * s(), 0.3f * s(), steps[1].x, steps[1].y)
        quadraticBezierTo(0.74f * s(), 0.3f * s(), steps[2].x, steps[2].y)
    }
    drawPath(path, c.copy(alpha = 0.4f), style = stroke(thin()))
    val f = seg(x, 0.05f, 0.85f)
    drawPath(partial(path, f), c, style = stroke(thin()))
    for ((i, st) in steps.withIndex()) {
        val lit = f >= i / 2f - 0.01f
        drawCircle(c.copy(alpha = if (lit) 1f else 0.35f), s() * 0.13f, st)
        if (lit) drawCircle(Color.White, s() * 0.05f, st)
        if (lit && abs(f - i / 2f) < 0.08f) glow(c, st, s() * 0.26f, 0.45f)
    }
    val m = PathMeasure().apply { setPath(path, false) }
    if (f < 1f) glow(c, m.getPosition(m.length * f), s() * 0.12f, 0.8f)
}

/** An open book whose page turns. */
private fun DrawScope.read(c: Color, x: Float) {
    val spine = p(0.5f, 0.24f)
    fun leaf(dir: Float, k: Float, alpha: Float) {
        val path = Path().apply {
            moveTo(spine.x, 0.24f * s())
            quadraticBezierTo(spine.x + dir * 0.2f * k * s(), 0.16f * s(), spine.x + dir * 0.42f * k * s(), 0.22f * s())
            lineTo(spine.x + dir * 0.42f * k * s(), 0.78f * s())
            quadraticBezierTo(spine.x + dir * 0.2f * k * s(), 0.72f * s(), spine.x, 0.8f * s())
            close()
        }
        drawPath(path, c.copy(alpha = 0.18f * alpha))
        drawPath(path, c.copy(alpha = alpha), style = stroke(thin()))
    }
    leaf(-1f, 1f, 1f)
    leaf(1f, 1f, 1f)
    for (i in 0 until 3) {
        val y = 0.38f + 0.12f * i
        drawLine(c.copy(alpha = 0.5f), p(0.16f, y), p(0.42f, y), thin() * 0.8f, StrokeCap.Round)
        drawLine(c.copy(alpha = 0.5f), p(0.58f, y), p(0.84f, y), thin() * 0.8f, StrokeCap.Round)
    }
    val turn = seg(x, 0.2f, 0.75f)
    val k = cos(turn * PI.toFloat())
    if (turn in 0.001f..0.999f) {
        // The turning leaf, drawn solid so it reads as the page in the hand.
        val dir = if (k >= 0) 1f else -1f
        val w = abs(k)
        val leafPath = Path().apply {
            moveTo(spine.x, 0.24f * s())
            quadraticBezierTo(spine.x + dir * 0.2f * w * s(), 0.1f * s(), spine.x + dir * 0.42f * w * s(), 0.16f * s())
            lineTo(spine.x + dir * 0.42f * w * s(), 0.74f * s())
            quadraticBezierTo(spine.x + dir * 0.2f * w * s(), 0.68f * s(), spine.x, 0.8f * s())
            close()
        }
        drawPath(leafPath, c.copy(alpha = 0.55f))
        drawPath(leafPath, c, style = stroke(thin()))
    }
}

// --------------------------------------------------------------------------- everyday

private fun DrawScope.gearShape(c: Color, o: Offset, r: Float) {
    val teeth = 8
    val path = Path()
    for (i in 0 until teeth * 2) {
        val a0 = i / (teeth * 2f) * TAU
        val a1 = (i + 1) / (teeth * 2f) * TAU
        val rr = if (i % 2 == 0) r else r * 0.78f
        if (i == 0) path.moveTo(o.x + cos(a0) * rr, o.y + sin(a0) * rr) else path.lineTo(o.x + cos(a0) * rr, o.y + sin(a0) * rr)
        path.lineTo(o.x + cos(a1) * rr, o.y + sin(a1) * rr)
    }
    path.close()
    drawPath(path, c)
    drawCircle(Color.White.copy(alpha = 0.85f), r * 0.32f, o)
}

/** A gear that keeps turning, slowly. */
private fun DrawScope.gear(c: Color, lap: Float) {
    val o = p(0.5f, 0.5f)
    rotate(lap * 180f, o) { gearShape(c, o, s() * 0.44f) }
}

/** A star that turns a little and twinkles, with sparkles taking turns beside it. */
private fun DrawScope.star(c: Color, x: Float, lap: Float) {
    val o = p(0.5f, 0.52f)
    val pulse = bump(x)
    glow(c, o, s() * 0.5f, 0.3f * pulse)
    rotate(lap * 72f, o) {
        val r = s() * (0.4f + 0.03f * pulse)
        val path = Path()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) r else r * 0.42f
            val a = (-90f + i * 36f) * PI.toFloat() / 180f
            if (i == 0) path.moveTo(o.x + cos(a) * rr, o.y + sin(a) * rr) else path.lineTo(o.x + cos(a) * rr, o.y + sin(a) * rr)
        }
        path.close()
        drawPath(path, c)
    }
    sparkle(c, p(0.88f, 0.12f), s() * 0.1f * bump(seg(x, 0f, 0.5f)))
    sparkle(c, p(0.12f, 0.86f), s() * 0.08f * bump(seg(x, 0.5f, 1f)))
}

/** A magnifier sweeping side to side, a glint crossing the glass. */
private fun DrawScope.search(c: Color, x: Float) {
    val dx = 0.08f * sin(TAU * x)
    val o = p(0.42f + dx, 0.42f)
    val r = s() * 0.24f
    drawCircle(c.copy(alpha = 0.15f), r, o)
    drawCircle(c, r, o, style = stroke())
    drawLine(c, Offset(o.x + r * 0.72f, o.y + r * 0.72f), Offset(o.x + r * 1.6f, o.y + r * 1.6f), line() * 1.3f, StrokeCap.Round)
    val g = seg(x, 0.2f, 0.6f)
    if (g in 0.01f..0.99f) {
        clipRect(left = o.x - r, top = o.y - r, right = o.x + r, bottom = o.y + r) {
            val gx = o.x - r + 2 * r * g
            drawLine(Color.White.copy(alpha = 0.6f), Offset(gx - r * 0.3f, o.y + r), Offset(gx + r * 0.3f, o.y - r), r * 0.22f)
        }
    }
}

/** The information mark, with a ring of light rippling out from it. */
private fun DrawScope.info(c: Color, x: Float) {
    val o = p(0.5f, 0.5f)
    val ring = seg(x, 0f, 0.8f)
    drawCircle(c.copy(alpha = 0.5f * (1f - ring)), s() * (0.36f + 0.12f * ring), o, style = stroke(thin()))
    drawCircle(c, s() * 0.36f, o)
    drawCircle(Color.White, s() * 0.05f, p(0.5f, 0.32f - 0.03f * bump(seg(x, 0.1f, 0.4f))))
    drawLine(Color.White, p(0.5f, 0.45f), p(0.5f, 0.68f), line(), StrokeCap.Round)
}

/** Three nodes; a pulse travels out from the first to the other two. */
private fun DrawScope.share(c: Color, x: Float) {
    val a = p(0.24f, 0.5f); val b = p(0.76f, 0.22f); val d = p(0.76f, 0.78f)
    drawLine(c, a, b, thin()); drawLine(c, a, d, thin())
    val f = seg(x, 0.05f, 0.6f)
    for (to in listOf(b, d)) glow(c, Offset(lerp(a.x, to.x, f), lerp(a.y, to.y, f)), s() * 0.12f, 0.9f * bump(f))
    for (n in listOf(a, b, d)) drawCircle(c, s() * 0.12f, n)
    val hit = bump(seg(x, 0.55f, 0.8f))
    drawCircle(c.copy(alpha = 0.6f * hit), s() * (0.12f + 0.07f * hit), b, style = stroke(thin()))
    drawCircle(c.copy(alpha = 0.6f * hit), s() * (0.12f + 0.07f * hit), d, style = stroke(thin()))
}

/** A paper plane flies off and comes back round. */
private fun DrawScope.send(c: Color, x: Float) {
    val f = seg(x, 0.1f, 0.5f)
    val back = seg(x, 0.6f, 0.95f)
    val dx = if (back > 0f) lerp(-0.6f, 0f, back) else 0.6f * f
    val dy = if (back > 0f) lerp(0.3f, 0f, back) else -0.3f * f
    translate(dx * s(), dy * s()) {
        val plane = Path().apply {
            moveTo(0.14f * s(), 0.5f * s()); lineTo(0.86f * s(), 0.2f * s()); lineTo(0.6f * s(), 0.82f * s())
            lineTo(0.46f * s(), 0.56f * s()); close()
        }
        drawPath(plane, c)
        drawLine(Color.White.copy(alpha = 0.7f), p(0.46f, 0.56f), p(0.86f, 0.2f), thin())
    }
}

/** An envelope whose flap lifts to show a letter. */
private fun DrawScope.mail(c: Color, x: Float) {
    val open = hold(x, 0.1f, 0.35f, 0.65f, 0.9f)
    val letterUp = 0.16f * open
    clipRect(bottom = 0.8f * s()) {
        drawRoundRect(Color.White.copy(alpha = 0.9f), p(0.24f, 0.32f - letterUp), sz(0.52f, 0.36f), CornerRadius(s() * 0.03f))
        drawLine(c.copy(alpha = 0.6f), p(0.32f, 0.42f - letterUp), p(0.66f, 0.42f - letterUp), thin(), StrokeCap.Round)
    }
    drawRoundRect(c, p(0.12f, 0.36f), sz(0.76f, 0.46f), CornerRadius(s() * 0.06f))
    val tip = lerp(0.62f, 0.12f, open)
    val flap = Path().apply { moveTo(0.12f * s(), 0.38f * s()); lineTo(0.5f * s(), tip * s()); lineTo(0.88f * s(), 0.38f * s()) }
    drawPath(flap, Color.White.copy(alpha = 0.85f), style = stroke(thin()))
}

/** A heart that beats twice and rests. */
private fun DrawScope.heart(c: Color, x: Float) {
    val beat = 1f + 0.16f * bump(seg(x, 0f, 0.12f)) + 0.1f * bump(seg(x, 0.16f, 0.28f))
    scale(beat, p(0.5f, 0.55f)) {
        val path = Path().apply {
            moveTo(0.5f * s(), 0.86f * s())
            cubicTo(-0.05f * s(), 0.52f * s(), 0.12f * s(), 0.04f * s(), 0.5f * s(), 0.3f * s())
            cubicTo(0.88f * s(), 0.04f * s(), 1.05f * s(), 0.52f * s(), 0.5f * s(), 0.86f * s())
            close()
        }
        drawPath(path, c)
    }
}

/** A tick drawn in one stroke. */
private fun DrawScope.check(c: Color, x: Float) {
    val tick = Path().apply { moveTo(0.18f * s(), 0.52f * s()); lineTo(0.42f * s(), 0.76f * s()); lineTo(0.84f * s(), 0.26f * s()) }
    drawPath(partial(tick, lerp(0.35f, 1f, seg(x, 0.05f, 0.4f))), c, style = stroke(line() * 1.4f))
}

/** A plus that turns a quarter and settles. */
private fun DrawScope.plus(c: Color, x: Float) {
    rotate(90f * seg(x, 0.1f, 0.4f), p(0.5f, 0.5f)) {
        drawLine(c, p(0.2f, 0.5f), p(0.8f, 0.5f), line() * 1.4f, StrokeCap.Round)
        drawLine(c, p(0.5f, 0.2f), p(0.5f, 0.8f), line() * 1.4f, StrokeCap.Round)
    }
}
