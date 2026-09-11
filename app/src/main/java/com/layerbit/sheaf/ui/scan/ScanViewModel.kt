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
import com.layerbit.sheaf.scan.PageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The scanner: capture, straighten, keep, repeat, then make a PDF.
 *
 * Pages accumulate as files on disk rather than as bitmaps in memory. Scanning a ten-page
 * document is the normal case, and ten full-size captures held in the heap is a crash.
 *
 * So each page is written out as soon as it is accepted, and only the page being adjusted is
 * ever decoded.
 */
class ScanViewModel(app: Application) : AndroidViewModel(app) {

    private val sheaf = app as SheafApplication

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    /**
     * The scanner's own job. It submits an Images to PDF operation, so that is the tool it
     * watches for - without the filter it would show a compress or a merge that happened to
     * be running elsewhere.
     */
    val jobState: StateFlow<JobState> = sheaf.jobRunner.state
        .map { job -> if (job.belongsTo(ToolId.IMAGES_TO_PDF)) job else JobState.Idle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, JobState.Idle)

    /** Takes the just-captured file, decodes it, and moves to corner adjustment. */
    fun captured(file: File) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val decoded = BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@runCatching null
                    // The sensor writes orientation into EXIF rather than rotating the pixels,
                    // so a portrait capture decodes sideways unless this is applied.
                    val upright = applyExifRotation(file, decoded)
                    PageProcessor.downscale(upright)
                }.getOrNull()
            }
            file.delete()

            if (bitmap == null) {
                _state.update { it.copy(busy = false, error = "That photo could not be read.") }
                return@launch
            }

            _state.update {
                it.copy(
                    busy = false,
                    editing = EditingPage(
                        bitmap = bitmap,
                        corners = PageProcessor.Corners.default(bitmap.width, bitmap.height)
                    )
                )
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

    fun setFilter(filter: PageProcessor.Filter) {
        _state.update { current ->
            val editing = current.editing ?: return@update current
            current.copy(editing = editing.copy(filter = filter))
        }
    }

    /** Flattens and filters the page being adjusted, writes it out, and clears the editor. */
    fun keepPage() {
        val editing = _state.value.editing ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }

            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val flattened = PageProcessor.flatten(editing.bitmap, editing.corners)
                    val filtered = PageProcessor.applyFilter(flattened, editing.filter)
                    if (filtered !== flattened) flattened.recycle()

                    val file = sheaf.workspace.newOutput("scan", "page", "jpg")
                    FileOutputStream(file).use { out ->
                        filtered.compress(Bitmap.CompressFormat.JPEG, SCAN_JPEG_QUALITY, out)
                    }
                    val thumbnail = PageProcessor.downscale(filtered, THUMBNAIL_PX)
                    if (thumbnail !== filtered) filtered.recycle()
                    ScannedPage(file, thumbnail)
                }.getOrNull()
            }

            editing.bitmap.recycle()

            if (saved == null) {
                _state.update { it.copy(busy = false, editing = null, error = "That page could not be saved.") }
                return@launch
            }
            _state.update { it.copy(busy = false, editing = null, pages = it.pages + saved) }
        }
    }

    /** Throws away the page being adjusted and goes back to the viewfinder. */
    fun discardPage() {
        _state.update { current ->
            current.editing?.bitmap?.recycle()
            current.copy(editing = null)
        }
    }

    fun removePage(index: Int) {
        _state.update { current ->
            val remaining = current.pages.toMutableList()
            if (index !in remaining.indices) return@update current
            val removed = remaining.removeAt(index)
            removed.file.delete()
            removed.thumbnail.recycle()
            current.copy(pages = remaining)
        }
    }

    /** Hands the accepted pages to the same Images to PDF operation the tool grid uses. */
    fun makePdf() {
        val pages = _state.value.pages
        if (pages.isEmpty()) {
            _state.update { it.copy(error = "Scan a page first.") }
            return
        }

        val inputs = pages.mapIndexed { index, page ->
            SheafFile(
                file = page.file,
                displayName = "Page ${index + 1}",
                origin = SheafFile.Origin.Derived("scan"),
                mimeType = SheafFile.MIME_JPEG
            )
        }

        _state.update { it.copy(error = null) }
        sheaf.jobRunner.submit(
            // FIT_IMAGE, because a scanned page has already been cropped to its own edges -
            // putting it on an A4 sheet with margins would add a border that was not there.
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
            current.pages.forEach { it.thumbnail.recycle() }
            ScanUiState()
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Where CameraX should write the next capture. */
    fun captureTarget(): File = sheaf.workspace.newOutput("capture", "raw", "jpg")

    override fun onCleared() {
        super.onCleared()
        _state.value.editing?.bitmap?.recycle()
        _state.value.pages.forEach { it.thumbnail.recycle() }
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

    private companion object {
        /**
         * 88, not 100. A scan is a photograph of paper; the difference above this is invisible
         * and the file size difference is not - and these get merged into one PDF.
         */
        const val SCAN_JPEG_QUALITY = 88
        const val THUMBNAIL_PX = 260
    }
}

enum class CornerHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/** A capture being adjusted, before it is accepted as a page. */
data class EditingPage(
    val bitmap: Bitmap,
    val corners: PageProcessor.Corners,
    val filter: PageProcessor.Filter = PageProcessor.Filter.DOCUMENT
)

/** A page that has been flattened, filtered and written out. */
data class ScannedPage(val file: File, val thumbnail: Bitmap)

data class ScanUiState(
    val pages: List<ScannedPage> = emptyList(),
    val editing: EditingPage? = null,
    val busy: Boolean = false,
    val error: String? = null
)
