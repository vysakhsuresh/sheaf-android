package com.layerbit.sheaf.jobs

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.ops.Op
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Starts operations and reports how they are going.
 *
 * One at a time, by [ExistingWorkPolicy.APPEND_OR_REPLACE] on a single work chain. PDF work is
 * memory-bound rather than CPU-bound, so running two large documents at once is how a device
 * with a 192 MB heap ends up killed - the throughput gain is imaginary and the failure is not.
 *
 * Job arguments live here in memory rather than in WorkManager's input Data, which is capped
 * near 10 KB. The consequence is honest and worth stating: if the process is killed before a
 * job starts, the queued job is lost. That is the right trade for an app whose work is
 * seconds-to-minutes and whose inputs are transient cache files that would not survive a
 * process death intact either.
 */
class JobRunner(private val context: Context) {

    data class Request(
        val op: Op,
        val inputs: List<SheafFile>,
        val passwords: Map<String, String>
    )

    private val pending = ConcurrentHashMap<String, Request>()

    /**
     * The jobs the user cancelled.
     *
     * A worker cannot work this out for itself. WorkManager's `isStopped` answers a wider
     * question - it is equally true when storage drops below this chain's constraint and when
     * the system's execution window runs out - and the arguments being gone is not an answer
     * either, because [cancelAll] drops them. Only this side knows which cancels came from a
     * person, and the difference decides whether a finished job's output is kept.
     */
    private val userCancelled = ConcurrentHashMap.newKeySet<String>()

    /**
     * The jobs [take] has handed out that have not reported a terminal state yet.
     *
     * A single field would be wrong: the chain is APPEND_OR_REPLACE, so a worker can still be
     * unwinding when the next one starts, and a cancel has to reach both. Entries leave through
     * [settled], which every exit from the worker runs.
     */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()

    /** Queues an operation. Returns the job id, which is also the WorkManager unique name. */
    fun submit(op: Op, inputs: List<SheafFile>, passwords: Map<String, String> = emptyMap()): String {
        val jobId = UUID.randomUUID().toString()
        pending[jobId] = Request(op, inputs, passwords)

        val request = OneTimeWorkRequestBuilder<OpWorker>()
            // The tool travels in the Data as well as in the Request, because the Request may
            // already be gone by the time the worker runs and a state the UI cannot attribute
            // to a tool is a state no screen shows.
            .setInputData(workDataOf(OpWorker.KEY_JOB_ID to jobId, OpWorker.KEY_TOOL to op.tool.name))
            // No network constraint, because there is no network. Storage-not-low is the one
            // that matters: an operation that runs out of disk halfway leaves a truncated PDF.
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .addTag(TAG_JOB)
            .build()

        WorkManager.getInstance(context)
            .beginUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            .enqueue()

        return jobId
    }

    fun cancelAll() {
        // Recorded first, because the two calls below do not take effect together:
        // cancelUniqueWork() is asynchronous, while the clear is immediate. A worker already
        // inside doWork() can therefore find its arguments gone with isStopped still false, and
        // without this it would report the user's own cancel as a job lost to a restart.
        //
        // Nothing is dropped here. An id is only forgotten once the worker it belongs to has
        // reported a terminal state, because that is the moment it can no longer ask; a second
        // cancel arriving while an earlier one is still unwinding must not answer the first
        // worker's question with "no". What never runs is pruned instead, below.
        userCancelled.addAll(pending.keys)
        userCancelled.addAll(inFlight)

        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)

        // A job cancelled before it ever started never calls settled(), so its id would stay
        // here for the life of the process. The cap is what bounds that, and it is safe at any
        // size: the ids are UUIDs, so a forgotten one can never be mistaken for a later job -
        // the only cost of dropping one too early is a cancel reported as a stop.
        if (userCancelled.size > MAX_REMEMBERED_CANCELS) {
            userCancelled.retainAll(pending.keys + inFlight)
        }

        // A cancelled worker never starts, so it never calls take() - and only take() drains
        // this map. Without the clear, every cancelled job's input list is held until the
        // process dies, which on a batch of forty scans is real memory for nothing.
        pending.clear()
    }

    /** Clears a finished result so a screen returning from the background starts clean. */
    fun acknowledge() {
        if (_state.value !is JobState.Running) _state.value = JobState.Idle
    }

    // --- called by OpWorker ---

    internal fun take(jobId: String): Request? = pending.remove(jobId)?.also { inFlight.add(jobId) }

    /** Whether the user cancelled this job, as opposed to anything else that stops a worker. */
    internal fun wasCancelledByUser(jobId: String): Boolean = jobId in userCancelled

    /**
     * The worker for [jobId] has published its terminal state and will not ask anything again.
     *
     * Called from every exit, including the throwing ones, which is what keeps [inFlight] from
     * naming a job that finished minutes ago - a stale entry there would hand the next cancel a
     * completed job to mark, and a job marked cancelled has its outcomes discarded.
     */
    internal fun settled(jobId: String) {
        inFlight.remove(jobId)
        userCancelled.remove(jobId)
    }

    internal fun publish(state: JobState) {
        _state.value = state
    }

    companion object {
        private const val UNIQUE_WORK = "sheaf.op.chain"
        private const val TAG_JOB = "sheaf.job"

        /**
         * How many cancelled-but-never-started ids to keep before pruning to what is still live.
         *
         * Only reached by cancelling repeatedly without anything running, so the number just has
         * to be comfortably larger than one batch of queued files.
         */
        private const val MAX_REMEMBERED_CANCELS = 256
    }
}
