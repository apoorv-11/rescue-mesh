package com.rescuemesh.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rescuemesh.R

/**
 * The redesign theme (spec, 14 Nov) — a synthesis of the user's two
 * references: the bold red SOS app and the soft green safety app.
 *
 * - Warm off-white base, white cards, soft diffused shadows.
 * - Rescue Red is the primary (SOS, primary actions, active nav).
 * - Safe Green + its mint tint carry I Am Safe and the entry hero.
 *
 * REPLICA ROUND (15 Nov): the typefaces are BUNDLED, not the device
 * default. The values come from the user's exported preview HTML
 * (`RescueMesh_UI_Redesign_Preview.html`): the preview renders in
 * **Archivo** (display/headline — weights 800/900 dominate) and
 * **Manrope** (everything else — SemiBold 600 is its most-used
 * weight; its body text is never regular-weight). Both families are
 * instanced static TTFs of the official google/fonts variable fonts
 * (OFL — see THIRD_PARTY_NOTICES.md) in `res/font/`:
 * Archivo 700/800/900, Manrope 400/500/600/700/800.
 *
 * FIDELITY PASS (15 Nov): the colour scheme below is COMPLETE — every
 * role of [lightColorScheme] is set explicitly. The first redesign
 * build left the surface-container / surfaceTint / inverse roles
 * unset, so every Card fell back to the Material baseline scheme's
 * purple-tinted containers and the whole app looked lavender-grey on
 * device instead of the preview's clean white cards. No role is left
 * to a default again: surfaces are warm whites/neutrals, and
 * surfaceTint is pinned to the scheme's own primary so any tonal
 * overlay tints red-family, never baseline purple.
 */
object RescueColors {
    val RescueRed = Color(0xFFE5322D)
    val SafeGreen = Color(0xFF1FA97A)
    val MintTint = Color(0xFFDFF5EA)
    val WarmBackground = Color(0xFFFAF7F4)
    val Ink = Color(0xFF15181D)
    val Muted = Color(0xFF6B7280)
    val CardWhite = Color.White
    val OutlineSoft = Color(0xFFDDD6CE)
    val SurfaceVariantSoft = Color(0xFFF3EEE7)
}

/** Display typeface of the preview: Archivo (700/800/900 bundled). */
val ArchivoFamily = FontFamily(
    Font(R.font.archivo_bold, FontWeight.Bold),
    Font(R.font.archivo_extrabold, FontWeight.ExtraBold),
    Font(R.font.archivo_black, FontWeight.Black)
)

/** UI/body typeface of the preview: Manrope (400–800 bundled). */
val ManropeFamily = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
    Font(R.font.manrope_extrabold, FontWeight.ExtraBold)
)

private val RescueColorScheme = lightColorScheme(
    // Primary — Rescue Red family.
    primary = RescueColors.RescueRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFDE3E1),
    onPrimaryContainer = Color(0xFF5F1410),
    // Secondary — Safe Green family (I Am Safe, entry hero, active nav).
    secondary = RescueColors.SafeGreen,
    onSecondary = Color.White,
    secondaryContainer = RescueColors.MintTint,
    onSecondaryContainer = Color(0xFF0B3D2C),
    // Tertiary — warm neutral, for quiet accents only.
    tertiary = RescueColors.Muted,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEFEAE4),
    onTertiaryContainer = RescueColors.Ink,
    // Error family.
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    // Base surfaces — the warm off-white page, ink text.
    background = RescueColors.WarmBackground,
    onBackground = RescueColors.Ink,
    surface = RescueColors.CardWhite,
    onSurface = RescueColors.Ink,
    // Warm neutrals only — no purple anywhere in the variant family.
    surfaceVariant = RescueColors.SurfaceVariantSoft,
    onSurfaceVariant = Color(0xFF5C554E),
    // The container ladder the Cards actually read: white at the top,
    // stepping down through warm neutrals. THIS is what killed the
    // lavender look — surfaceContainerLowest is pure white.
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFDFBF9),
    surfaceContainer = Color(0xFFF7F3EF),
    surfaceContainerHigh = Color(0xFFF1ECE7),
    surfaceContainerHighest = Color(0xFFEAE4DE),
    surfaceBright = Color(0xFFFFFDF9),
    surfaceDim = Color(0xFFE7E0D8),
    // Pinned explicitly: an unset surfaceTint inherits the Material
    // baseline (purple) tint in component overlays.
    surfaceTint = RescueColors.RescueRed,
    outline = Color(0xFFC9C2BA),
    outlineVariant = Color(0xFFE3DDD5),
    inverseSurface = Color(0xFF2B2724),
    inverseOnSurface = Color(0xFFF5F0EA),
    inversePrimary = Color(0xFFFFB4A8),
    scrim = Color.Black
)

private val RescueTypography = Typography(
    // Display/headlines: Archivo, the preview's heavy weights.
    headlineLarge = TextStyle(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.Black,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.01).em
    ),
    headlineMedium = TextStyle(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.01).em
    ),
    headlineSmall = TextStyle(
        fontFamily = ArchivoFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.01).em
    ),
    // Titles: Manrope 600–700.
    titleLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp
    ),
    titleMedium = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    // Body: Manrope SemiBold — the preview's body text is never
    // regular-weight (600 is its most-used weight); only the small
    // meta size steps down to Medium.
    bodyLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelLarge = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    // Section labels: the references' small-caps look (callers pass
    // already-uppercased text; the style adds the tracking).
    labelSmall = TextStyle(
        fontFamily = ManropeFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.08.em
    )
)

// Radii from the exported preview HTML: cards 22, chips 16,
// small elements 14, buttons/nav pills fully round.
private val RescueShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(22.dp)
)

/** The app theme — MainActivity wraps the whole UI in this. */
@Composable
fun RescueMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RescueColorScheme,
        typography = RescueTypography,
        shapes = RescueShapes,
        content = content
    )
}
