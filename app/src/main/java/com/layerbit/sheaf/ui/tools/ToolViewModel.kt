package com.layerbit.sheaf.ui.tools

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.jobs.JobState
import com.layerbit.sheaf.jobs.belongsTo
import com.layerbit.sheaf.ops.AddImageOp
import com.layerbit.sheaf.ops.AddTextOp
import com.layerbit.sheaf.ops.AttachmentsOp
import com.layerbit.sheaf.ops.BackgroundOp
import com.layerbit.sheaf.ops.BookmarksOp
import com.layerbit.sheaf.ops.CleanOp
import com.layerbit.sheaf.ops.CreateFormOp
import com.layerbit.sheaf.ops.DigitalSignOp
import com.layerbit.sheaf.ops.PdfAOp
import com.layerbit.sheaf.ops.CompareOp
import com.layerbit.sheaf.ops.RentReceipts
import com.layerbit.sheaf.ops.RentReceiptsOp
import com.layerbit.sheaf.ops.EditTextOp
import com.layerbit.sheaf.ops.TextEditPlans
import com.layerbit.sheaf.pdf.EditablePage
import com.layerbit.sheaf.pdf.TextEdit
import com.layerbit.sheaf.ops.PageLabelsOp
import com.layerbit.sheaf.ops.AnnotateOp
import com.layerbit.sheaf.ops.FillFormOp
import com.layerbit.sheaf.ops.HeaderFooterOp
import com.layerbit.sheaf.ops.InsertPagesOp
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
import com.layerbit.sheaf.ops.OfficeTarget
import com.layerbit.sheaf.ops.OfficeToPdfOp
import com.layerbit.sheaf.ops.PdfToOfficeOp
import com.layerbit.sheaf.ops.ImagesToPdfOp
import com.layerbit.sheaf.ops.MergeOp
import com.layerbit.sheaf.ops.Op
import com.layerbit.sheaf.ops.OrganiseOp
import com.layerbit.sheaf.ops.PdfToImagesOp
import com.layerbit.sheaf.ops.RemovePasswordOp
import com.layerbit.sheaf.ops.ResizePhotoOp
import com.layerbit.sheaf.ops.KeyPointsOp
import com.layerbit.sheaf.ops.RecipeOp
import com.layerbit.sheaf.ops.EditPicturesOp
import com.layerbit.sheaf.pdf.PictureEdit
import com.layerbit.sheaf.ops.PhotoPixels
import com.layerbit.sheaf.ops.SetPasswordOp
import com.layerbit.sheaf.ops.SplitOp
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.pdf.AttachmentInfo
import com.layerbit.sheaf.pdf.CleanSpec
import com.layerbit.sheaf.pdf.CompressionLevel
import com.layerbit.sheaf.pdf.NewField
import com.layerbit.sheaf.pdf.OcrScript
import com.layerbit.sheaf.pdf.SignSpec
import com.layerbit.sheaf.pdf.OutlineEntry
import com.layerbit.sheaf.pdf.PageLabelSpec
import com.layerbit.sheaf.pdf.CropSpec
import com.layerbit.sheaf.pdf.DocumentMetadata
import com.layerbit.sheaf.pdf.DocumentPermissions
import com.layerbit.sheaf.pdf.FormField
import com.layerbit.sheaf.pdf.StampSpec
import com.layerbit.sheaf.pdf.ImageStamp
import com.layerbit.sheaf.pdf.PageArea
import com.layerbit.sheaf.pdf.PageMark
import com.layerbit.sheaf.pdf.PagePoint
import com.layerbit.sheaf.pdf.PageSelection
import com.layerbit.sheaf.pdf.PageSize
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

    fun start(tool: ToolId, preset: String? = null) {
        if (_state.value.tool == tool) return
        sheaf.jobRunner.acknowledge()
        val config = ToolConfig.defaultFor(tool).let { base ->
            when (preset) {
                com.layerbit.sheaf.ops.Suggest.PRESET_EXCEL -> base.copy(officeTarget = OfficeTarget.EXCEL)
                else -> base
            }
        }
        _state.value = ToolUiState(tool = tool, config = config)

        // A share of several documents from another app lands here rather than in the viewer,
        // which can only show one of them. The list is taken once and cleared, so coming back
        // to this tool later does not reload what was shared an hour ago.
        val shared = sheaf.handoff.take()
        if (shared.isNotEmpty()) addDocuments(shared)

        // Files opened from Open a file, already looked at, handed to the tool picked for them.
        val opened = sheaf.handoff.takeFiles()
        if (opened.isNotEmpty()) {
            viewModelScope.launch {
                _state.update { it.copy(busy = true) }
                for (file in opened) addOpenedNow(file)
                _state.update { it.copy(busy = false) }
            }
        }
    }

    /**
     * Takes a file Open a file already copied in, keeping its name and type. Finished before
     * it returns, so several arrive in the order they were opened. Copied again, so this tool's
     * list owns what it holds and Start over on Open a file cannot pull it out from under it.
     */
    private suspend fun addOpenedNow(source: SheafFile) {
        if (!source.file.isFile) return
        val copied = withContext(Dispatchers.IO) {
            runCatching {
                val destination = sheaf.workspace.newOutput(source.baseName, "carried", source.file.extension.ifBlank { "pdf" })
                source.file.copyTo(destination, overwrite = true)
                SheafFile(destination, source.displayName, SheafFile.Origin.Derived("carried"), source.mimeType)
            }.getOrNull()
        } ?: return
        val inspected = inspect(copied)
        _state.update { current ->
            val combined = if (current.tool?.acceptsMultiple == false) listOf(inspected) else current.documents + inspected
            current.copy(documents = combined, config = current.config.forSeveral(current.metaSeed, combined.size))
        }
        loadFileThumbs()
    }

    // ---- choosing documents ----

    /**
     * Takes a file Sheaf already made - the result of the previous tool in a chain.
     *
     * Not imported, because there is nothing to import from: it is already in the workspace.
     * It is copied all the same, so that the next tool's output cannot be mistaken for its
     * input and so that this tool's own list owns what it holds.
     */
    fun addLocalDocument(path: String) {
        val source = java.io.File(path)
        if (!source.isFile) {
            _state.update { it.copy(error = "That result is no longer available.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    val destination = sheaf.workspace.newOutput(
                        source.nameWithoutExtension,
                        "carried",
                        source.extension.ifBlank { "pdf" }
                    )
                    source.copyTo(destination, overwrite = true)
                    SheafFile(destination, source.name, SheafFile.Origin.Derived("carried"))
                }.getOrNull()
            }

            if (copied == null) {
                _state.update { it.copy(busy = false, error = "That result could not be read.") }
                return@launch
            }

            val inspected = inspect(copied)
            _state.update { current ->
                val combined = if (current.tool?.acceptsMultiple == false) {
                    listOf(inspected)
                } else {
                    current.documents + inspected
                }
                current.copy(
                    documents = combined,
                    busy = false,
                    formFields = emptyList(),
                    formValues = emptyMap(),
                    formRead = false,
                    outline = emptyList(),
                    outlineRead = false,
                    attachments = emptyList(),
                    attachmentsRead = false,
                    findReport = null,
                    newFields = emptyList(),
                    pictures = emptyMap(),
                    pictureChanges = emptyMap(),
                    selectedPicture = null,
                    editPages = emptyMap(),
                    lineEdits = emptyMap(),
                    selectedLine = null,
                    replaceStatus = null
                )
            }
            loadFileThumbs()
        }
    }

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
                current.copy(
                    documents = combined,
                    busy = false,
                    error = failure,
                    config = current.config.forSeveral(current.metaSeed, combined.size),
                    // A different document is a different form, so what was read about the
                    // last one has to go rather than be shown against this one.
                    formFields = emptyList(),
                    formValues = emptyMap(),
                    formRead = false,
                    outline = emptyList(),
                    outlineRead = false,
                    attachments = emptyList(),
                    attachmentsRead = false,
                    findReport = null,
                    newFields = emptyList(),
                    pictures = emptyMap(),
                    pictureChanges = emptyMap(),
                    selectedPicture = null,
                    editPages = emptyMap(),
                    lineEdits = emptyMap(),
                    selectedLine = null,
                    replaceStatus = null
                )
            }
            loadFileThumbs()
        }
    }

    /**
     * Draws each chosen file's first page, or its picture, small, for its row. One at a time and
     * only once a file has been read, so the run button never waits on a thumbnail.
     */
    private fun loadFileThumbs() {
        viewModelScope.launch {
            for (doc in _state.value.documents) {
                val key = doc.file.file.path
                if (key in _state.value.fileThumbs || !doc.isReady) continue
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching {
                        when {
                            doc.file.isPdf -> {
                                val engine = if (doc.encrypted) sheaf.encryptedReader else sheaf.pdfEngine
                                engine.open(doc.file.file, doc.password).use { it.renderPage(0, FILE_THUMB_PX) }
                            }
                            doc.file.isImage -> {
                                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                android.graphics.BitmapFactory.decodeFile(doc.file.file.path, options)
                                options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / FILE_THUMB_PX).coerceAtLeast(1)
                                options.inJustDecodeBounds = false
                                android.graphics.BitmapFactory.decodeFile(doc.file.file.path, options)
                            }
                            else -> null
                        }
                    }.getOrNull()
                } ?: continue
                _state.update { it.copy(fileThumbs = it.fileThumbs + (key to bitmap)) }
            }
        }
    }

    /**
     * Takes every file in a folder and in the folders inside it that this tool can use. The
     * order is the folder's, by name; they can be dragged into another.
     */
    fun addFolder(tree: Uri) {
        val tool = _state.value.tool ?: return
        _state.update { it.copy(busy = true, error = null, notice = null) }
        viewModelScope.launch {
            val (wanted, skipped) = withContext(Dispatchers.IO) {
                val keep = mutableListOf<Uri>()
                var other = 0
                fun walk(dir: androidx.documentfile.provider.DocumentFile, depth: Int) {
                    if (depth > 8) return
                    for (child in dir.listFiles().sortedBy { it.name?.lowercase() }) {
                        val name = child.name.orEmpty()
                        if (name.startsWith(".")) continue
                        if (child.isDirectory) { walk(child, depth + 1); continue }
                        if (folderAccepts(tool, name)) keep += child.uri else other += 1
                    }
                }
                runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(getApplication(), tree)?.let { walk(it, 0) } }
                keep to other
            }
            _state.update { it.copy(busy = false) }
            if (wanted.isEmpty()) {
                val what = when (tool.input) {
                    ToolId.InputKind.IMAGES -> "a picture"
                    ToolId.InputKind.OFFICE -> "a Word, Excel or text file"
                    else -> "a PDF"
                }
                _state.update { it.copy(error = "Nothing in that folder is $what.") }
                return@launch
            }
            if (skipped > 0) {
                _state.update {
                    it.copy(notice = "${wanted.size} from the folder; $skipped other ${if (skipped == 1) "file was" else "files were"} left out.")
                }
            }
            addDocuments(wanted)
        }
    }

    private fun folderAccepts(tool: ToolId, name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (tool.input) {
            ToolId.InputKind.IMAGES -> ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")
            ToolId.InputKind.OFFICE -> ext in setOf("docx", "xlsx", "txt", "md", "csv")
            ToolId.InputKind.PDF_AND_FILES -> true
            else -> ext == "pdf"
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
            val wanted = tool?.input == ToolId.InputKind.IMAGES || tool == ToolId.ATTACHMENTS ||
                tool == ToolId.ADD_IMAGE
            return@withContext SelectedDoc(
                file = file,
                unreadableReason = if (wanted) null else "This is an image, and this tool needs a PDF."
            )
        }
        if (!file.isPdf) {
            // Attachments takes any file at all after the PDF; it is never opened, only carried.
            if (tool == ToolId.ATTACHMENTS || tool == ToolId.DIGITAL_SIGN) return@withContext SelectedDoc(file = file)
            if (tool == ToolId.OFFICE_TO_PDF) {
                return@withContext SelectedDoc(
                    file = file,
                    unreadableReason = if (file.isOffice) null else "Sheaf can turn .docx, .xlsx, .txt, .md and .csv files into PDFs; this is something else."
                )
            }
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
            // From the first file only. Seeding from each in turn left the last file's title in
            // the boxes, and the boxes then went onto every file of the batch.
            if (tool == ToolId.METADATA && _state.value.metaSeed == null && _state.value.documents.isEmpty()) {
                val seed = DocumentMetadata(info.title.orEmpty(), info.author.orEmpty(), info.subject.orEmpty(), info.keywords.orEmpty())
                _state.update { current ->
                    current.copy(
                        metaSeed = seed,
                        config = current.config.copy(
                            metaTitle = seed.title.orEmpty(),
                            metaAuthor = seed.author.orEmpty(),
                            metaSubject = seed.subject.orEmpty(),
                            metaKeywords = seed.keywords.orEmpty()
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

    /**
     * Drops one chosen document, named rather than numbered.
     *
     * A row's index belongs to the frame it was composed in, so two Removes tapped before the
     * next frame both arrive carrying the same index - once valid, once pointing at whichever
     * row has since slid into that place. Taking the document itself makes the second tap a
     * no-op rather than a crash or, worse, the removal of its neighbour. The match is on the
     * cache copy's path, which is unique per import and survives the row being replaced by
     * [setPasswordFor] or [verifyPassword] while the tap is in flight.
     */
    fun removeDocument(doc: SelectedDoc) {
        var removed: SelectedDoc? = null
        _state.update { current ->
            // Cleared on entry, not just at the start of the call: update re-runs its lambda
            // whenever another writer wins the compare-and-set, and the delete below must see
            // only what the attempt that won actually did.
            removed = null
            val remaining = current.documents.toMutableList()
            val at = remaining.indexOfFirst { it.file.file == doc.file.file }
            if (at < 0) return@update current
            removed = remaining.removeAt(at)
            current.copy(documents = remaining, pageOrder = emptyList(), rotations = emptyMap())
        }
        // Deleting the copy this screen owns waits until the state is committed, because a
        // delete inside the lambda would run once per attempt - on files the surviving list
        // still expects to be there.
        removed?.file?.file?.delete()
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
            loadFileThumbs()
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

        // Remove areas and Mark up both show ONE page at a time, big enough to aim at.
        // Rendering the whole document at that size is hundreds of megabytes of bitmap and
        // was the one path in this app that could not survive a long scan, so these two
        // render on demand.
        if (_state.value.tool == ToolId.REDACT || _state.value.tool == ToolId.ANNOTATE || _state.value.tool == ToolId.CREATE_FORM ||
            _state.value.tool == ToolId.EDIT_IMAGES || _state.value.tool == ToolId.EDIT_TEXT
        ) {
            loadPageCountOnly(doc)
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val thumbs = withContext(Dispatchers.IO) {
                runCatching {
                    val engine = if (doc.encrypted) sheaf.encryptedReader else sheaf.pdfEngine
                    engine.open(doc.file.file, doc.password).use { opened ->
                        (0 until opened.pageCount).map { index ->
                            // Past the cap the page is listed but not drawn. Four hundred
                            // thumbnails is a hundred megabytes of bitmap, and the heap this
                            // app is allowed is the reason it can be trusted with a
                            // four-hundred-page document at all.
                            val bitmap = if (index < MAX_THUMBNAILS) {
                                runCatching {
                                    opened.renderPage(index, THUMBNAIL_WIDTH_PX)
                                }.getOrNull()
                            } else {
                                null
                            }
                            index to bitmap
                        }
                    }
                }.getOrNull().orEmpty()
            }
            _state.update { current ->
                val order = thumbs.map { (index, _) -> index }
                current.copy(
                    busy = false,
                    pageOrder = order,
                    thumbnails = thumbs
                        .mapNotNull { (index, bitmap) -> bitmap?.let { b -> index to b } }
                        .toMap(),
                    thumbnailsCapped = order.size > MAX_THUMBNAILS,
                    // Extract pages can be driven by tapping or by typing, and they are the
                    // same selection: whatever is in the field when the pages arrive is shown
                    // as ticked, so the two never disagree.
                    selectedPages = if (current.tool == ToolId.EXTRACT) {
                        PageSelection.parse(current.config.pageSpec, order.size).toSet()
                    } else {
                        current.selectedPages
                    }
                )
            }
        }
    }

    /** Lists the pages without drawing them, then draws the first one. */
    private fun loadPageCountOnly(doc: SelectedDoc) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val engine = if (doc.encrypted) sheaf.encryptedReader else sheaf.pdfEngine
                    engine.open(doc.file.file, doc.password).use { it.pageCount }
                }.getOrDefault(0)
            }
            _state.update { it.copy(busy = false, pageOrder = (0 until count).toList()) }
            if (count > 0) renderBigPage(0)
            if (count > 0 && _state.value.tool == ToolId.EDIT_IMAGES) loadPictures(0)
            if (count > 0 && _state.value.tool == ToolId.EDIT_TEXT) loadEditable(0)
        }
    }

    /**
     * Draws one page at reading size for the canvases that work on a page at a time.
     *
     * The ones it replaces are dropped from the map but NOT recycled: Compose may still be
     * drawing the page that is leaving the screen this frame, and recycling a bitmap out from
     * under a draw is an immediate crash. Letting them be collected costs a moment of memory
     * and nothing else.
     */
    private fun renderBigPage(index: Int) {
        val doc = _state.value.documents.firstOrNull() ?: return
        if (_state.value.thumbnails.containsKey(index)) return

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val engine = if (doc.encrypted) sheaf.encryptedReader else sheaf.pdfEngine
                    engine.open(doc.file.file, doc.password).use { opened ->
                        opened.renderPage(index, REDACT_PAGE_WIDTH_PX)
                    }
                }.getOrNull()
            }
            _state.update { current ->
                if (bitmap == null) current else current.copy(thumbnails = mapOf(index to bitmap))
            }
        }
    }

    fun togglePageSelected(index: Int) {
        _state.update { current ->
            val selected = current.selectedPages.toMutableSet()
            if (!selected.add(index)) selected.remove(index)
            current.copy(selectedPages = selected, config = current.specFrom(selected))
        }
    }

    fun selectAllPages() {
        _state.update { current ->
            val all = current.pageOrder.toSet()
            current.copy(selectedPages = all, config = current.specFrom(all))
        }
    }

    fun clearPageSelection() {
        _state.update { current ->
            current.copy(selectedPages = emptySet(), config = current.specFrom(emptySet()))
        }
    }

    /**
     * Keeps Extract pages' typed field in step with what has been tapped.
     *
     * The field is not replaced by the grid, because "1-3, 40-" on a four-hundred-page
     * document is faster to type than it is to tap. They are two ways into one value, so the
     * value is written from whichever was used last.
     */
    private fun ToolUiState.specFrom(selected: Set<Int>): ToolConfig =
        if (tool == ToolId.EXTRACT) {
            config.copy(pageSpec = PageSelection.describe(selected.toList()))
        } else {
            config
        }

    fun rotateSelected(degrees: Int) {
        _state.update { current ->
            if (current.selectedPages.isEmpty()) return@update current
            val rotations = current.rotations.toMutableMap()
            for (index in current.selectedPages) {
                rotations[index] = ((rotations[index] ?: 0) + degrees).mod(360)
            }
            current.copy(rotations = rotations, pageEdits = current.withEditRemembered())
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
            current.copy(
                pageOrder = remaining,
                selectedPages = emptySet(),
                error = null,
                pageEdits = current.withEditRemembered()
            )
        }
    }

    fun movePage(from: Int, to: Int) {
        _state.update { current ->
            val order = current.pageOrder.toMutableList()
            if (from !in order.indices || to !in order.indices) return@update current
            order.add(to, order.removeAt(from))
            current.copy(pageOrder = order.toList(), pageEdits = current.withEditRemembered())
        }
    }

    /**
     * Steps back through the page edits.
     *
     * Reordering forty pages by hand and then deleting the wrong one is the moment this app
     * either keeps or loses someone's afternoon. Nothing has been written to disk yet - the
     * whole organise screen is a plan - so undo is just the previous plan.
     */
    fun undoPageEdit() {
        _state.update { current ->
            val previous = current.pageEdits.lastOrNull() ?: return@update current
            current.copy(
                pageOrder = previous.order,
                rotations = previous.rotations,
                pageEdits = current.pageEdits.dropLast(1),
                selectedPages = emptySet(),
                error = null
            )
        }
    }

    // ---- running ----

    fun run() {
        val current = _state.value
        val tool = current.tool ?: return
        val ready = current.documents.filter { it.isReady }
        if (ready.isEmpty() && tool.input != ToolId.InputKind.NONE) {
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
            ToolId.OCR -> OcrOp(script = config.ocrScript)
            ToolId.EDIT_TEXT -> EditTextOp(state.textEdits())
            ToolId.PDF_A -> PdfAOp()
            ToolId.CREATE_FORM -> CreateFormOp(state.newFields)
            ToolId.DIGITAL_SIGN -> DigitalSignOp(
                password = config.certPassword,
                spec = SignSpec(
                    reason = config.signReason.trim(),
                    location = config.signLocation.trim(),
                    visible = config.signVisible,
                    onFirstPage = config.signOnFirstPage
                )
            )
            ToolId.COMPARE -> CompareOp()
            ToolId.RENT_RECEIPTS -> RentReceiptsOp(
                RentReceipts(
                    tenant = config.tenant,
                    landlord = config.landlord,
                    address = config.rentAddress,
                    rent = config.rent,
                    startYear = config.rentYear,
                    startMonth = config.rentMonth,
                    months = config.rentMonths,
                    mode = config.rentMode,
                    pan = config.landlordPan
                )
            )
            ToolId.EXTRACT_TEXT -> ExtractTextOp(config.pageSpec.takeIf { it.isNotBlank() })
            ToolId.SCAN -> {
                // The scanner has its own screen; it never reaches the generic tool flow.
                _state.update { it.copy(error = "Open the scanner from the home screen.") }
                return null
            }
            ToolId.WATERMARK -> WatermarkOp(
                WatermarkSpec(
                    text = config.watermarkText.trim(),
                    opacity = config.watermarkOpacity,
                    layout = config.watermarkLayout,
                    colour = config.watermarkColour
                ),
                pageSpec = config.pageSpec
            )
            ToolId.PAGE_NUMBERS -> PageNumberOp(
                PageNumberSpec(
                    position = config.numberPosition,
                    format = config.numberFormat,
                    startAt = config.numberStartAt,
                    skipFirst = config.numberSkipFirst,
                    padTo = if (config.bates) config.batesDigits else 0,
                    prefix = if (config.bates) config.batesPrefix else ""
                ),
                continueAcrossFiles = config.bates
            )
            ToolId.CROP -> CropOp(
                CropSpec(
                    topMm = if (config.cropAuto) 0f else config.cropTopMm,
                    rightMm = if (config.cropAuto) 0f else config.cropRightMm,
                    bottomMm = if (config.cropAuto) 0f else config.cropBottomMm,
                    leftMm = if (config.cropAuto) 0f else config.cropLeftMm,
                    auto = config.cropAuto,
                    resizeTo = config.resizeTo,
                    enlarge = config.cropEnlarge
                ),
                pageSpec = config.pageSpec
            )
            ToolId.BACKGROUND -> BackgroundOp(config.backgroundColour, config.pageSpec)
            ToolId.PAGE_LABELS -> PageLabelsOp(
                if (config.removeLabels) {
                    null
                } else {
                    PageLabelSpec(
                        frontPages = config.labelFrontPages,
                        frontStyle = config.labelFrontStyle,
                        style = config.labelStyle,
                        startAt = config.labelStartAt,
                        prefix = config.labelPrefix.trim()
                    )
                }
            )
            ToolId.BOOKMARKS -> BookmarksOp(state.outline)
            ToolId.ATTACHMENTS -> AttachmentsOp(extract = config.extractAttachments)
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
            ToolId.HEADER_FOOTER -> HeaderFooterOp(
                StampSpec(
                    headerText = config.headerText,
                    footerText = config.footerText,
                    align = config.stampAlign,
                    fontSize = config.stampSize,
                    skipFirst = config.stampSkipFirst
                )
            )
            ToolId.INSERT -> InsertPagesOp(
                atPage = config.insertAt,
                blankPages = if (state.documents.size > 1) 0 else config.blankPages,
                replaceSpec = if (config.replacePages) config.replaceSpec else ""
            )
            ToolId.FORMS -> FillFormOp(
                // Only what was actually typed. Sending every field back would rewrite the
                // ones the document already had values in, which is how a form loses an
                // answer nobody touched.
                values = state.formValues,
                flatten = config.flattenForm
            )
            ToolId.ANNOTATE -> AnnotateOp(state.marks)
            ToolId.REDACT -> RedactOp(state.redactions)
            ToolId.N_UP -> NUpOp(config.perSheet, config.nUpPaper)
            ToolId.SPLIT_BY_SIZE -> SplitBySizeOp(config.partMb.toLong() * 1024 * 1024)
            ToolId.EXTRACT_IMAGES -> ExtractImagesOp()
            ToolId.METADATA -> when (config.metaMode) {
                ToolConfig.MetaMode.EDIT -> {
                    // One file: an empty box clears that detail. Several: an empty box leaves
                    // each file's own, so one author set on ten files does not wipe ten titles.
                    val several = state.documents.count { it.isReady } > 1
                    fun field(value: String) = if (several && value.isBlank()) null else value
                    MetadataOp(
                        DocumentMetadata(
                            title = field(config.metaTitle),
                            author = field(config.metaAuthor),
                            subject = field(config.metaSubject),
                            keywords = field(config.metaKeywords)
                        )
                    )
                }
                ToolConfig.MetaMode.STRIP -> MetadataOp(DocumentMetadata(stripAll = true))
                ToolConfig.MetaMode.CLEAN -> CleanOp(
                    CleanSpec(
                        metadata = true,
                        scripts = config.cleanScripts,
                        attachments = config.cleanAttachments,
                        comments = config.cleanComments
                    )
                )
            }
            ToolId.MERGE -> MergeOp()
            ToolId.EXTRACT -> ExtractPagesOp(config.pageSpec, remove = config.removePages)
            ToolId.SPLIT -> SplitOp(
                when (config.splitMode) {
                    ToolConfig.SplitMode.EVERY_N -> SplitOp.Mode.EveryNPages(config.splitSize)
                    ToolConfig.SplitMode.EACH_PAGE -> SplitOp.Mode.EachPage
                    ToolConfig.SplitMode.RANGES -> SplitOp.Mode.Ranges(config.pageSpec)
                    ToolConfig.SplitMode.AT_BOOKMARKS -> SplitOp.Mode.AtBookmarks
                }
            )
            ToolId.ORGANISE -> OrganiseOp(state.pageOrder, state.rotations.filterValues { it != 0 })
            ToolId.IMAGES_TO_PDF -> ImagesToPdfOp(
                PageSpec(size = config.pageSize, marginPoints = config.marginPoints),
                separate = config.separatePdfs
            )
            ToolId.PDF_TO_IMAGES -> PdfToImagesOp(
                dpi = config.dpi,
                format = config.imageFormat,
                pageSpec = config.pageSpec.takeIf { it.isNotBlank() }
            )
            ToolId.PDF_TO_OFFICE -> PdfToOfficeOp(config.officeTarget, config.convertPictures)
            ToolId.OFFICE_TO_PDF -> OfficeToPdfOp()
            ToolId.COMPRESS -> CompressOp(
                config.compression,
                targetBytes = if (config.compressToSize) config.compressTargetMb.toLong() * 1024 * 1024 else null,
                allowPictures = config.compressToSize && config.compressPictures
            )
            ToolId.ADD_IMAGE -> AddImageOp(
                anchor = config.signAnchor,
                widthFraction = config.signWidth,
                page = config.markPage
            )
            ToolId.SET_PASSWORD -> {
                if (!config.restrictOnly && config.newPassword.isBlank()) {
                    _state.update { it.copy(error = "Choose a password first.") }
                    return null
                }
                if (!config.restrictOnly && config.newPassword != config.confirmPassword) {
                    _state.update { it.copy(error = "The two passwords do not match.") }
                    return null
                }
                SetPasswordOp(
                    password = config.newPassword,
                    permissions = DocumentPermissions(
                        allowPrinting = config.allowPrinting,
                        allowCopying = config.allowCopying,
                        allowChanges = config.allowChanges
                    ),
                    restrictOnly = config.restrictOnly
                )
            }
            ToolId.REMOVE_PASSWORD -> RemovePasswordOp()
            ToolId.EDIT_IMAGES -> EditPicturesOp(state.pictureEdits())
            ToolId.SUMMARISE -> KeyPointsOp()
            ToolId.RECIPES -> {
                val recipe = state.recipes.firstOrNull { it.id == state.recipeId }
                if (recipe == null) {
                    _state.update { it.copy(error = "Choose a recipe, or make one.") }
                    return null
                }
                val steps = recipe.steps.map { step ->
                    // The password is typed for this run and goes only into this run.
                    val config = if (step.tool == ToolId.SET_PASSWORD) {
                        step.config.copy(newPassword = config.newPassword, confirmPassword = config.newPassword)
                    } else {
                        step.config
                    }
                    buildOp(step.tool, ToolUiState(tool = step.tool, config = config, documents = state.documents)) ?: return null
                }
                RecipeOp(recipe.name, steps)
            }
            ToolId.RESIZE_PHOTO -> ResizePhotoOp(
                kb = config.photoKb.toFloat(),
                pixels = when (config.photoMode) {
                    ToolConfig.PhotoMode.KEEP -> PhotoPixels.Keep
                    ToolConfig.PhotoMode.PASSPORT -> PhotoPixels.Passport
                    ToolConfig.PhotoMode.EXACT -> PhotoPixels.Exact(config.photoWidth, config.photoHeight)
                }
            )
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
                        // The first page, so a result is seen to be the right document before
                        // it is saved or sent.
                        file.isPdf -> sheaf.pdfEngine.open(file.file, null).use { opened ->
                            ResultPreview.Pdf(opened.renderPage(0, RESULT_THUMB_PX), opened.pageCount)
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

    /**
     * Bundles results into one ZIP, named after the first of them.
     *
     * Stored, not compressed: PDFs and JPEGs are already compressed and deflating them again
     * costs time for nothing. The point of the ZIP is one file to hand over, not a smaller one.
     */
    fun zipResults(files: List<SheafFile>, onReady: (SheafFile?) -> Unit) {
        viewModelScope.launch {
            val zip = withContext(Dispatchers.IO) {
                runCatching {
                    val base = files.firstOrNull()?.baseName ?: "sheaf"
                    val output = sheaf.workspace.newOutput(base, "bundle", "zip")
                    java.util.zip.ZipOutputStream(output.outputStream().buffered()).use { out ->
                        out.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                        val taken = mutableSetOf<String>()
                        for (file in files) {
                            var name = file.displayName.replace('/', '_').replace('\\', '_')
                            var n = 2
                            while (!taken.add(name.lowercase())) {
                                val dot = file.displayName.lastIndexOf('.')
                                name = if (dot > 0) "${file.displayName.substring(0, dot)} ($n)${file.displayName.substring(dot)}" else "${file.displayName} ($n)"
                                n += 1
                            }
                            out.putNextEntry(java.util.zip.ZipEntry(name))
                            file.file.inputStream().use { it.copyTo(out) }
                            out.closeEntry()
                        }
                    }
                    SheafFile(output, "$base.zip", SheafFile.Origin.Derived("bundle"), mimeType = "application/zip")
                }.getOrNull()
            }
            onReady(zip)
        }
    }

    fun writeTo(destination: Uri, file: SheafFile, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { sheaf.exporter.writeTo(destination, file) }
            onDone(result.exceptionOrNull()?.message)
        }
    }

    // ---- saving without a picker ----

    /** Whether saving should ask for a destination, or use the folder already chosen. */
    val askWhereToSave: Boolean get() = sheaf.settings.current.askWhereToSave

    /** The folder the user granted for results, or null if they have not chosen one. */
    fun savedFolder(): Uri? = sheaf.settings.current.saveFolder?.let(Uri::parse)

    fun folderIntent(): Intent = sheaf.exporter.openFolderIntent()

    fun rememberFolder(folder: Uri) {
        sheaf.settings.update { it.copy(saveFolder = folder.toString()) }
    }

    /**
     * Writes several results into one folder.
     *
     * The whole point of the folder grant: PDF to images on a forty-page document produces
     * forty files, and forty trips through the system picker is not something anyone does
     * twice. Failures are counted rather than thrown, because thirty-nine saved files and one
     * failure is a useful outcome that should be reported as such.
     */
    fun writeInto(folder: Uri, files: List<SheafFile>, onDone: (String) -> Unit) {
        if (files.isEmpty()) return
        viewModelScope.launch {
            var saved = 0
            var firstProblem: String? = null
            for (file in files) {
                val problem = runCatching { sheaf.exporter.writeInto(folder, file) }
                    .getOrElse { it.message ?: "${file.displayName} could not be written." }
                if (problem == null) saved += 1 else if (firstProblem == null) firstProblem = problem
            }
            onDone(
                when {
                    firstProblem != null && saved == 0 -> firstProblem
                    firstProblem != null -> "Saved $saved of ${files.size}. $firstProblem"
                    saved == 1 -> "Saved ${files.first().displayName}"
                    else -> "Saved $saved files"
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.thumbnails.values.forEach { it.recycle() }
        _state.value.fileThumbs.values.forEach { it.recycle() }
        _state.value.previews.values.filterIsInstance<ResultPreview.Pdf>().forEach { it.firstPage.recycle() }
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
        val doc = _state.value.documents.firstOrNull { it.file.isPdf } ?: return
        if (_state.value.previewPage != null) return

        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val engine = if (doc.encrypted) sheaf.encryptedReader else sheaf.pdfEngine
                    engine.open(doc.file.file, doc.password).use { opened ->
                        val index = (_state.value.config.markPage - 1)
                            .coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))
                        opened.renderPage(index, PREVIEW_PAGE_WIDTH_PX) to opened.pageSize(index)
                    }
                }.getOrNull()
            }
            _state.update { it.copy(previewPage = bitmap?.first, previewPageSize = bitmap?.second) }
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

    /** The signatures the user kept, for the row above the pad. */
    fun loadSavedSignatures() {
        if (_state.value.savedSignatures.isNotEmpty()) return
        viewModelScope.launch {
            val kept = withContext(Dispatchers.IO) { sheaf.signatures.list() }
            _state.update { it.copy(savedSignatures = kept) }
        }
    }

    /** Keeps the signature now on the pad, so it can be used again without redrawing it. */
    fun keepSignature() {
        val drawn = _state.value.signatureFile ?: return
        viewModelScope.launch {
            val kept = withContext(Dispatchers.IO) { sheaf.signatures.keep(drawn) }
            if (kept == null) {
                _state.update { it.copy(error = "That signature could not be kept.") }
            } else {
                _state.update { it.copy(savedSignatures = sheaf.signatures.list(), error = null) }
            }
        }
    }

    /**
     * Uses a kept signature for this document.
     *
     * Copied into the workspace first. The job reads the file it is given, and handing it the
     * stored one would mean an operation reading from the folder that is meant to outlive it.
     */
    fun useSavedSignature(kept: java.io.File) {
        viewModelScope.launch {
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    val destination = newSignatureFile()
                    kept.copyTo(destination, overwrite = true)
                    destination
                }.getOrNull()
            }
            if (copy == null) {
                _state.update { it.copy(error = "That signature could not be read.") }
            } else {
                setSignature(copy)
            }
        }
    }

    fun forgetSignature(kept: java.io.File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { sheaf.signatures.forget(kept) }
            _state.update { it.copy(savedSignatures = sheaf.signatures.list()) }
        }
    }

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

    // ---- forms ----

    /**
     * Reads the document's fillable fields.
     *
     * Most PDFs have none: a form has to have been built as one, and a scan of a printed form
     * is a picture. [ToolUiState.formRead] is what lets the screen say that out loud instead
     * of showing an empty list that looks like a failure.
     */
    fun loadFormFields() {
        val doc = _state.value.documents.firstOrNull() ?: return
        if (_state.value.formRead) return

        viewModelScope.launch {
            val fields = withContext(Dispatchers.IO) {
                runCatching {
                    sheaf.surgeon.readFormFields(PdfInput(doc.file.file, doc.password))
                }.getOrDefault(emptyList())
            }
            _state.update { current ->
                current.copy(
                    formFields = fields,
                    // Seeded with what the document already holds, so a half-filled form is
                    // shown as it is rather than as empty boxes.
                    formValues = fields.filter { it.value.isNotEmpty() }
                        .associate { it.name to it.value },
                    formRead = true
                )
            }
        }
    }

    fun setFormValue(name: String, value: String) {
        _state.update { it.copy(formValues = it.formValues + (name to value), error = null) }
    }

    /**
     * Reads the bookmarks the document already has, so the editor starts from them rather
     * than from nothing. A document with none starts the list empty, and the screen says so.
     */
    fun loadOutline() {
        val doc = _state.value.documents.firstOrNull() ?: return
        if (_state.value.outlineRead) return
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { sheaf.surgeon.readOutline(PdfInput(doc.file.file, doc.password)) }
                    .getOrDefault(emptyList())
            }
            _state.update { it.copy(outline = entries, outlineRead = true) }
        }
    }

    fun addBookmark(afterIndex: Int = -1) {
        _state.update { current ->
            val list = current.outline.toMutableList()
            val at = (afterIndex + 1).coerceIn(0, list.size)
            // A new entry sits beside the one above it: same depth, next page along.
            val above = list.getOrNull(at - 1)
            val pageCount = current.documents.firstOrNull()?.pageCount ?: 1
            val page = ((above?.pageIndex ?: -1) + 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            list.add(at, OutlineEntry(title = "", pageIndex = page, depth = above?.depth ?: 0))
            current.copy(outline = list, error = null)
        }
    }

    fun setBookmark(index: Int, title: String? = null, page: Int? = null) {
        _state.update { current ->
            val entry = current.outline.getOrNull(index) ?: return@update current
            val pageCount = current.documents.firstOrNull()?.pageCount ?: Int.MAX_VALUE
            val list = current.outline.toMutableList()
            list[index] = entry.copy(
                title = title ?: entry.title,
                pageIndex = page?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: entry.pageIndex
            )
            current.copy(outline = list)
        }
    }

    fun removeBookmark(index: Int) {
        _state.update { current ->
            if (index !in current.outline.indices) return@update current
            current.copy(outline = current.outline.filterIndexed { i, _ -> i != index })
        }
    }

    fun moveBookmark(from: Int, to: Int) {
        _state.update { current ->
            if (from !in current.outline.indices || to !in current.outline.indices) return@update current
            val list = current.outline.toMutableList()
            list.add(to, list.removeAt(from))
            current.copy(outline = list)
        }
    }

    /** Nests an entry one level deeper under the one above, or brings it back out. */
    fun indentBookmark(index: Int, deeper: Boolean) {
        _state.update { current ->
            val entry = current.outline.getOrNull(index) ?: return@update current
            val above = current.outline.getOrNull(index - 1)
            val depth = if (deeper) {
                minOf(entry.depth + 1, (above?.depth ?: -1) + 1)
            } else {
                (entry.depth - 1).coerceAtLeast(0)
            }
            if (depth == entry.depth) return@update current
            val list = current.outline.toMutableList()
            list[index] = entry.copy(depth = depth)
            current.copy(outline = list)
        }
    }

    fun clearBookmarks() {
        _state.update { it.copy(outline = emptyList()) }
    }

    /** Lists the files already inside the first document, so the screen can show them. */
    fun loadAttachments() {
        val doc = _state.value.documents.firstOrNull { it.file.isPdf } ?: return
        if (_state.value.attachmentsRead) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { sheaf.surgeon.readAttachments(PdfInput(doc.file.file, doc.password)) }
                    .getOrDefault(emptyList())
            }
            _state.update { it.copy(attachments = found, attachmentsRead = true) }
        }
    }

    fun setRedactPage(index: Int) {
        _state.update { it.copy(canvasPage = index) }
        renderBigPage(index)
    }

    fun addRedaction(page: Int, area: PageArea) {
        _state.update { current ->
            val existing = current.redactions[page].orEmpty()
            current.copy(redactions = current.redactions + (page to existing + area), error = null)
        }
    }

    fun clearRedactions(page: Int) {
        _state.update { it.copy(redactions = it.redactions - page) }
    }

    /**
     * Finds the things people most need taken out of a document before sharing it - Aadhaar
     * and PAN numbers, phone numbers, email addresses, card numbers, or any text typed - and
     * marks each one for removal, exactly as if a box had been drawn over it.
     *
     * The boxes land in [ToolUiState.redactions] like drawn ones, so they can be seen on the
     * page, cleared per page, and go out through the same genuine removal.
     */
    fun findSensitive() {
        val doc = _state.value.documents.firstOrNull() ?: return
        val config = _state.value.config
        val patterns = buildList {
            if (config.findAadhaar) add(Triple("Aadhaar numbers", AADHAAR, { s: String -> true }))
            if (config.findPan) add(Triple("PAN numbers", PAN, { s: String -> true }))
            if (config.findPhone) add(Triple("phone numbers", PHONE, { s: String -> s.count { it.isDigit() } in 10..13 }))
            if (config.findEmail) add(Triple("email addresses", EMAIL, { s: String -> true }))
            if (config.findCard) add(Triple("card numbers", CARD, { s: String -> luhn(s) }))
            val typed = config.findText.trim()
            if (typed.isNotEmpty()) add(Triple("\"$typed\"", Regex(Regex.escape(typed), RegexOption.IGNORE_CASE), { s: String -> true }))
        }
        if (patterns.isEmpty()) {
            _state.update { it.copy(findReport = "Choose what to look for first.") }
            return
        }
        _state.update { it.copy(finding = true, findReport = null) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                val input = PdfInput(doc.file.file, doc.password)
                patterns.map { (label, pattern, accept) ->
                    label to runCatching { sheaf.surgeon.findAreas(input, pattern, accept) }.getOrDefault(emptyMap())
                }
            }
            _state.update { current ->
                var redactions = current.redactions
                val counts = mutableListOf<String>()
                val pages = mutableSetOf<Int>()
                for ((label, byPage) in found) {
                    val n = byPage.values.sumOf { it.size }
                    if (n == 0) continue
                    counts += "$n $label"
                    for ((page, areas) in byPage) {
                        val existing = redactions[page].orEmpty()
                        // The same area found twice - a number that matches two patterns - is
                        // one box, not two.
                        val fresh = areas.filter { area -> existing.none { it == area } }
                        if (fresh.isNotEmpty()) redactions = redactions + (page to existing + fresh)
                        pages += page
                    }
                }
                current.copy(
                    redactions = redactions,
                    finding = false,
                    findReport = if (counts.isEmpty()) {
                        "Nothing found. A scanned page has no text to search; run Make searchable first."
                    } else {
                        "Marked " + counts.joinToString(", ") + " on " +
                            (if (pages.size == 1) "1 page" else "${pages.size} pages") + ". Tap Remove to take them out."
                    }
                )
            }
        }
    }

    /** The check digit test every real card number passes. Keeps a phone number from being one. */
    private fun luhn(text: String): Boolean {
        val digits = text.filter { it.isDigit() }
        if (digits.length !in 13..19) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    // ---- creating a form ----

    fun addNewField(page: Int, area: PageArea) {
        _state.update { current ->
            val kind = current.config.fieldKind
            val n = current.newFields.count { it.kind == kind } + 1
            val name = when (kind) {
                NewField.Kind.TEXT -> "Text $n"
                NewField.Kind.MULTILINE -> "Paragraph $n"
                NewField.Kind.CHECK -> "Tick $n"
                NewField.Kind.CHOICE -> "Choice $n"
            }
            current.copy(newFields = current.newFields + NewField(kind, name, page, area), error = null)
        }
    }

    fun setNewField(index: Int, name: String? = null, kind: NewField.Kind? = null, options: String? = null) {
        _state.update { current ->
            val field = current.newFields.getOrNull(index) ?: return@update current
            val list = current.newFields.toMutableList()
            list[index] = field.copy(
                name = name ?: field.name,
                kind = kind ?: field.kind,
                options = options?.split(',', '\n')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: field.options
            )
            current.copy(newFields = list)
        }
    }

    fun removeNewField(index: Int) {
        _state.update { current -> current.copy(newFields = current.newFields.filterIndexed { i, _ -> i != index }) }
    }

    fun clearNewFields(page: Int) {
        _state.update { current -> current.copy(newFields = current.newFields.filter { it.pageIndex != page }) }
    }

    // ---- form answers kept for next time ----

    private val answersDir: java.io.File
        get() = java.io.File(getApplication<Application>().filesDir, "answers").apply { mkdirs() }

    fun loadSavedAnswers() {
        val files = answersDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        _state.update { it.copy(savedAnswers = files) }
    }

    /**
     * Keeps what was typed, by the form's own field names, so the same form next month
     * starts filled in. Kept on the phone, in the app's own files, like a signature.
     */
    fun keepAnswers() {
        val current = _state.value
        val values = current.formValues.filterValues { it.isNotBlank() }
        if (values.isEmpty()) return
        val doc = current.documents.firstOrNull() ?: return
        val json = org.json.JSONObject().apply {
            put("form", doc.file.displayName)
            put("kept", System.currentTimeMillis())
            put("values", org.json.JSONObject(values))
        }
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date())
        val file = java.io.File(answersDir, "${doc.file.baseName} $stamp.json")
        runCatching { file.writeText(json.toString()) }
        loadSavedAnswers()
    }

    fun useAnswers(file: java.io.File) {
        val values = runCatching {
            val json = org.json.JSONObject(file.readText()).getJSONObject("values")
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrNull() ?: return
        _state.update { current ->
            // Only the fields this form has. Answers kept from another form, or from an older
            // version of this one, are not forced into fields that do not exist.
            val names = current.formFields.map { it.name }.toSet()
            val matching = values.filterKeys { it in names }
            current.copy(
                formValues = current.formValues + matching,
                error = if (matching.isEmpty()) "None of those answers fit this form's fields." else null
            )
        }
    }

    fun forgetAnswers(file: java.io.File) {
        if (file.parentFile?.absolutePath == answersDir.absolutePath) file.delete()
        loadSavedAnswers()
    }

    /** Decodes the picture Add an image will place, small, for the preview. */
    fun loadStampBitmap() {
        val image = _state.value.documents.firstOrNull { it.file.isImage } ?: run {
            _state.update { it.copy(stampBitmap = null) }
            return
        }
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val options = android.graphics.BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    android.graphics.BitmapFactory.decodeFile(image.file.file.path, options)
                    options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / PREVIEW_PX).coerceAtLeast(1)
                    options.inJustDecodeBounds = false
                    android.graphics.BitmapFactory.decodeFile(image.file.file.path, options)
                }.getOrNull()
            }
            _state.update { it.copy(stampBitmap = bitmap) }
        }
    }

    // ---- recipes ----

    private val recipesFile: java.io.File
        get() = java.io.File(getApplication<Application>().filesDir, "recipes.json")

    fun loadRecipes() {
        viewModelScope.launch {
            val kept = withContext(Dispatchers.IO) { Recipes.read(recipesFile) }
            _state.update { it.copy(recipes = kept, recipeId = it.recipeId ?: kept.firstOrNull()?.id) }
        }
    }

    fun selectRecipe(id: String) {
        _state.update { it.copy(recipeId = id, error = null) }
    }

    fun newRecipe() {
        _state.update { it.copy(draft = RecipeDraft(), draftStep = null, recipeProblem = null) }
    }

    fun cancelRecipe() {
        _state.update { it.copy(draft = null, draftStep = null, recipeProblem = null) }
    }

    fun setRecipeName(name: String) {
        _state.update { current -> current.copy(draft = current.draft?.copy(name = name.take(80)), recipeProblem = null) }
    }

    fun addRecipeStep(tool: ToolId) {
        _state.update { current ->
            val draft = current.draft ?: return@update current
            if (draft.steps.any { it.tool == tool }) return@update current.copy(recipeProblem = "${tool.title} is already a step.")
            val steps = draft.steps + RecipeStep(tool, ToolConfig.defaultFor(tool))
            current.copy(draft = draft.copy(steps = steps), draftStep = steps.lastIndex, recipeProblem = null)
        }
    }

    fun removeRecipeStep(index: Int) {
        _state.update { current ->
            val draft = current.draft ?: return@update current
            current.copy(draft = draft.copy(steps = draft.steps.filterIndexed { i, _ -> i != index }), draftStep = null, recipeProblem = null)
        }
    }

    fun openRecipeStep(index: Int?) {
        _state.update { it.copy(draftStep = if (it.draftStep == index) null else index) }
    }

    fun changeRecipeStep(index: Int, transform: (ToolConfig) -> ToolConfig) {
        _state.update { current ->
            val draft = current.draft ?: return@update current
            val steps = draft.steps.toMutableList()
            val step = steps.getOrNull(index) ?: return@update current
            steps[index] = step.copy(config = transform(step.config))
            current.copy(draft = draft.copy(steps = steps), recipeProblem = null)
        }
    }

    fun saveRecipe() {
        val draft = _state.value.draft ?: return
        val problem = Recipes.problemWith(draft.name.trim(), draft.steps)
            ?: draft.steps.firstNotNullOfOrNull { step -> stepProblem(step)?.let { "${step.tool.title}: $it" } }
        if (problem != null) {
            _state.update { it.copy(recipeProblem = problem) }
            return
        }
        val recipe = Recipe(java.util.UUID.randomUUID().toString(), draft.name.trim(), draft.steps)
        val all = _state.value.recipes + recipe
        viewModelScope.launch {
            val kept = withContext(Dispatchers.IO) { Recipes.write(recipesFile, all) }
            _state.update {
                it.copy(
                    recipes = all,
                    recipeId = recipe.id,
                    draft = null,
                    draftStep = null,
                    recipeProblem = null,
                    error = if (kept) null else "The recipe could not be kept on this phone; it works until you leave this screen."
                )
            }
        }
    }

    fun deleteRecipe(id: String) {
        val all = _state.value.recipes.filterNot { it.id == id }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Recipes.write(recipesFile, all) }
            _state.update { it.copy(recipes = all, recipeId = if (it.recipeId == id) all.firstOrNull()?.id else it.recipeId) }
        }
    }

    /** Why a step's options will not run, judged the way the tool's own screen judges them. */
    private fun stepProblem(step: RecipeStep): String? {
        if (step.tool == ToolId.SET_PASSWORD) return null
        val stand = SelectedDoc(SheafFile(java.io.File("recipe.pdf"), "recipe.pdf", SheafFile.Origin.Derived("recipe")), pageCount = 2)
        return ToolUiState(tool = step.tool, config = step.config, documents = listOf(stand, stand)).blockedReason
    }

    // ---- edit text ----

    fun setEditPage(index: Int) {
        _state.update { it.copy(canvasPage = index, selectedLine = null) }
        renderBigPage(index)
        loadEditable(index)
    }

    private fun loadEditable(page: Int) {
        val doc = _state.value.documents.firstOrNull() ?: return
        if (_state.value.editPages.containsKey(page)) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { sheaf.surgeon.editableLines(PdfInput(doc.file.file, doc.password), listOf(page))[page] }.getOrNull()
            } ?: EditablePage(emptyList(), false)
            _state.update { it.copy(editPages = it.editPages + (page to found)) }
        }
    }

    /** Opens a line to be changed, with the paper and ink sampled where it sits. */
    fun selectLine(index: Int?) {
        _state.update { current ->
            if (index == null) return@update current.copy(selectedLine = null)
            val page = current.canvasPage
            val line = current.editPages[page]?.lines?.getOrNull(index) ?: return@update current
            val onPage = current.lineEdits[page].orEmpty()
            if (onPage.containsKey(index)) return@update current.copy(selectedLine = index, error = null)
            val (paper, ink) = current.thumbnails[page]?.let { sampleColours(it, line.area) } ?: (TextEditPlans.WHITE to TextEditPlans.INK)
            current.copy(
                selectedLine = index,
                lineEdits = current.lineEdits + (page to (onPage + (index to LineEdit(line.text, paper, ink)))),
                error = null
            )
        }
    }

    fun setLineText(text: String) {
        _state.update { current ->
            val index = current.selectedLine ?: return@update current
            val page = current.canvasPage
            val onPage = current.lineEdits[page].orEmpty()
            val edit = onPage[index] ?: return@update current
            current.copy(lineEdits = current.lineEdits + (page to (onPage + (index to edit.copy(text = text.replace('\n', ' '))))))
        }
    }

    /** Puts the selected line back as it was. */
    fun undoLine() {
        _state.update { current ->
            val index = current.selectedLine ?: return@update current
            val page = current.canvasPage
            current.copy(lineEdits = current.lineEdits + (page to (current.lineEdits[page].orEmpty() - index)), selectedLine = null)
        }
    }

    /**
     * Reads the words on a scanned page with the phone's recogniser, so its lines can be
     * tapped and changed like any other. Only the words that change are repainted.
     */
    fun readScannedPage() {
        val doc = _state.value.documents.firstOrNull() ?: return
        val page = _state.value.canvasPage
        val script = _state.value.config.ocrScript
        _state.update { it.copy(readingScan = true, error = null) }
        viewModelScope.launch {
            val read = withContext(Dispatchers.IO) {
                runCatching {
                    val engine = if (doc.encrypted) sheaf.encryptedReader else sheaf.pdfEngine
                    engine.open(doc.file.file, doc.password).use { opened ->
                        val size = opened.pageSize(page)
                        val bitmap = opened.renderPage(page, SCAN_READ_WIDTH_PX)
                        try {
                            val result = sheaf.ocr.read(bitmap, script)
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            val shares = mutableMapOf<PageArea, Float>()
                            fun share(a: PageArea): Float = shares.getOrPut(a) { inkShare(pixels, bitmap.width, bitmap.height, a) }
                            val all = result.lines.flatMap { line -> line.words }.map { w ->
                                share(PageArea(w.left / bitmap.width, w.top / bitmap.height, w.width / bitmap.width, w.height / bitmap.height))
                            }.filter { it > 0f }.sorted()
                            val median = all.getOrNull(all.size / 2) ?: 0f
                            val lines = TextEditPlans.scanLines(result.lines, bitmap.width, bitmap.height, size.heightPoints) { a ->
                                median > 0f && share(a) > median * 1.3f
                            }
                            EditablePage(lines, true, size.widthPoints, size.heightPoints)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            _state.update { current ->
                read.fold(
                    onSuccess = { found ->
                        current.copy(
                            readingScan = false,
                            editPages = current.editPages + (page to found),
                            error = if (found.lines.isEmpty()) {
                                "No words could be read on this page: it may be handwritten, or too faint. Write on it with Add text instead."
                            } else {
                                null
                            }
                        )
                    },
                    onFailure = { current.copy(readingScan = false, error = "The words on this page could not be read.") }
                )
            }
        }
    }

    /** How much of a word's box is ink: heavier type has more. */
    private fun inkShare(pixels: IntArray, width: Int, height: Int, a: PageArea): Float {
        val x0 = (a.left * width).toInt().coerceIn(0, width - 1)
        val x1 = ((a.left + a.width) * width).toInt().coerceIn(x0 + 1, width)
        val y0 = (a.top * height).toInt().coerceIn(0, height - 1)
        val y1 = ((a.top + a.height) * height).toInt().coerceIn(y0 + 1, height)
        var dark = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = pixels[y * width + x]
            if (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF) < 384) dark++
        }
        return dark.toFloat() / ((x1 - x0) * (y1 - y0))
    }

    private fun sampleColours(bitmap: Bitmap, area: PageArea): Pair<Int, Int> {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return TextEditPlans.colours(pixels, bitmap.width, bitmap.height, area)
    }

    /**
     * Replaces whole words wherever they appear, on every page whose words can be read, and
     * leaves each change outlined on its page to be checked or adjusted.
     */
    fun replaceEverywhere() {
        val doc = _state.value.documents.firstOrNull() ?: return
        val find = _state.value.config.findText.trim()
        if (find.isEmpty()) {
            _state.update { it.copy(replaceStatus = "Type what to find.") }
            return
        }
        val with = _state.value.config.replaceText.trim()
        val matchCase = _state.value.config.matchCase
        val pageCount = _state.value.pageOrder.size
        _state.update { it.copy(replacing = true, replaceStatus = "Looking through the pages…") }
        viewModelScope.launch {
            val input = PdfInput(doc.file.file, doc.password)
            val missing = (0 until pageCount).filter { it !in _state.value.editPages }
            val loaded = withContext(Dispatchers.IO) {
                runCatching { sheaf.surgeon.editableLines(input, missing) }.getOrDefault(emptyMap())
            }
            _state.update { it.copy(editPages = loaded + it.editPages) }
            val pattern = TextEditPlans.wholeWords(find, matchCase)
            val snapshot = _state.value
            val hits = snapshot.editPages.filter { (page, p) ->
                p.lines.withIndex().any { (i, line) -> pattern.containsMatchIn(snapshot.lineEdits[page]?.get(i)?.text ?: line.text) }
            }.keys
            // Pages not on screen are drawn small, once, to find their paper and ink.
            val drawings: Map<Int, Bitmap> = withContext(Dispatchers.IO) {
                runCatching {
                    val engine = if (doc.encrypted) sheaf.encryptedReader else sheaf.pdfEngine
                    engine.open(doc.file.file, doc.password).use { opened ->
                        hits.filter { it !in snapshot.thumbnails }.associateWith { page -> opened.renderPage(page, COLOUR_SAMPLE_WIDTH_PX) }
                    }
                }.getOrDefault(emptyMap())
            }
            var places = 0
            val touched = mutableSetOf<Int>()
            _state.update { current ->
                places = 0
                touched.clear()
                var all = current.lineEdits
                for (page in hits) {
                    val lines = current.editPages[page]?.lines ?: continue
                    val drawing = drawings[page] ?: current.thumbnails[page]
                    var onPage = all[page].orEmpty()
                    for ((i, line) in lines.withIndex()) {
                        val before = onPage[i]?.text ?: line.text
                        val count = pattern.findAll(before).count()
                        if (count == 0) continue
                        val after = TextEditPlans.replaceIn(before, find, with, matchCase)
                        val colours = onPage[i]?.let { it.paper to it.ink }
                            ?: drawing?.let { sampleColours(it, line.area) }
                            ?: (TextEditPlans.WHITE to TextEditPlans.INK)
                        onPage = onPage + (i to LineEdit(after, colours.first, colours.second))
                        places += count
                        touched += page
                    }
                    all = all + (page to onPage)
                }
                val unread = current.editPages.values.any { it.scanned && it.lines.isEmpty() }
                current.copy(
                    lineEdits = all,
                    replacing = false,
                    replaceStatus = if (places == 0) {
                        "\"$find\" isn't in the text of this document." +
                            (if (unread) " Scanned pages have to be read first: open one and tap Read the words on this page." else "")
                    } else {
                        "Changed in $places ${if (places == 1) "place" else "places"}, on ${touched.size} " +
                            "${if (touched.size == 1) "page" else "pages"}. Each is outlined on its page; tap one to adjust it."
                    }
                )
            }
            drawings.values.forEach { it.recycle() }
        }
    }

    // ---- edit pictures ----

    fun setPicturesPage(index: Int) {
        _state.update { it.copy(canvasPage = index, selectedPicture = null) }
        renderBigPage(index)
        loadPictures(index)
    }

    /** Finds the pictures on one page, once. */
    private fun loadPictures(page: Int) {
        val doc = _state.value.documents.firstOrNull() ?: return
        if (_state.value.pictures.containsKey(page)) return
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { sheaf.surgeon.findPictures(PdfInput(doc.file.file, doc.password), page) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(pictures = it.pictures + (page to found)) }
        }
    }

    fun selectPicture(index: Int?) {
        _state.update { it.copy(selectedPicture = index) }
    }

    private fun changePicture(page: Int, index: Int, transform: (PictureChange) -> PictureChange) {
        _state.update { current ->
            val original = current.pictures[page]?.getOrNull(index) ?: return@update current
            val onPage = current.pictureChanges[page].orEmpty()
            val change = transform(onPage[index] ?: PictureChange(original))
            current.copy(pictureChanges = current.pictureChanges + (page to (onPage + (index to change))), selectedPicture = index, error = null)
        }
    }

    fun movePicture(page: Int, index: Int, area: PageArea) = changePicture(page, index) { it.copy(area = area) }

    /** Takes a picture out, or, tapped again, keeps it. Taking it out forgets any swap. */
    fun toggleRemovePicture(page: Int, index: Int) = changePicture(page, index) {
        if (it.removed) it.copy(removed = false) else it.copy(removed = true, replacement = null, replacementBitmap = null)
    }

    /** Puts the chosen image in place of the selected picture. */
    fun replacePicture(uri: Uri) {
        val state = _state.value
        val index = state.selectedPicture
        if (index == null) {
            _state.update { it.copy(error = "Tap a picture on the page first, then choose the new one.") }
            return
        }
        val page = state.canvasPage
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val imported = sheaf.documentStore.import(uri)
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(imported.file.path, options)
                    if (options.outWidth <= 0) return@runCatching null
                    options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / PREVIEW_PX).coerceAtLeast(1)
                    options.inJustDecodeBounds = false
                    imported.file to android.graphics.BitmapFactory.decodeFile(imported.file.path, options)
                }.getOrNull()
            }
            if (loaded == null) {
                _state.update { it.copy(error = "That picture couldn't be read. Try a JPEG or PNG.") }
                return@launch
            }
            changePicture(page, index) { it.copy(removed = false, replacement = loaded.first, replacementBitmap = loaded.second) }
        }
    }

    // ---- marking up ----

    fun setMarkupPage(index: Int) {
        _state.update { it.copy(canvasPage = index) }
        renderBigPage(index)
    }

    fun addHighlight(page: Int, area: PageArea) {
        val colour = _state.value.config.markupColour
        addMark(page, PageMark.Highlight(area, colour))
    }

    fun addInk(page: Int, points: List<PagePoint>) {
        val config = _state.value.config
        addMark(page, PageMark.Ink(points, config.markupColour, config.penWidth))
    }

    /** A dragged-out area, made into whichever mark the current tool draws from one. */
    fun addArea(page: Int, area: PageArea) {
        val config = _state.value.config
        val mark = when (config.markupTool) {
            ToolConfig.MarkupTool.HIGHLIGHT -> PageMark.Highlight(area, config.markupColour)
            ToolConfig.MarkupTool.UNDERLINE -> PageMark.Shape(PageMark.Shape.Kind.UNDERLINE, area, config.markupColour, config.penWidth)
            ToolConfig.MarkupTool.STRIKETHROUGH -> PageMark.Shape(PageMark.Shape.Kind.STRIKETHROUGH, area, config.markupColour, config.penWidth)
            ToolConfig.MarkupTool.BOX -> PageMark.Shape(PageMark.Shape.Kind.BOX, area, config.markupColour, config.penWidth)
            ToolConfig.MarkupTool.CIRCLE -> PageMark.Shape(PageMark.Shape.Kind.CIRCLE, area, config.markupColour, config.penWidth)
            else -> return
        }
        addMark(page, mark)
    }

    /** A drag from one point to another: a line, or an arrow pointing where it ended. */
    fun addLine(page: Int, from: PagePoint, to: PagePoint) {
        val config = _state.value.config
        val arrow = when (config.markupTool) {
            ToolConfig.MarkupTool.LINE -> false
            ToolConfig.MarkupTool.ARROW -> true
            else -> return
        }
        addMark(page, PageMark.Line(from, to, config.markupColour, config.penWidth, arrow))
    }

    /** A tap: a note or a stamp lands there, saying what the options say. */
    fun addAt(page: Int, at: PagePoint) {
        val config = _state.value.config
        val mark = when (config.markupTool) {
            ToolConfig.MarkupTool.NOTE -> {
                if (config.markupText.isBlank()) {
                    _state.update { it.copy(error = "Type what the note should say first.") }
                    return
                }
                PageMark.Note(at, config.markupText.trim(), config.markupColour, config.noteSize)
            }
            ToolConfig.MarkupTool.STAMP -> {
                if (config.stampText.isBlank()) {
                    _state.update { it.copy(error = "Type what the stamp should say first.") }
                    return
                }
                PageMark.Stamp(at, config.stampText.trim(), config.markupColour)
            }
            else -> return
        }
        addMark(page, mark)
    }

    private fun addMark(page: Int, mark: PageMark) {
        _state.update { current ->
            val existing = current.marks[page].orEmpty()
            current.copy(marks = current.marks + (page to existing + mark), error = null)
        }
    }

    /** Takes the last mark off this page. A finger slips, and redrawing the lot is not it. */
    fun undoMark(page: Int) {
        _state.update { current ->
            val existing = current.marks[page].orEmpty()
            if (existing.isEmpty()) return@update current
            current.copy(marks = current.marks + (page to existing.dropLast(1)))
        }
    }

    fun clearMarks(page: Int) {
        _state.update { it.copy(marks = it.marks - page) }
    }

    private companion object {
        /** About a sixth of a phone screen. Enough to recognise a page, not to read it. */
        const val THUMBNAIL_WIDTH_PX = 220

        /**
         * How many pages are drawn as pictures.
         *
         * A thumbnail is a third of a megabyte and a long scan has hundreds of pages, which
         * is a heap this app does not have. Past this they are listed, numbered and still
         * selectable - they just have no picture, and the screen says why.
         */
        const val MAX_THUMBNAILS = 120

        /** Bates numbers are conventionally six digits wide. */

        /** Readable enough to aim a redaction box at, small enough to hold a few of. */
        const val REDACT_PAGE_WIDTH_PX = 900

        /** Big enough to judge a margin or a watermark on a phone screen. */
        const val PREVIEW_PAGE_WIDTH_PX = 700

        /** Enough to see what came out without holding a whole book in memory. */
        const val PREVIEW_CHARS = 4000
        const val PREVIEW_PX = 420

        /** A result's first page, beside its name. */
        const val RESULT_THUMB_PX = 160

        /** A chosen file's first page, in its row. */
        const val FILE_THUMB_PX = 120

        /** About 300 dpi across an A4 page: what the recogniser reads a scan at. */
        const val SCAN_READ_WIDTH_PX = 2480

        /** Enough of a page to tell its paper from its ink. */
        const val COLOUR_SAMPLE_WIDTH_PX = 700
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
    data class Pdf(val firstPage: Bitmap, val pages: Int) : ResultPreview
}

/** One state of the organise screen's plan, kept so the next edit can be undone. */
data class PageEdit(val order: List<Int>, val rotations: Map<Int, Int>)

/** Deep enough that nobody reaches the bottom; shallow enough to be a few kilobytes. */
private const val MAX_PAGE_EDITS = 30

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
    /** Something worth knowing that is not a problem: what a folder held. */
    val notice: String? = null,
    /** Organise only: the page order being edited, as indices into the original document. */
    val pageOrder: List<Int> = emptyList(),
    val selectedPages: Set<Int> = emptySet(),
    val rotations: Map<Int, Int> = emptyMap(),
    val thumbnails: Map<Int, Bitmap> = emptyMap(),
    /** Sign only: the drawn signature, written out as a transparent PNG. */
    val signatureFile: java.io.File? = null,
    /** Redact only: the boxes drawn on each page, normalised to the page. */
    val redactions: Map<Int, List<PageArea>> = emptyMap(),
    /** Which page the one-page-at-a-time canvases are showing - redaction and mark-up. */
    val canvasPage: Int = 0,
    /** Each chosen file's first page or picture, small, keyed by its path. */
    val fileThumbs: Map<String, Bitmap> = emptyMap(),
    /** Something to look at for each finished result, keyed by its path. */
    val previews: Map<String, ResultPreview> = emptyMap(),
    /** Page one, for the tools whose effect can be shown before running. */
    val previewPage: Bitmap? = null,
    /** The preview page's size in points, so millimetres can be drawn on it to scale. */
    val previewPageSize: PageSize? = null,
    /** The drawn signature, for the placement preview. */
    val signatureBitmap: Bitmap? = null,
    /** What compressing this document would actually produce. */
    val sizeCheck: SizeCheck = SizeCheck.Idle,
    /** Mark up only: the highlights and strokes drawn on each page. */
    val marks: Map<Int, List<PageMark>> = emptyMap(),
    /** True when the document has more pages than were drawn, so the UI can say so. */
    val thumbnailsCapped: Boolean = false,
    /** Organise only: the plans before each edit, newest last, for undo. */
    val pageEdits: List<PageEdit> = emptyList(),
    /** Sign only: signatures the user kept, newest first. */
    val savedSignatures: List<java.io.File> = emptyList(),
    /** Fill in a form only: the fields the document declares, and what has been typed. */
    val formFields: List<FormField> = emptyList(),
    val formValues: Map<String, String> = emptyMap(),
    /** Bookmarks: the list being edited, seeded from the document once [outlineRead]. */
    val outline: List<OutlineEntry> = emptyList(),
    val outlineRead: Boolean = false,
    /** Create a form: the fields drawn so far, on whichever pages. */
    val newFields: List<NewField> = emptyList(),
    /** Remove areas: what the last Find and mark found, in words, or null before one ran. */
    val findReport: String? = null,
    val finding: Boolean = false,
    /** Fill in a form: the answer sets kept from earlier, newest first. */
    val savedAnswers: List<java.io.File> = emptyList(),
    /** Add an image: the picture, decoded small, for the placement preview. */
    val stampBitmap: Bitmap? = null,
    /** Attachments: what the first document already carries, once [attachmentsRead]. */
    val attachments: List<AttachmentInfo> = emptyList(),
    val attachmentsRead: Boolean = false,
    /** Recipes: the ones kept, the one chosen, and one being made. */
    val recipes: List<Recipe> = emptyList(),
    val recipeId: String? = null,
    val draft: RecipeDraft? = null,
    val draftStep: Int? = null,
    val recipeProblem: String? = null,
    /** Document details: what the first file said, so a second file can clear the boxes it filled. */
    val metaSeed: DocumentMetadata? = null,
    /** Edit text: the lines each page offers, and the changes typed, by page then line. */
    val editPages: Map<Int, EditablePage> = emptyMap(),
    val lineEdits: Map<Int, Map<Int, LineEdit>> = emptyMap(),
    val selectedLine: Int? = null,
    val readingScan: Boolean = false,
    val replacing: Boolean = false,
    val replaceStatus: String? = null,
    /** Edit pictures: the pictures found on each page, where they were. */
    val pictures: Map<Int, List<PageArea>> = emptyMap(),
    /** Edit pictures: what has been done to them, by page and then by the picture's place in [pictures]. */
    val pictureChanges: Map<Int, Map<Int, PictureChange>> = emptyMap(),
    val selectedPicture: Int? = null,
    /** True once the form has been looked for, which is how "none" differs from "not yet". */
    val formRead: Boolean = false
) {
    /** The plan as it stands, pushed onto the undo stack before it is changed. */
    fun withEditRemembered(): List<PageEdit> =
        (pageEdits + PageEdit(pageOrder, rotations)).takeLast(MAX_PAGE_EDITS)

    val canUndoPageEdit: Boolean get() = pageEdits.isNotEmpty()

    /** Edit text: every changed line as the writer takes it; the preview draws the same list. */
    fun textEdits(page: Int? = null): List<TextEdit> = lineEdits.filterKeys { page == null || it == page }.flatMap { (p, onPage) ->
        val editable = editPages[p] ?: return@flatMap emptyList()
        val aspect = if (editable.widthPoints > 0f) editable.heightPoints / editable.widthPoints else 1.414f
        onPage.flatMap { (i, edit) ->
            val line = editable.lines.getOrNull(i) ?: return@flatMap emptyList()
            TextEditPlans.edits(p, line, edit.text, edit.paper, edit.ink, aspect) { text ->
                helveticaWidth(text, line.sizePoints) / editable.widthPoints.coerceAtLeast(1f)
            }
        }
    }

    /** Edit pictures: every change that does something, as the surgeon takes them. */
    fun pictureEdits(): Map<Int, List<PictureEdit>> = pictureChanges.mapValues { (page, changes) ->
        changes.mapNotNull { (i, change) ->
            val original = pictures[page]?.getOrNull(i) ?: return@mapNotNull null
            if (!change.changedFrom(original)) return@mapNotNull null
            PictureEdit(
                original,
                when {
                    change.removed -> PictureEdit.Action.Remove
                    change.replacement != null -> PictureEdit.Action.Replace(change.replacement)
                    else -> PictureEdit.Action.Move(change.area)
                }
            )
        }
    }.filterValues { it.isNotEmpty() }

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
            tool == ToolId.RENT_RECEIPTS -> when {
                config.tenant.isBlank() -> "Type the tenant's name."
                config.landlord.isBlank() -> "Type the owner's name."
                config.rentAddress.isBlank() -> "Type the address of the property."
                config.rent <= 0 -> "Type the monthly rent."
                else -> null
            }
            tool == ToolId.OFFICE_TO_PDF && documents.any { it.file.isPdf } ->
                "This one is already a PDF. Pick a Word, Excel or text file."
            documents.isEmpty() -> if (tool.input == ToolId.InputKind.IMAGES) {
                "Choose at least one image."
            } else {
                "Choose a file."
            }
            documents.any { !it.isReady } ->
                documents.firstNotNullOfOrNull { it.unreadableReason }
                    ?: "One file still needs its password."
            tool == ToolId.MERGE && documents.size < 2 -> "Merging needs at least two files."
            tool == ToolId.RECIPES && recipes.none { it.id == recipeId } -> "Choose a recipe, or make one."
            tool == ToolId.RECIPES && recipes.first { it.id == recipeId }.needsPassword && config.newPassword.isBlank() ->
                "Type the password to lock them with."
            tool == ToolId.EDIT_IMAGES && pictureEdits().isEmpty() -> "Move, take out or replace a picture first."
            tool == ToolId.RESIZE_PHOTO && config.photoKb < 2 -> "Type a size of at least 2 KB."
            tool == ToolId.RESIZE_PHOTO && config.photoMode == ToolConfig.PhotoMode.EXACT &&
                (config.photoWidth !in 16..4096 || config.photoHeight !in 16..4096) ->
                "Type a width and a height between 16 and 4096 pixels."
            tool == ToolId.COMPARE && documents.size != 2 ->
                if (documents.size < 2) "Add the newer version as the second file." else "Compare takes exactly two files: the older, then the newer."
            tool == ToolId.EDIT_TEXT && lineEdits.values.all { it.isEmpty() } -> "Tap a line of text and change it."
            tool == ToolId.EDIT_TEXT && textEdits().isEmpty() -> "Change the words first."
            tool == ToolId.SIGN && signatureFile == null -> "Draw your signature above."
            tool == ToolId.ANNOTATE && marks.values.all { it.isEmpty() } ->
                "Highlight a line or draw on the page."
            tool == ToolId.REDACT && redactions.values.all { it.isEmpty() } ->
                "Drag a box over what should be removed."
            tool == ToolId.ADD_TEXT && config.noteText.isBlank() -> "Type the text to add."
            tool == ToolId.HEADER_FOOTER &&
                config.headerText.isBlank() && config.footerText.isBlank() ->
                "Type a header or a footer."
            tool == ToolId.INSERT && documents.size < 2 && config.blankPages <= 0 ->
                "Add the PDF to insert, or choose how many blank pages to add."
            tool == ToolId.INSERT && config.replacePages && config.replaceSpec.isBlank() ->
                "Type the pages to replace."
            tool == ToolId.CREATE_FORM && newFields.isEmpty() -> "Drag a box on the page where a field should go."
            tool == ToolId.CREATE_FORM && newFields.any { it.name.isBlank() } -> "Give every field a name."
            tool == ToolId.DIGITAL_SIGN && documents.none { it.file.isPdf } -> "Choose the PDF first."
            tool == ToolId.DIGITAL_SIGN && documents.none { !it.file.isPdf } -> "Add your certificate file (.pfx or .p12)."
            tool == ToolId.DIGITAL_SIGN && documents.count { it.file.isPdf } > 1 -> "Sign one PDF at a time."
            tool == ToolId.ADD_IMAGE && documents.none { it.file.isPdf } -> "Choose the PDF first."
            tool == ToolId.ADD_IMAGE && documents.none { it.file.isImage } -> "Add the image to put on the page."
            tool == ToolId.ATTACHMENTS && !documents.first().file.isPdf ->
                "The first file has to be the PDF. Remove the others and pick it first."
            tool == ToolId.ATTACHMENTS && !config.extractAttachments && documents.size < 2 ->
                "Add the files to put inside it."
            tool == ToolId.ATTACHMENTS && config.extractAttachments && attachmentsRead && attachments.isEmpty() ->
                "This document has no files attached to it."
            tool == ToolId.BOOKMARKS && !outlineRead -> "Reading the bookmarks…"
            tool == ToolId.BOOKMARKS && outline.isNotEmpty() && outline.all { it.title.isBlank() } ->
                "Give the bookmarks a title."
            tool == ToolId.FORMS && !formRead -> "Reading the form…"
            tool == ToolId.FORMS && formFields.isEmpty() ->
                "This document has no form fields. Use Add text to write on it instead."
            tool == ToolId.FORMS && formValues.values.none { it.isNotBlank() } ->
                "Fill in at least one field."
            tool == ToolId.WATERMARK && config.watermarkText.isBlank() ->
                "Type the watermark text."
            tool == ToolId.SET_PASSWORD && config.restrictOnly &&
                config.allowPrinting && config.allowCopying && config.allowChanges ->
                "Choose at least one thing to restrict."
            tool == ToolId.SET_PASSWORD && !config.restrictOnly && config.newPassword.isBlank() ->
                "Choose a password."
            tool == ToolId.SET_PASSWORD && !config.restrictOnly && config.newPassword != config.confirmPassword ->
                "The two passwords do not match."
            tool == ToolId.EXTRACT && config.pageSpec.isBlank() ->
                if (config.removePages) "Tap the pages to remove, or type them." else "Tap the pages to keep, or type them."
            tool == ToolId.CROP && !config.cropAuto && config.resizeTo == null &&
                config.cropTopMm == 0f && config.cropRightMm == 0f && config.cropBottomMm == 0f && config.cropLeftMm == 0f ->
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
    val watermarkOpacity: Float = 0.22f,
    val watermarkLayout: WatermarkSpec.Layout = WatermarkSpec.Layout.ONCE_DIAGONAL,
    val watermarkColour: Int = WATERMARK_GREY,

    val numberPosition: PageNumberSpec.Position = PageNumberSpec.Position.BOTTOM_CENTRE,
    val numberFormat: String = "{n}",
    val numberStartAt: Int = 1,
    val numberSkipFirst: Int = 0,
    val bates: Boolean = false,
    val batesPrefix: String = "",
    val batesDigits: Int = 6,

    /** Crop: millimetres off each edge as the reader sees the page, or the white found. */
    val cropTopMm: Float = 0f,
    val cropRightMm: Float = 0f,
    val cropBottomMm: Float = 0f,
    val cropLeftMm: Float = 0f,
    val cropAuto: Boolean = false,
    val resizeTo: PageSpec.Size? = null,
    val cropEnlarge: Boolean = true,

    /** Page background: packed ARGB. */
    val backgroundColour: Int = BACKGROUND_CREAM,

    /** Page labels. */
    val labelFrontPages: Int = 0,
    val labelFrontStyle: PageLabelSpec.Style = PageLabelSpec.Style.ROMAN_LOWER,
    val labelStyle: PageLabelSpec.Style = PageLabelSpec.Style.DECIMAL,
    val labelStartAt: Int = 1,
    val labelPrefix: String = "",
    val removeLabels: Boolean = false,

    /** Attachments: take them out rather than put them in. */
    val extractAttachments: Boolean = false,

    /** Extract: take the named pages out instead of keeping only them. */
    val removePages: Boolean = false,

    /** Insert: put the new pages in place of these rather than beside them. */
    val replacePages: Boolean = false,
    val replaceSpec: String = "",

    /** Images to PDF: one file per image. */
    val separatePdfs: Boolean = false,

    /** Digital signature. The password is held here for the run and nowhere else. */
    val certPassword: String = "",
    val signReason: String = "",
    val signLocation: String = "",
    val signVisible: Boolean = true,
    val signOnFirstPage: Boolean = false,

    /** Create a form: what the next dragged box becomes. */
    val fieldKind: NewField.Kind = NewField.Kind.TEXT,

    /** Make searchable: which script's model reads the pages. */
    val ocrScript: OcrScript = OcrScript.LATIN,

    /** Find and replace text. [findText] is shared with Remove areas' Find and mark. */
    val replaceText: String = "",
    val matchCase: Boolean = false,

    /** Rent receipts. */
    val tenant: String = "",
    val landlord: String = "",
    val rentAddress: String = "",
    val rent: Long = 0L,
    val rentYear: Int = 2026,
    val rentMonth: Int = 4,
    val rentMonths: Int = 12,
    val rentMode: String = "Bank transfer",
    val landlordPan: String = "",

    /** PDF to Office: which file to make, and whether the pictures come along. */
    val officeTarget: OfficeTarget = OfficeTarget.WORD,
    val convertPictures: Boolean = true,

    /** Compress: get under a size rather than pick a level. */
    val compressToSize: Boolean = false,
    val compressTargetMb: Int = 2,
    /** Compress to a size: let pages become pictures if nothing else gets under it. */
    val compressPictures: Boolean = false,

    /** Remove areas: what Find and mark looks for. */
    val findAadhaar: Boolean = true,
    val findPan: Boolean = true,
    val findPhone: Boolean = false,
    val findEmail: Boolean = false,
    val findCard: Boolean = false,
    val findText: String = "",

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

    val headerText: String = "",
    val footerText: String = "",
    val stampAlign: StampSpec.Align = StampSpec.Align.CENTRE,
    val stampSize: Float = 9f,
    val stampSkipFirst: Int = 0,

    /** Insert only: the page the new pages land at, and how many blanks if no file is given. */
    val insertAt: Int = 1,
    val blankPages: Int = 0,

    /** Fill in a form only: draw the values into the page and take the fields away. */
    val flattenForm: Boolean = false,

    /** Mark up only: which kind of mark the finger makes, in what colour and how thick. */
    val markupTool: MarkupTool = MarkupTool.HIGHLIGHT,
    val markupColour: Int = HIGHLIGHT_YELLOW,
    val penWidth: Float = 2.5f,
    /** What a note says, and what a stamp says, before they are placed by a tap. */
    val markupText: String = "",
    val stampText: String = "APPROVED",

    /** Add a password only: what the document asks readers to allow. */
    val allowPrinting: Boolean = true,
    val allowCopying: Boolean = true,
    val allowChanges: Boolean = true,
    /** Opens freely, but restricted; no password to type. */
    val restrictOnly: Boolean = false,

    val perSheet: Int = 2,
    val nUpPaper: PageSpec.Size = PageSpec.Size.A4,
    /** Split by size: the limit each part stays under, in whole megabytes. */
    val partMb: Int = 10,

    val metaMode: MetaMode = MetaMode.EDIT,
    val metaTitle: String = "",
    val metaAuthor: String = "",
    val metaSubject: String = "",
    val metaKeywords: String = "",
    val cleanScripts: Boolean = true,
    val cleanAttachments: Boolean = true,
    val cleanComments: Boolean = true,

    /** Resize photo to KB: the limit, in KB of 1,000 bytes, and how many pixels. */
    val photoKb: Int = 50,
    val photoMode: PhotoMode = PhotoMode.KEEP,
    val photoWidth: Int = 300,
    val photoHeight: Int = 400
) {
    enum class SplitMode { EVERY_N, EACH_PAGE, RANGES, AT_BOOKMARKS }

    enum class PhotoMode(val label: String) {
        KEEP("Keep its shape"),
        PASSPORT("Passport photo, 3.5 × 4.5 cm"),
        EXACT("Width × height in pixels")
    }

    /** What Document details does: edit the fields, blank them, or clean everything hidden. */
    enum class MetaMode { EDIT, STRIP, CLEAN }

    /** The mark-up kit. [drags] marks are dragged out; [taps] marks are put down with a tap. */
    enum class MarkupTool(val label: String, val drags: Boolean, val taps: Boolean) {
        HIGHLIGHT("Highlight", true, false),
        PEN("Draw", false, false),
        UNDERLINE("Underline", true, false),
        STRIKETHROUGH("Strike through", true, false),
        BOX("Box", true, false),
        CIRCLE("Circle", true, false),
        LINE("Line", true, false),
        ARROW("Arrow", true, false),
        NOTE("Note", false, true),
        STAMP("Stamp", false, true);

        /** Highlight has its own pale palette; everything else is drawn in ink. */
        val usesInk: Boolean get() = this != HIGHLIGHT
    }

    companion object {
        fun defaultFor(tool: ToolId): ToolConfig = when (tool) {
            // Extracting defaults to nothing selected on purpose: "1-" would silently produce
            // a copy of the whole document and look like the tool did nothing.
            ToolId.EXTRACT -> ToolConfig(pageSpec = "")
            ToolId.SPLIT -> ToolConfig(splitMode = SplitMode.EVERY_N, splitSize = 1)
            // The financial year starts in April; receipts usually run from there.
            ToolId.RENT_RECEIPTS -> RentReceiptsOp.thisMonth().let { (year, month) ->
                ToolConfig(rentYear = if (month >= 4) year else year - 1, rentMonth = 4)
            }
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

/**
 * What Find and mark looks for. The Indian identifiers first, because they are what people
 * here most often need out of a document before it goes anywhere.
 */
val AADHAAR = Regex("\\b[2-9]\\d{3}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b")
val PAN = Regex("\\b[A-Z]{5}\\d{4}[A-Z]\\b")
val PHONE = Regex("(?:\\+\\d{1,3}[\\s-]?)?(?:\\(?\\d{2,5}\\)?[\\s-]?)?\\d{3,5}[\\s-]?\\d{4,5}\\b")
val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
val CARD = Regex("\\b(?:\\d[ -]?){12,18}\\d\\b")

/** A pen green, dark enough to read on paper. */
val INK_GREEN: Int = 0xFF1E7B34.toInt()

/** The words a stamp usually says. Any other can be typed. */
val STAMP_WORDS = listOf("APPROVED", "PAID", "RECEIVED", "DRAFT", "COPY", "CONFIDENTIAL", "VOID", "FINAL")

/** Watermark inks: grey unless asked, red for VOID or COPY, blue for a company name. */
val WATERMARK_GREY: Int = 0xFF6B6B75.toInt()
val WATERMARK_RED: Int = 0xFFCC1F1F.toInt()
val WATERMARK_BLUE: Int = 0xFF1A45B8.toInt()

/**
 * Page backgrounds. Pale on purpose: a background has to let black type through, and the
 * usual reasons for one - easier on the eyes, a draft, a section - all want something soft.
 */
val BACKGROUND_CREAM: Int = 0xFFFFF8E7.toInt()
val BACKGROUND_GREY: Int = 0xFFEFEFEF.toInt()
val BACKGROUND_BLUE: Int = 0xFFE8F0FB.toInt()
val BACKGROUND_GREEN: Int = 0xFFE9F5E9.toInt()
val BACKGROUND_PINK: Int = 0xFFFBE9EE.toInt()
val BACKGROUND_YELLOW: Int = 0xFFFFF5B8.toInt()
val BACKGROUND_SEPIA: Int = 0xFFF4ECD8.toInt()
val BACKGROUND_DARK: Int = 0xFF1E1E22.toInt()
val NOTE_RED: Int = 0xFFC62828.toInt()
val NOTE_BLUE: Int = 0xFF1A4FA0.toInt()

/**
 * Highlighter colours, and pen colours.
 *
 * Separate sets because they are doing opposite things: a highlight sits under the words and
 * has to let them through, so it is pale; a pen sits on top of them and has to be seen, so it
 * is not. The same palette for both gives a yellow pen nobody can read.
 */
val HIGHLIGHT_YELLOW: Int = 0xFFFFE14D.toInt()
val HIGHLIGHT_GREEN: Int = 0xFF9BE37A.toInt()
val HIGHLIGHT_PINK: Int = 0xFFFF9EC4.toInt()
val HIGHLIGHT_BLUE: Int = 0xFF8FD2FF.toInt()

/** What has been done to one picture on a page, in Edit pictures. */
data class PictureChange(
    /** Where it is now, as fractions of the page. */
    val area: PageArea,
    val removed: Boolean = false,
    val replacement: java.io.File? = null,
    val replacementBitmap: Bitmap? = null
) {
    fun changedFrom(original: PageArea): Boolean = removed || replacement != null ||
        kotlin.math.abs(area.left - original.left) + kotlin.math.abs(area.top - original.top) +
        kotlin.math.abs(area.width - original.width) + kotlin.math.abs(area.height - original.height) > 0.003f
}

/** A line being changed in Edit text: its new words, and the page's paper and ink round it. */
data class LineEdit(val text: String, val paper: Int, val ink: Int)

/** Near enough to Helvetica for deciding what fits; the writer measures exactly. */
private val measurePaint by lazy { android.graphics.Paint().apply { typeface = android.graphics.Typeface.SANS_SERIF } }

internal fun helveticaWidth(text: String, sizePoints: Float): Float = synchronized(measurePaint) {
    measurePaint.textSize = sizePoints
    measurePaint.measureText(text)
}

/**
 * Document details with more than one file: the boxes filled from the first file are emptied
 * while untouched, because an empty box then means "keep each file's own".
 */
internal fun ToolConfig.forSeveral(seed: DocumentMetadata?, files: Int): ToolConfig {
    if (seed == null || files < 2) return this
    val untouched = metaTitle == seed.title.orEmpty() && metaAuthor == seed.author.orEmpty() &&
        metaSubject == seed.subject.orEmpty() && metaKeywords == seed.keywords.orEmpty()
    return if (untouched) copy(metaTitle = "", metaAuthor = "", metaSubject = "", metaKeywords = "") else this
}

/** A recipe being made: its name and steps so far. */
data class RecipeDraft(val name: String = "", val steps: List<RecipeStep> = emptyList())
