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

    private val _state = MutableStateFlow<JobState>(JobState.Idle)
    val state: StateFlow<JobState> = _state.asStateFlow()

    /** Queues an operation. Returns the job id, which is also the WorkManager unique name. */
    fun submit(op: Op, inputs: List<SheafFile>, passwords: Map<String, String> = emptyMap()): String {
        val jobId = UUID.randomUUID().toString()
        pending[jobId] = Request(op, inputs, passwords)

        val request = OneTimeWorkRequestBuilder<OpWorker>()
            .setInputData(workDataOf(OpWorker.KEY_JOB_ID to jobId))
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
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK)
    }

    /** Clears a finished result so a screen returning from the background starts clean. */
    fun acknowledge() {
        if (_state.value !is JobState.Running) _state.value = JobState.Idle
    }

    // --- called by OpWorker ---

    internal fun take(jobId: String): Request? = pending.remove(jobId)

    internal fun publish(state: JobState) {
        _state.value = state
    }

    companion object {
        private const val UNIQUE_WORK = "sheaf.op.chain"
        private const val TAG_JOB = "sheaf.job"
    }
}
