package io.github.xxcay.clipboard.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Palette {
    val Orange = Color(0xFFFF6A1F)
    val OrangeLight = Color(0xFFFF9040)
    val OrangeDeep = Color(0xFFFF5A1F)
    val Soft = Color(0xFFFFF1E7)
    val Softer = Color(0xFFFFF7F2)
    val Ink = Color(0xFF1C1917)
    val Muted = Color(0xFF8C837D)
    val Line = Color(0xFFF1E9E3)
    val Bg = Color(0xFFFFFBF8)
    val Danger = Color(0xFFE5484D)
    val DangerSoft = Color(0xFFFFF0F0)
    val Ok = Color(0xFF30A46C)

    val Gradient = Brush.linearGradient(listOf(OrangeLight, OrangeDeep))
}

private val Colors = lightColorScheme(
    primary = Palette.Orange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE6D6),
    onPrimaryContainer = Color(0xFF5C1D00),
    secondary = Color(0xFF9A5B36),
    onSecondary = Color.White,
    secondaryContainer = Palette.Soft,
    onSecondaryContainer = Color(0xFF5C1D00),
    tertiary = Palette.Orange,
    background = Palette.Bg,
    onBackground = Palette.Ink,
    surface = Color.White,
    onSurface = Palette.Ink,
    surfaceVariant = Color(0xFFF7F2EE),
    onSurfaceVariant = Palette.Muted,
    surfaceTint = Color.White,
    outline = Color(0xFFE8DFD8),
    outlineVariant = Palette.Line,
    error = Palette.Danger,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFAF6),
    surfaceContainer = Color(0xFFFFF7F1),
    surfaceContainerHigh = Color(0xFFFFF3EB),
    surfaceContainerHighest = Color(0xFFFFEFE4),
)

private val Type = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ClipTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, shapes = AppShapes, content = content)
}

val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, color = Palette.Orange)
