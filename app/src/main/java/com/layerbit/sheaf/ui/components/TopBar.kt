package com.layerbit.sheaf.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.layerbit.sheaf.R
import com.layerbit.sheaf.ui.theme.SheafColors

/**
 * The bar every screen wears: a round back button, the title, and round icon buttons for
 * whatever the screen offers. Round because a circle reads as a button at a glance where a
 * bare glyph did not, and because the same circles are what the viewer's bar uses.
 */
@Composable
fun SheafTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    /** A tool's own icon, beside its title, in its group's colour. */
    iconRes: Int? = null,
    accent: Color? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SheafColors.Background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (onBack != null) {
            RoundIconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.app_back), onClick = onBack)
        }
        if (iconRes != null) {
            IconTile(iconRes = iconRes, accent = accent ?: SheafColors.Band, size = 36.dp)
        }
        Column(modifier = Modifier.weight(1f).padding(start = if (onBack == null && iconRes == null) 8.dp else 4.dp)) {
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

/** A circular icon button on a quiet surface. */
@Composable
fun RoundIconButton(
    icon: ImageVector? = null,
    /**
     * What TalkBack says. Required, not nullable: a round button is nothing but an icon, and an
     * icon without a name is announced as "unlabelled button" - which is no button at all to
     * someone who cannot see it.
     */
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    tint: Color = SheafColors.Muted,
    filled: Boolean = false,
    size: androidx.compose.ui.unit.Dp = 40.dp
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .pressScale(interaction, 0.92f)
            .size(size)
            .background(if (filled) SheafColors.Band else SheafColors.Surface, CircleShape)
            .border(1.dp, if (filled) SheafColors.Band else SheafColors.Border, CircleShape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            // The label sits on the touch target, not just on the glyph inside it, so the whole
            // circle is one announced button rather than a button with an image in it.
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        val colour = if (filled) SheafColors.OnBand else tint
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(size * 0.5f))
        } else if (iconRes != null) {
            Icon(painterResource(iconRes), contentDescription = null, tint = colour, modifier = Modifier.size(size * 0.5f))
        }
    }
}
