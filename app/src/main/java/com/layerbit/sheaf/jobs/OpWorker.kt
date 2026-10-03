package com.layerbit.sheaf.jobs

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.ops.Op
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
                runner.publish(JobState.Crashed(tool, "This operation could not be started. Start it again."))
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
                        JobState.Crashed(tool, "This operation was lost when the app restarted. Start it again.")
                    }
                )
            }
            // take() never recorded this one, but a cancel may still have: forget it either way,
            // because nothing will ask about it again.
            runner.settled(jobId)
            return Result.failure()
        }
        val op = request.op

        val opContext = OpContext(
            workspace = app.workspace,
            engine = app.pdfEngine,
            encryptedReader = app.encryptedReader,
            surgeon = app.surgeon,
            ocr = app.ocr,
            passwords = request.passwords,
            cancelled = { isStopped }
        )

        return try {
            runWork(runner, jobId, op, request, opContext)
        } finally {
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
        opContext: OpContext
    ): Result {
        return try {
            setForeground(JobNotifications.foregroundInfo(applicationContext, op.title, 0, 0))
            runner.publish(JobState.Running(op.tool, op.title, 0, 0, ""))

            val outcomes = op.run(request.inputs, opContext) { progress: Progress ->
                runner.publish(
                    JobState.Running(op.tool, op.title, progress.unitsDone, progress.unitsTotal, progress.label)
                )
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
                    "This document needed more memory than the device would give. Try fewer pages at once."
                // setForeground() is inside this try for exactly this throw: from API 31 the
                // system refuses a foreground service that starts while the app is in the
                // background, which is where a job queued just before the user left Sheaf runs.
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && t is ForegroundServiceStartNotAllowedException ->
                    "Android would not let this operation start while Sheaf was in the background. " +
                        "Open Sheaf and start it again."
                else -> t.message ?: "The operation stopped unexpectedly."
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
        } else {
            JobState.Crashed(
                tool,
                "Android stopped this operation before it finished, which usually means storage " +
                    "ran low or it ran for too long. Start it again."
            )
        }

    companion object {
        const val KEY_JOB_ID = "job_id"

        /** The [ToolId] name, so a lost job can still be reported to the screen that started it. */
        const val KEY_TOOL = "tool"
    }
}
