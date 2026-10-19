package com.layerbit.sheaf.ui.open

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.ops.Suggest
import com.layerbit.sheaf.pdf.PdfException
import com.layerbit.sheaf.pdf.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One opened file and what was found out about it. */
data class OpenedFile(val file: SheafFile, val facts: Suggest.Facts)

data class OpenUiState(
    val files: List<OpenedFile> = emptyList(),
    /** "Looked at 2 of 5…", while files are being read. */
    val status: String? = null,
    val error: String? = null,
    val suggestions: List<Suggest.Suggestion> = emptyList()
) {
    val busy: Boolean get() = status != null
}

/**
 * Open a file: anything at all - a PDF, photos, a Word file, a whole folder - looked at on the
 * phone, and the tools that fit it offered with the reason in words.
 */
class OpenViewModel(app: Application) : AndroidViewModel(app) {

    private val sheaf = app as SheafApplication
    private val _state = MutableStateFlow(OpenUiState())
    val state: StateFlow<OpenUiState> = _state.asStateFlow()

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val total = uris.size
            _state.update { it.copy(status = "Looking at ${plural(total, "file")} on this phone…", error = null) }
            val problems = mutableListOf<String>()
            for ((i, uri) in uris.withIndex()) {
                val imported = runCatching { sheaf.documentStore.import(uri) }
                imported.exceptionOrNull()?.let { problems += it.message ?: "One file could not be read." }
                val file = imported.getOrNull() ?: continue
                val facts = withContext(Dispatchers.IO) { factsOf(file) }
                _state.update { current ->
                    val files = current.files + OpenedFile(file, facts)
                    current.copy(
                        files = files,
                        status = if (i + 1 < total) "Looked at ${i + 1} of $total…" else current.status,
                        suggestions = Suggest.suggest(files.map { it.facts })
                    )
                }
            }
            _state.update { it.copy(status = null, error = problems.takeIf { p -> p.isNotEmpty() }?.joinToString(" ")) }
        }
    }

    /** Every file in a folder and the folders inside it, by name. Hidden files are left out. */
    fun addFolder(tree: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(status = "Looking in the folder…", error = null) }
            val uris = withContext(Dispatchers.IO) {
                val out = mutableListOf<Uri>()
                fun walk(dir: DocumentFile, depth: Int) {
                    if (depth > 8) return
                    for (child in dir.listFiles().sortedBy { it.name?.lowercase() }) {
                        if (child.name.orEmpty().startsWith(".")) continue
                        if (child.isDirectory) walk(child, depth + 1) else out += child.uri
                    }
                }
                runCatching { DocumentFile.fromTreeUri(getApplication(), tree)?.let { walk(it, 0) } }
                out
            }
            if (uris.isEmpty()) {
                _state.update { it.copy(status = null, error = "That folder is empty.") }
            } else {
                add(uris)
            }
        }
    }

    fun startOver() {
        _state.value.files.forEach { it.file.file.delete() }
        _state.value = OpenUiState()
    }

    /** The files a suggestion takes, for the tool's own screen. */
    fun handOver(suggestion: Suggest.Suggestion) {
        sheaf.handoff.offerFiles(suggestion.takes.mapNotNull { _state.value.files.getOrNull(it)?.file })
    }

    /** All the PDFs opened, for a recipe. */
    fun handOverPdfs() {
        sheaf.handoff.offerFiles(_state.value.files.map { it.file }.filter { it.isPdf })
    }

    private fun factsOf(file: SheafFile): Suggest.Facts {
        val extension = file.displayName.substringAfterLast('.', "").lowercase()
        if (file.isImage) return Suggest.Facts(Suggest.Kind.IMAGE, file.sizeBytes, extension = extension)
        if (!file.isPdf) {
            val office = extension in setOf("docx", "xlsx", "txt", "md", "csv")
            return Suggest.Facts(if (office) Suggest.Kind.OFFICE else Suggest.Kind.OTHER, file.sizeBytes, extension = extension)
        }
        val input = PdfInput(file.file)
        return try {
            val info = sheaf.surgeon.inspect(input)
            val scanned = runCatching { sheaf.surgeon.editableLines(input, listOf(0))[0]?.scanned == true }.getOrDefault(false)
            val forms = runCatching { sheaf.surgeon.readFormFields(input).isNotEmpty() }.getOrDefault(false)
            val words = runCatching { sheaf.surgeon.extractText(input, listOf(0))[0].orEmpty() }.getOrDefault("")
            val (amounts, id) = Suggest.wordsSay(words)
            Suggest.Facts(
                Suggest.Kind.PDF, file.sizeBytes, pages = info.pageCount, scanned = scanned, forms = forms,
                amounts = amounts, idNumber = id, extension = extension
            )
        } catch (_: PdfException.PasswordRequired) {
            Suggest.Facts(Suggest.Kind.PDF, file.sizeBytes, locked = true, extension = extension)
        } catch (_: Exception) {
            Suggest.Facts(Suggest.Kind.PDF, file.sizeBytes, unreadable = true, extension = extension)
        }
    }

    private fun plural(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"
}
