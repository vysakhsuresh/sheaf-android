package com.layerbit.sheaf.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.prefs.Settings
import com.layerbit.sheaf.prefs.ThemeChoice
import com.layerbit.sheaf.ui.components.ChipRow
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.components.ToggleRow
import com.layerbit.sheaf.ui.components.formatBytes
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * Everything about the app the user gets to decide.
 *
 * Grouped by when someone would come looking: how it looks, how it reads, what happens when a
 * job finishes, and what is taking up space. Every switch says what it costs underneath it,
 * because a settings screen full of bare labels is a quiz.
 *
 * There is nothing here about accounts, sync or analytics, and there never will be - the app
 * has no INTERNET permission, so there is nothing to configure.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    workspaceBytes: Long,
    bookmarkCount: Int,
    onChange: ((Settings) -> Settings) -> Unit,
    onClearWorkspace: () -> Unit,
    onClearRecents: () -> Unit,
    onClearBookmarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("Appearance")
                Spacer(Modifier.height(10.dp))
                ChipRow(
                    options = ThemeChoice.entries.map { it to it.label },
                    selected = settings.theme,
                    onSelect = { choice -> onChange { it.copy(theme = choice) } }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A page is always shown on white paper, whichever of these is on. Night " +
                        "colours in the viewer invert the page itself, and that is a separate " +
                        "switch in the document's own menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("Reading")
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    title = "Keep the screen on",
                    detail = "While a document is open. Reading is the one thing people do " +
                        "without touching the screen.",
                    checked = settings.keepScreenOn,
                    onChange = { on -> onChange { it.copy(keepScreenOn = on) } }
                )
                ToggleRow(
                    title = "Reopen where I left off",
                    detail = "Remembers the page, per document. Only the page number is " +
                        "stored, never anything from inside the file.",
                    checked = settings.resumeReading,
                    onChange = { on -> onChange { it.copy(resumeReading = on) } }
                )
                ToggleRow(
                    title = "Show the page counter",
                    detail = "The badge over the bottom of the page. Tap it to jump.",
                    checked = settings.showPageBadge,
                    onChange = { on -> onChange { it.copy(showPageBadge = on) } }
                )
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("When a job finishes")
                Spacer(Modifier.height(4.dp))
                ToggleRow(
                    title = "Open the result straight away",
                    detail = "Shows the finished document in the viewer as soon as it is ready.",
                    checked = settings.openResultWhenDone,
                    onChange = { on -> onChange { it.copy(openResultWhenDone = on) } }
                )
                ToggleRow(
                    title = "Ask where to save every time",
                    detail = "Off means results go to one folder you choose, which is what " +
                        "makes saving forty exported pages one gesture instead of forty.",
                    checked = settings.askWhereToSave,
                    onChange = { on -> onChange { it.copy(askWhereToSave = on) } }
                )
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("Space and history")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Working files: ${formatBytes(workspaceBytes)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SheafColors.Text
                )
                Text(
                    "Results waiting to be saved, and copies of documents you opened. Sheaf " +
                        "trims these itself; clearing them now is safe once you have saved " +
                        "what you wanted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 2.dp)
                )
                TextButton(onClick = onClearWorkspace, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Clear working files", color = SheafColors.BandBright)
                }

                Spacer(Modifier.height(10.dp))
                TextButton(onClick = onClearRecents) {
                    Text("Clear the recents list", color = SheafColors.Muted)
                }
                TextButton(onClick = onClearBookmarks) {
                    Text(
                        if (bookmarkCount > 0) {
                            "Remove all $bookmarkCount marked pages"
                        } else {
                            "No marked pages"
                        },
                        color = if (bookmarkCount > 0) SheafColors.Muted else SheafColors.Dim
                    )
                }
            }
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth()) {
                SectionHeading("What this app cannot do")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sheaf has no internet permission at all. It cannot upload a document, " +
                        "report a crash, or check for an update - and the manifest in the " +
                        "repository is how you can verify that rather than taking a promise " +
                        "for it. Printing works because the system's print service does the " +
                        "talking, not this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Muted
                )
            }
        }
    }
}
