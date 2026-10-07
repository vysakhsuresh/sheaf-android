package com.layerbit.sheaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val shape = MaterialTheme.shapes.medium
    val base = modifier
        .clip(shape)
        .background(SheafColors.Surface)
        .border(
            1.dp,
            if (highlighted) SheafColors.Band.copy(alpha = 0.55f) else SheafColors.Border,
            shape
        )
    val haptics = LocalHapticFeedback.current
    val tappable = when {
        onClick == null -> base
        onLongClick == null -> base.clickable(onClick = onClick)
        else -> base.combinedClickable(
            onClick = onClick,
            // Compose Foundation, unlike the View system, performs no haptic of its own on a
            // long press. Starring a tool is the only long press in the app and it confirms
            // itself with a 1dp border and a small star, both underneath the finger that is
            // still holding the card down - so without this the gesture completes with no
            // sign at all that it worked.
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLongClick()
            }
        )
    }
    Column(modifier = tappable.padding(16.dp), content = content)
}

@Composable
fun SectionHeading(
    text: String,
    modifier: Modifier = Modifier,
    /**
     * The group's own hue, where the heading labels one. Grey everywhere else, which is most
     * places - a heading that is merely organising a form has no group to belong to.
     */
    color: Color = SheafColors.Dim
) {
    Text(
        text = text.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelSmall,
        color = color,
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
    modifier: Modifier = Modifier,
    /**
     * The colour each option stands for, drawn as a disc before its label.
     *
     * Only the markup tools pass this, and only because theirs is the one control in the app
     * whose subject is colour: "Yellow" against "Green" is a difference nobody can judge from
     * two words. It is a parameter here rather than a second copy of this composable because
     * a second copy is what existed before, and it had already drifted.
     */
    swatch: ((T) -> Color)? = null
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // Zero, not 8, because each chip now sits in a 48dp box and brings 7dp of its own air
        // above and below. Spacing the boxes as well would put 22dp between two wrapped rows
        // of what is meant to read as one control.
        verticalArrangement = Arrangement.spacedBy(0.dp),
        // Tells a screen reader these are one set of alternatives rather than a handful of
        // unrelated buttons, so it can announce "2 of 4" as it moves between them.
        modifier = modifier.fillMaxWidth().selectableGroup()
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val shape = RoundedCornerShape(999.dp)
            // The gesture is on this box and not on the pill, which is the whole point of the
            // box. A pill is about 34dp tall - 13.5sp of text and 8dp either side - and a bare
            // clickable gets none of the 48dp minimum that Material's own controls enforce, so
            // every choice in the app was a target a third smaller than the guideline. The box
            // takes the touch and stays invisible; the pill keeps its own size.
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = 48.dp)
                    .selectable(
                        selected = active,
                        role = Role.RadioButton,
                        onClick = { onSelect(value) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        // Ahead of the background, so the ripple is a pill too. Without it the
                        // press washed a rectangle across a rounded chip.
                        .clip(shape)
                        .background(if (active) SheafColors.BandDim else SheafColors.SurfaceDim)
                        .border(1.dp, if (active) SheafColors.Band else SheafColors.Border, shape)
                        .padding(
                            start = if (swatch == null) 14.dp else 10.dp,
                            end = 14.dp,
                            top = 8.dp,
                            bottom = 8.dp
                        )
                ) {
                    if (swatch != null) {
                        // Ringed, which is what keeps black ink from disappearing into the
                        // chip and pale yellow from disappearing into nothing at all.
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(swatch(value))
                                .border(1.dp, SheafColors.Border, CircleShape)
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (active) SheafColors.BandBright else SheafColors.Muted
                    )
                }
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
            // toggleable rather than clickable, so the row carries the state as well as the
            // gesture. As a clickable it announced itself as an unlabelled tappable thing and
            // the Switch beside it announced a state with no name, so every setting in the
            // app was read out twice and neither half was a sentence.
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
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
            // Null hands the gesture to the row above, which is the thing that is labelled.
            // Tapping the switch still works - the touch falls through to the same toggleable.
            onCheckedChange = null,
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

/**
 * An icon that does something, with a name a screen reader can say.
 *
 * Before this there were seven controls in the app drawn as punctuation - a back chevron that
 * was a literal "<" at three different sizes depending on the screen, search step arrows, an
 * overflow glyph, the page-reorder arrows. None of them had an accessible name, so TalkBack
 * read them out as "left-pointing single angle quotation mark, button" or skipped them
 * entirely, and none of them matched the twenty-seven drawn tool icons sitting beside them.
 *
 * [label] is required rather than optional, which is the point of having this at all: the
 * drawing problem and the accessibility problem were the same problem, and a primitive that
 * cannot be built without a name closes both of them permanently.
 *
 * [state] is for a control that is on or off - night mode, a bookmark - and is announced
 * after the name, so TalkBack says "Night colours, on" rather than leaving the reader to
 * work out which way the switch is sitting.
 *
 * The semantics go on the button's own modifier chain and the Icon inside is explicitly
 * undescribed. Putting clearAndSetSemantics on the button instead would take the click
 * action out of the semantics tree with it, and TalkBack could then name the control but not
 * press it.
 */
@Composable
fun IconAction(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = SheafColors.Muted,
    state: String? = null,
    enabled: Boolean = true
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics {
            contentDescription = label
            if (state != null) stateDescription = state
        }
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (enabled) tint else SheafColors.Dim,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * One thing that went wrong, named and explained.
 *
 * Lifted out of the tool screen, where it was private, because three other places need the
 * same shape and two of them are in packages that could not see it: a job that crashed, a
 * capture the scanner could not write, a camera that would not bind. A failure should look
 * the same wherever it is reported, and the alternative to this was each screen inventing
 * its own red text.
 *
 * [isError] separates "this did not work" from "this was left alone", which the batch runner
 * already distinguishes and which the colours should too.
 */
@Composable
fun ProblemRow(name: String?, reason: String, isError: Boolean, modifier: Modifier = Modifier) {
    val accent = if (isError) SheafColors.Failed else SheafColors.Skipped
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SheafColors.SurfaceDim)
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(14.dp)
    ) {
        if (!name.isNullOrBlank()) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(reason, style = MaterialTheme.typography.bodyMedium, color = SheafColors.Muted)
    }
}

/**
 * The rhythm a scrolling screen is laid out on.
 *
 * Four screens had invented four: Home 10/12/32, Settings 12/12/40, About 12/8/32, Tool
 * 14/20/20. None of them is wrong and the difference is invisible on any one screen, but
 * having a name for it is what stops the next screen inventing a fifth.
 */
val ScreenGutter = 20.dp

/** Content padding for a full-screen LazyColumn. The bottom is generous so the last row clears the gesture bar. */
val ListRhythm = PaddingValues(start = ScreenGutter, end = ScreenGutter, top = 12.dp, bottom = 32.dp)

/** The gap between rows of such a list, stated once. */
val ListGap = 12.dp
