package com.layerbit.sheaf.ui.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.jobs.JobState
import com.layerbit.sheaf.jobs.belongsTo
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ops.ImagesToPdfOp
import com.layerbit.sheaf.pdf.PageSpec
import com.layerbit.sheaf.scan.AutoCaptureTracker
import com.layerbit.sheaf.scan.PageProcessor
import com.layerbit.sheaf.scan.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.annotation.StringRes
import com.layerbit.sheaf.R
import com.layerbit.sheaf.text.Strings
import java.io.File
import java.io.FileOutputStream

/**
 * The scanner: capture, straighten, keep, repeat, then make a PDF.
 *
 * Pages accumulate as files on disk rather than as bitmaps in memory. Scanning a ten-page
 * document is the normal case, and ten full-size captures held in the heap is a crash.
 *
 * So each page is written out as soon as it is accepted, and only the page being adjusted is
 * ever decoded. Beside each page sits the photo it was cut from, uncropped, so the page can be
 * reopened and cropped again later: a crop that looked right on the phone and is a little off
 * in the strip is fixed in two taps instead of rescanned.
 *
 * Three ways of working, chosen in the viewfinder:
 *  - One page: each photo opens for its corners to be checked, then joins the strip.
 *  - Batch: each photo is cropped to the outline the detector found and goes straight into
 *    the strip, so a stack of pages is one photo after another. Any page can be reopened.
 *  - ID card: the front, then the back, each checked, then both laid out on one A4 page.
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val sheaf = app as SheafApplication

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    /** Whether the viewfinder takes the picture itself once the page is held still. */
    val autoCapture: StateFlow<Boolean> = sheaf.settings.state
        .map { it.autoCapture }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sheaf.settings.current.autoCapture)

    /**
     * One page is processed at a time, whatever asked for it.
     *
     * A batch can be photographed faster than it is processed, and each page in flight holds
     * a full-size photo and a full-size result. Queuing them behind one lock keeps that to one
     * page's worth of memory however fast the shutter goes.
     */
    private val work = Mutex()

    /**
     * The scanner's own job. It submits an Images to PDF operation, so that is the tool it
     * watches for - without the filter it would show a compress or a merge that happened to
     * be running elsewhere.
     */
    val jobState: StateFlow<JobState> = sheaf.jobRunner.state
        .map { job -> if (job.belongsTo(ToolId.IMAGES_TO_PDF)) job else JobState.Idle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, JobState.Idle)

    /**
     * Auto-capture's memory of the last photo. Kept here rather than in the viewfinder, which
     * is rebuilt every time the corner editor closes - a fresh one would see the page just
     * kept still lying under the camera and take it again.
     */
    val autoTracker = AutoCaptureTracker()

    fun toggleAutoCapture() = sheaf.settings.update { it.copy(autoCapture = !it.autoCapture) }

    fun setMode(mode: ScanMode) {
        _state.update { current ->
            if (current.mode == mode) return@update current
            // A front photographed in ID card mode means nothing on its own.
            current.pendingIdFront?.original?.delete()
            current.copy(mode = mode, pendingIdFront = null, error = null)
        }
    }

    /**
     * Takes the just-captured file and either opens it for corner adjustment or, in batch
     * mode, crops it straight into the strip.
     *
     * @param liveQuad the outline the viewfinder had found when the shutter went, normalised
     *   to the visible frame. Used when the still photo itself yields nothing.
     */
    fun captured(file: File, liveQuad: Quad?) {
        val mode = _state.value.mode
        val batch = mode == ScanMode.BATCH
        if (batch) _state.update { it.copy(processing = it.processing + 1, error = null) }
        else _state.update { it.copy(busy = true, error = null) }

        viewModelScope.launch {
            work.withLock {
                val photo = withContext(Dispatchers.IO) {
                    runCatching { decodeUpright(file) }.getOrNull()
                }
                file.delete()

                if (photo == null) {
                    _state.update {
                        it.copy(
                            busy = false,
                            processing = if (batch) it.processing - 1 else it.processing,
                            error = Strings.get(R.string.reading_scan_photo_unreadable)
                        )
                    }
                    return@withLock
                }

                val (corners, found) = withContext(Dispatchers.Default) {
                    val still = runCatching { PageProcessor.detectCorners(photo) }.getOrNull()
                    when {
                        still != null -> still to true
                        liveQuad != null -> PageProcessor.Corners.fromQuad(liveQuad, photo.width, photo.height) to true
                        else -> PageProcessor.Corners.default(photo.width, photo.height) to false
                    }
                }

                if (batch) {
                    val filter = _state.value.lastFilter
                    val page = withContext(Dispatchers.IO) {
                        runCatching {
                            val source = PageSource(saveOriginal(photo), corners, filter)
                            buildPage(PageKind.PAGE, listOf(source), mapOf(0 to photo))
                        }.getOrNull()
                    }
                    photo.recycle()
                    _state.update {
                        it.copy(
                            processing = it.processing - 1,
                            pages = if (page != null) it.pages + page else it.pages,
                            error = if (page == null) Strings.get(R.string.reading_scan_page_not_saved) else it.error
                        )
                    }
                    return@withLock
                }

                val purpose = when {
                    mode != ScanMode.ID_CARD -> EditPurpose.NewPage
                    _state.value.pendingIdFront == null -> EditPurpose.IdFront
                    else -> EditPurpose.IdBack
                }
                _state.update {
                    it.copy(
                        busy = false,
                        editing = EditingPage(
                            bitmap = photo,
                            corners = corners,
                            // Cards carry photos and colour that the hard Document look would
                            // flatten, so they start as they are.
                            filter = if (mode == ScanMode.ID_CARD) PageProcessor.Filter.ORIGINAL else it.lastFilter,
                            purpose = purpose,
                            detected = found
                        )
                    )
                }
            }
        }
    }

    fun moveCorner(which: CornerHandle, x: Float, y: Float) {
        _state.update { current ->
            val editing = current.editing ?: return@update current
            val clampedX = x.coerceIn(0f, editing.bitmap.width.toFloat())
            val clampedY = y.coerceIn(0f, editing.bitmap.height.toFloat())
            val point = PageProcessor.Point(clampedX, clampedY)
            val c = editing.corners
            val moved = when (which) {
                CornerHandle.TOP_LEFT -> c.copy(topLeft = point)
                CornerHandle.TOP_RIGHT -> c.copy(topRight = point)
                CornerHandle.BOTTOM_RIGHT -> c.copy(bottomRight = point)
                CornerHandle.BOTTOM_LEFT -> c.copy(bottomLeft = point)
            }
            current.copy(editing = editing.copy(corners = moved))
        }
    }

    /** Puts the corners back on the whole photo, for a page that fills the frame edge to edge. */
    fun useWholePhoto() {
        _state.update { current ->
            val editing = current.editing ?: return@update current
            current.copy(editing = editing.copy(corners = PageProcessor.Corners.default(editing.bitmap.width, editing.bitmap.height, inset = 0f)))
        }
    }

    fun setFilter(filter: PageProcessor.Filter) {
        _state.update { current ->
            val editing = current.editing ?: return@update current
            current.copy(
                editing = editing.copy(filter = filter),
                // Remembered for batch pages, which never open the editor to be asked. ID
                // cards keep their own default and do not change it.
                lastFilter = if (editing.purpose.isCard) current.lastFilter else filter
            )
        }
    }

    /** Accepts the corners and look for the page being adjusted. What follows depends on why it was open. */
    fun keepPage() {
        val editing = _state.value.editing ?: return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            work.withLock {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { keep(editing) }.getOrElse { KeepOutcome.Failed }
                }
                editing.bitmap.recycle()
                _state.update { current ->
                    when (outcome) {
                        is KeepOutcome.Added -> current.copy(busy = false, editing = null, pages = current.pages + outcome.page)
                        is KeepOutcome.FrontHeld -> current.copy(busy = false, editing = null, pendingIdFront = outcome.front)
                        is KeepOutcome.Replaced -> {
                            val old = current.pages.getOrNull(outcome.index)
                            val pages = current.pages.toMutableList()
                            if (old != null) {
                                old.file.delete()
                                old.thumbnail.recycle()
                                pages[outcome.index] = outcome.page
                            }
                            current.copy(busy = false, editing = null, pages = pages)
                        }
                        is KeepOutcome.NextSide -> current.copy(busy = false, editing = outcome.next)
                        KeepOutcome.Failed -> current.copy(busy = false, editing = null, error = Strings.get(R.string.reading_scan_page_not_saved))
                    }
                }
            }
        }
    }

    private fun keep(editing: EditingPage): KeepOutcome {
        val chosen = { original: File -> PageSource(original, editing.corners, editing.filter) }
        return when (val purpose = editing.purpose) {
            EditPurpose.NewPage -> {
                val source = chosen(saveOriginal(editing.bitmap))
                KeepOutcome.Added(buildPage(PageKind.PAGE, listOf(source), mapOf(0 to editing.bitmap)))
            }
            EditPurpose.IdFront -> KeepOutcome.FrontHeld(chosen(saveOriginal(editing.bitmap)))
            EditPurpose.IdBack -> {
                val front = _state.value.pendingIdFront ?: return KeepOutcome.Failed
                val back = chosen(saveOriginal(editing.bitmap))
                val page = buildPage(PageKind.ID_CARD, listOf(front, back), mapOf(1 to editing.bitmap))
                _state.update { it.copy(pendingIdFront = null) }
                KeepOutcome.Added(page)
            }
            is EditPurpose.Recrop -> {
                val sources = purpose.sources.toMutableList()
                sources[purpose.side] = chosen(sources[purpose.side].original)
                val next = purpose.side + 1
                if (next < sources.size) {
                    // The back of a card, after its front.
                    val photo = decodeUpright(sources[next].original) ?: return KeepOutcome.Failed
                    KeepOutcome.NextSide(
                        EditingPage(
                            bitmap = photo,
                            corners = sources[next].corners,
                            filter = sources[next].filter,
                            purpose = purpose.copy(side = next, sources = sources),
                            detected = true
                        )
                    )
                } else {
                    KeepOutcome.Replaced(purpose.index, buildPage(purpose.kind, sources, mapOf(purpose.side to editing.bitmap)))
                }
            }
        }
    }

    /** Throws away the page being adjusted and goes back to the viewfinder. A recrop changes nothing. */
    fun discardPage() {
        _state.update { current ->
            current.editing?.bitmap?.recycle()
            current.copy(editing = null)
        }
    }

    /** Opens a kept page again on its uncropped photo, with the corners it was cut with. */
    fun reopenPage(index: Int) {
        val page = _state.value.pages.getOrNull(index) ?: return
        if (_state.value.editing != null) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            work.withLock {
                val first = page.sources.first()
                val photo = withContext(Dispatchers.IO) { runCatching { decodeUpright(first.original) }.getOrNull() }
                if (photo == null) {
                    _state.update { it.copy(busy = false, error = Strings.get(R.string.reading_scan_photo_gone)) }
                    return@withLock
                }
                _state.update {
                    it.copy(
                        busy = false,
                        editing = EditingPage(
                            bitmap = photo,
                            corners = first.corners,
                            filter = first.filter,
                            purpose = EditPurpose.Recrop(index, 0, page.kind, page.sources),
                            detected = true
                        )
                    )
                }
            }
        }
    }

    fun removePage(index: Int) {
        _state.update { current ->
            val remaining = current.pages.toMutableList()
            if (index !in remaining.indices) return@update current
            val removed = remaining.removeAt(index)
            removed.file.delete()
            removed.sources.forEach { it.original.delete() }
            removed.thumbnail.recycle()
            current.copy(pages = remaining)
        }
    }

    /** Hands the accepted pages to the same Images to PDF operation the tool grid uses. */
    fun makePdf() {
        val current = _state.value
        val pages = current.pages
        if (pages.isEmpty()) {
            _state.update { it.copy(error = Strings.get(R.string.reading_scan_first)) }
            return
        }
        if (current.processing > 0) {
            _state.update { it.copy(error = Strings.get(R.string.reading_scan_still_saving)) }
            return
        }

        val inputs = pages.mapIndexed { index, page ->
            SheafFile(
                file = page.file,
                displayName = Strings.get(R.string.reading_page_n, index + 1),
                origin = SheafFile.Origin.Derived("scan"),
                mimeType = SheafFile.MIME_JPEG
            )
        }

        _state.update { it.copy(error = null) }
        sheaf.jobRunner.submit(
            // FIT_IMAGE, because a scanned page has already been cropped to its own edges -
            // putting it on an A4 sheet with margins would add a border that was not there.
            // An ID card page is already A4-shaped, so it fits exactly too.
            op = ImagesToPdfOp(PageSpec(size = PageSpec.Size.FIT_IMAGE), outputName = "Scan"),
            inputs = inputs
        )
    }

    fun cancelJob() = sheaf.jobRunner.cancelAll()

    /** The share sheet for a finished scan, through the same FileProvider every result uses. */
    fun shareIntent(files: List<SheafFile>) = sheaf.exporter.shareIntent(files)

    fun acknowledgeResult() {
        sheaf.jobRunner.acknowledge()
        _state.update { current ->
            releaseOriginals(current)
            current.pages.forEach { it.thumbnail.recycle() }
            ScanUiState(mode = current.mode, lastFilter = current.lastFilter)
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * A shutter press that produced no file.
     *
     * Every other write to [ScanUiState.error] comes from work this model did itself, so the
     * camera had nowhere to report to and the viewfinder swallowed its failures in silence -
     * a press that did nothing at all, with no page appearing and no reason given. [reason]
     * is phrased by the caller, which is the only place that knows what CameraX objected to.
     */
    fun captureFailed(reason: String) {
        _state.update { it.copy(error = reason) }
    }

    /** Where CameraX should write the next capture. */
    fun captureTarget(): File = sheaf.workspace.newOutput("capture", "raw", "jpg")

    override fun onCleared() {
        super.onCleared()
        val current = _state.value
        current.editing?.bitmap?.recycle()
        current.pages.forEach { it.thumbnail.recycle() }
        // The uncropped photos only exist so pages can be recropped here. Once the scanner is
        // gone nothing can reopen them, so they would only sit in scratch until trimmed.
        releaseOriginals(current)
    }

    private fun releaseOriginals(state: ScanUiState) {
        state.pages.forEach { page -> page.sources.forEach { it.original.delete() } }
        state.pendingIdFront?.original?.delete()
    }

    /**
     * The uncropped photo, kept for recropping.
     *
     * Written at the size it is already held at - at most [PageProcessor.MAX_CAPTURE_DIMENSION]
     * on the long edge and already upright - so reopening it needs no rotation and no second
     * downscale, and it costs a fraction of the camera's own file.
     */
    private fun saveOriginal(photo: Bitmap): File {
        val file = sheaf.workspace.newOutput("scan", "original", "jpg")
        FileOutputStream(file).use { out -> photo.compress(Bitmap.CompressFormat.JPEG, ORIGINAL_JPEG_QUALITY, out) }
        return file
    }

    /**
     * Cuts a page from its sources and writes it out.
     *
     * @param inMemory photos already decoded, by source index, so the one just captured is not
     *   read back from disk. The caller still owns and recycles them.
     */
    private fun buildPage(kind: PageKind, sources: List<PageSource>, inMemory: Map<Int, Bitmap>): ScannedPage {
        fun cut(index: Int): Bitmap {
            val source = sources[index]
            val held = inMemory[index]
            val photo = held ?: decodeUpright(source.original) ?: error("The photo could not be read")
            try {
                return PageProcessor.process(photo, source.corners, source.filter)
            } finally {
                if (held == null) photo.recycle()
            }
        }

        val page = when (kind) {
            PageKind.PAGE -> cut(0)
            PageKind.ID_CARD -> {
                val front = cut(0)
                val back = cut(1)
                PageProcessor.composeIdCard(front, back).also {
                    front.recycle()
                    back.recycle()
                }
            }
        }

        val file = sheaf.workspace.newOutput("scan", "page", "jpg")
        FileOutputStream(file).use { out -> page.compress(Bitmap.CompressFormat.JPEG, SCAN_JPEG_QUALITY, out) }
        val thumbnail = PageProcessor.downscale(page, THUMBNAIL_PX)
        if (thumbnail !== page) page.recycle()
        return ScannedPage(file, thumbnail, sources, kind)
    }

    /** Decodes a photo already subsampled, turned upright and shrunk to the working size. */
    private fun decodeUpright(file: File): Bitmap? {
        val decoded = decodeSampled(file) ?: return null
        // The sensor writes orientation into EXIF rather than rotating the pixels, so a
        // portrait capture decodes sideways unless this is applied.
        val upright = applyExifRotation(file, decoded)
        val scaled = PageProcessor.downscale(upright)
        if (scaled !== upright) upright.recycle()
        return scaled
    }

    /**
     * Decodes the capture already subsampled, but never below what [PageProcessor.downscale]
     * is about to produce anyway.
     *
     * A 12-megapixel capture decoded whole is 48 MB, the rotation below holds a second copy of
     * it while it works, and the downscale adds a third before either is collected. On a phone
     * with a small heap that is an OutOfMemoryError, and because the caller's `runCatching`
     * takes a Throwable it surfaces as "That photo could not be read." - blaming the photo.
     *
     * Subsampling by a power of two is nearly free inside the decoder. Stopping at the last
     * power that still leaves the long edge above [PageProcessor.MAX_CAPTURE_DIMENSION] means
     * the proper resample still does the final step, so nothing is lost to the cheap one.
     */
    private fun decodeSampled(file: File): Bitmap? {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)

        val longest = maxOf(options.outWidth, options.outHeight)
        if (longest <= 0) return null

        var sample = 1
        while (longest / (sample * 2) >= PageProcessor.MAX_CAPTURE_DIMENSION) sample *= 2

        options.inSampleSize = sample
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun applyExifRotation(file: File, bitmap: Bitmap): Bitmap {
        val degrees = when (
            runCatching { ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            ) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees == 0f) return bitmap

        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private sealed interface KeepOutcome {
        data class Added(val page: ScannedPage) : KeepOutcome
        data class FrontHeld(val front: PageSource) : KeepOutcome
        data class Replaced(val index: Int, val page: ScannedPage) : KeepOutcome
        data class NextSide(val next: EditingPage) : KeepOutcome
        data object Failed : KeepOutcome
    }

    private companion object {
        /**
         * 88, not 100. A scan is a photograph of paper; the difference above this is invisible
         * and the file size difference is not - and these get merged into one PDF.
         */
        const val SCAN_JPEG_QUALITY = 88
        /** A little higher than the page: it is the source every recrop starts from. */
        const val ORIGINAL_JPEG_QUALITY = 92
        const val THUMBNAIL_PX = 260
    }
}

enum class CornerHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

enum class ScanMode(@StringRes val labelRes: Int) {
    SINGLE(R.string.reading_scan_mode_single),
    BATCH(R.string.reading_scan_mode_batch),
    ID_CARD(R.string.reading_scan_mode_id_card);

    val label: String get() = Strings.get(labelRes)
}

enum class PageKind { PAGE, ID_CARD }

/** One photo a page is cut from, and how it was cut. An ID card page has two. */
data class PageSource(
    val original: File,
    val corners: PageProcessor.Corners,
    val filter: PageProcessor.Filter
)

/** Why the editor is open, which decides what keeping does and what the buttons say. */
sealed interface EditPurpose {
    data object NewPage : EditPurpose
    data object IdFront : EditPurpose
    data object IdBack : EditPurpose
    data class Recrop(val index: Int, val side: Int, val kind: PageKind, val sources: List<PageSource>) : EditPurpose

    val isCard: Boolean
        get() = this == IdFront || this == IdBack || (this is Recrop && kind == PageKind.ID_CARD)
}

/** A photo being adjusted, before it is accepted as a page. */
data class EditingPage(
    val bitmap: Bitmap,
    val corners: PageProcessor.Corners,
    val filter: PageProcessor.Filter = PageProcessor.Filter.DOCUMENT,
    val purpose: EditPurpose = EditPurpose.NewPage,
    /** Whether the corners came from a detected outline rather than the default rectangle. */
    val detected: Boolean = false
)

/** A page that has been cut, filtered and written out, with the photos it came from. */
data class ScannedPage(
    val file: File,
    val thumbnail: Bitmap,
    val sources: List<PageSource> = emptyList(),
    val kind: PageKind = PageKind.PAGE
)

data class ScanUiState(
    val pages: List<ScannedPage> = emptyList(),
    val editing: EditingPage? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val mode: ScanMode = ScanMode.SINGLE,
    /** The front of a card, cropped and waiting for its back. */
    val pendingIdFront: PageSource? = null,
    /** Batch photos taken but not yet in the strip. */
    val processing: Int = 0,
    val lastFilter: PageProcessor.Filter = PageProcessor.Filter.DOCUMENT
)
