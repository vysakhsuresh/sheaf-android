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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.layerbit.sheaf.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Icons that move a little, the way Kshanam's do.
 *
 * Every icon has one small movement that says what it does - Scan sweeps a line down the page,
 * Merge draws its sheets together, a lock shakes, a recipe turns - and makes it once in a
 * while rather than all the time. Each icon's moment is shifted by its own seed, so a screen of
 * forty tools ripples rather than twitching in step, and most of the time everything is still.
 *
 * WHY ONE CLOCK. A single slow transition, provided once at the root, drives every icon on
 * screen. Icons read it only inside graphicsLayer and drawWithContent, which run in the draw
 * phase, so the clock ticking never recomposes a card or a list - only the pictures redraw.
 *
 * It switches itself off when the phone's own animations are off (Developer options or the
 * accessibility "Remove animations" switch, which both set the animator scale to zero), and
 * when the user turns Lively icons off in Settings.
 */
val LocalLivelyClock = staticCompositionLocalOf<State<Float>?> { null }

/** Twelve seconds per lap; each icon spends the first [BURST] of its own lap moving. */
private const val LAP_MS = 12_000
private const val BURST = 0.13f
private const val TAU = (2 * PI).toFloat()

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

/** The one movement an icon makes. */
enum class Motion {
    NONE, SCAN, NUDGE, SQUEEZE, STRETCH, HOP, WIGGLE, FLIP, SQUASH, PULSE, WRITE, POP,
    DROP, SWING, SHINE, TILT, SHIFT, RISE, SHAKE, LIFT, SPIN, PAGE, TURN
}

/** Which movement each drawing makes. Chosen for what the tool does, not at random. */
fun motionFor(@DrawableRes icon: Int): Motion = when (icon) {
    R.drawable.ic_tool_scan, R.drawable.ic_tool_ocr, R.drawable.ic_shortcut_scan -> Motion.SCAN
    R.drawable.ic_tool_extract_text, R.drawable.ic_tool_summarise -> Motion.NUDGE
    R.drawable.ic_tool_merge, R.drawable.ic_shortcut_merge -> Motion.SQUEEZE
    R.drawable.ic_tool_split, R.drawable.ic_tool_split_size -> Motion.STRETCH
    R.drawable.ic_tool_extract, R.drawable.ic_tool_extract_images, R.drawable.ic_tool_pdf_to_images -> Motion.HOP
    R.drawable.ic_tool_organise, R.drawable.ic_tool_n_up -> Motion.WIGGLE
    R.drawable.ic_tool_images_to_pdf, R.drawable.ic_tool_office_to_pdf, R.drawable.ic_tool_pdf_to_office -> Motion.FLIP
    R.drawable.ic_tool_compress, R.drawable.ic_tool_resize_photo, R.drawable.ic_shortcut_compress -> Motion.SQUASH
    R.drawable.ic_tool_watermark, R.drawable.ic_tool_background -> Motion.PULSE
    R.drawable.ic_tool_sign, R.drawable.ic_tool_digital_sign, R.drawable.ic_tool_annotate,
    R.drawable.ic_tool_edit_text, R.drawable.ic_tool_add_text -> Motion.WRITE
    R.drawable.ic_tool_add_image, R.drawable.ic_tool_insert, R.drawable.ic_tool_create_form,
    R.drawable.ic_tool_forms, R.drawable.ic_tool_edit_images -> Motion.POP
    R.drawable.ic_tool_page_numbers, R.drawable.ic_tool_page_labels, R.drawable.ic_tool_header_footer -> Motion.TILT
    R.drawable.ic_tool_bookmarks -> Motion.DROP
    R.drawable.ic_tool_attachments -> Motion.SWING
    R.drawable.ic_tool_redact -> Motion.SHINE
    R.drawable.ic_tool_metadata, R.drawable.ic_tool_crop -> Motion.TURN
    R.drawable.ic_tool_compare -> Motion.SHIFT
    R.drawable.ic_tool_receipt, R.drawable.ic_tool_archive -> Motion.RISE
    R.drawable.ic_tool_lock -> Motion.SHAKE
    R.drawable.ic_tool_unlock -> Motion.LIFT
    R.drawable.ic_tool_recipe -> Motion.SPIN
    R.drawable.ic_tool_read -> Motion.PAGE
    else -> Motion.POP
}

/** The same for the Material glyphs on the round buttons; most of them are better still. */
fun motionFor(icon: ImageVector): Motion = when (icon.name.substringAfterLast('.')) {
    "Settings" -> Motion.SPIN
    "Star", "Favorite" -> Motion.POP
    "Search" -> Motion.TILT
    "Info" -> Motion.HOP
    "Share", "Send" -> Motion.NUDGE
    "Lock" -> Motion.SHAKE
    "Email" -> Motion.TILT
    else -> Motion.NONE
}

/** A steady seed for an icon, so the same tool always moves at the same point in the lap. */
fun livelySeed(key: Any): Int = (key.hashCode() and 0x7fffffff) % 97

/** Progress 0..1 through this icon's movement, or null while it rests. */
private fun burst(clock: Float, seed: Int): Float? {
    val t = (clock + seed * 0.137f) % 1f
    return if (t < BURST) t / BURST else null
}

/** 0 → 1 → 0 with soft ends: the shape of most of the movements. */
private fun bump(u: Float) = sin(u * PI.toFloat()).let { it * it }

/** A decaying back-and-forth, for things that shake or swing and settle. */
private fun settle(u: Float, cycles: Float) = sin(u * TAU * cycles) * (1f - u)

/**
 * Moves the glyph. Applied to the icon itself, inside its tile, so the tile stays put and only
 * the drawing comes alive.
 */
fun Modifier.lively(motion: Motion, seed: Int): Modifier = if (motion == Motion.NONE) this else composed {
    val clock = LocalLivelyClock.current ?: return@composed this
    graphicsLayer {
        val u = burst(clock.value, seed) ?: return@graphicsLayer
        val w = size.width
        when (motion) {
            Motion.SCAN -> scaleX = 1f + 0.04f * bump(u)
            Motion.NUDGE -> translationX = w * 0.12f * bump(u)
            Motion.SQUEEZE -> scaleX = 1f - 0.22f * bump(u)
            Motion.STRETCH -> scaleX = 1f + 0.22f * bump(u)
            Motion.HOP -> translationY = -w * 0.18f * bump(u)
            Motion.WIGGLE -> rotationZ = 12f * settle(u, 2f)
            Motion.FLIP -> rotationY = 360f * easeInOut(u)
            Motion.SQUASH -> { scaleX = 1f - 0.2f * bump(u); scaleY = 1f - 0.2f * bump(u) }
            Motion.PULSE -> alpha = 1f - 0.55f * bump(u)
            Motion.WRITE -> { rotationZ = -10f * bump(u); translationX = w * 0.06f * sin(u * TAU * 3f) * (1f - u) }
            Motion.POP -> { scaleX = 1f + 0.18f * bump(u); scaleY = 1f + 0.18f * bump(u) }
            Motion.DROP -> translationY = -w * 0.25f * (1f - u) * abs(sin(u * TAU * 1.5f))
            Motion.SWING -> { transformOrigin = TransformOrigin(0.5f, 0f); rotationZ = 18f * settle(u, 1.5f) }
            Motion.SHINE -> scaleY = 1f + 0.05f * bump(u)
            Motion.TILT -> rotationZ = 14f * bump(u)
            Motion.TURN -> rotationZ = 90f * easeInOut(u)
            Motion.SHIFT -> translationX = w * 0.12f * sin(u * TAU) * (1f - u * 0.5f)
            Motion.RISE -> translationY = -w * 0.14f * bump(u)
            Motion.SHAKE -> rotationZ = 14f * settle(u, 3f)
            Motion.LIFT -> { translationY = -w * 0.1f * bump(u); rotationZ = -8f * bump(u) }
            Motion.SPIN -> rotationZ = 360f * easeInOut(u)
            Motion.PAGE -> { transformOrigin = TransformOrigin(0f, 0.5f); rotationY = -55f * bump(u) }
            Motion.NONE -> Unit
        }
    }
}

/**
 * What a few movements add to the tile behind the glyph: Scan's light passing down the page,
 * a shine across Remove areas. Drawn over the tile, clipped to it by the tile's own shape.
 */
fun Modifier.livelyTile(motion: Motion, seed: Int, accent: Color): Modifier =
    if (motion != Motion.SCAN && motion != Motion.SHINE) this else composed {
        val clock = LocalLivelyClock.current ?: return@composed this
        drawWithContent {
            drawContent()
            val u = burst(clock.value, seed) ?: return@drawWithContent
            if (motion == Motion.SCAN) {
                val y = size.height * (0.15f + 0.7f * u)
                drawLine(
                    color = accent.copy(alpha = 0.9f * bump(u)),
                    start = Offset(size.width * 0.18f, y),
                    end = Offset(size.width * 0.82f, y),
                    strokeWidth = size.height * 0.05f,
                    cap = StrokeCap.Round
                )
            } else {
                val x = size.width * (-0.3f + 1.6f * u)
                drawRect(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                        start = Offset(x - size.width * 0.25f, 0f),
                        end = Offset(x + size.width * 0.25f, size.height)
                    )
                )
            }
        }
    }

private fun easeInOut(u: Float): Float { val c = u.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }
