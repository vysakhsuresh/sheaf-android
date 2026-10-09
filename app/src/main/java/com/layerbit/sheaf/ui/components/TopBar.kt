package com.layerbit.sheaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ui.theme.SheafColors
import com.layerbit.sheaf.ui.theme.ToolAccent
import com.layerbit.sheaf.ui.theme.chromeSweep

/**
 * The bar every screen wears.
 *
 * It exists because navigation was previously invisible. Getting back relied on the system
 * gesture, and About sat at the very bottom of a long scroll, which on a screen that now holds
 * two dozen tools meant it was effectively unreachable. A bar costs one row and puts both
 * within reach from anywhere.
 */
@Composable
fun SheafTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /**
     * The tool this screen belongs to, drawn as the same chip its card wears on the board.
     *
     * Both or neither. A tool screen used to open as a title over an empty form, with no
     * sign that it was the violet card you had just tapped; the chip is the one thing that
     * carries the board colour through the tap. Screens that are not a tool - Settings,
     * About, the Library - pass nothing and keep the plain bar.
     */
    icon: Int? = null,
    accent: ToolAccent? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // A shallow lift that fades back into the background, so the title has a plane of
            // its own without a drawn line under it.
            .background(chromeSweep())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (onBack != null) {
            IconAction(
                icon = R.drawable.ic_chevron_left,
                label = "Back",
                onClick = onBack
            )
        }

        if (icon != null && accent != null) {
            AccentTile(
                icon = icon,
                accent = accent,
                size = 30.dp,
                modifier = Modifier.padding(start = if (onBack == null) 12.dp else 4.dp, end = 4.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack == null && icon == null) 12.dp else 0.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = SheafColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SheafColors.Dim,
                    // Two lines here where the title gets one. A filename has to be capped
                    // somewhere, but this line is a sentence: on a tool screen it is that
                    // tool's summary, and the summary appears nowhere else once you are
                    // inside the tool. In a bar that has already given up a back button's
                    // width, the longer ones do not fit on a 360dp screen in one line.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        actions()
    }
}
