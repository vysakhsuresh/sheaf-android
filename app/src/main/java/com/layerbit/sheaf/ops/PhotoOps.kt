package com.layerbit.sheaf.ops

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.text.Strings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A photo or a signature under the size a form asks for: 20 KB, 50 KB, whatever is typed.
 *
 * Government and exam forms in India ask for exactly this - "photo under 50 KB, signature
 * under 20 KB" - and the usual answer is an upload site. Here the size is hit by searching:
 * the best JPEG quality that fits first, then, if even the lowest acceptable quality does not
 * fit, the largest picture that does. Pictures are made smaller by halving before the final
 * resize, which is what keeps the thin strokes of a signature from breaking up.
 *
 * Every picture comes out a clean JPEG with nothing hidden in it: the phone's location and
 * camera details are left behind. 1 KB here is 1,000 bytes, so the result is under the limit
 * however the form counts.
 */
class ResizePhotoOp(
    /** The size each picture must come in under, in kilobytes of 1,000 bytes. */
    private val kb: Float,
    private val pixels: PhotoPixels = PhotoPixels.Keep
) : Op {

    override val tool = ToolId.RESIZE_PHOTO
    override val title = Strings.get(R.string.engine_title_resize_photo, PhotoFit.limitText(kb))
    override val arity = Op.Arity.EachIndependently

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> = withContext(Dispatchers.IO) {
        PhotoFit.problem(kb, pixels)?.let { problem ->
            return@withContext inputs.map { OpOutcome.Failed(it.displayName, problem) }
        }
        val under = PhotoFit.limitText(kb)
        inputs.mapIndexed { i, input ->
            context.checkCancelled()
            onProgress(
                Progress(
                    i, inputs.size,
                    if (inputs.size == 1) Strings.get(R.string.engine_progress_fitting_one, under)
                    else Strings.get(R.string.engine_progress_fitting_many, i + 1, inputs.size, under)
                )
            )
            try {
                fit(input, context)
            } catch (e: OutOfMemoryError) {
                OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_photo_out_of_memory))
            } catch (e: OpCancelled) {
                throw e
            } catch (e: Exception) {
                OpOutcome.Failed(input.displayName, e.message ?: Strings.get(R.string.engine_photo_unreadable))
            }
        }.also { onProgress(Progress(inputs.size, inputs.size, Strings.get(R.string.engine_done))) }
    }

    private fun fit(input: SheafFile, context: OpContext): OpOutcome {
        val bytes = input.file.readBytes()
        val kind = PhotoFit.kindOf(bytes)
            ?: return OpOutcome.Failed(input.displayName, Strings.get(R.string.engine_photo_not_image))
        val limit = (kb * 1000).roundToInt()
        val before = bytes.size.toLong()
        val stem = PhotoFit.stem(input.displayName)
        val under = PhotoFit.limitText(kb)
        val hidden = PhotoFit.hiddenNote(kind, bytes, input.file)

        val orientation = if (kind == PhotoFit.Kind.JPEG || kind == PhotoFit.Kind.HEIC || kind == PhotoFit.Kind.WEBP) {
            runCatching {
                ExifInterface(input.file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(1).takeIf { it in 1..8 } ?: 1
        } else 1

        // How big the picture is, as stored, without decoding it.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return OpOutcome.Failed(input.displayName, PhotoFit.unreadable(kind))
        }
        val swap = orientation in 5..8
        val seenW = if (swap) bounds.outHeight else bounds.outWidth
        val seenH = if (swap) bounds.outWidth else bounds.outHeight
        val plan = PhotoFit.plan(seenW, seenH, pixels)

        // A JPEG already the right size and shape: leave the picture byte for byte, and only
        // take out what is hidden in it.
        if (kind == PhotoFit.Kind.JPEG && orientation == 1 && plan.cropW == seenW && plan.cropH == seenH &&
            plan.outW == seenW && plan.outH == seenH
        ) {
            val stripped = PhotoFit.stripJpeg(bytes)
            if (stripped != null && stripped.size < limit) {
                if (stripped.size.toLong() == before) {
                    return OpOutcome.Skipped(
                        input.displayName,
                        Strings.get(R.string.engine_photo_already_fine, PhotoFit.sizeText(before), under, seenW, seenH)
                    )
                }
                val out = context.workspace.newOutput("$stem (under $under)", "resized", "jpg")
                out.writeBytes(stripped)
                return OpOutcome.Produced(
                    SheafFile(out, "$stem (under $under).jpg", SheafFile.Origin.Derived("resized"), SheafFile.MIME_JPEG),
                    Strings.get(R.string.engine_photo_stripped, PhotoFit.sizeText(before), PhotoFit.sizeText(stripped.size.toLong()), under, seenW, seenH, hidden)
                )
            }
        }

        // Decode no bigger than the cap needs: a 50 MP photo is 200 MB of pixels otherwise.
        val stored = decode(bytes, bounds.outWidth, bounds.outHeight)
            ?: return OpOutcome.Failed(input.displayName, PhotoFit.unreadable(kind))
        val source = try {
            upright(stored, orientation, plan, seenW, seenH)
        } finally {
            stored.recycle()
        }

        try {
            val pyramid = Pyramid(source)
            var size = plan.outW to plan.outH
            var shot = PhotoFit.bestQuality(limit) { q -> jpeg(pyramid.at(size.first, size.second), q) }
            var shrunk = false

            if (!shot.fits && plan.mayShrink) {
                val floorScale = min(1.0, PhotoFit.SMALLEST_SIDE.toDouble() / max(plan.outW, plan.outH))
                fun at(scale: Double) = max(1, (plan.outW * scale).roundToInt()) to max(1, (plan.outH * scale).roundToInt())
                var failing = 1.0
                var failingBytes = shot.bytes.size
                var fitting: Double? = null
                var smallest = shot.bytes to size
                var tries = 0
                while (tries < 10 && failing > floorScale) {
                    tries += 1
                    val scale = max(floorScale, min(failing * 0.95, failing * sqrt(limit.toDouble() / failingBytes) * 0.9))
                    val s = at(scale)
                    val encoded = jpeg(pyramid.at(s.first, s.second), PhotoFit.LOWEST_QUALITY)
                    if (encoded.size < limit) {
                        fitting = scale
                        break
                    }
                    failing = scale
                    failingBytes = encoded.size
                    smallest = encoded to s
                    if (scale <= floorScale) break
                }

                if (fitting == null) {
                    val (smallBytes, smallSize) = smallest
                    if (smallBytes.size >= before) {
                        return OpOutcome.Skipped(
                            input.displayName,
                            Strings.get(R.string.engine_photo_too_small_kept, under, PhotoFit.sizeText(before))
                        )
                    }
                    val out = context.workspace.newOutput("$stem (resized)", "resized", "jpg")
                    out.writeBytes(smallBytes)
                    return OpOutcome.Produced(
                        SheafFile(out, "$stem (resized).jpg", SheafFile.Origin.Derived("resized"), SheafFile.MIME_JPEG),
                        Strings.get(
                            R.string.engine_photo_smallest_clear,
                            PhotoFit.sizeText(before), PhotoFit.sizeText(smallBytes.size.toLong()), under, smallSize.first, smallSize.second, hidden
                        )
                    )
                }

                // Between a size that fits and one that does not, find the largest that fits.
                var lo: Double = fitting
                var hi = failing
                repeat(6) {
                    val loSide = max(at(lo).first, at(lo).second)
                    val hiSide = max(at(hi).first, at(hi).second)
                    if (hiSide - loSide <= max(2, (loSide * 0.02).toInt())) return@repeat
                    val mid = (lo + hi) / 2
                    val s = at(mid)
                    if (jpeg(pyramid.at(s.first, s.second), PhotoFit.LOWEST_QUALITY).size < limit) lo = mid else hi = mid
                }
                size = at(lo)
                shot = PhotoFit.bestQuality(limit) { q -> jpeg(pyramid.at(size.first, size.second), q) }
                shrunk = true
            }

            if (!shot.fits) {
                // Exact pixels were asked for and are never shrunk: say what came of it.
                val out = context.workspace.newOutput("$stem (resized)", "resized", "jpg")
                out.writeBytes(shot.bytes)
                return OpOutcome.Produced(
                    SheafFile(out, "$stem (resized).jpg", SheafFile.Origin.Derived("resized"), SheafFile.MIME_JPEG),
                    Strings.get(
                        R.string.engine_photo_exact_too_small,
                        PhotoFit.sizeText(before), PhotoFit.sizeText(shot.bytes.size.toLong()), under, size.first, size.second, hidden
                    )
                )
            }

            val dims = buildString {
                append(
                    Strings.get(
                        if (pixels is PhotoPixels.Passport) R.string.engine_photo_dims_passport else R.string.engine_photo_dims,
                        size.first, size.second
                    )
                )
                when {
                    shrunk && pixels is PhotoPixels.Keep -> append(Strings.get(R.string.engine_photo_made_smaller, seenW, seenH))
                    shrunk -> append(Strings.get(R.string.engine_photo_made_smaller_than, plan.outW, plan.outH))
                    pixels is PhotoPixels.Keep && plan.capped -> append(Strings.get(R.string.engine_photo_it_was, seenW, seenH))
                    pixels is PhotoPixels.Passport && plan.outW < PhotoFit.PASSPORT_W ->
                        append(Strings.get(R.string.engine_photo_not_enlarged, PhotoFit.PASSPORT_W, PhotoFit.PASSPORT_H))
                }
                if (orientation != 1) append(Strings.get(R.string.engine_photo_turned_upright))
                if (kind != PhotoFit.Kind.JPEG) append(Strings.get(R.string.engine_photo_now_jpeg))
            }
            val out = context.workspace.newOutput("$stem (under $under)", "resized", "jpg")
            out.writeBytes(shot.bytes)
            return OpOutcome.Produced(
                SheafFile(out, "$stem (under $under).jpg", SheafFile.Origin.Derived("resized"), SheafFile.MIME_JPEG),
                Strings.get(R.string.engine_photo_done, PhotoFit.sizeText(before), PhotoFit.sizeText(shot.bytes.size.toLong()), under, dims, hidden)
            )
        } finally {
            source.recycle()
        }
    }

    /** Decodes at the smallest power-of-two step that still holds every pixel the cap allows. */
    private fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        var sample = 1
        while ((width.toLong() / (sample * 2)) * (height.toLong() / (sample * 2)) >= PhotoFit.MAX_PIXELS) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    /**
     * The crop the plan asks for, turned upright and laid on white (JPEG has no transparency),
     * at no more than the pixel cap.
     */
    private fun upright(stored: Bitmap, orientation: Int, plan: PhotoFit.Plan, seenW: Int, seenH: Int): Bitmap {
        // The plan is in the full-size upright picture; the decode may be a fraction of it.
        val decodedSeenW = if (orientation in 5..8) stored.height else stored.width
        val k = decodedSeenW.toFloat() / seenW
        val cropW = max(1, (plan.cropW * k).roundToInt())
        val cropH = max(1, (plan.cropH * k).roundToInt())
        // Half straight away when the crop is at least twice the output both ways: the first
        // halving step for free.
        var scale = if (cropW >= plan.outW * 2 && cropH >= plan.outH * 2) 0.5f else 1f
        scale = min(scale, sqrt(PhotoFit.MAX_PIXELS.toFloat() / (cropW.toFloat() * cropH)))
        val w = max(1, (cropW * scale).roundToInt())
        val h = max(1, (cropH * scale).roundToInt())

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val m = Matrix()
        // Stored pixels to upright pixels.
        val sw = stored.width.toFloat()
        val sh = stored.height.toFloat()
        when (orientation) {
            2 -> m.setValues(floatArrayOf(-1f, 0f, sw, 0f, 1f, 0f, 0f, 0f, 1f))
            3 -> m.setValues(floatArrayOf(-1f, 0f, sw, 0f, -1f, sh, 0f, 0f, 1f))
            4 -> m.setValues(floatArrayOf(1f, 0f, 0f, 0f, -1f, sh, 0f, 0f, 1f))
            5 -> m.setValues(floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))
            6 -> m.setValues(floatArrayOf(0f, -1f, sh, 1f, 0f, 0f, 0f, 0f, 1f))
            7 -> m.setValues(floatArrayOf(0f, -1f, sh, -1f, 0f, sw, 0f, 0f, 1f))
            8 -> m.setValues(floatArrayOf(0f, 1f, 0f, -1f, 0f, sw, 0f, 0f, 1f))
        }
        // Then the crop, then the first scale.
        m.postTranslate(-plan.cropX * k, -plan.cropY * k)
        m.postScale(w.toFloat() / cropW, h.toFloat() / cropH)
        val paint = Paint().apply { isFilterBitmap = scale != 1f || orientation != 1; isAntiAlias = false }
        canvas.drawBitmap(stored, m, paint)
        return out
    }

    private fun jpeg(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /**
     * Halvings of the picture, kept so each size asked for starts from the nearest one.
     * Each halving averages four pixels into one, which is what a straight resize to a
     * twentieth does not do - and why a signature resized that way comes out broken.
     */
    private class Pyramid(base: Bitmap) {
        private val levels = mutableListOf(base)
        private var last: Triple<Int, Int, Bitmap>? = null

        fun at(w: Int, h: Int): Bitmap {
            last?.let { (lw, lh, b) -> if (lw == w && lh == h) return b }
            var source = levels.first()
            var i = 0
            while (source.width >= w * 2 && source.height >= h * 2) {
                i += 1
                if (i >= levels.size) {
                    levels += Bitmap.createScaledBitmap(source, max(1, source.width / 2), max(1, source.height / 2), true)
                }
                source = levels[i]
            }
            val made = if (source.width == w && source.height == h) source else {
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                Canvas(out).drawBitmap(source, Rect(0, 0, source.width, source.height), RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                out
            }
            last?.third?.let { old -> if (old !in levels && old !== made) old.recycle() }
            last = Triple(w, h, made)
            return made
        }
    }
}

/** How many pixels the result has. */
sealed interface PhotoPixels {
    /** As many as it has, unless it needs fewer to fit. */
    data object Keep : PhotoPixels

    /** 3.5 × 4.5 cm at 300 dpi, cut from the middle. */
    data object Passport : PhotoPixels

    /** Exactly this, cut from the middle to the shape. Never made smaller. */
    data class Exact(val width: Int, val height: Int) : PhotoPixels
}

/** The arithmetic of fitting a picture under a size. No Android types, so it is tested on the JVM. */
object PhotoFit {
    const val PASSPORT_W = 413
    const val PASSPORT_H = 531
    const val BEST_QUALITY = 92
    const val LOWEST_QUALITY = 50
    const val SMALLEST_SIDE = 160
    const val MAX_PIXELS = 16_777_216
    const val MIN_KB = 2f
    const val MIN_SIDE = 16
    const val MAX_SIDE = 4096

    enum class Kind { JPEG, PNG, GIF, WEBP, BMP, HEIC, AVIF }

    data class Plan(
        val cropX: Int, val cropY: Int, val cropW: Int, val cropH: Int,
        val outW: Int, val outH: Int,
        val mayShrink: Boolean,
        /** Keep only: the picture was over the pixel cap and was brought under it. */
        val capped: Boolean = false
    )

    class Shot(val fits: Boolean, val bytes: ByteArray, val quality: Int)

    fun problem(kb: Float, pixels: PhotoPixels): String? = when {
        !(kb >= MIN_KB) -> Strings.get(R.string.engine_photo_min_size)
        pixels is PhotoPixels.Exact && (pixels.width !in MIN_SIDE..MAX_SIDE || pixels.height !in MIN_SIDE..MAX_SIDE) ->
            Strings.get(R.string.engine_photo_pixels_range, MIN_SIDE, MAX_SIDE)
        else -> null
    }

    /** What the picture is, from its first bytes rather than its name. */
    fun kindOf(b: ByteArray): Kind? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        fun ascii(from: Int, len: Int) = if (b.size >= from + len) String(b, from, len, Charsets.US_ASCII) else ""
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> Kind.JPEG
            at(0) == 0x89 && ascii(1, 3) == "PNG" -> Kind.PNG
            ascii(0, 4) == "GIF8" -> Kind.GIF
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP" -> Kind.WEBP
            ascii(0, 2) == "BM" -> Kind.BMP
            ascii(4, 4) == "ftyp" && ascii(8, 4) in setOf("avif", "avis") -> Kind.AVIF
            ascii(4, 4) == "ftyp" && ascii(8, 4) in setOf("heic", "heix", "hevc", "heim", "heis", "mif1", "msf1") -> Kind.HEIC
            else -> null
        }
    }

    fun unreadable(kind: Kind) = when (kind) {
        Kind.AVIF -> Strings.get(R.string.engine_photo_no_avif)
        Kind.HEIC -> Strings.get(R.string.engine_photo_no_heic)
        else -> Strings.get(R.string.engine_photo_unreadable)
    }

    /** Which part of the upright picture is kept, and how many pixels it comes out at. */
    fun plan(w: Int, h: Int, pixels: PhotoPixels): Plan {
        fun middle(aspect: Double): IntArray = if (w.toDouble() / h > aspect) {
            val cw = min(w, max(1, (h * aspect).roundToInt()))
            intArrayOf(floor((w - cw) / 2.0).toInt(), 0, cw, h)
        } else {
            val ch = min(h, max(1, (w / aspect).roundToInt()))
            intArrayOf(0, floor((h - ch) / 2.0).toInt(), w, ch)
        }
        return when (pixels) {
            PhotoPixels.Keep -> {
                val s = min(1.0, sqrt(MAX_PIXELS.toDouble() / (w.toDouble() * h)))
                Plan(0, 0, w, h, max(1, floor(w * s).toInt()), max(1, floor(h * s).toInt()), true, s < 1.0)
            }
            PhotoPixels.Passport -> {
                val c = middle(PASSPORT_W.toDouble() / PASSPORT_H)
                val big = c[2] >= PASSPORT_W
                Plan(c[0], c[1], c[2], c[3], if (big) PASSPORT_W else c[2], if (big) PASSPORT_H else c[3], true)
            }
            is PhotoPixels.Exact -> {
                val c = middle(pixels.width.toDouble() / pixels.height)
                Plan(c[0], c[1], c[2], c[3], pixels.width, pixels.height, false)
            }
        }
    }

    /** The highest JPEG quality that comes in under [limit], by halving the range. */
    fun bestQuality(limit: Int, encode: (Int) -> ByteArray): Shot {
        val best = encode(BEST_QUALITY)
        if (best.size < limit) return Shot(true, best, BEST_QUALITY)
        val lowest = encode(LOWEST_QUALITY)
        if (lowest.size >= limit) return Shot(false, lowest, LOWEST_QUALITY)
        var lo = LOWEST_QUALITY
        var loBytes = lowest
        var hi = BEST_QUALITY
        while (hi - lo > 2) {
            val mid = (lo + hi) / 2
            val bytes = encode(mid)
            if (bytes.size < limit) {
                lo = mid
                loBytes = bytes
            } else {
                hi = mid
            }
        }
        return Shot(true, loBytes, lo)
    }

    /** "50 KB", "19.5 KB". */
    fun limitText(kb: Float): String {
        val rounded = (kb * 10).roundToInt() / 10.0
        return if (rounded == floor(rounded)) "${rounded.toLong()} KB" else "$rounded KB"
    }

    /** A size that never rounds up: 49,999 bytes is "49 KB", not "50 KB". */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000 -> "${floor(bytes / 100_000.0) / 10} MB"
        bytes >= 10_000 -> "${bytes / 1000} KB"
        else -> "${max(0.1, floor(bytes / 100.0) / 10)} KB"
    }

    fun stem(name: String): String =
        name.replace(Regex("\\.[A-Za-z0-9]{1,5}$"), "").ifBlank { "Image" }

    /**
     * A JPEG with its hidden segments taken out and the picture left byte for byte: EXIF and
     * XMP, comments, maker data. The colour profile and Adobe's colour marker stay, because
     * the picture would look different without them. Null when it cannot be walked safely -
     * an unusual frame, CMYK - so the caller re-encodes instead.
     */
    fun stripJpeg(b: ByteArray): ByteArray? {
        if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
        val out = ByteArrayOutputStream(b.size)
        out.write(0xFF); out.write(0xD8)
        var i = 2
        var sawFrame = false
        while (i + 4 <= b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i += 1; continue }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > b.size) return null
            if (marker == 0xDA) {
                if (!sawFrame) return null
                out.write(b, i, b.size - i)
                return out.toByteArray()
            }
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                if (marker > 0xC2) return null
                val components = b[i + 9].toInt() and 0xFF
                if (components != 1 && components != 3) return null
                sawFrame = true
            }
            val keep = when (marker) {
                0xE1, in 0xE3..0xED, 0xEF, 0xFE -> false
                0xE2 -> String(b, i + 4, min(11, len - 2), Charsets.US_ASCII) == "ICC_PROFILE"
                else -> true
            }
            if (keep) out.write(b, i, len + 2)
            i += len + 2
        }
        return null
    }

    /** What the original carried that the result leaves behind, as a clause for the detail. */
    fun hiddenNote(kind: Kind, b: ByteArray, file: File): String {
        if (kind == Kind.HEIC || kind == Kind.AVIF) return Strings.get(R.string.engine_photo_hidden_any)
        val location = (kind == Kind.JPEG || kind == Kind.WEBP) &&
            runCatching { ExifInterface(file.path).latLong != null }.getOrDefault(false)
        if (location) return Strings.get(R.string.engine_photo_hidden_location)
        val details = when (kind) {
            Kind.JPEG -> stripJpeg(b)?.let { it.size != b.size } ?: true
            Kind.PNG -> listOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME").any { chunk -> String(b, Charsets.ISO_8859_1).contains(chunk) }
            Kind.WEBP -> String(b, 0, min(b.size, 4096), Charsets.ISO_8859_1).let { it.contains("EXIF") || it.contains("XMP ") }
            else -> false
        }
        return if (details) Strings.get(R.string.engine_photo_hidden_camera) else ""
    }
}
