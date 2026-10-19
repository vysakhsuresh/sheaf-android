package com.layerbit.sheaf.ops

import com.layerbit.sheaf.files.SheafFile

/**
 * Several tools, one after another, on any number of files: a recipe.
 *
 * This is the function composition the plural signature promised. Each step's results are
 * the next step's inputs. A file a step skips - already as small as it gets - goes on to the
 * next step as it was; a file a step fails on drops out and is reported at the end. Files made
 * in the middle are thrown away once the next step has read them.
 */
class RecipeOp(private val name: String, private val steps: List<Op>) : Op {

    override val tool = ToolId.RECIPES
    override val title = name
    override val arity = Op.Arity.Together(min = 1)

    override suspend fun run(
        inputs: List<SheafFile>,
        context: OpContext,
        onProgress: (Progress) -> Unit
    ): List<OpOutcome> {
        if (steps.isEmpty()) return listOf(OpOutcome.Failed("", "This recipe has no steps."))
        val originals = inputs.map { it.file }.toSet()
        var current = inputs
        val failures = mutableListOf<OpOutcome>()
        // Why a file came through a step untouched, kept for the final list.
        val untouched = HashMap<String, String>()
        var lastProduced = emptyList<OpOutcome.Produced>()

        for ((k, op) in steps.withIndex()) {
            context.checkCancelled()
            val label = "Step ${k + 1} of ${steps.size}: ${op.title}"
            onProgress(Progress(k, steps.size, label))
            val outcomes = if (op.arity == Op.Arity.ExactlyOne) {
                current.flatMap { file -> op.run(listOf(file), context) { p -> onProgress(Progress(k, steps.size, "$label. ${p.label}")) } }
            } else {
                op.run(current, context) { p -> onProgress(Progress(k, steps.size, "$label. ${p.label}")) }
            }

            val next = mutableListOf<SheafFile>()
            val produced = mutableListOf<OpOutcome.Produced>()
            for ((i, outcome) in outcomes.withIndex()) {
                when (outcome) {
                    is OpOutcome.Produced -> { next += outcome.file; produced += outcome }
                    is OpOutcome.Skipped -> {
                        // Skips name their input; match it back, or by position for one-to-one steps.
                        val input = current.firstOrNull { it.displayName == outcome.inputName } ?: current.getOrNull(i)
                        if (input != null) {
                            next += input
                            untouched[input.file.path] = outcome.reason
                        }
                    }
                    is OpOutcome.Failed -> failures += OpOutcome.Failed(outcome.inputName, "${op.title}: ${outcome.reason}")
                }
            }
            // What the step before made and nobody now holds is no longer needed.
            for (file in current) {
                if (file.file !in originals && next.none { it.file == file.file }) file.file.delete()
            }
            current = next
            lastProduced = produced
            if (current.isEmpty()) break
        }
        onProgress(Progress(steps.size, steps.size, "Done"))

        val results = current.map { file ->
            lastProduced.firstOrNull { it.file.file == file.file }
                ?: OpOutcome.Produced(file, untouched[file.file.path] ?: "Unchanged")
        }
        return results + failures
    }
}
