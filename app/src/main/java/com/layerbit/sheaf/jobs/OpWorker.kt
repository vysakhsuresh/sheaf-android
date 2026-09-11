package com.layerbit.sheaf.jobs

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.layerbit.sheaf.SheafApplication
import com.layerbit.sheaf.ops.OpCancelled
import com.layerbit.sheaf.ops.OpContext
import com.layerbit.sheaf.ops.Progress

/**
 * Runs one [com.layerbit.sheaf.ops.Op] as background work.
 *
 * Every operation goes through here, including ones that look instantaneous. A three-page
 * merge finishes before the notification is drawn, but the same code path having two modes -
 * "quick ones inline, slow ones as work" - is how the slow path ends up untested. One path.
 *
 * The inputs are passed as a job id rather than as file paths in the input Data, because
 * WorkManager's Data is capped at about 10 KB and a batch of forty documents with long names
 * from a scanner app will exceed it. [JobRunner] holds the real arguments in memory and this
 * worker collects them by id.
 */
class OpWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val app = applicationContext as SheafApplication
        val runner = app.jobRunner

        val request = runner.take(jobId) ?: return Result.failure()
        val op = request.op

        setForeground(JobNotifications.foregroundInfo(applicationContext, op.title, 0, 0))
        runner.publish(JobState.Running(op.tool, op.title, 0, 0, ""))

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
            val outcomes = op.run(request.inputs, opContext) { progress: Progress ->
                runner.publish(
                    JobState.Running(op.tool, op.title, progress.unitsDone, progress.unitsTotal, progress.label)
                )
            }
            runner.publish(JobState.Finished(op.tool, op.title, outcomes))
            Result.success()
        } catch (_: OpCancelled) {
            runner.publish(JobState.Cancelled(op.tool))
            Result.success()
        } catch (e: Exception) {
            // An operation is expected to report per-file problems as OpOutcome.Failed, so
            // reaching here means something broader went wrong. The message is shown to the
            // user, which is why Op throws types with sentences in them.
            runner.publish(JobState.Crashed(op.tool, e.message ?: "The operation stopped unexpectedly."))
            Result.failure()
        }
    }

    companion object {
        const val KEY_JOB_ID = "job_id"
    }
}
