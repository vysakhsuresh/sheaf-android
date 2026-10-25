package com.layerbit.sheaf.jobs

import com.layerbit.sheaf.text.Strings
import com.layerbit.sheaf.R
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.data.OutputStore
import com.layerbit.sheaf.ops.Op
import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.ops.OpCancelled
import com.layerbit.sheaf.ops.OpContext
import com.layerbit.sheaf.ops.Progress
import com.layerbit.sheaf.ops.ToolId
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs one [com.layerbit.sheaf.ops.Op] as background work.
 *
 * Every operation goes through here, including ones that look instantaneous. A three-page
 * merge finishes before the notification is drawn, but the same code path having two modes -
 * "quick ones inline, slow ones as work" - is how the slow path ends up untested. One path.
 *
 * The operation is passed as a job id rather than in the input Data. WorkManager's Data is
 * capped at about 10 KB, and neither a configured Op nor a batch of forty documents with long
 * scanner-app names fits in that. [JobRunner] holds the real arguments in memory, and this
 * worker collects them by id.
 *
 * Whatever happens to a job [JobRunner] queued, this worker publishes exactly one terminal
 * state before it returns. A job that ends without one leaves the UI on a progress bar that
 * will never move, next to a Cancel that cannot help it, for the life of the process.
 */
class OpWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    /** Past this, a batch finishes the file in hand and stops. Set once doWork has a job. */
    private var softDeadline = Long.MAX_VALUE

    /** Past this, even the file in hand stops, a minute before Android would stop it. */
    private var hardDeadline = Long.MAX_VALUE

    private val pastHardDeadline get() = System.currentTimeMillis() > hardDeadline

    /**
     * A batch handed to the op one file at a time, so it can stop between two of them.
     *
     * Progress is reported per file, the same granularity the ops' own batch loops use, with
     * the inner label kept so a long file still shows its pages moving. Files the clock did not
     * reach are reported as skipped with the reason, so the user knows exactly which to run
     * again rather than finding a batch that silently came up short.
     */
    private suspend fun runFileByFile(
        op: Op,
        request: JobRunner.Request,
        context: OpContext,
        report: (Progress) -> Unit
    ): List<OpOutcome> {
        val inputs = request.inputs
        val outcomes = mutableListOf<OpOutcome>()
        for ((index, input) in inputs.withIndex()) {
            if (System.currentTimeMillis() > softDeadline) {
                inputs.drop(index).forEach { left ->
                    outcomes += OpOutcome.Skipped(
                        left.displayName,
                        Strings.get(R.string.app_job_skipped_deadline)
                    )
                }
                break
            }
            context.checkCancelled()
            outcomes += op.run(listOf(input), context) { inner ->
                val label = if (inner.label.isBlank() || inner.label == Strings.get(R.string.app_job_done) || (inner.unitsTotal > 0 && inner.unitsDone >= inner.unitsTotal)) input.displayName else inner.label
                report(Progress(index, inputs.size, Strings.get(R.string.app_job_file_progress, index + 1, inputs.size, label)))
            }
        }
        report(Progress(inputs.size, inputs.size, Strings.get(R.string.app_job_done)))
        return outcomes
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as SheafApplication
        val runner = app.jobRunner

        // Every state is filtered by tool, so a state with no tool on it reaches no screen.
        // JobRunner.submit() always writes both keys, which is what makes the promise above
        // hold: the only input Data nothing can be reported for is Data this app did not write.
        val tool = inputData.getString(KEY_TOOL)?.let { name ->
            ToolId.entries.firstOrNull { it.name == name }
        }

        val jobId = inputData.getString(KEY_JOB_ID)
        if (jobId == null) {
            if (tool != null) {
                runner.publish(JobState.Crashed(tool, Strings.get(R.string.app_job_not_started)))
            }
            return Result.failure()
        }

        val request = runner.take(jobId)
        if (request == null) {
            // The arguments only live in memory, so a worker that outlived them - a retry, or
            // WorkManager reviving the queue after the process was killed - has nothing to run.
            if (tool != null) {
                // A cancel clears the arguments too, so check that before blaming a restart.
                runner.publish(
                    if (runner.wasCancelledByUser(jobId)) {
                        JobState.Cancelled(tool)
                    } else {
                        JobState.Crashed(tool, Strings.get(R.string.app_job_lost))
                    }
                )
            }
            // take() never recorded this one, but a cancel may still have: forget it either way,
            // because nothing will ask about it again.
            runner.settled(jobId)
            return Result.failure()
        }
        val op = request.op

        // Android 15 gives a dataSync foreground service six hours in any 24 and then stops it
        // outright. Below 35 there is no such cap, so the budget is unlimited there.
        val started = System.currentTimeMillis()
        val budget = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runner.foregroundBudget(started)
        } else {
            Long.MAX_VALUE / 4
        }
        softDeadline = started + budget
        hardDeadline = softDeadline + (JobRunner.HARD_LIMIT_MS - JobRunner.SOFT_LIMIT_MS)

        val opContext = OpContext(
            workspace = app.workspace,
            engine = app.pdfEngine,
            encryptedReader = app.encryptedReader,
            surgeon = app.surgeon,
            ocr = app.ocr,
            passwords = request.passwords,
            // The hard deadline is the last resort for one file that alone outlasts the budget:
            // stopping it ourselves a minute early is what lets the reason be said, where the
            // system's own stop would arrive as a kill.
            cancelled = { isStopped || System.currentTimeMillis() > hardDeadline }
        )

        return try {
            runWork(runner, jobId, op, request, opContext, app.outputs)
        } finally {
            runner.recordForegroundRun(started, System.currentTimeMillis())
            // Every exit, including the two that throw: once a terminal state is published this
            // worker will not consult the runner again, and an id left behind is one a later
            // cancel would mark - discarding the outcomes of a job that had already finished.
            runner.settled(jobId)
        }
    }

    private suspend fun runWork(
        runner: JobRunner,
        jobId: String,
        op: Op,
        request: JobRunner.Request,
        opContext: OpContext,
        /** Where a produced file is written down, before anything is told it exists. */
        outputs: OutputStore
    ): Result {
        return try {
            setForeground(JobNotifications.foregroundInfo(applicationContext, op.title, 0, 0))
            runner.publish(JobState.Running(op.tool, op.title, 0, 0, ""))

            var lastNotified = 0L
            val report = { progress: Progress ->
                runner.publish(
                    JobState.Running(op.tool, op.title, progress.unitsDone, progress.unitsTotal, progress.label)
                )
                // The notification follows along, at most once a second: the shade redraws on
                // every update and a page-by-page OCR would otherwise post hundreds a minute.
                val now = System.currentTimeMillis()
                if (now - lastNotified >= 1000) {
                    lastNotified = now
                    setForegroundAsync(
                        JobNotifications.foregroundInfo(applicationContext, op.title, progress.unitsDone, progress.unitsTotal)
                    )
                }
            }
            val outcomes = if (op.runsFileByFile && request.inputs.size > 1) {
                runFileByFile(op, request, opContext, report)
            } else {
                op.run(request.inputs, opContext, report)
            }
            // A cancel does not always arrive as a throw. OpCancelled is a CancellationException
            // and so an Exception, which an op with a broad catch of its own can swallow before
            // returning a half-done result, and showing that as a finished run is worse than
            // saying nothing. Only the user's own cancel discards outcomes, though: a worker
            // stopped because storage dropped low or because its execution window ended reaches
            // here with isStopped set too, but with a complete result and files already written.
            if (runner.wasCancelledByUser(jobId)) {
                runner.publish(JobState.Cancelled(op.tool))
            } else {
                // Written down before the state is published, so the Library knows about a
                // result by the time any screen can show it. This is the window the whole
                // outputs table exists to close: between a file being produced and the user
                // getting round to exporting it, something has to know it is worth keeping.
                val now = System.currentTimeMillis()
                outcomes.filterIsInstance<OpOutcome.Produced>().forEach { produced ->
                    outputs.record(produced.file, op.tool, now)
                }
                runner.publish(JobState.Finished(op.tool, op.title, outcomes))
            }
            Result.success()
        } catch (_: OpCancelled) {
            // Above the general CancellationException arm because OpCancelled is one of them.
            runner.publish(stoppedState(runner, jobId, op.tool))
            Result.success()
        } catch (e: CancellationException) {
            // WorkManager cancels this coroutine when it stops the worker, so the throw that
            // unwinds a stopped run is often not ours. It is rethrown rather than reported as
            // success: the run really did not finish.
            runner.publish(stoppedState(runner, jobId, op.tool))
            throw e
        } catch (t: Throwable) {
            // Throwable rather than Exception on purpose. A page too large to rasterise throws
            // OutOfMemoryError, and an Error leaving doWork() publishes nothing at all - the job
            // then stays Running forever, which is the one outcome the UI cannot recover from.
            //
            // An operation is expected to report per-file problems as OpOutcome.Failed, so
            // reaching here means something broader went wrong. The reason is shown to the user,
            // which is why Op throws types with sentences in them - and why the two framework
            // messages that can land here, neither of them addressed to a person, are replaced.
            val reason = when {
                t is OutOfMemoryError ->
                    Strings.get(R.string.app_job_out_of_memory)
                // setForeground() is inside this try for exactly this throw: from API 31 the
                // system refuses a foreground service that starts while the app is in the
                // background, which is where a job queued just before the user left Sheaf runs.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && t is ForegroundServiceStartNotAllowedException ->
                    Strings.get(R.string.app_job_background_start)
                else -> t.message ?: Strings.get(R.string.app_job_stopped_unexpectedly)
            }
            runner.publish(JobState.Crashed(op.tool, reason))
            Result.failure()
        }
    }

    /**
     * The run stopped before it finished.
     *
     * Only a cancel the user asked for is reported as one. Everything else that stops a worker -
     * storage falling below the chain's constraint, the system's execution window - is not
     * something they did, so "Cancelled" would leave them looking for the button they did not
     * press.
     */
    private fun stoppedState(runner: JobRunner, jobId: String, tool: ToolId): JobState =
        if (runner.wasCancelledByUser(jobId)) {
            JobState.Cancelled(tool)
        } else if (pastHardDeadline) {
            JobState.Crashed(
                tool,
                Strings.get(R.string.app_job_too_long)
            )
        } else {
            JobState.Crashed(
                tool,
                Strings.get(R.string.app_job_stopped_by_system)
            )
        }

    companion object {
        const val KEY_JOB_ID = "job_id"

        /** The [ToolId] name, so a lost job can still be reported to the screen that started it. */
        const val KEY_TOOL = "tool"
    }
}
