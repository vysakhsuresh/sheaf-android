package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.files.Workspace
import com.layerbit.sheaf.pdf.PdfEngine
import com.layerbit.sheaf.pdf.PdfSurgeon

/**
 * Every tool in Sheaf, expressed as one shape.
 *
 *     List<SheafFile> -> Op -> List<SheafFile>
 *
 * Plural on both sides from the first commit, and that is the most consequential decision in
 * this codebase. Merge is many-to-one, split is one-to-many, compress is one-to-one, and a
 * batch is just the same operation handed more inputs - so batch mode is not a feature to
 * build later, it is what the type already means. Chaining tools into a saved preset is then
 * function composition rather than new machinery.
 *
 * The competition treats "process twenty files at once" as the thing you pay for. Here it
 * falls out of the signature, and that is the point of difference worth protecting. Resist
 * any pull towards a convenient `fun run(input: SheafFile): SheafFile` - one of those and
 * the property is gone.
 */
interface Op {

    /**
     * Which tool this is. Configuration lives in the instance - a compress at Strong and a
     * compress at Light are two instances of the same [tool], which is what a saved preset
     * will serialise once presets arrive.
     */
    val tool: ToolId

    /** What this run is called, including its settings: "Compress (Balanced)". */
    val title: String

    /** How many inputs this makes sense for. Checked before a run is offered. */
    val arity: Arity

    /**
     * Runs the operation.
     *
     * Implementations write every output through [OpContext.workspace] and must not modify any
     * input file. They report progress as they go, and check [OpContext.isCancelled] often
     * enough that a user who taps cancel sees it happen - a page boundary is the natural place.
     *
     * @return one [OpOutcome] per logical unit of work, in input order. A failure for one file
     *   is reported as [OpOutcome.Failed] rather than thrown, so one unreadable document in a
     *   batch of forty does not discard the other thirty-nine.
     * @throws OpCancelled if the job was cancelled. That is the one case where unwinding the
     *   whole run is right.
     */
    suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome>

    sealed interface Arity {
        /** Runs on each input independently. Compress, rotate, OCR. */
        data object EachIndependently : Arity

        /** Needs at least [min] inputs, consumed together. Merge. */
        data class Together(val min: Int) : Arity

        /** Exactly one input. Split, extract pages. */
        data object ExactlyOne : Arity
    }
}

/** What an operation is given to work with. Assembled once per job. */
class OpContext(
    val workspace: Workspace,
    /** Reading and rasterising. */
    val engine: PdfEngine,
    /** Restructuring and writing. */
    val surgeon: PdfSurgeon,
    /**
     * Passwords the user supplied for encrypted inputs, keyed by input file path.
     *
     * In memory only. Never written to the database, never logged, and gone when the job
     * finishes - a password typed to open one document is not a credential this app stores.
     */
    val passwords: Map<String, String> = emptyMap(),
    private val cancelled: () -> Boolean = { false }
) {
    val isCancelled: Boolean get() = cancelled()

    /** Throws if the job was cancelled. Call at each natural checkpoint. */
    fun checkCancelled() {
        if (isCancelled) throw OpCancelled()
    }
}

/**
 * The result for one unit of work.
 *
 * [Failed] carries a human-readable reason rather than an exception because it is shown to the
 * user directly: "page 4 could not be read" is actionable, a stack trace is not.
 */
sealed interface OpOutcome {
    data class Produced(val file: SheafFile) : OpOutcome
    data class Failed(val inputName: String, val reason: String) : OpOutcome
    data class Skipped(val inputName: String, val reason: String) : OpOutcome
}

/**
 * How far along a job is.
 *
 * [unitsDone] out of [unitsTotal] counts pages rather than files wherever a file can take a
 * while, because a progress bar that sits at "1 of 2" for ninety seconds tells the user
 * nothing about whether the app has hung.
 */
data class Progress(
    val unitsDone: Int,
    val unitsTotal: Int,
    val label: String
) {
    val fraction: Float
        get() = if (unitsTotal <= 0) 0f else (unitsDone.toFloat() / unitsTotal).coerceIn(0f, 1f)
}

/** Thrown out of [Op.run] when the user cancelled. Not an error; nothing is reported for it. */
class OpCancelled : Exception("Cancelled")
