package com.ds.localtaskmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Indigo = Color(0xFF6366F1)
private val LightIndigo = Color(0xFF818CF8)
private val IndigoContainer = Color(0xFFE0E7FF)
private val AppBackground = Color(0xFFF8FAFC)
private val Slate = Color(0xFF1E293B)
private val Pink = Color(0xFFF472B6)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = IndigoContainer,
    onPrimaryContainer = Slate,
    secondary = LightIndigo,
    onSecondary = Slate,
    secondaryContainer = IndigoContainer,
    onSecondaryContainer = Slate,
    tertiary = Pink,
    onTertiary = Slate,
    tertiaryContainer = Color(0xFFFCE7F3),
    onTertiaryContainer = Slate,
    background = AppBackground,
    onBackground = Slate,
    surface = AppBackground,
    onSurface = Slate,
    surfaceVariant = IndigoContainer,
    onSurfaceVariant = Color(0xFF475569),
    outline = LightIndigo,
    outlineVariant = Color(0xFFC7D2FE),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = AppBackground,
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE9EDF5),
    surfaceContainerHighest = IndigoContainer,
    inverseSurface = Slate,
    inverseOnSurface = AppBackground,
    inversePrimary = LightIndigo,
    surfaceTint = Indigo,
)

private val DarkColors = darkColorScheme(
    primary = LightIndigo,
    onPrimary = Slate,
    primaryContainer = Indigo,
    onPrimaryContainer = AppBackground,
    secondary = IndigoContainer,
    onSecondary = Slate,
    secondaryContainer = Color(0xFF4548AE),
    onSecondaryContainer = AppBackground,
    tertiary = Pink,
    onTertiary = Slate,
    tertiaryContainer = Color(0xFF6E3053),
    onTertiaryContainer = Color(0xFFFCE7F3),
    background = Slate,
    onBackground = AppBackground,
    surface = Slate,
    onSurface = AppBackground,
    surfaceVariant = Color(0xFF334155),
    onSurfaceVariant = IndigoContainer,
    outline = LightIndigo,
    outlineVariant = Color(0xFF475569),
    surfaceContainerLowest = Color(0xFF172033),
    surfaceContainerLow = Color(0xFF263449),
    surfaceContainer = Color(0xFF2B394E),
    surfaceContainerHigh = Color(0xFF334155),
    surfaceContainerHighest = Color(0xFF3B4A60),
    inverseSurface = AppBackground,
    inverseOnSurface = Slate,
    inversePrimary = Indigo,
    surfaceTint = LightIndigo,
)

private val DstTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun DstTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colors,
        typography = DstTypography,
        content = content,
    )
}
