package com.layerbit.sheaf.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ops.ToolId
import com.layerbit.sheaf.ui.components.EmptyState
import com.layerbit.sheaf.ui.components.SheafTopBar
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * Tools you set up and walked away from.
 *
 * THE SCREEN IS HERE AND THE FEATURE IS NOT, which is a deliberate order rather than an
 * oversight. Restoring a session means persisting ToolConfig, and that is forty fields across
 * seven enums - too much for a column each, and the honest answer is a serialiser the project
 * does not have a dependency for yet. Adding one is a decision worth making on its own rather
 * than smuggling in behind a screen.
 *
 * It says so plainly in the meantime. An empty tab that explains itself is better than a tab
 * that looks broken, and far better than a half-restore that brings back the files but
 * silently drops the settings - which is the shape this would take if it were rushed.
 */
@Composable
fun SessionsScreen(onPickTool: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        SheafTopBar(title = "In progress")
        EmptyState(
            // Mark up's amber. This tab is for a tool left half-configured, and marking up is
            // the group where that happens most.
            icon = R.drawable.ic_nav_sessions,
            accent = SheafColors.ToolAccents[ToolId.Group.MARK.ordinal],
            heading = "Nothing in progress.",
            detail = "Sheaf does not yet remember a tool you set up and left. Until it " +
                "does, a tool you walk away from starts fresh when you come back to it."
        ) {
            TextButton(onClick = onPickTool, modifier = Modifier.padding(top = 8.dp)) {
                Text("Pick a tool", color = SheafColors.BandBright)
            }
        }
    }
}
