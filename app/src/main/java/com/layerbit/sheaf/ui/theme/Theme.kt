package com.layerbit.sheaf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.layerbit.sheaf.R
import com.layerbit.sheaf.prefs.ThemeChoice

/**
 * Ink and paper, with one magenta band.
 *
 * The family reads as a set: LayerLink is cool blue-black because it is a tool, Deja is warm
 * charcoal because it is a memory, Abhyas takes the accent warm because its audience is
 * students. Sheaf is a tool, so it keeps the cool blue-black base - and takes the one
 * saturated colour in the app from the band on its own icon.
 *
 * That band colour is spent in exactly one place at a time: the primary action on screen.
 * Everything else is ink, paper and the greys between them, which is what makes a page of a
 * document the brightest thing in the window rather than the chrome around it.
 *
 * WHY THESE ARE OBSERVABLE STATE RATHER THAN CONSTANTS. Every screen reads SheafColors
 * directly, which was the right call while there was one palette and the wrong one the moment
 * there were two. The alternative to this is a CompositionLocal threaded through every
 * composable in the app; the cost of that is a change in fifteen files and a parameter on
 * everything, and the benefit over this is that two themes could be shown side by side, which
 * nothing here will ever do. So the palette is a holder: [SheafTheme] sets it, Compose sees a
 * state read during composition, and every screen recomposes into the new colours.
 */
object SheafColors {
    var Background by mutableStateOf(Dark.background)
        private set
    var Surface by mutableStateOf(Dark.surface)
        private set
    var SurfaceDim by mutableStateOf(Dark.surfaceDim)
        private set
    var Border by mutableStateOf(Dark.border)
        private set
    var BorderStrong by mutableStateOf(Dark.borderStrong)
        private set
    var Text by mutableStateOf(Dark.text)
        private set
    var Muted by mutableStateOf(Dark.muted)
        private set
    var Dim by mutableStateOf(Dark.dim)
        private set

    var Band by mutableStateOf(Dark.band)
        private set
    var BandBright by mutableStateOf(Dark.bandBright)
        private set
    var BandDim by mutableStateOf(Dark.bandDim)
        private set
    var OnBand by mutableStateOf(Dark.onBand)
        private set

    /** The page itself. A rendered PDF page sits on this, never on Surface. */
    var Paper by mutableStateOf(Dark.paper)
        private set

    /**
     * Operation outcome, and the only place these are defined. A result means the same thing
     * in the job notification, the batch list and the history screen, so it has to look the
     * same in all three.
     */
    var Running by mutableStateOf(Dark.running)
        private set
    var Done by mutableStateOf(Dark.done)
        private set
    var Failed by mutableStateOf(Dark.failed)
        private set
    var Skipped by mutableStateOf(Dark.skipped)
        private set

    /** True while the dark palette is in force, for the few places that need to know. */
    var isDark by mutableStateOf(true)
        private set

    internal fun apply(palette: Palette) {
        if (Background == palette.background && isDark == palette.dark) return
        Background = palette.background
        Surface = palette.surface
        SurfaceDim = palette.surfaceDim
        Border = palette.border
        BorderStrong = palette.borderStrong
        Text = palette.text
        Muted = palette.muted
        Dim = palette.dim
        Band = palette.band
        BandBright = palette.bandBright
        BandDim = palette.bandDim
        OnBand = palette.onBand
        Paper = palette.paper
        Running = palette.running
        Done = palette.done
        Failed = palette.failed
        Skipped = palette.skipped
        isDark = palette.dark
    }
}

/** One palette. Two instances exist and nothing else may construct one. */
internal data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceDim: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val dim: Color,
    val band: Color,
    val bandBright: Color,
    val bandDim: Color,
    val onBand: Color,
    val paper: Color,
    val running: Color,
    val done: Color,
    val failed: Color,
    val skipped: Color
)

internal val Dark = Palette(
    dark = true,
    background = Color(0xFF101A2B),
    surface = Color(0xFF17233A),
    surfaceDim = Color(0xFF131E31),
    border = Color(0xFF243349),
    borderStrong = Color(0xFF31435E),
    text = Color(0xFFF0F3F8),
    muted = Color(0xFF9BAAC2),
    dim = Color(0xFF64748B),
    band = Color(0xFFE0257A),
    bandBright = Color(0xFFFF5C9E),
    bandDim = Color(0xFF3A0E22),
    onBand = Color(0xFFFFF2F7),
    paper = Color(0xFFF7F5EF),
    running = Color(0xFF5BA9E8),
    done = Color(0xFF58C08C),
    failed = Color(0xFFE0705F),
    skipped = Color(0xFFE8A33D)
)

/**
 * The same app in daylight.
 *
 * Not an inversion. The band goes a shade deeper because the bright magenta that reads as an
 * accent on near-black reads as a highlighter pen on paper, and the two "bright" and "dim"
 * roles swap weight: on a light ground, emphasis is darker than the text around it, not
 * lighter. Paper stays white, because a page is a page.
 */
internal val Light = Palette(
    dark = false,
    background = Color(0xFFF4F2EC),
    surface = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEAE7DF),
    border = Color(0xFFDCD7CB),
    borderStrong = Color(0xFFBDB5A4),
    text = Color(0xFF121A26),
    muted = Color(0xFF4C5869),
    dim = Color(0xFF78849B),
    band = Color(0xFFC81E6C),
    bandBright = Color(0xFF9E1453),
    bandDim = Color(0xFFFBE2EE),
    onBand = Color(0xFFFFF7FA),
    paper = Color(0xFFFFFFFF),
    running = Color(0xFF1C6FB8),
    done = Color(0xFF17784E),
    failed = Color(0xFFA83C27),
    skipped = Color(0xFF8A5C0B)
)

/** Space Grotesk, the same face LayerLink, Deja, Abhyas and layerbit.co.in use. */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.space_grotesk_bold, FontWeight.Bold)
)

private val SheafTypography = Typography(
    displayLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 46.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.8).sp),
    displayMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.4).sp),
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 13.5.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 12.5.sp, fontWeight = FontWeight.Normal),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun SheafTheme(theme: ThemeChoice = ThemeChoice.DARK, content: @Composable () -> Unit) {
    val dark = when (theme) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val palette = if (dark) Dark else Light
    SheafColors.apply(palette)

    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.band,
            onPrimary = palette.onBand,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceDim,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            error = palette.failed
        )
    } else {
        lightColorScheme(
            primary = palette.band,
            onPrimary = palette.onBand,
            background = palette.background,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceDim,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            error = palette.failed
        )
    }

    MaterialTheme(colorScheme = scheme, typography = SheafTypography) {
        // Anything drawing raw Text outside a styled slot still lands on the family face
        // rather than falling back to the platform default.
        CompositionLocalProvider(
            LocalTextStyle provides SheafTypography.bodyLarge,
            content = content
        )
    }
}
