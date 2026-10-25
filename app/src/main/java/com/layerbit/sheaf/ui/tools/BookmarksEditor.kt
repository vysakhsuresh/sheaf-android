package com.layerbit.sheaf.ui.tools

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.layerbit.sheaf.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.pdf.OutlineEntry
import com.layerbit.sheaf.ui.components.Panel
import com.layerbit.sheaf.ui.components.RowBetween
import com.layerbit.sheaf.ui.components.SectionHeading
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * The document's table of contents, as a list that can be edited.
 *
 * One row per bookmark: its title, the page it opens, and arrows to nest it under the entry
 * above or bring it back out. Indentation is shown by inset rather than by a tree control,
 * because a phone screen has room for one column of text and a tree widget would spend it
 * on lines.
 *
 * The list starts from what the document already holds, so a chapter heading somebody else
 * wrote is edited rather than retyped, and a document with none starts empty and says so.
 */
@Composable
fun BookmarksEditor(
    entries: List<OutlineEntry>,
    pageCount: Int?,
    onAdd: (Int) -> Unit,
    onSet: (Int, String?, Int?) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onIndent: (Int, Boolean) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RowBetween {
            SectionHeading(
                if (entries.isEmpty()) stringResource(R.string.tools_no_bookmarks)
                else pluralStringResource(R.plurals.tools_bookmarks, entries.size, entries.size)
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.tools_clear_all), color = SheafColors.Dim) }
            }
        }

        entries.forEachIndexed { index, entry ->
            Panel(modifier = Modifier.fillMaxWidth().padding(start = (entry.depth.coerceIn(0, 4) * 16).dp)) {
                Field(
                    value = entry.title,
                    label = if (entry.depth > 0) stringResource(R.string.tools_title_nested) else stringResource(R.string.tools_title),
                    keyboardType = KeyboardType.Text,
                    onChange = { text -> onSet(index, text, null) }
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.tools_opens_page), style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
                    Column(modifier = Modifier.width(96.dp)) {
                        Field(
                            value = (entry.pageIndex + 1).toString(),
                            label = "",
                            keyboardType = KeyboardType.Number,
                            onChange = { text ->
                                val page = text.filter { it.isDigit() }.toIntOrNull() ?: 1
                                onSet(index, null, (page - 1).coerceIn(0, ((pageCount ?: Int.MAX_VALUE) - 1).coerceAtLeast(0)))
                            }
                        )
                    }
                    pageCount?.let {
                        Text(stringResource(R.string.tools_of_n, it), style = MaterialTheme.typography.bodySmall, color = SheafColors.Dim)
                    }
                }
                RowBetween(modifier = Modifier.padding(top = 2.dp)) {
                    Row {
                        TextButton(onClick = { onMove(index, index - 1) }, enabled = index > 0) {
                            Text(stringResource(R.string.tools_up), color = SheafColors.Muted)
                        }
                        TextButton(onClick = { onMove(index, index + 1) }, enabled = index < entries.lastIndex) {
                            Text(stringResource(R.string.tools_down), color = SheafColors.Muted)
                        }
                        TextButton(onClick = { onIndent(index, false) }, enabled = entry.depth > 0) {
                            Text(stringResource(R.string.tools_out), color = SheafColors.Muted)
                        }
                        TextButton(
                            onClick = { onIndent(index, true) },
                            enabled = index > 0 && entry.depth <= entries[index - 1].depth
                        ) {
                            Text(stringResource(R.string.tools_in), color = SheafColors.Muted)
                        }
                    }
                    TextButton(onClick = { onRemove(index) }) { Text(stringResource(R.string.tools_remove), color = SheafColors.Dim) }
                }
            }
        }

        OutlinedButton(onClick = { onAdd(entries.lastIndex) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (entries.isEmpty()) stringResource(R.string.tools_add_bookmark) else stringResource(R.string.tools_add_another), color = SheafColors.Text)
        }
    }
}
