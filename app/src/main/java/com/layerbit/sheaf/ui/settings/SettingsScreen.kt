package com.layerbit.sheaf.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
            Section(title = stringResource(R.string.app_settings_appearance), iconRes = R.drawable.ic_tool_background, accent = Color(0xFFB08CFF)) {
                ThemePicker(selected = settings.theme, onSelect = { choice -> onChange { it.copy(theme = choice) } })
                Text(
                    stringResource(R.string.app_settings_appearance_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 10.dp)
                )
                // Android 13+ keeps a language per app in its own settings page, and that page
                // is the right place to pick one: it is what the launcher, notifications and
                // the share sheet all read too. Older Android follows the phone's language.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    val context = LocalContext.current
                    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(SheafColors.Border))
                    RowBetween {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(stringResource(R.string.app_settings_language), style = MaterialTheme.typography.bodyLarge, color = SheafColors.Text)
                            Text(stringResource(R.string.app_settings_language_detail), style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
                        }
                        SecondaryButton(
                            text = stringResource(R.string.app_settings_language_choose),
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                                            .setData(android.net.Uri.fromParts("package", context.packageName, null))
                                    )
                                }
                            },
                            tall = false
                        )
                    }
                }
            }
        }

        item {
            Section(title = stringResource(R.string.app_settings_reading), iconRes = R.drawable.ic_tool_read, accent = SheafColors.Band) {
                ToggleRow(
                    title = stringResource(R.string.app_settings_keep_on),
                    detail = stringResource(R.string.app_settings_keep_on_detail),
                    checked = settings.keepScreenOn,
                    onChange = { on -> onChange { it.copy(keepScreenOn = on) } }
                )
                Divider()
                ToggleRow(
                    title = stringResource(R.string.app_settings_resume),
                    detail = stringResource(R.string.app_settings_resume_detail),
                    checked = settings.resumeReading,
                    onChange = { on -> onChange { it.copy(resumeReading = on) } }
                )
                Divider()
                ToggleRow(
                    title = stringResource(R.string.app_settings_badge),
                    detail = stringResource(R.string.app_settings_badge_detail),
                    checked = settings.showPageBadge,
                    onChange = { on -> onChange { it.copy(showPageBadge = on) } }
                )
            }
        }

        item {
            Section(title = stringResource(R.string.app_settings_finish), iconRes = R.drawable.ic_tool_organise, accent = Color(0xFF3ED3C0)) {
                ToggleRow(
                    title = stringResource(R.string.app_settings_open_result),
                    detail = stringResource(R.string.app_settings_open_result_detail),
                    checked = settings.openResultWhenDone,
                    onChange = { on -> onChange { it.copy(openResultWhenDone = on) } }
                )
                Divider()
                ToggleRow(
                    title = stringResource(R.string.app_settings_editable_marks),
                    detail = stringResource(R.string.app_settings_editable_marks_detail),
                    checked = settings.editableMarks,
                    onChange = { on -> onChange { it.copy(editableMarks = on) } }
                )
                Divider()
                ToggleRow(
                    title = stringResource(R.string.app_settings_ask_save),
                    detail = stringResource(R.string.app_settings_ask_save_detail),
                    checked = settings.askWhereToSave,
                    onChange = { on -> onChange { it.copy(askWhereToSave = on) } }
                )
            }
        }

        item {
            Section(title = stringResource(R.string.app_settings_space), iconRes = R.drawable.ic_tool_split_size, accent = Color(0xFF5B9DFF)) {
                RowBetween {
                    Column {
                        Text(
                            formatBytes(workspaceBytes),
                            style = MaterialTheme.typography.headlineSmall,
                            color = SheafColors.Text
                        )
                        Text(
                            stringResource(R.string.app_settings_working_files),
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Dim
                        )
                    }
                    SecondaryButton(text = stringResource(R.string.app_clear), onClick = onClearWorkspace, enabled = workspaceBytes > 0, tall = false)
                }
                Text(
                    stringResource(R.string.app_settings_working_files_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Divider()
                RowBetween {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.app_settings_recents), style = MaterialTheme.typography.bodyLarge, color = SheafColors.Text)
                        Text(stringResource(R.string.app_settings_recents_detail), style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
                    }
                    SecondaryButton(text = stringResource(R.string.app_clear), onClick = onClearRecents, tall = false)
                }
                Divider()
                RowBetween {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(stringResource(R.string.app_settings_bookmarks), style = MaterialTheme.typography.bodyLarge, color = SheafColors.Text)
                        Text(
                            if (bookmarkCount > 0) pluralStringResource(R.plurals.app_settings_bookmark_count, bookmarkCount, bookmarkCount) else stringResource(R.string.app_settings_bookmarks_none),
                            style = MaterialTheme.typography.bodySmall,
                            color = SheafColors.Dim
                        )
                    }
                    SecondaryButton(text = stringResource(R.string.app_clear), onClick = onClearBookmarks, enabled = bookmarkCount > 0, tall = false)
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
                        Text(stringResource(R.string.app_settings_cannot), style = MaterialTheme.typography.titleMedium, color = SheafColors.Text)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Pill(stringResource(R.string.app_pill_no_internet), SheafColors.Done)
                            Pill(stringResource(R.string.app_pill_no_account), SheafColors.Done)
                            Pill(stringResource(R.string.app_pill_no_tracking), SheafColors.Done)
                        }
                        Text(
                            stringResource(R.string.app_settings_cannot_detail),
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
    Section(title = stringResource(R.string.app_settings_crash_log), iconRes = R.drawable.ic_tool_attachments, accent = SheafColors.Failed) {
        RowBetween {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(
                    if (count == 0) stringResource(R.string.app_settings_crash_none) else pluralStringResource(R.plurals.app_settings_crash_count, count, count),
                    style = MaterialTheme.typography.bodyLarge,
                    color = SheafColors.Text
                )
                Text(
                    stringResource(R.string.app_settings_crash_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim
                )
            }
        }
        if (count > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 10.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.app_settings_crash_send),
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
                        runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.app_settings_crash_chooser))) }
                    },
                    tall = false
                )
                SecondaryButton(
                    text = stringResource(R.string.app_delete),
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
                        ThemeChoice.SYSTEM -> stringResource(R.string.app_theme_system)
                        ThemeChoice.LIGHT -> stringResource(R.string.app_theme_light)
                        ThemeChoice.DARK -> stringResource(R.string.app_theme_dark)
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
