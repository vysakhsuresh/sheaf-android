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
import androidx.compose.ui.graphics.Brush
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

    /** The lift the chrome sits on, so a bar is not the same flat field as the content. */
    var SurfaceTint by mutableStateOf(Dark.surfaceTint)
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

    /** The far end of the brand sweep. Paint with [BrandSweep], not with this directly. */
    var BandTo by mutableStateOf(Dark.bandTo)
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
        SurfaceTint = palette.surfaceTint
        Border = palette.border
        BorderStrong = palette.borderStrong
        Text = palette.text
        Muted = palette.muted
        Dim = palette.dim
        Band = palette.band
        BandTo = palette.bandTo
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
 * The colours one tool group is drawn in.
 *
 * [tile] and [tileTo] are the two stops of the gradient behind a 36dp icon, [onTile] is the
 * glyph on it, [ink] is the group's name in text, and [edge] is the card's border. They come
 * as a set because they are only ever correct together.
 *
 * FIVE ROLES, WHERE THERE USED TO BE TWO, AND WHY THAT CHANGED. The first version of this
 * held a tile and an ink and argued that a third colour would make each hue a theme of its
 * own. The argument was sound and the result was still wrong: the tiles sat at 1.34 to
 * 1.50:1 against the card they were drawn on, so the one coloured thing on a tool card was a
 * 36dp square you had to look for. Twenty-six cards, each a grey rectangle with a grey
 * border, differing in a wash nobody could see - the board read as grey no matter how
 * carefully the five hues had been picked.
 *
 * So the hue now carries the card rather than decorating it: a saturated gradient tile you
 * can see across the room, the card's own border in the same family, and a wash of [tile] at
 * a tenth opacity behind the whole panel. That is what makes the board five blocks of colour
 * instead of a wall.
 *
 * THE SPLIT BETWEEN [onTile] AND [ink] IS THE WHOLE TRICK, and it is why this is five roles
 * and not three. The tile is now a saturated mid-dark in BOTH palettes - which is what lets
 * the same chip read on a navy card and on a white one - so the glyph on it is near-white
 * either way. But the group's name is text on [SheafColors.Surface], and there emphasis runs
 * the other way: lighter than the ground in the dark palette, darker in the light one. One
 * colour cannot do both jobs, and when it tried, the amber group's heading was the hardest
 * line of text in the app.
 *
 * Every pair here has been checked. [onTile] clears 4.5:1 on both gradient stops - the worst
 * of the ten is 4.92:1 - and [ink] clears 4.5:1 on Surface. No other combination of these
 * has been checked, so none of them may be mixed.
 */
@Immutable
data class ToolAccent(
    val tile: Color,
    val tileTo: Color,
    val onTile: Color,
    val ink: Color,
    val edge: Color
)

/**
 * The brand sweep: the band colour running into violet, corner to corner.
 *
 * One definition, because a gradient that is diagonal on a button and vertical on a tab is
 * two gradients and the app stops looking like it was drawn by one hand. Every primary
 * action, the selected tab and the brand mark read this.
 *
 * No explicit start and end, so Compose resolves the sweep against whatever it is drawn into
 * rather than against the screen - a 40dp pill and a full-width button each get the whole
 * ramp instead of a slice of one big gradient.
 *
 * These are functions and not vals because they read [SheafColors], which is observable
 * state: a val computed once would hold the dark palette forever. Each call is one small
 * allocation during composition, which is what a Brush is.
 */
fun brandSweep(): Brush = Brush.linearGradient(listOf(SheafColors.Band, SheafColors.BandTo))

/** A tool group's tile, as the gradient it is meant to be drawn with. */
fun ToolAccent.sweep(): Brush = Brush.linearGradient(listOf(tile, tileTo))

/**
 * What the chrome is painted with - the top bar, the bottom bar.
 *
 * Vertical and shallow. The bars used to be a flat fill of exactly the colour behind them,
 * which meant the only thing separating a title from the list under it was the gap. A lift
 * that fades back into the background gives the bar an edge without drawing a line.
 */
fun chromeSweep(): Brush =
    Brush.verticalGradient(listOf(SheafColors.SurfaceTint, SheafColors.Background))

/** One palette. Two instances exist and nothing else may construct one. */
internal data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceDim: Color,
    val surfaceTint: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val muted: Color,
    val dim: Color,
    val band: Color,
    val bandTo: Color,
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
    // Carries more blue than the near-neutral it replaced (#101A2B/#17233A). The old pair
    // differed from plain charcoal by so little that the app read as grey before the accents
    // were even examined, and a base with some hue in it is what the five tool colours sit
    // against rather than float on.
    background = Color(0xFF0B1324),
    surface = Color(0xFF161F38),
    surfaceDim = Color(0xFF111A2F),
    // The lift under a top bar and a bottom bar, so the chrome is not the same flat field as
    // the content scrolling behind it.
    surfaceTint = Color(0xFF1C2746),
    border = Color(0xFF273458),
    borderStrong = Color(0xFF3A4C7A),
    text = Color(0xFFF2F5FB),
    muted = Color(0xFFA2B2CE),
    // The AA floor for the ink ramp, which is where nearly every explanatory line in the app
    // lands - section headings, hints, tool summaries, "Page 7 of 212". 5.60:1 on Surface and
    // a clear step below Muted at 7.60:1, which is what keeps the three levels legible as a
    // ramp rather than as three greys.
    dim = Color(0xFF8A98B2),
    band = Color(0xFFE0257A),
    // The second stop of the brand gradient. Magenta into violet, which is the band on
    // Sheaf's own icon read as a sweep rather than a flat fill; it is what a primary button, a
    // selected tab and the brand mark are painted with.
    bandTo = Color(0xFF7C3AED),
    bandBright = Color(0xFFFF5C9E),
    bandDim = Color(0xFF3A0E22),
    onBand = Color(0xFFFFF2F7),
    paper = Color(0xFFF7F5EF),
    // Read, Pages, Mark up, Convert, Protect - in Group order, because that is how they are
    // indexed.
    toolAccents = listOf(
        ToolAccent(Color(0xFFC2185B), Color(0xFF8E1668), Color(0xFFFFFFFF), Color(0xFFFF8FBC), Color(0xFF8E3461)),
        ToolAccent(Color(0xFF0E6BA8), Color(0xFF0A4680), Color(0xFFFFFFFF), Color(0xFF7FCDFF), Color(0xFF2C5A86)),
        ToolAccent(Color(0xFFA35C0A), Color(0xFF7E3A08), Color(0xFFFFFFFF), Color(0xFFFFCB63), Color(0xFF8A6224)),
        ToolAccent(Color(0xFF0A7A54), Color(0xFF07614F), Color(0xFFFFFFFF), Color(0xFF6FE0AC), Color(0xFF2A6E55)),
        ToolAccent(Color(0xFF5B36C4), Color(0xFF3A1E8C), Color(0xFFFFFFFF), Color(0xFFBCA6FF), Color(0xFF5B4D9E))
    ),
    running = Color(0xFF4FB3FF),
    done = Color(0xFF3ED598),
    failed = Color(0xFFFF6B5B),
    skipped = Color(0xFFFFB02E)
)

/**
 * The same app in daylight.
 *
 * Not an inversion. The band goes a shade deeper because the bright magenta that reads as an
 * accent on near-black reads as a highlighter pen on paper, and the two "bright" and "dim"
 * roles swap weight: on a light ground, emphasis is darker than the text around it, not
 * lighter. Paper stays white, because a page is a page.
 *
 * The tool tiles are the one thing that does NOT flip. They are saturated mid-darks in both
 * palettes, carrying a white glyph in both, because a chip that is a pale tint on white is
 * the same invisible chip the dark palette already had - and because a group should be the
 * same colour whichever way the phone is set.
 */
internal val Light = Palette(
    dark = false,
    // A hint of the base hue rather than the warm paper tone it replaced, so the white cards
    // on it read as lifted. Still well short of a tint anyone would call blue.
    background = Color(0xFFF2F1FB),
    surface = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8E7F4),
    surfaceTint = Color(0xFFFAFAFE),
    border = Color(0xFFD9D7E8),
    borderStrong = Color(0xFFB4B1CA),
    text = Color(0xFF121A26),
    muted = Color(0xFF4C5869),
    // The same AA floor in the other direction: on a light ground the ramp goes darker.
    // 5.37:1 on Surface.
    dim = Color(0xFF5F6B82),
    band = Color(0xFFC81E6C),
    bandTo = Color(0xFF6D28D9),
    bandBright = Color(0xFF9E1453),
    bandDim = Color(0xFFFBE2EE),
    onBand = Color(0xFFFFF7FA),
    paper = Color(0xFFFFFFFF),
    // The same five groups, a shade brighter where a white glyph still clears AA, and the
    // edges are mid-tones rather than tints because a 1dp pastel line on white is not a
    // border.
    toolAccents = listOf(
        ToolAccent(Color(0xFFD81B60), Color(0xFFA61A72), Color(0xFFFFFFFF), Color(0xFF9E1453), Color(0xFFE07CAC)),
        ToolAccent(Color(0xFF1270B8), Color(0xFF0C4E8C), Color(0xFFFFFFFF), Color(0xFF11557F), Color(0xFF6FA9D8)),
        ToolAccent(Color(0xFFA85E08), Color(0xFF8E4208), Color(0xFFFFFFFF), Color(0xFF7A5410), Color(0xFFD29B4A)),
        ToolAccent(Color(0xFF0C7D55), Color(0xFF076B58), Color(0xFFFFFFFF), Color(0xFF116244), Color(0xFF5CB596)),
        ToolAccent(Color(0xFF6940D6), Color(0xFF42239C), Color(0xFFFFFFFF), Color(0xFF4B32A8), Color(0xFF9B82E4))
    ),
    running = Color(0xFF0B63B5),
    done = Color(0xFF0C7D55),
    failed = Color(0xFFC0351E),
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
            secondary = palette.bandTo,
            onSecondary = palette.onBand,
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
            secondary = palette.bandTo,
            onSecondary = palette.onBand,
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
