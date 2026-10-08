package com.layerbit.sheaf.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
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
import androidx.compose.ui.unit.dp
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
 * BAND IS WHAT YOU PAINT WITH, BANDBRIGHT IS WHAT YOU WRITE WITH. Band fills a button, draws
 * a border, washes a selection; BandBright is for a glyph or a word that has to be read. The
 * rule exists because [Band] on [Surface] is 3.5:1, which is under AA for body text, and
 * because the app was using both for text and so could not look consistent even where it was
 * legible. Anywhere the accent is ink - a label, a count, a link - reach for BandBright.
 *
 * AND THEN THERE ARE THE FIVE. The paragraph above used to end by saying the band was the
 * only saturated colour in the app and everything else was ink, paper and grey. That was
 * true, and on one screen it was a mistake: the board is twenty-six tool cards and
 * twenty-five of them were drawn identically - the same grey tile, the same grey glyph, the
 * same two lines of text - so a wall of equally-weighted rectangles was the first thing
 * anybody saw. Restraint and uniformity are not the same thing, and what had been built was
 * the second one.
 *
 * So each of the five tool groups now owns a hue, in [ToolAccents]. It is wayfinding and not
 * decoration: the colour is what lets someone find the page tools again without reading
 * twenty-six titles, and a group's heading wears the same hue as its cards so the two read as
 * one block. Five is deliberate - it is the number of groups, it is near the limit of what
 * anyone can hold as a set, and no sixth may be added without a sixth group to justify it.
 *
 * Where they may NOT go: on a document. A rendered page sits on [Paper] and the chrome around
 * it stays grey, because the original argument still holds - the page has to be the brightest
 * thing in the window. These live on the tool board, in the tool picker, and nowhere a
 * document is on screen.
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

    /** The five tool-group hues, in group order. See the note on [ToolAccent]. */
    var ToolAccents by mutableStateOf(Dark.toolAccents)
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
        ToolAccents = palette.toolAccents
        Running = palette.running
        Done = palette.done
        Failed = palette.failed
        Skipped = palette.skipped
        isDark = palette.dark
    }
}

/**
 * The two colours one tool group is drawn in.
 *
 * [tile] is the wash behind a 36dp icon, [ink] is the glyph on it and the group's heading.
 * They come as a pair because they are only ever correct together: every [ink] here clears
 * 4.5:1 on its own [tile] and on [Surface], and no other combination of the ten has been
 * checked.
 *
 * Two colours and not three. A hue that also had to supply a border or a pressed state would
 * be a theme of its own, five times over, and the chip would stop being the same chip.
 */
@Immutable
data class ToolAccent(val tile: Color, val ink: Color)

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
    val toolAccents: List<ToolAccent>,
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
    // Raised from #64748B, which was 3.30:1 on Surface and so under AA for body text. Dim is
    // the colour of nearly every explanatory line in the app - section headings, hints, tool
    // summaries, "Page 7 of 212" - so the prose carrying the app's character was the hardest
    // text in it to read. #8795A8 is 5.15:1 on Surface, 5.72:1 on Background, and still a
    // clear step below Muted at 6.67:1, which is what keeps the three-level ink ramp legible
    // as a ramp.
    dim = Color(0xFF8795A8),
    band = Color(0xFFE0257A),
    bandBright = Color(0xFFFF5C9E),
    bandDim = Color(0xFF3A0E22),
    onBand = Color(0xFFFFF2F7),
    paper = Color(0xFFF7F5EF),
    // Read, Pages, Mark up, Convert, Protect - in Group order, because that is how they are
    // indexed.
    //
    // THE TILES ARE LIFTED WELL CLEAR OF SURFACE, and that is the whole lesson of the first
    // attempt. They were originally near-black washes sitting at 1.02 to 1.07:1 against the
    // card they are drawn on - which is to say invisible as shapes. Four of them got away
    // with it on hue alone, because rose, amber, jade and iris are nowhere near a blue-black
    // ground. Azure was not so lucky: this app's base IS navy, so a navy chip on it had
    // neither lightness nor hue to separate by, and the Pages group came out looking unfinished
    // beside the others. These sit at 1.34 to 1.50:1, close enough together that no group
    // shouts over its neighbours, and far enough from Surface to read as an object.
    //
    // Every ink still clears AA on its own tile and on Surface; the worst of the ten is 5.22:1.
    toolAccents = listOf(
        ToolAccent(tile = Color(0xFF6E1C3E), ink = Color(0xFFFF8FBC)),
        ToolAccent(tile = Color(0xFF14415F), ink = Color(0xFF7FCDFF)),
        ToolAccent(tile = Color(0xFF533A10), ink = Color(0xFFFFCB63)),
        ToolAccent(tile = Color(0xFF114835), ink = Color(0xFF6FE0AC)),
        ToolAccent(tile = Color(0xFF3B2E6B), ink = Color(0xFFBCA6FF))
    ),
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
    // The same AA fix as the dark palette, in the other direction: on a light ground the
    // under-contrast grey has to go darker, not lighter. 5.37:1 on Surface, 4.80:1 on
    // Background.
    dim = Color(0xFF5F6B82),
    band = Color(0xFFC81E6C),
    bandBright = Color(0xFF9E1453),
    bandDim = Color(0xFFFBE2EE),
    onBand = Color(0xFFFFF7FA),
    paper = Color(0xFFFFFFFF),
    // The same five in daylight, and not an inversion of them: on a light ground the tile is
    // the pale end and the ink is the dark end, which is the same swap the band makes above.
    // Deepened for the same reason the dark ones were lifted - a tint a shade off white is a
    // chip nobody can see. 1.26 to 1.41:1 against Surface, inks from 5.38:1 on their tile.
    toolAccents = listOf(
        ToolAccent(tile = Color(0xFFF7CEE1), ink = Color(0xFF9E1453)),
        ToolAccent(tile = Color(0xFFD2E6F6), ink = Color(0xFF11557F)),
        ToolAccent(tile = Color(0xFFF5E4BC), ink = Color(0xFF7A5410)),
        ToolAccent(tile = Color(0xFFCBEBDB), ink = Color(0xFF116244)),
        ToolAccent(tile = Color(0xFFDED4F7), ink = Color(0xFF4B32A8))
    ),
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
    // The one slot that is not a choice about this app's voice but about whether it is heard
    // at all. Material3's Button, OutlinedButton and TextButton each wrap their content in
    // ProvideContentColorTextStyle(typography.labelLarge), which overrides the LocalTextStyle
    // set at the bottom of this file - so while this was undefined it resolved to Material's
    // own default, and every one of the app's sixty-odd button labels rendered in Roboto
    // beside Space Grotesk body text in the same panel. The styles above are the design; this
    // one is the design reaching the buttons.
    labelLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
)

/**
 * Two radii, named, so that a twelfth one is never typed.
 *
 * Panels, sheets and cards are 14; fields, chips-that-are-not-pills and small wells are 8.
 * Before this there were ten distinct corner radii in the app and four of them were not
 * chosen by anyone here - a 4.dp search field and three 28.dp bottom sheets, all Material
 * defaults sitting directly above 14.dp panels.
 *
 * Material's five-step scale is collapsed onto those two deliberately: large and extraLarge
 * both answer 14 because a bottom sheet in this app is a panel that arrived from below, not
 * a different kind of surface. Two exceptions stay at their call sites with the comment that
 * earns them - a rendered page keeps its near-square 3.dp, because paper does, and a chip
 * keeps its stadium, because a chip is not a card.
 *
 * Note that Button does NOT read this: ButtonDefaults.shape resolves a hardcoded CircleShape,
 * so a button that should not be a pill has to say so itself.
 */
private val SheafShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(14.dp)
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

    // The roles below are every one Material will reach for in this app, not every one it
    // defines. The ones that were missing were not neutral: a scheme leaves its gaps at
    // Material's own baseline, and the baseline is a warm purple-grey that shows up in
    // exactly the two places that float above everything else - surfaceContainer, which is
    // what a DropdownMenu paints itself with, and inverseSurface, which is what a Snackbar
    // paints itself with. So the viewer's overflow menu and every confirmation in the app
    // were the two surfaces not drawn from this palette. Scrim is deliberately left alone:
    // the baseline is already black and listing it would imply somebody chose it.
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
            surfaceContainerLowest = palette.surfaceDim,
            surfaceContainerLow = palette.surfaceDim,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface,
            surfaceContainerHighest = palette.surface,
            inverseSurface = palette.text,
            inverseOnSurface = palette.background,
            outline = palette.border,
            outlineVariant = palette.border,
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
            surfaceContainerLowest = palette.surfaceDim,
            surfaceContainerLow = palette.surfaceDim,
            surfaceContainer = palette.surface,
            surfaceContainerHigh = palette.surface,
            surfaceContainerHighest = palette.surface,
            inverseSurface = palette.text,
            inverseOnSurface = palette.background,
            outline = palette.border,
            outlineVariant = palette.border,
            error = palette.failed
        )
    }

    MaterialTheme(colorScheme = scheme, typography = SheafTypography, shapes = SheafShapes) {
        CompositionLocalProvider(
            // Anything drawing raw Text outside a styled slot still lands on the family face
            // rather than falling back to the platform default.
            LocalTextStyle provides SheafTypography.bodyLarge,
            // Not cosmetic, and not reachable through the colour scheme. LocalContentColor
            // defaults to Color.Black and only a Surface composable changes it - and there is
            // no Surface anywhere in this app, because every panel is drawn by hand. Material
            // tints the press ripple from this value, so until now every bare .clickable in
            // the app drew a black ripple on a near-black panel: a press that produced no
            // feedback at all. One line gives Panel, ChipRow, ToggleRow, the recents rows,
            // the search results and the page thumbs a visible touch response.
            LocalContentColor provides palette.text,
            content = content
        )
    }
}
