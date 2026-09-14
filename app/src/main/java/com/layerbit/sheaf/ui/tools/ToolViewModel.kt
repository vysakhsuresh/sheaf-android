package com.layerbit.sheaf.ui.tools

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.jobs.JobState
import com.layerbit.sheaf.jobs.belongsTo
import com.layerbit.sheaf.ops.AddTextOp
import com.layerbit.sheaf.ops.CompressOp
import com.layerbit.sheaf.ops.CropOp
import com.layerbit.sheaf.ops.ExtractImagesOp
import com.layerbit.sheaf.ops.ExtractPagesOp
import com.layerbit.sheaf.ops.MetadataOp
import com.layerbit.sheaf.ops.NUpOp
import com.layerbit.sheaf.ops.PageNumberOp
import com.layerbit.sheaf.ops.RedactOp
import com.layerbit.sheaf.ops.SignOp
import com.layerbit.sheaf.ops.SplitBySizeOp
import com.layerbit.sheaf.ops.WatermarkOp
import com.layerbit.sheaf.ops.ExtractTextOp
import com.layerbit.sheaf.ops.OcrOp
import com.layerbit.sheaf.ops.ImagesToPdfOp
import com.layerbit.sheaf.ops.MergeOp
import com.layerbit.sheaf.ops.Op
import com.layerbit.sheaf.ops.OrganiseOp
import com.layerbit.sheaf.ops.PdfToImagesOp
import com.layerbit.sheaf.ops.RemovePasswordOp
import com.layerbit.sheaf.ops.SetPasswordOp
import com.layerbit.sheaf.ops.SplitOp
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.pdf.CompressionLevel
import com.layerbit.sheaf.pdf.CropSpec
import com.layerbit.sheaf.pdf.DocumentMetadata
import com.layerbit.sheaf.pdf.ImageStamp
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageNumberSpec
import com.layerbit.sheaf.pdf.WatermarkSpec
import com.layerbit.sheaf.pdf.ImageFormat
import com.layerbit.sheaf.pdf.PageSpec
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import com.layerbit.sheaf.pdf.TextNote
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

/**
 * Drives one tool from picking files to saving a result.
 *
 * Every tool runs the same four steps - choose files, set options, run, do something with the
 * results - so there is one screen and one model rather than nine of each. What differs
 * between tools is [ToolConfig] and which [Op] gets built at the end, which is exactly the
 * amount of variation the plural operation type was meant to leave.
 */
class ToolViewModel(app: Application) : AndroidViewModel(app) {

    private val sheaf = app as SheafApplication

    private val _state = MutableStateFlow(ToolUiState())
    val state: StateFlow<ToolUiState> = _state.asStateFlow()

    /**
     * The running or finished job, but only when it belongs to THIS tool.
     *
     * The runner is app-wide on purpose - a job has to outlive the screen that started it -
     * but that means every screen sees every job. Without this filter, finishing a Compress
     * and then opening Merge showed Merge the compressed file as though it had produced it.
     */
    val jobState: StateFlow<JobState> = sheaf.jobRunner.state
        .map { job -> if (job.belongsTo(_state.value.tool)) job else JobState.Idle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, JobState.Idle)

    fun start(tool: ToolId) {
        if (_state.value.tool == tool) return
        sheaf.jobRunner.acknowledge()
        _state.value = ToolUiState(tool = tool, config = ToolConfig.defaultFor(tool))
    }

    // ---- choosing documents ----

    fun addDocuments(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val added = mutableListOf<SelectedDoc>()
            var failure: String? = null

            for (uri in uris) {
                try {
                    val imported = sheaf.documentStore.import(uri)
                    added += inspect(imported)
                } catch (e: Exception) {
                    // Say which one failed. "Could not add file" when four were picked leaves
                    // the user to work out which by elimination.
                    failure = e.message ?: "One of those files could not be read."
                }
            }

            _state.update { current ->
                val combined = if (current.tool?.acceptsMultiple == false) {
                    // One-file tools keep only the last pick. Everything it replaces is
                    // deleted here; leaving them behind quietly filled the cache with copies
                    // of every file the user changed their mind about.
                    (current.documents + added).dropLast(1).forEach { it.file.file.delete() }
                    added.takeLast(1).ifEmpty { current.documents }
                } else {
                    current.documents + added
                }
                current.copy(documents = combined, busy = false, error = failure)
            }
        }
    }

    /**
     * Reads page count up front, and finds out whether the document needs a password.
     *
     * Knowing this before the run is what lets the UI ask for the password inline instead of
     * failing the job and making the user start over.
     */
    private suspend fun inspect(file: SheafFile): SelectedDoc = withContext(Dispatchers.IO) {
        val tool = _state.value.tool

        // An image picked for Images to PDF is not a document and must not be opened as one.
        // This used to run for everything, which is how a .webp came back as "Header doesn't
        // contain versioninfo".
        if (file.isImage) {
            val wanted = tool?.input == ToolId.InputKind.IMAGES
            return@withContext SelectedDoc(
                file = file,
                unreadableReason = if (wanted) null else "This is an image, and this tool needs a PDF."
            )
        }
        if (!file.isPdf) {
            return@withContext SelectedDoc(
                file = file,
                unreadableReason = if (tool?.input == ToolId.InputKind.IMAGES) {
                    "This is not an image."
                } else {
                    "This is not a PDF."
                }
            )
        }
        try {
            val info = sheaf.surgeon.inspect(PdfInput(file.file))

            // Document details cannot be edited without showing what they already are. Filling
            // the fields here is the difference between an editor and three empty boxes.
            if (tool == ToolId.METADATA) {
                _state.update { current ->
                    current.copy(
                        config = current.config.copy(
                            metaTitle = info.title.orEmpty(),
                            metaAuthor = info.author.orEmpty(),
                            metaSubject = info.subject.orEmpty()
                        )
                    )
                }
            }

            SelectedDoc(
                file = file,
                pageCount = info.pageCount,
                encrypted = info.isEncrypted,
                producer = info.producer
            )
        } catch (_: PdfException.PasswordRequired) {
            SelectedDoc(file = file, encrypted = true, needsPassword = true)
        } catch (e: Exception) {
            SelectedDoc(file = file, unreadableReason = e.message ?: "This file could not be read.")
        }
    }

    fun removeDocument(index: Int) {
        _state.update { current ->
            val remaining = current.documents.toMutableList()
            remaining.removeAt(index).also { it.file.file.delete() }
            current.copy(documents = remaining, pageOrder = emptyList(), rotations = emptyMap())
        }
    }

    /** Merge order is the output order, so the list has to be rearrangeable. */
    fun moveDocument(from: Int, to: Int) {
        _state.update { current ->
            val list = current.documents.toMutableList()
            if (from !in list.indices || to !in list.indices) return@update current
            list.add(to, list.removeAt(from))
            current.copy(documents = list)
        }
    }

    fun setPasswordFor(index: Int, password: String) {
        _state.update { current ->
            val list = current.documents.toMutableList()
            if (index !in list.indices) return@update current
            list[index] = list[index].copy(password = password)
            current.copy(documents = list)
        }
    }

    /** Re-checks a document once its password has been typed. */
    fun verifyPassword(index: Int) {
        val doc = _state.value.documents.getOrNull(index) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { sheaf.surgeon.inspect(PdfInput(doc.file.file, doc.password)) }
            }
            _state.update { current ->
                val list = current.documents.toMutableList()
                if (index !in list.indices) return@update current
                list[index] = result.fold(
                    onSuccess = { info ->
                        list[index].copy(
                            pageCount = info.pageCount,
                            needsPassword = false,
                            unreadableReason = null
                        )
                    },
                    onFailure = { list[index].copy(unreadableReason = "That password did not open it.") }
                )
                current.copy(documents = list, pageOrder = emptyList())
            }
        }
    }

    // ---- options ----

    fun updateConfig(transform: (ToolConfig) -> ToolConfig) {
        _state.update { current ->
            val updated = transform(current.config)
            // A changed compression level makes the last size check meaningless, and a changed
            // page makes the rendered preview show the wrong one.
            val clearCheck = updated.compression != current.config.compression
            val clearPage = updated.markPage != current.config.markPage
            if (clearPage) current.previewPage?.recycle()
            current.copy(
                config = updated,
                sizeCheck = if (clearCheck) SizeCheck.Idle else current.sizeCheck,
                previewPage = if (clearPage) null else current.previewPage
            )
        }
    }

    // ---- organise: page thumbnails ----

    /**
     * Loads low-resolution thumbnails for the organise grid.
     *
     * Deliberately tiny. The grid shows a page at about a sixth of screen width, and rendering
     * a 200-page document at full width to show it at thumbnail size is how an organise screen
     * becomes the thing that finally exhausts the heap.
     */
    fun loadPages() {
        val doc = _state.value.documents.firstOrNull() ?: return
        if (_state.value.pageOrder.isNotEmpty()) return

        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            // Remove areas needs pages big enough to read, because a box has to be drawn
            // over particular words. Organise only needs to recognise a page, so it gets the
            // small ones - rendering a 200-page document at full width would not survive.
            val width = if (_state.value.tool == ToolId.REDACT) REDACT_PAGE_WIDTH_PX else THUMBNAIL_WIDTH_PX
            val thumbs = withContext(Dispatchers.IO) {
                runCatching {
                    val engine = if (doc.encrypted) sheaf.encryptedReader else sheaf.pdfEngine
                    engine.open(doc.file.file, doc.password).use { opened ->
                        (0 until opened.pageCount).map { index ->
                            index to runCatching { opened.renderPage(index, width) }.getOrNull()
                        }
                    }
                }.getOrNull().orEmpty()
            }
            _state.update {
                it.copy(
                    busy = false,
                    pageOrder = thumbs.map { (index, _) -> index },
                    thumbnails = thumbs.mapNotNull { (index, bitmap) -> bitmap?.let { b -> index to b } }.toMap()
                )
            }
        }
    }

    fun togglePageSelected(index: Int) {
        _state.update { current ->
            val selected = current.selectedPages.toMutableSet()
            if (!selected.add(index)) selected.remove(index)
            current.copy(selectedPages = selected)
        }
    }

    fun rotateSelected(degrees: Int) {
        _state.update { current ->
            if (current.selectedPages.isEmpty()) return@update current
            val rotations = current.rotations.toMutableMap()
            for (index in current.selectedPages) {
                rotations[index] = ((rotations[index] ?: 0) + degrees).mod(360)
            }
            current.copy(rotations = rotations)
        }
    }

    fun deleteSelectedPages() {
        _state.update { current ->
            if (current.selectedPages.isEmpty()) return@update current
            val remaining = current.pageOrder.filterNot { it in current.selectedPages }
            // A document with no pages is not a document. Refuse rather than produce one.
            if (remaining.isEmpty()) {
                return@update current.copy(error = "A document has to keep at least one page.")
            }
            current.copy(pageOrder = remaining, selectedPages = emptySet(), error = null)
        }
    }

    fun movePage(from: Int, to: Int) {
        _state.update { current ->
            val order = current.pageOrder.toMutableList()
            if (from !in order.indices || to !in order.indices) return@update current
            order.add(to, order.removeAt(from))
            current.copy(pageOrder = order)
        }
    }

    // ---- running ----

    fun run() {
        val current = _state.value
        val tool = current.tool ?: return
        val ready = current.documents.filter { it.isReady }
        if (ready.isEmpty()) {
            _state.update { it.copy(error = "Add a file first.") }
            return
        }

        val op = buildOp(tool, current) ?: return
        val passwords = ready
            .mapNotNull { doc -> doc.password?.takeIf { it.isNotEmpty() }?.let { doc.file.file.path to it } }
            .toMap()

        _state.update { it.copy(error = null) }
        sheaf.jobRunner.submit(op, ready.map { it.file }, passwords)
    }

    private fun buildOp(tool: ToolId, state: ToolUiState): Op? {
        val config = state.config
        return when (tool) {
            ToolId.OCR -> OcrOp()
            ToolId.EXTRACT_TEXT -> ExtractTextOp(config.pageSpec.takeIf { it.isNotBlank() })
            ToolId.SCAN -> {
                // The scanner has its own screen; it never reaches the generic tool flow.
                _state.update { it.copy(error = "Open the scanner from the home screen.") }
                return null
            }
            ToolId.WATERMARK -> WatermarkOp(
                WatermarkSpec(
                    text = config.watermarkText,
                    opacity = config.watermarkOpacity,
                    degrees = if (config.watermarkDiagonal) 45f else 0f,
                    tiled = config.watermarkTiled
                )
            )
            ToolId.PAGE_NUMBERS -> PageNumberOp(
                PageNumberSpec(
                    position = config.numberPosition,
                    format = config.numberFormat,
                    startAt = config.numberStartAt,
                    skipFirst = config.numberSkipFirst,
                    padTo = if (config.bates) BATES_WIDTH else 0
                )
            )
            ToolId.CROP -> CropOp(
                CropSpec(
                    left = config.trim, top = config.trim, right = config.trim, bottom = config.trim,
                    resizeTo = config.resizeTo
                )
            )
            ToolId.SIGN -> {
                val signature = state.signatureFile
                if (signature == null) {
                    _state.update { it.copy(error = "Draw your signature first.") }
                    return null
                }
                SignOp(
                    ImageStamp(
                        image = signature,
                        pageIndex = (config.markPage - 1).coerceAtLeast(0),
                        anchor = config.signAnchor,
                        widthFraction = config.signWidth
                    )
                )
            }
            ToolId.ADD_TEXT -> AddTextOp(
                TextNote(
                    pageIndex = (config.markPage - 1).coerceAtLeast(0),
                    text = config.noteText,
                    left = config.noteLeft,
                    top = config.noteTop,
                    sizePoints = config.noteSize,
                    colour = config.noteColour,
                    cover = config.noteCover
                )
            )
            ToolId.REDACT -> RedactOp(state.redactions)
            ToolId.N_UP -> NUpOp(config.perSheet)
            ToolId.SPLIT_BY_SIZE -> SplitBySizeOp(config.maxPartBytes)
            ToolId.EXTRACT_IMAGES -> ExtractImagesOp()
            ToolId.METADATA -> MetadataOp(
                if (config.stripMetadata) {
                    DocumentMetadata(stripAll = true)
                } else {
                    DocumentMetadata(
                        title = config.metaTitle,
                        author = config.metaAuthor,
                        subject = config.metaSubject
                    )
                }
            )
            ToolId.MERGE -> MergeOp()
            ToolId.EXTRACT -> ExtractPagesOp(config.pageSpec)
            ToolId.SPLIT -> SplitOp(
                when (config.splitMode) {
                    ToolConfig.SplitMode.EVERY_N -> SplitOp.Mode.EveryNPages(config.splitSize)
                    ToolConfig.SplitMode.EACH_PAGE -> SplitOp.Mode.EachPage
                    ToolConfig.SplitMode.RANGES -> SplitOp.Mode.Ranges(config.pageSpec)
                }
            )
            ToolId.ORGANISE -> OrganiseOp(state.pageOrder, state.rotations.filterValues { it != 0 })
            ToolId.IMAGES_TO_PDF -> ImagesToPdfOp(
                PageSpec(size = config.pageSize, marginPoints = config.marginPoints)
            )
            ToolId.PDF_TO_IMAGES -> PdfToImagesOp(
                dpi = config.dpi,
                format = config.imageFormat,
                pageSpec = config.pageSpec.takeIf { it.isNotBlank() }
            )
            ToolId.COMPRESS -> CompressOp(config.compression)
            ToolId.SET_PASSWORD -> {
                if (config.newPassword.isBlank()) {
                    _state.update { it.copy(error = "Choose a password first.") }
                    return null
                }
                if (config.newPassword != config.confirmPassword) {
                    _state.update { it.copy(error = "The two passwords do not match.") }
                    return null
                }
                SetPasswordOp(config.newPassword)
            }
            ToolId.REMOVE_PASSWORD -> RemovePasswordOp()
        }
    }

    fun cancel() = sheaf.jobRunner.cancelAll()

    fun acknowledgeResult() {
        sheaf.jobRunner.acknowledge()
        _state.update { it.copy(pageOrder = emptyList(), selectedPages = emptySet(), rotations = emptyMap()) }
    }

    // ---- results ----

    /**
     * Loads something the reader can actually look at for a finished result.
     *
     * Several tools produce a file that looks exactly like its input - Make searchable is the
     * clearest case - and a row saying only "written, 2.1 MB" reads as nothing having
     * happened. A few lines of the text, or a thumbnail, is the difference between trusting
     * the tool and not.
     */
    fun loadPreview(file: SheafFile) {
        if (_state.value.previews.containsKey(file.file.path)) return
        viewModelScope.launch {
            val preview = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        file.isText -> ResultPreview.Text(
                            file.file.bufferedReader().use { it.readText() }.take(PREVIEW_CHARS)
                        )
                        file.isImage -> android.graphics.BitmapFactory.Options().let { options ->
                            // Decode bounds first, then subsample. A 300 DPI page export is a
                            // large bitmap and a preview needs a few hundred pixels of it.
                            options.inJustDecodeBounds = true
                            android.graphics.BitmapFactory.decodeFile(file.file.path, options)
                            options.inSampleSize =
                                (maxOf(options.outWidth, options.outHeight) / PREVIEW_PX)
                                    .coerceAtLeast(1)
                            options.inJustDecodeBounds = false
                            android.graphics.BitmapFactory.decodeFile(file.file.path, options)
                                ?.let { ResultPreview.Picture(it) }
                        }
                        else -> null
                    }
                }.getOrNull()
            } ?: return@launch

            _state.update { it.copy(previews = it.previews + (file.file.path to preview)) }
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = getApplication<Application>()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Sheaf", text))
    }

    fun exportIntent(file: SheafFile) =
        sheaf.exporter.createDocumentIntent(file.displayName, file.mimeType)

    fun shareIntent(files: List<SheafFile>) = sheaf.exporter.shareIntent(files)

    fun writeTo(destination: Uri, file: SheafFile, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { sheaf.exporter.writeTo(destination, file) }
            onDone(result.exceptionOrNull()?.message)
        }
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.thumbnails.values.forEach { it.recycle() }
        _state.value.previews.values.filterIsInstance<ResultPreview.Picture>()
            .forEach { it.bitmap.recycle() }
        _state.value.previewPage?.recycle()
        _state.value.signatureBitmap?.recycle()
    }

    // ---- previews ----

    /**
     * Renders page one for the effect preview.
     *
     * Only the first page, and only for the tools that visibly change how a page looks.
     * Organise and Remove areas load every page because they need them; these four need one,
     * and rendering a whole document to show a margin would be wasteful.
     */
    fun loadPreviewPage() {
        val doc = _state.value.documents.firstOrNull() ?: return
        if (_state.value.previewPage != null) return

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val engine = if (doc.encrypted) sheaf.encryptedReader else sheaf.pdfEngine
                    engine.open(doc.file.file, doc.password).use { opened ->
                        val index = (_state.value.config.markPage - 1)
                            .coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
                        opened.renderPage(index, PREVIEW_PAGE_WIDTH_PX)
                    }
                }.getOrNull()
            }
            _state.update { it.copy(previewPage = bitmap) }
        }
    }

    /**
     * Runs the real compression into a temp file to report what it would produce.
     *
     * Not an estimate from image sizes - the actual operation, on the actual document, thrown
     * away afterwards. An approximation would be wrong in exactly the cases that matter, and
     * the question people are asking is whether this file will get under an attachment limit.
     */
    fun checkCompressedSize() {
        val doc = _state.value.documents.firstOrNull() ?: return
        if (_state.value.sizeCheck is SizeCheck.Working) return

        viewModelScope.launch {
            _state.update { it.copy(sizeCheck = SizeCheck.Working) }
            val probe = sheaf.workspace.newOutput(doc.file.baseName, "size-check")
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    sheaf.surgeon.compress(
                        input = PdfInput(doc.file.file, doc.password),
                        level = _state.value.config.compression,
                        output = probe
                    )
                }.getOrNull()
            }
            probe.delete()

            _state.update {
                it.copy(
                    sizeCheck = if (result == null) {
                        SizeCheck.Failed
                    } else {
                        SizeCheck.Done(result.originalBytes, result.compressedBytes)
                    }
                )
            }
        }
    }

    // ---- sign and redact ----

    /** Where a freshly drawn signature is written. */
    fun newSignatureFile(): java.io.File = sheaf.workspace.newOutput("signature", "ink", "png")

    /** Stores the drawn signature. Replaces any previous one rather than accumulating files. */
    fun setSignature(file: java.io.File) {
        _state.update { current ->
            current.signatureFile?.takeIf { it != file }?.delete()
            current.signatureBitmap?.recycle()
            current.copy(
                signatureFile = file,
                // Decoded once so the placement preview can show the real ink rather than a
                // rectangle standing in for it.
                signatureBitmap = runCatching {
                    android.graphics.BitmapFactory.decodeFile(file.path)
                }.getOrNull(),
                error = null
            )
        }
    }

    fun setRedactPage(index: Int) = _state.update { it.copy(redactPage = index) }

    fun addRedaction(page: Int, area: PageArea) {
        _state.update { current ->
            val existing = current.redactions[page].orEmpty()
            current.copy(redactions = current.redactions + (page to existing + area), error = null)
        }
    }

    fun clearRedactions(page: Int) {
        _state.update { it.copy(redactions = it.redactions - page) }
    }

    private companion object {
        /** About a sixth of a phone screen. Enough to recognise a page, not to read it. */
        const val THUMBNAIL_WIDTH_PX = 220

        /** Bates numbers are conventionally six digits wide. */
        const val BATES_WIDTH = 6

        /** Readable enough to aim a redaction box at, small enough to hold a few of. */
        const val REDACT_PAGE_WIDTH_PX = 900

        /** Big enough to judge a margin or a watermark on a phone screen. */
        const val PREVIEW_PAGE_WIDTH_PX = 700

        /** Enough to see what came out without holding a whole book in memory. */
        const val PREVIEW_CHARS = 4000
        const val PREVIEW_PX = 420
    }
}

/** Something the reader can look at for a finished result. */
/** What a real trial compression reported, so the choice is made on a number rather than a hope. */
sealed interface SizeCheck {
    data object Idle : SizeCheck
    data object Working : SizeCheck
    data object Failed : SizeCheck
    data class Done(val originalBytes: Long, val compressedBytes: Long) : SizeCheck {
        val savedFraction: Float
            get() = if (originalBytes <= 0) 0f
            else ((originalBytes - compressedBytes).toFloat() / originalBytes).coerceAtLeast(0f)
    }
}

sealed interface ResultPreview {
    data class Text(val content: String) : ResultPreview
    data class Picture(val bitmap: Bitmap) : ResultPreview
}

/** A document the user picked, and what we know about it. */
data class SelectedDoc(
    val file: SheafFile,
    val pageCount: Int? = null,
    val encrypted: Boolean = false,
    val needsPassword: Boolean = false,
    val password: String? = null,
    val unreadableReason: String? = null,
    /** The app that wrote the file. Shown by Document details, since stripping it is the point. */
    val producer: String? = null
) {
    val isReady: Boolean get() = unreadableReason == null && !needsPassword
}

data class ToolUiState(
    val tool: ToolId? = null,
    val documents: List<SelectedDoc> = emptyList(),
    val config: ToolConfig = ToolConfig(),
    val busy: Boolean = false,
    val error: String? = null,
    /** Organise only: the page order being edited, as indices into the original document. */
    val pageOrder: List<Int> = emptyList(),
    val selectedPages: Set<Int> = emptySet(),
    val rotations: Map<Int, Int> = emptyMap(),
    val thumbnails: Map<Int, Bitmap> = emptyMap(),
    /** Sign only: the drawn signature, written out as a transparent PNG. */
    val signatureFile: java.io.File? = null,
    /** Redact only: the boxes drawn on each page, normalised to the page. */
    val redactions: Map<Int, List<PageArea>> = emptyMap(),
    /** Redact only: which page the canvas is showing. */
    val redactPage: Int = 0,
    /** Something to look at for each finished result, keyed by its path. */
    val previews: Map<String, ResultPreview> = emptyMap(),
    /** Page one, for the tools whose effect can be shown before running. */
    val previewPage: Bitmap? = null,
    /** The drawn signature, for the placement preview. */
    val signatureBitmap: Bitmap? = null,
    /** What compressing this document would actually produce. */
    val sizeCheck: SizeCheck = SizeCheck.Idle
) {
    /**
     * Why the button is disabled, or null when it is not.
     *
     * A greyed-out button with no explanation is the thing people report as "it does nothing".
     * Every reason a tool cannot run yet is named here and shown under the button.
     */
    val blockedReason: String?
        get() = when {
            tool == null -> "No tool selected."
            busy -> null
            documents.isEmpty() -> if (tool.input == ToolId.InputKind.IMAGES) {
                "Choose at least one image."
            } else {
                "Choose a file."
            }
            documents.any { !it.isReady } ->
                documents.firstNotNullOfOrNull { it.unreadableReason }
                    ?: "One file still needs its password."
            tool == ToolId.MERGE && documents.size < 2 -> "Merging needs at least two files."
            tool == ToolId.SIGN && signatureFile == null -> "Draw your signature above."
            tool == ToolId.REDACT && redactions.values.all { it.isEmpty() } ->
                "Drag a box over what should be removed."
            tool == ToolId.ADD_TEXT && config.noteText.isBlank() -> "Type the text to add."
            tool == ToolId.WATERMARK && config.watermarkText.isBlank() ->
                "Type the watermark text."
            tool == ToolId.SET_PASSWORD && config.newPassword.isBlank() ->
                "Choose a password."
            tool == ToolId.SET_PASSWORD && config.newPassword != config.confirmPassword ->
                "The two passwords do not match."
            tool == ToolId.EXTRACT && config.pageSpec.isBlank() ->
                "Type which pages to keep."
            tool == ToolId.CROP && config.trim == 0f && config.resizeTo == null ->
                "Choose how much to trim, or a page size."
            else -> null
        }

    val canRun: Boolean get() = blockedReason == null && !busy
}

/** Every option any tool takes. One type, because one screen renders all of them. */
data class ToolConfig(
    val pageSpec: String = "",
    val splitMode: SplitMode = SplitMode.EVERY_N,
    val splitSize: Int = 1,
    val dpi: Int = 200,
    val imageFormat: ImageFormat = ImageFormat.PNG,
    val pageSize: PageSpec.Size = PageSpec.Size.A4,
    val marginPoints: Float = 36f,
    val compression: CompressionLevel = CompressionLevel.BALANCED,
    val newPassword: String = "",
    val confirmPassword: String = "",

    val watermarkText: String = "",
    val watermarkOpacity: Float = 0.18f,
    val watermarkDiagonal: Boolean = true,
    val watermarkTiled: Boolean = true,

    val numberPosition: PageNumberSpec.Position = PageNumberSpec.Position.BOTTOM_CENTRE,
    val numberFormat: String = "{n}",
    val numberStartAt: Int = 1,
    val numberSkipFirst: Int = 0,
    val bates: Boolean = false,

    val trim: Float = 0f,
    val resizeTo: PageSpec.Size? = null,

    /** The page a signature or a note lands on, and the page the preview shows. */
    val markPage: Int = 1,
    val signAnchor: ImageStamp.Anchor = ImageStamp.Anchor.BOTTOM_RIGHT,
    val signWidth: Float = 0.3f,

    val noteText: String = "",
    /** Where the note sits, as a fraction of the page. Set by tapping the preview. */
    val noteLeft: Float = 0.1f,
    val noteTop: Float = 0.1f,
    val noteSize: Float = 12f,
    val noteColour: Int = NOTE_BLACK,
    val noteCover: Boolean = false,

    val perSheet: Int = 2,
    val maxPartBytes: Long = 10L * 1024 * 1024,

    val stripMetadata: Boolean = false,
    val metaTitle: String = "",
    val metaAuthor: String = "",
    val metaSubject: String = ""
) {
    enum class SplitMode { EVERY_N, EACH_PAGE, RANGES }

    companion object {
        fun defaultFor(tool: ToolId): ToolConfig = when (tool) {
            // Extracting defaults to nothing selected on purpose: "1-" would silently produce
            // a copy of the whole document and look like the tool did nothing.
            ToolId.EXTRACT -> ToolConfig(pageSpec = "")
            ToolId.SPLIT -> ToolConfig(splitMode = SplitMode.EVERY_N, splitSize = 1)
            else -> ToolConfig()
        }
    }
}

/**
 * The three inks a note can be written in.
 *
 * Packed ARGB rather than a Compose colour, because the value is carried all the way down to
 * the PDF engine, which knows nothing about Compose.
 *
 * Black is a shade off 0x000000 on purpose: it is what the rest of the app treats as ink, and
 * true black beside scanned type looks harder than the page it is written on.
 */
val NOTE_BLACK: Int = 0xFF111111.toInt()
val NOTE_RED: Int = 0xFFC62828.toInt()
val NOTE_BLUE: Int = 0xFF1A4FA0.toInt()
