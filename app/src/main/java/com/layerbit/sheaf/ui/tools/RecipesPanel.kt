package com.layerbit.sheaf.ui.tools

import androidx.compose.ui.res.stringResource
import com.layerbit.sheaf.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.PrimaryButton
import com.layerbit.sheaf.ui.components.SecondaryButton
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * The recipes kept on this phone, the one to run, and the builder for a new one. Each step's
 * options are the tool's own options panel, so a step is set up exactly as the tool would be.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipesPanel(
    state: ToolUiState,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onNew: () -> Unit,
    onName: (String) -> Unit,
    onAddStep: (ToolId) -> Unit,
    onRemoveStep: (Int) -> Unit,
    onOpenStep: (Int) -> Unit,
    onStepChange: (Int, (ToolConfig) -> ToolConfig) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onPassword: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeading(stringResource(R.string.tools_recipes))
        Text(
            stringResource(R.string.tools_recipes_intro),
            style = MaterialTheme.typography.bodySmall,
            color = SheafColors.Muted
        )
        if (state.recipes.isEmpty() && state.draft == null) {
            Text(stringResource(R.string.tools_no_recipes), style = MaterialTheme.typography.bodyMedium, color = SheafColors.Dim)
        }
        for (recipe in state.recipes) {
            val chosen = recipe.id == state.recipeId
            Panel(modifier = Modifier.fillMaxWidth(), onClick = { onSelect(recipe.id) }, highlighted = chosen, inset = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chosen, onClick = { onSelect(recipe.id) }, colors = RadioButtonDefaults.colors(selectedColor = SheafColors.Band))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(recipe.name, style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Text(recipe.summary, style = MaterialTheme.typography.bodySmall, color = SheafColors.Muted)
                    }
                    TextButton(onClick = { onDelete(recipe.id) }) { Text(stringResource(R.string.tools_delete), color = SheafColors.Failed) }
                }
            }
        }
        val chosen = state.recipes.firstOrNull { it.id == state.recipeId }
        if (chosen?.needsPassword == true && state.draft == null) {
            PasswordField(value = state.config.newPassword, label = stringResource(R.string.tools_recipe_password), onChange = onPassword)
        }

        val draft = state.draft
        if (draft == null) {
            SecondaryButton(text = stringResource(R.string.tools_make_recipe), onClick = onNew, tall = false)
        } else {
            Panel(modifier = Modifier.fillMaxWidth(), inset = 14.dp) {
                SectionHeading(stringResource(R.string.tools_new_recipe))
                Field(value = draft.name, label = stringResource(R.string.tools_recipe_name), keyboardType = KeyboardType.Text, onChange = onName)
                draft.steps.forEachIndexed { i, step ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.tools_step_n, i + 1, step.tool.title), style = MaterialTheme.typography.titleMedium, color = SheafColors.Text, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onOpenStep(i) }) { Text(if (state.draftStep == i) stringResource(R.string.tools_close) else stringResource(R.string.tools_options), color = SheafColors.Band) }
                        TextButton(onClick = { onRemoveStep(i) }) { Text(stringResource(R.string.tools_take_out), color = SheafColors.Failed) }
                    }
                    if (state.draftStep == i) {
                        if (step.tool == ToolId.MERGE) {
                            Hint(stringResource(R.string.tools_recipe_merge))
                        } else if (step.tool == ToolId.SET_PASSWORD) {
                            Hint(stringResource(R.string.tools_recipe_password_hint))
                            val c = step.config
                            com.layerbit.sheaf.ui.components.ToggleRow(title = stringResource(R.string.tools_restrict_only), checked = c.restrictOnly, onChange = { on -> onStepChange(i) { it.copy(restrictOnly = on) } })
                            com.layerbit.sheaf.ui.components.ToggleRow(title = stringResource(R.string.tools_allow_printing), checked = c.allowPrinting, onChange = { on -> onStepChange(i) { it.copy(allowPrinting = on) } })
                            com.layerbit.sheaf.ui.components.ToggleRow(title = stringResource(R.string.tools_allow_copying), checked = c.allowCopying, onChange = { on -> onStepChange(i) { it.copy(allowCopying = on) } })
                            com.layerbit.sheaf.ui.components.ToggleRow(title = stringResource(R.string.tools_allow_changes), checked = c.allowChanges, onChange = { on -> onStepChange(i) { it.copy(allowChanges = on) } })
                        } else {
                            ToolOptions(tool = step.tool, config = step.config, pageCount = null, onChange = { t -> onStepChange(i, t) })
                        }
                    }
                }
                Text(stringResource(R.string.tools_add_step), style = MaterialTheme.typography.labelLarge, color = SheafColors.Muted, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (tool in Recipes.TOOLS.filter { t -> draft.steps.none { it.tool == t } }) {
                        OutlinedButton(onClick = { onAddStep(tool) }) { Text(tool.title, color = SheafColors.Text, maxLines = 1) }
                    }
                }
                state.recipeProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SheafColors.Failed, modifier = Modifier.padding(top = 8.dp)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    PrimaryButton(text = stringResource(R.string.tools_save_recipe), onClick = onSave, tall = false)
                    SecondaryButton(text = stringResource(R.string.tools_cancel), onClick = onCancel, tall = false)
                }
            }
        }
    }
}

/** What the Recipes panel can ask of the view model, gathered so the screen takes one value. */
class RecipeActions(
    val load: () -> Unit = {},
    val select: (String) -> Unit = {},
    val delete: (String) -> Unit = {},
    val new: () -> Unit = {},
    val name: (String) -> Unit = {},
    val addStep: (ToolId) -> Unit = {},
    val removeStep: (Int) -> Unit = {},
    val openStep: (Int) -> Unit = {},
    val changeStep: (Int, (ToolConfig) -> ToolConfig) -> Unit = { _, _ -> },
    val save: () -> Unit = {},
    val cancel: () -> Unit = {}
)
