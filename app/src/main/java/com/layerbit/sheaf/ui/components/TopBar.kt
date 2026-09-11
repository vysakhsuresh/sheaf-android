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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * The bar every screen wears.
 *
 * It exists because navigation was previously invisible. Getting back relied on the system
 * gesture, and About sat at the very bottom of a long scroll, which on a screen that now holds
 * twenty-one tools meant it was effectively unreachable. A bar costs one row and puts both
 * within reach from anywhere.
 */
@Composable
fun SheafTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SheafColors.Background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text("‹", style = MaterialTheme.typography.titleLarge, color = SheafColors.Muted)
            }
        }

        Column(modifier = Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp)) {
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        actions()
    }
}
