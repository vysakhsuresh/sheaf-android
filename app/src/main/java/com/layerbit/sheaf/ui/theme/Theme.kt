package com.layerbit.sheaf.ui.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.layerbit.sheaf.R

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
 */
object SheafColors {
    val Background = Color(0xFF101A2B)
    val Surface = Color(0xFF17233A)
    val SurfaceDim = Color(0xFF131E31)
    val Border = Color(0xFF243349)
    val BorderStrong = Color(0xFF31435E)
    val Text = Color(0xFFF0F3F8)
    val Muted = Color(0xFF9BAAC2)
    val Dim = Color(0xFF64748B)

    val Band = Color(0xFFE0257A)
    val BandBright = Color(0xFFFF5C9E)
    val BandDim = Color(0xFF3A0E22)
    val OnBand = Color(0xFFFFF2F7)

    /** The page itself. A rendered PDF page sits on this, never on Surface. */
    val Paper = Color(0xFFF7F5EF)

    /**
     * Operation outcome, and the only place these are defined. A result means the same thing
     * in the job notification, the batch list and the history screen, so it has to look the
     * same in all three.
     */
    val Running = Color(0xFF5BA9E8)
    val Done = Color(0xFF58C08C)
    val Failed = Color(0xFFE0705F)
    val Skipped = Color(0xFFE8A33D)
}

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

private val SheafColorScheme = darkColorScheme(
    primary = SheafColors.Band,
    onPrimary = SheafColors.OnBand,
    background = SheafColors.Background,
    onBackground = SheafColors.Text,
    surface = SheafColors.Surface,
    onSurface = SheafColors.Text,
    surfaceVariant = SheafColors.SurfaceDim,
    onSurfaceVariant = SheafColors.Muted,
    outline = SheafColors.Border,
    error = SheafColors.Failed
)

@Composable
fun SheafTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SheafColorScheme,
        typography = SheafTypography
    ) {
        // Anything drawing raw Text outside a styled slot still lands on the family face
        // rather than falling back to the platform default.
        CompositionLocalProvider(
            LocalTextStyle provides SheafTypography.bodyLarge,
            content = content
        )
    }
}
