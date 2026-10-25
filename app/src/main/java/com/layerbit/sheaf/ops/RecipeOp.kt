package com.layerbit.sheaf.ops

import com.layerbit.sheaf.R
import com.layerbit.sheaf.files.SheafFile
import com.layerbit.sheaf.text.Strings

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
        if (steps.isEmpty()) return listOf(OpOutcome.Failed("", Strings.get(R.string.engine_recipe_empty)))
        val originals = inputs.map { it.file }.toSet()
        var current = inputs
        val failures = mutableListOf<OpOutcome>()
        // What each file has been through, step by step, for its line in the results.
        val notes = HashMap<String, String>()
        var lastProduced = emptyList<OpOutcome.Produced>()

        for ((k, op) in steps.withIndex()) {
            context.checkCancelled()
            val label = Strings.get(R.string.engine_recipe_step, k + 1, steps.size, op.title)
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
                    is OpOutcome.Produced -> {
                        next += outcome.file
                        produced += outcome
                        // A step that makes one file per file carries the earlier story on.
                        val before = if (outcomes.size == current.size) notes[current[i].file.path] else null
                        notes[outcome.file.file.path] = listOfNotNull(before, outcome.detail ?: op.title).joinToString("; ")
                    }
                    is OpOutcome.Skipped -> {
                        // Skips name their input; match it back, or by position for one-to-one steps.
                        val input = current.firstOrNull { it.displayName == outcome.inputName } ?: current.getOrNull(i)
                        if (input != null) {
                            next += input
                            notes[input.file.path] = (notes[input.file.path]?.let { "$it; " } ?: "") + "${op.title}: ${outcome.reason}"
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
        onProgress(Progress(steps.size, steps.size, Strings.get(R.string.engine_done)))

        val results = current.map { file ->
            lastProduced.firstOrNull { it.file.file == file.file }
                ?: OpOutcome.Produced(file, notes[file.file.path] ?: Strings.get(R.string.engine_recipe_unchanged))
        }
        return results + failures
    }
}
