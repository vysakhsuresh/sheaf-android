package com.layerbit.sheaf.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as AndroidMatrix
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** One change to a picture already on a page, found by where it was. */
data class PictureEdit(val area: PageArea, val action: Action) {
    sealed interface Action {
        data object Remove : Action

        /** To a new place and size on the page, as fractions of what the reader sees. */
        data class Move(val to: PageArea) : Action

        /** Another picture in its place, fitted inside the old one's box. */
        data class Replace(val image: File) : Action
    }
}

data class PictureReport(val removed: Int, val moved: Int, val replaced: Int, val missed: Int) {
    val done: Int get() = removed + moved + replaced
}

/**
 * Finding and changing the pictures drawn on a page.
 *
 * A picture on a page is one instruction in the page's drawing - "draw image Im3" - made under
 * whatever transforms came before it, and those transforms are what place and size it. Walking
 * the drawing with the transforms tracked gives each picture's box; changing a picture is then
 * changing that one instruction: taking it out, wrapping it in one more transform, or pointing
 * it at another image. Nothing else on the page is touched.
 *
 * Only pictures drawn by the page itself are offered. One inside a form, or an inline image, is
 * part of something larger and is left alone. A picture taken out is no longer drawn; its data
 * may still be in the file, which is why Remove areas, not this, is for something private.
 */
internal object PictureSurgery {

    /** A row-vector affine transform, as PDF writes them: [a b c d e f]. */
    data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        /** This, then [then]. */
        fun then(then: Affine) = Affine(
            a * then.a + b * then.c, a * then.b + b * then.d,
            c * then.a + d * then.c, c * then.b + d * then.d,
            e * then.a + f * then.c + then.e, e * then.b + f * then.d + then.f
        )

        fun apply(x: Double, y: Double) = (a * x + c * y + e) to (b * x + d * y + f)

        fun inverse(): Affine? {
            val det = a * d - b * c
            if (kotlin.math.abs(det) < 1e-12) return null
            return Affine(d / det, -b / det, -c / det, a / det, (c * f - d * e) / det, (b * e - a * f) / det)
        }

        companion object {
            val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    class Call(val nameAt: Int, val doAt: Int, val name: COSName, val ctm: Affine, val area: PageArea)

    /** The pictures the page draws itself, with where each one shows. Tiny slivers are left out. */
    fun calls(page: PDPage, tokens: List<Any>): List<Call> {
        val resources = page.resources ?: return emptyList()
        val shown = ShownPage(page)
        val out = mutableListOf<Call>()
        val stack = ArrayDeque<Affine>()
        var ctm = Affine.IDENTITY
        val operands = mutableListOf<Pair<Int, Any>>()
        for ((i, token) in tokens.withIndex()) {
            if (token !is Operator) {
                operands += i to token
                continue
            }
            when (token.name) {
                "q" -> stack.addLast(ctm)
                "Q" -> ctm = stack.removeLastOrNull() ?: Affine.IDENTITY
                "cm" -> {
                    val n = operands.map { it.second }.filterIsInstance<COSNumber>().map { it.floatValue().toDouble() }
                    if (n.size == 6) ctm = Affine(n[0], n[1], n[2], n[3], n[4], n[5]).then(ctm)
                }
                "Do" -> {
                    val (at, name) = operands.lastOrNull() ?: (-1 to null)
                    if (name is COSName && runCatching { resources.isImageXObject(name) }.getOrDefault(false)) {
                        val area = viewArea(shown, ctm)
                        if (area.width > 0.01f && area.height > 0.01f) out += Call(at, i, name, ctm, area)
                    }
                }
            }
            operands.clear()
        }
        return out
    }

    /** The unit square under [ctm], as fractions of the page the reader sees. */
    fun viewArea(shown: ShownPage, ctm: Affine): PageArea {
        val corners = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0).map { (x, y) -> ctm.apply(x, y) }
            .map { (x, y) -> toView(shown, x.toFloat(), y.toFloat()) }
        val left = corners.minOf { it.first }
        val right = corners.maxOf { it.first }
        val top = corners.minOf { it.second }
        val bottom = corners.maxOf { it.second }
        return PageArea(left, top, right - left, bottom - top)
    }

    /** A point in the stored page, as fractions across and down the page the reader sees. */
    private fun toView(shown: ShownPage, x: Float, y: Float): Pair<Float, Float> {
        val box = shown.box
        val (across, down) = when (shown.rotation) {
            90 -> (y - box.lowerLeftY) to (x - box.lowerLeftX)
            180 -> (box.upperRightX - x) to (y - box.lowerLeftY)
            270 -> (box.upperRightY - y) to (box.upperRightX - x)
            else -> (x - box.lowerLeftX) to (box.upperRightY - y)
        }
        return across / shown.width to down / shown.height
    }

    fun tokens(page: PDPage): MutableList<Any> {
        val parser = PDFStreamParser(page)
        parser.parse()
        return parser.tokens.toMutableList()
    }

    /** Intersection over union of two boxes: how surely they are the same picture. */
    fun overlap(p: PageArea, q: PageArea): Float {
        val w = min(p.left + p.width, q.left + q.width) - max(p.left, q.left)
        val h = min(p.top + p.height, q.top + q.height) - max(p.top, q.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (p.width * p.height + q.width * q.height - inter)
    }

    /** Applies [edits] to one page. Returns what was done. */
    fun edit(doc: PDDocument, page: PDPage, edits: List<PictureEdit>): PictureReport {
        val tokens = tokens(page)
        val calls = calls(page, tokens)
        val used = mutableSetOf<Int>()
        // Each edit to the call whose box it matches best, if well enough to be sure.
        val matched = edits.mapNotNull { edit ->
            val best = calls.withIndex()
                .filter { it.index !in used }
                .maxByOrNull { overlap(it.value.area, edit.area) }
                ?.takeIf { overlap(it.value.area, edit.area) > 0.5f }
                ?: return@mapNotNull null
            used += best.index
            best.value to edit
        }

        val resources = page.resources ?: PDResources().also { page.resources = it }
        val shown = ShownPage(page)
        var removed = 0
        var moved = 0
        var replaced = 0
        // Replacement token lists, keyed by where the call starts; applied from the end so the
        // earlier positions stay right.
        val splices = mutableListOf<Triple<Int, Int, List<Any>>>()
        for ((call, edit) in matched) {
            when (val action = edit.action) {
                PictureEdit.Action.Remove -> {
                    splices += Triple(call.nameAt, call.doAt, emptyList())
                    removed += 1
                }
                is PictureEdit.Action.Move -> {
                    val old = call.area
                    val to = action.to
                    val from = shown.storedRect(old.left, old.top, old.left + old.width, old.top + old.height)
                    val target = shown.storedRect(to.left, to.top, to.left + to.width, to.top + to.height)
                    if (from.width <= 0f || from.height <= 0f) continue
                    val sx = (target.width / from.width).toDouble()
                    val sy = (target.height / from.height).toDouble()
                    val shift = Affine(sx, 0.0, 0.0, sy, target.lowerLeftX - from.lowerLeftX * sx, target.lowerLeftY - from.lowerLeftY * sy)
                    val inverse = call.ctm.inverse() ?: continue
                    // Written in the space the call is drawn in: the extra transform L has to
                    // satisfy L × ctm = ctm × shift, so L = ctm × shift × ctm⁻¹.
                    val local = call.ctm.then(shift).then(inverse)
                    splices += Triple(call.nameAt, call.doAt, wrapped(local, call.name))
                    moved += 1
                }
                is PictureEdit.Action.Replace -> {
                    val image = load(doc, action.image) ?: continue
                    val name = resources.add(image)
                    // Fitted inside the old box keeping its own shape, centred, in the unit
                    // square the old picture was drawn into.
                    val box = call.area
                    val boxAspect = (box.width * shown.width) / (box.height * shown.height)
                    val aspect = image.width.toFloat() / image.height
                    val fx = if (aspect >= boxAspect) 1f else aspect / boxAspect
                    val fy = if (aspect >= boxAspect) boxAspect / aspect else 1f
                    val fit = Affine(fx.toDouble(), 0.0, 0.0, fy.toDouble(), (1.0 - fx) / 2, (1.0 - fy) / 2)
                    splices += Triple(call.nameAt, call.doAt, wrapped(fit, name))
                    replaced += 1
                }
            }
        }

        if (splices.isNotEmpty()) {
            for ((from, to, with) in splices.sortedByDescending { it.first }) {
                if (from < 0) continue
                repeat(to - from + 1) { tokens.removeAt(from) }
                tokens.addAll(from, with)
            }
            val stream = PDStream(doc)
            stream.createOutputStream(COSName.FLATE_DECODE).use { out -> ContentStreamWriter(out).writeTokens(tokens) }
            page.setContents(stream)
        }
        return PictureReport(removed, moved, replaced, edits.size - matched.size)
    }

    private fun wrapped(m: Affine, name: COSName): List<Any> = listOf(
        Operator.getOperator("q"),
        num(m.a), num(m.b), num(m.c), num(m.d), num(m.e), num(m.f), Operator.getOperator("cm"),
        name, Operator.getOperator("Do"),
        Operator.getOperator("Q")
    )

    private fun num(v: Double): COSBase = COSFloat(if (kotlin.math.abs(v) < 1e-9) 0f else v.toFloat())

    /**
     * The replacement picture, turned upright and no bigger than 2000 pixels on its longest
     * side. PNG where it may be see-through, JPEG otherwise.
     */
    private fun load(doc: PDDocument, file: File): com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2000) sample *= 2
        var bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = max(bitmap.width, bitmap.height)
        if (longest > 2000) {
            val k = 2000f / longest
            bitmap = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * k).toInt()), max(1, (bitmap.height * k).toInt()), true)
        }
        val turn = runCatching {
            when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (turn != 0f) {
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, AndroidMatrix().apply { postRotate(turn) }, true)
        }
        return if (bitmap.hasAlpha()) LosslessFactory.createFromImage(doc, bitmap) else JPEGFactory.createFromImage(doc, bitmap, 0.9f)
    }
}
