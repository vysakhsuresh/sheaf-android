package com.layerbit.sheaf.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.layerbit.sheaf.ui.theme.SheafColors
import java.util.Locale

/**
 * The handful of shapes every screen is built from. One definition of a panel, a heading, a
 * button and a chip, so screens do not each invent their own padding - and so the whole app
 * changes character when one of these does.
 */

val PanelShape = RoundedCornerShape(18.dp)
val ButtonShape = RoundedCornerShape(14.dp)

/** A press that gives a little, which is most of what makes a surface feel like a button. */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, amount: Float = 0.975f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) amount else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "press"
    )
    return this.scale(scale)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    /** A second gesture on the same panel - starring a tool, in practice. */
    onLongClick: (() -> Unit)? = null,
    highlighted: Boolean = false,
    /** A tint behind the panel: a success, a warning, a group's colour. */
    tint: Color? = null,
    inset: androidx.compose.ui.unit.Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val surface = tint?.let { it.copy(alpha = if (SheafColors.isDark) 0.14f else 0.10f) } ?: SheafColors.Surface
    val border = when {
        highlighted -> SheafColors.Band.copy(alpha = 0.6f)
        tint != null -> tint.copy(alpha = 0.35f)
        else -> SheafColors.Border
    }
    var base = modifier
    if (onClick != null) base = base.pressScale(interaction)
    base = base
        .clip(PanelShape)
        .background(SheafColors.Surface)
        .then(if (tint != null) Modifier.background(surface) else Modifier)
        .border(1.dp, border, PanelShape)
    val tappable = when {
        onClick == null -> base
        onLongClick == null -> base.clickable(interactionSource = interaction, indication = null, onClick = onClick)
        else -> base.combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
    }
    Column(modifier = tappable.padding(inset), content = content)
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier, accent: Color? = null) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (accent != null) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Box(Modifier.size(8.dp))
        }
        Text(
            text = text.uppercase(Locale.ROOT),
            style = MaterialTheme.typography.labelSmall,
            color = SheafColors.Dim,
            letterSpacing = 1.4.sp
        )
    }
}

/** A screen's own title, bigger than the bar's. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier = modifier) {
        Text(text, style = MaterialTheme.typography.headlineMedium, color = SheafColors.Text)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = SheafColors.Muted,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
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
 * The one coloured button on a screen: what the screen is for.
 *
 * The band runs as a gradient rather than a flat fill, which is what makes it read as a
 * thing with weight rather than a rectangle. Disabled, it goes quiet rather than grey, so
 * the eye still knows where the action will be once the form is complete.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    tall: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val brush = if (enabled) {
        Brush.horizontalGradient(listOf(SheafColors.Band, SheafColors.BandBright))
    } else {
        Brush.horizontalGradient(listOf(SheafColors.SurfaceDim, SheafColors.SurfaceDim))
    }
    Row(
        modifier = modifier
            .pressScale(interaction)
            .height(if (tall) 52.dp else 40.dp)
            .clip(ButtonShape)
            .background(brush)
            .then(if (!enabled) Modifier.border(1.dp, SheafColors.Border, ButtonShape) else Modifier)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val tint = if (enabled) SheafColors.OnBand else SheafColors.Dim
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Box(Modifier.size(8.dp))
        } else if (iconRes != null) {
            Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1)
    }
}

/** The quieter sibling: outlined, for the second-best thing to do. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    iconRes: Int? = null,
    tall: Boolean = true
) {
    val interaction = remember { MutableInteractionSource() }
    val tint = if (enabled) SheafColors.Text else SheafColors.Dim
    Row(
        modifier = modifier
            .pressScale(interaction)
            .height(if (tall) 52.dp else 40.dp)
            .clip(ButtonShape)
            .background(SheafColors.Surface)
            .border(1.dp, SheafColors.BorderStrong, ButtonShape)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Box(Modifier.size(8.dp))
        } else if (iconRes != null) {
            Icon(painterResource(iconRes), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1)
    }
}

/** A small coloured square with an icon in it: the mark every tool and file carries. */
@Composable
fun IconTile(
    iconRes: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    filled: Boolean = false
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                if (filled) {
                    Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.75f)))
                } else {
                    Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.12f)))
                },
                RoundedCornerShape(size * 0.3f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (filled) Color.White else accent,
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

/** A word in a capsule: a state, a file type, a count. */
@Composable
fun Pill(text: String, colour: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (filled) Color.White else colour,
        modifier = modifier
            .background(if (filled) colour else colour.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .padding(horizontal = 9.dp, vertical = 3.dp)
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
 * One definition for the whole app. The chosen chip is solid band with light type, so the
 * answer to a question is visible from across the room; the others sit back in the surface.
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
            val fill by animateColorAsState(if (active) SheafColors.Band else SheafColors.SurfaceDim, label = "chip")
            val ink by animateColorAsState(if (active) SheafColors.OnBand else SheafColors.Muted, label = "chipInk")
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .pressScale(interaction, 0.95f)
                    .background(fill, shape)
                    .border(1.dp, if (active) SheafColors.Band else SheafColors.Border, shape)
                    .clickable(interactionSource = interaction, indication = null) { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink
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
