package com.layerbit.sheaf.jobs

import com.layerbit.sheaf.ops.OpOutcome
import com.layerbit.sheaf.ops.ToolId

/**
 * What the UI knows about a running or finished operation.
 *
 * A job outlives the screen that started it, which is the whole reason it is a Worker. So
 * this state lives in [JobRunner] and is observed, rather than being held in a ViewModel that
 * dies with its screen.
 */
sealed interface JobState {

    data object Idle : JobState

    data class Running(
        val tool: ToolId,
        val opTitle: String,
        val unitsDone: Int,
        val unitsTotal: Int,
        val label: String
    ) : JobState {
        val fraction: Float
            get() = if (unitsTotal <= 0) 0f else (unitsDone.toFloat() / unitsTotal).coerceIn(0f, 1f)
    }

    /**
     * Finished. [outcomes] holds one entry per unit of work, successes and failures together,
     * because a batch that half worked is the normal case and the user needs to see which half.
     */
    data class Finished(
        val tool: ToolId,
        val opTitle: String,
        val outcomes: List<OpOutcome>
    ) : JobState {
        val produced get() = outcomes.filterIsInstance<OpOutcome.Produced>()
        val failed get() = outcomes.filterIsInstance<OpOutcome.Failed>()
        val skipped get() = outcomes.filterIsInstance<OpOutcome.Skipped>()
        val allSucceeded get() = failed.isEmpty() && skipped.isEmpty()
    }

    data class Cancelled(val tool: ToolId) : JobState

    /**
     * The job itself could not run - as opposed to individual files failing, which is
     * [Finished] with failures in it. Reaching here means something was wrong with the setup.
     */
    data class Crashed(val tool: ToolId, val reason: String) : JobState
}

/**
 * Whether this job was started by [tool].
 *
 * The runner is app-wide so a job survives the screen that started it, which means every
 * screen also sees every other screen's job. Each one filters on this.
 */
fun JobState.belongsTo(tool: ToolId?): Boolean = when (this) {
    is JobState.Running -> this.tool == tool
    is JobState.Finished -> this.tool == tool
    is JobState.Cancelled -> this.tool == tool
    is JobState.Crashed -> this.tool == tool
    JobState.Idle -> true
}
