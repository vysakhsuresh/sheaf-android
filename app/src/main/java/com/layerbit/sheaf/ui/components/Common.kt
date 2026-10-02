package com.layerbit.sheaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.border
import com.layerbit.sheaf.ui.theme.SheafColors
import java.util.Locale

/**
 * The handful of shapes every screen is built from. Same idea as Deja's and Abhyas's
 * components/Common.kt: one definition of a panel and a section heading, so screens do not
 * each invent their own padding.
 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** A second gesture on the same panel - starring a tool, in practice. */
    onLongClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val base = modifier
        .clip(shape)
        .background(SheafColors.Surface)
        .border(
            1.dp,
            if (highlighted) SheafColors.Band.copy(alpha = 0.55f) else SheafColors.Border,
            shape
        )
    val tappable = when {
        onClick == null -> base
        onLongClick == null -> base.clickable(onClick = onClick)
        else -> base.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    }
    Column(modifier = tappable.padding(16.dp), content = content)
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = SheafColors.Dim,
        modifier = modifier
    )
}

/** Label above, value below - the pairing used for document details throughout. */
@Composable
fun LabelledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionHeading(label)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = SheafColors.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun RowBetween(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * Human file sizes. Base 1024 with the shorter unit names, matching what Android's own file
 * managers show, so a number here agrees with the number in Files.
 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

fun formatPageCount(pages: Int): String = if (pages == 1) "1 page" else "$pages pages"

/**
 * A row of mutually exclusive choices, as chips.
 *
 * One definition for the whole app. The tool options screen and the settings screen are the
 * same control answering different questions, and two copies of it drift apart the first time
 * one of them is adjusted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val shape = RoundedCornerShape(999.dp)
            Row(
                modifier = Modifier
                    .background(if (active) SheafColors.BandDim else SheafColors.SurfaceDim, shape)
                    .border(1.dp, if (active) SheafColors.Band else SheafColors.Border, shape)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) SheafColors.BandBright else SheafColors.Muted
                )
            }
        }
    }
}

/** A setting that is on or off, with the sentence that says what it costs. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = SheafColors.Text)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SheafColors.OnBand,
                checkedTrackColor = SheafColors.Band,
                uncheckedThumbColor = SheafColors.Muted,
                uncheckedTrackColor = SheafColors.SurfaceDim,
                uncheckedBorderColor = SheafColors.Border
            )
        )
    }
}
