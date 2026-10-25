package com.layerbit.sheaf.ui.components

import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.layerbit.sheaf.ui.icons.AnimatedGlyph
import com.layerbit.sheaf.ui.icons.glyphFor
import com.layerbit.sheaf.ui.icons.glyphSeed

/**
 * The one clock every moving icon reads - see ui/icons/Glyphs.kt for the drawings.
 *
 * A single slow transition, provided once at the root, as Kshanam does it. Icons read it only
 * while drawing, so it ticking never recomposes a card or a list; only the pictures redraw.
 * Null when motion is off: Android's animations switched off (Developer options, or the
 * accessibility "Remove animations"), or Lively icons turned off in Settings. The drawings
 * then show a still frame that still says what each tool does.
 */
val LocalLivelyClock = staticCompositionLocalOf<State<Float>?> { null }

/** Twelve seconds a lap; each drawing plays its action four times in it. */
private const val LAP_MS = 12_000

@Composable
fun ProvideLivelyClock(enabled: Boolean, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemAllows = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f) > 0f
    }
    if (!enabled || !systemAllows) {
        CompositionLocalProvider(LocalLivelyClock provides null, content = content)
        return
    }
    val clock = rememberInfiniteTransition(label = "lively").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(LAP_MS, easing = LinearEasing)),
        label = "lively-clock"
    )
    CompositionLocalProvider(LocalLivelyClock provides clock, content = content)
}

/**
 * A Material icon, or its drawn and moving counterpart where there is one (the gear turns,
 * the star twinkles, the magnifier searches). Everything else is the plain icon: an arrow or
 * a cross has no action of its own to show.
 */
@Composable
fun LivelyIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current
) {
    val glyph = glyphFor(imageVector)
    if (glyph == null) {
        androidx.compose.material3.Icon(imageVector, contentDescription, modifier, tint)
    } else {
        AnimatedGlyph(glyph, tint, modifier.described(contentDescription), glyphSeed(imageVector.name))
    }
}

/** The same for a drawable, which moves when [iconRes] names a tool's drawing. */
@Composable
fun LivelyIcon(
    painter: Painter,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current,
    @DrawableRes iconRes: Int? = null
) {
    val glyph = iconRes?.let(::glyphFor)
    if (glyph == null) {
        androidx.compose.material3.Icon(painter, contentDescription, modifier, tint)
    } else {
        AnimatedGlyph(glyph, tint, modifier.described(contentDescription), glyphSeed(iconRes))
    }
}

private fun Modifier.described(text: String?): Modifier =
    if (text == null) this else semantics { contentDescription = text }
