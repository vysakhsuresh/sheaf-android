package com.layerbit.sheaf.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.layerbit.sheaf.brand.BrandLinks
import com.layerbit.sheaf.files.CrashLog
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.prefs.Settings
import com.layerbit.sheaf.prefs.ThemeChoice
import com.layerbit.sheaf.ui.components.IconTile
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.Pill
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SecondaryButton
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
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Section(title = "Appearance", iconRes = R.drawable.ic_tool_background, accent = Color(0xFFB08CFF)) {
                ThemePicker(selected = settings.theme, onSelect = { choice -> onChange { it.copy(theme = choice) } })
                Text(
                    "A page is always shown on white paper, whichever of these is on. Night " +
                        "colours in the viewer invert the page itself, and that is a separate " +
                        "switch in the document's own menu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
        }

        item {
            Section(title = "Reading", iconRes = R.drawable.ic_tool_read, accent = SheafColors.Band) {
                ToggleRow(
                    title = "Keep the screen on",
                    detail = "While a document is open. Reading is the one thing people do " +
                        "without touching the screen.",
                    checked = settings.keepScreenOn,
                    onChange = { on -> onChange { it.copy(keepScreenOn = on) } }
                )
                Divider()
                ToggleRow(
                    title = "Reopen where I left off",
                    detail = "Remembers the page, per document. Only the page number is " +
                        "stored, never anything from inside the file.",
                    checked = settings.resumeReading,
                    onChange = { on -> onChange { it.copy(resumeReading = on) } }
                )
                Divider()
                ToggleRow(
                    title = "Show the page counter",
                    detail = "The badge over the bottom of the page. Tap it to jump.",
                    checked = settings.showPageBadge,
                    onChange = { on -> onChange { it.copy(showPageBadge = on) } }
                )
            }
        }

        item {
            Section(title = "When a job finishes", iconRes = R.drawable.ic_tool_organise, accent = Color(0xFF3ED3C0)) {
                ToggleRow(
                    title = "Open the result straight away",
                    detail = "Shows the finished document in the viewer as soon as it is ready.",
                    checked = settings.openResultWhenDone,
                    onChange = { on -> onChange { it.copy(openResultWhenDone = on) } }
                )
                Divider()
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
            Section(title = "Space and history", iconRes = R.drawable.ic_tool_split_size, accent = Color(0xFF5B9DFF)) {
                RowBetween {
                    Column {
                        Text(
                            formatBytes(workspaceBytes),
                            style = MaterialTheme.typography.headlineSmall,
                            color = SheafColors.Text
                        )
                        Text(
                            "of working files on this phone",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Dim
                        )
                    }
                    SecondaryButton(text = "Clear", onClick = onClearWorkspace, enabled = workspaceBytes > 0, tall = false)
                }
                Text(
                    "Results waiting to be saved, and copies of documents you opened. Sheaf " +
                        "trims these itself; clearing them now is safe once you have saved " +
                        "what you wanted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Divider()
                RowBetween {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Recent documents", style = MaterialTheme.typography.bodyLarge, color = SheafColors.Text)
                        Text("The list on the home screen, and where you got to in each.", style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
                    }
                    SecondaryButton(text = "Clear", onClick = onClearRecents, tall = false)
                }
                Divider()
                RowBetween {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("Marked pages", style = MaterialTheme.typography.bodyLarge, color = SheafColors.Text)
                        Text(
                            if (bookmarkCount > 0) "$bookmarkCount ${if (bookmarkCount == 1) "page" else "pages"} starred across your documents." else "None yet. The star in the viewer marks a page.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Dim
                        )
                    }
                    SecondaryButton(text = "Clear", onClick = onClearBookmarks, enabled = bookmarkCount > 0, tall = false)
                }
            }
        }

        item {
            CrashLogSection()
        }

        item {
            Panel(modifier = Modifier.fillMaxWidth(), tint = SheafColors.Done) {
                Row(verticalAlignment = Alignment.Top) {
                    IconTile(iconRes = R.drawable.ic_tool_lock, accent = SheafColors.Done, size = 40.dp)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text("What this app cannot do", style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Pill("No internet", SheafColors.Done)
                            Pill("No account", SheafColors.Done)
                            Pill("No tracking", SheafColors.Done)
                        }
                        Text(
                            "Sheaf has no internet permission at all. It cannot upload a document, " +
                                "report a crash, or check for an update - and the manifest in the " +
                                "repository is how you can verify that rather than taking a promise " +
                                "for it. Printing works because the system's print service does the " +
                                "talking, not this app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Muted,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * The crash log, offered rather than sent. Sharing goes through the share sheet, so the user
 * picks the app and the person; the log never leaves on its own.
 */
@Composable
private fun CrashLogSection() {
    val context = LocalContext.current
    var count by remember { mutableIntStateOf(CrashLog.count(context)) }
    Section(title = "Crash log", iconRes = R.drawable.ic_tool_attachments, accent = SheafColors.Failed) {
        RowBetween {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    if (count == 0) "No crashes recorded" else "${if (count == 1) "1 crash" else "$count crashes"} recorded",
                    style = MaterialTheme.typography.bodyLarge,
                    color = SheafColors.Text
                )
                Text(
                    "If Sheaf ever closes unexpectedly, a short technical log is kept here. " +
                        "Nothing from your documents is in it, and it is only sent if you send it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
            }
        }
        if (count > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                SecondaryButton(
                    text = "Send the log",
                    onClick = {
                        val log = CrashLog.latest(context) ?: return@SecondaryButton
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", log)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_EMAIL, arrayOf(BrandLinks.SUPPORT_EMAIL))
                            putExtra(Intent.EXTRA_SUBJECT, "Sheaf crash log")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(Intent.createChooser(send, "Send the crash log")) }
                    },
                    tall = false
                )
                SecondaryButton(
                    text = "Delete",
                    onClick = {
                        CrashLog.clear(context)
                        count = 0
                    },
                    tall = false
                )
            }
        }
    }
}

/** A panel with a coloured tile and a title, which is what makes the sections tell apart. */
@Composable
private fun Section(title: String, iconRes: Int, accent: Color, content: @Composable () -> Unit) {
    Panel(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            IconTile(iconRes = iconRes, accent = accent, size = 34.dp)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = SheafColors.Text,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        content()
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SheafColors.Border))
}

/**
 * The three looks as three small pictures of themselves, because "Dark" is a word and a dark
 * swatch is the thing.
 */
@Composable
private fun ThemePicker(selected: ThemeChoice, onSelect: (ThemeChoice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        ThemeChoice.entries.forEach { choice ->
            val active = choice == selected
            val shape = RoundedCornerShape(14.dp)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(SheafColors.SurfaceDim, shape)
                    .border(if (active) 2.dp else 1.dp, if (active) SheafColors.Band else SheafColors.Border, shape)
                    .clickable { onSelect(choice) }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Swatch(choice)
                Text(
                    when (choice) {
                        ThemeChoice.SYSTEM -> "System"
                        ThemeChoice.LIGHT -> "Light"
                        ThemeChoice.DARK -> "Dark"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) SheafColors.Text else SheafColors.Muted,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun Swatch(choice: ThemeChoice) {
    val inkBg = Color(0xFF0B1220)
    val inkCard = Color(0xFF141E33)
    val paperBg = Color(0xFFF5F4EF)
    val paperCard = Color(0xFFFFFFFF)
    val shape = RoundedCornerShape(8.dp)
    Row(modifier = Modifier.fillMaxWidth().height(44.dp).background(SheafColors.Border, shape).padding(1.dp)) {
        val halves = when (choice) {
            ThemeChoice.SYSTEM -> listOf(paperBg to paperCard, inkBg to inkCard)
            ThemeChoice.LIGHT -> listOf(paperBg to paperCard, paperBg to paperCard)
            ThemeChoice.DARK -> listOf(inkBg to inkCard, inkBg to inkCard)
        }
        halves.forEachIndexed { i, (bg, card) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(
                        bg,
                        if (i == 0) RoundedCornerShape(topStart = 7.dp, bottomStart = 7.dp) else RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp)
                    )
                    .padding(6.dp)
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().height(10.dp).background(card, RoundedCornerShape(3.dp)))
                    Spacer(Modifier.height(4.dp))
                    Box(Modifier.size(width = 22.dp, height = 6.dp).background(Color(0xFFE62E80), RoundedCornerShape(3.dp)))
                }
            }
        }
    }
}
