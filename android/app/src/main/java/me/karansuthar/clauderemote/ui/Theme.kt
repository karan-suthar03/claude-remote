package me.karansuthar.clauderemote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------- brand

/** The Claude-style coral, used as-is for the brand mark. */
val BrandCoral = Color(0xFFD97757)

/** Monospace family for paths, code and the brand mark. */
val MonoFamily: FontFamily = FontFamily.Monospace

/** Text style for code blocks and inline code. */
val CodeTextStyle = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 19.sp,
    letterSpacing = 0.sp,
)

// ---------------------------------------------------------------- colors

// Light: paper neutrals. Primary is a deeper terracotta so white labels on it pass 4.5:1.
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFFB85533),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF7E3DA),
    onPrimaryContainer = Color(0xFF5C2412),
    inversePrimary = Color(0xFFF0A285),
    secondary = Color(0xFF4E7356),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE8DD),
    onSecondaryContainer = Color(0xFF1F3324),
    tertiary = Color(0xFF9A6400),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF6E5C6),
    onTertiaryContainer = Color(0xFF3E2A00),
    background = Color(0xFFFAF9F7),
    onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFAF9F7),
    onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFE6E2DD),
    onSurfaceVariant = Color(0xFF6B6661),
    surfaceTint = Color(0xFFB85533),
    inverseSurface = Color(0xFF2B2927),
    inverseOnSurface = Color(0xFFF3F1EE),
    error = Color(0xFFC2483D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF8DCD8),
    onErrorContainer = Color(0xFF5B1A12),
    outline = Color(0xFF9A948E),
    outlineVariant = Color(0xFFDDD8D2),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAF9F7),
    surfaceDim = Color(0xFFE3DFDA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F4F1),
    surfaceContainer = Color(0xFFF3F1EE),
    surfaceContainerHigh = Color(0xFFECE9E5),
    surfaceContainerHighest = Color(0xFFE6E2DD),
)

// Dark: near-black warm neutrals; the brand coral with near-black labels (4.9:1).
private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFD97757),
    onPrimary = Color(0xFF1A1311),
    primaryContainer = Color(0xFF3D2820),
    onPrimaryContainer = Color(0xFFF5CDBE),
    inversePrimary = Color(0xFFB85533),
    secondary = Color(0xFF8DB394),
    onSecondary = Color(0xFF10180F),
    secondaryContainer = Color(0xFF2A3A2D),
    onSecondaryContainer = Color(0xFFCDE3D0),
    tertiary = Color(0xFFE5A84B),
    onTertiary = Color(0xFF1F1400),
    tertiaryContainer = Color(0xFF3D2F17),
    onTertiaryContainer = Color(0xFFF4D9A8),
    background = Color(0xFF151413),
    onBackground = Color(0xFFEDE8E3),
    surface = Color(0xFF151413),
    onSurface = Color(0xFFEDE8E3),
    surfaceVariant = Color(0xFF33302E),
    onSurfaceVariant = Color(0xFFA39E98),
    surfaceTint = Color(0xFFD97757),
    inverseSurface = Color(0xFFEDE8E3),
    inverseOnSurface = Color(0xFF232120),
    error = Color(0xFFE8806F),
    onError = Color(0xFF2A0E0A),
    errorContainer = Color(0xFF4A1F1A),
    onErrorContainer = Color(0xFFF6CBC4),
    outline = Color(0xFF6B6560),
    outlineVariant = Color(0xFF3A3735),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2B2927),
    surfaceDim = Color(0xFF151413),
    surfaceContainerLowest = Color(0xFF100F0E),
    surfaceContainerLow = Color(0xFF1C1B1A),
    surfaceContainer = Color(0xFF232120),
    surfaceContainerHigh = Color(0xFF2B2927),
    surfaceContainerHighest = Color(0xFF33302E),
)

// ---------------------------------------------------------------- type

private val Sans: FontFamily = FontFamily.SansSerif

private fun style(weight: FontWeight, size: Int, line: Int, spacing: Double): TextStyle = TextStyle(
    fontFamily = Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

val AppTypography = Typography(
    displayLarge = style(FontWeight.SemiBold, 57, 64, -0.8),
    displayMedium = style(FontWeight.SemiBold, 45, 52, -0.6),
    displaySmall = style(FontWeight.SemiBold, 36, 44, -0.4),
    headlineLarge = style(FontWeight.SemiBold, 32, 40, -0.4),
    headlineMedium = style(FontWeight.SemiBold, 28, 36, -0.3),
    headlineSmall = style(FontWeight.SemiBold, 24, 32, -0.2),
    titleLarge = style(FontWeight.SemiBold, 20, 28, -0.1),
    titleMedium = style(FontWeight.SemiBold, 16, 24, 0.0),
    titleSmall = style(FontWeight.SemiBold, 14, 20, 0.0),
    bodyLarge = style(FontWeight.Normal, 16, 24, 0.0),
    bodyMedium = style(FontWeight.Normal, 15, 22, 0.0),
    bodySmall = style(FontWeight.Normal, 13, 18, 0.1),
    labelLarge = style(FontWeight.Medium, 14, 20, 0.1),
    labelMedium = style(FontWeight.Medium, 12, 16, 0.2),
    labelSmall = style(FontWeight.Medium, 11, 16, 0.2),
)

// ---------------------------------------------------------------- shapes

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

// ---------------------------------------------------------------- shared bits

/** The ">_" brand mark on a coral rounded square. */
@Composable
internal fun BrandMark(size: Dp, fontSize: TextUnit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .background(BrandCoral, RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ">_",
            color = Color.White,
            fontFamily = MonoFamily,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize,
            letterSpacing = 0.sp,
        )
    }
}

/** A small muted uppercase section label. */
@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        fontSize = 12.sp,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
