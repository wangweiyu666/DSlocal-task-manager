package com.ds.localtaskmanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.settings.UiPalette

val LocalReduceMotion = staticCompositionLocalOf { false }

private val Indigo = Color(0xFF818CF8)
private val LightIndigo = Color(0xFF818CF8)
private val IndigoContainer = Color(0xFFE0E7FF)
private val AppBackground = Color(0xFFF8FAFC)
private val Slate = Color(0xFF1E293B)
private val Pink = Color(0xFFF472B6)
private val ErrorRed = Color(0xFFB9505A)
private val DarkErrorRed = Color(0xFFFFB3B8)
private val ErrorContainer = Color(0xFFF9DDE0)
private val DarkErrorContainer = Color(0xFF7D2934)

private val SkyBlue = Color(0xFF78A4CB)
private val SkySecondary = Color(0xFF95BDD7)
private val SkyContainer = Color(0xFFB4E1EB)
private val SkyYellow = Color(0xFFF9E8A2)

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
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainer,
    onErrorContainer = Color(0xFF6E2630),
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
    error = DarkErrorRed,
    onError = Color(0xFF5F111D),
    errorContainer = DarkErrorContainer,
    onErrorContainer = Color(0xFFFFD9DC),
)

private val SkyLightColors = lightColorScheme(
    primary = SkyBlue,
    onPrimary = Slate,
    primaryContainer = SkyContainer,
    onPrimaryContainer = Slate,
    secondary = SkySecondary,
    onSecondary = Slate,
    secondaryContainer = Color(0xFFD9EDF3),
    onSecondaryContainer = Slate,
    tertiary = SkyYellow,
    onTertiary = Slate,
    tertiaryContainer = Color(0xFFFFF3C4),
    onTertiaryContainer = Slate,
    background = AppBackground,
    onBackground = Slate,
    surface = AppBackground,
    onSurface = Slate,
    surfaceVariant = SkyContainer,
    onSurfaceVariant = Color(0xFF455A69),
    outline = SkyBlue,
    outlineVariant = SkySecondary,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = AppBackground,
    surfaceContainer = Color(0xFFF0F6F8),
    surfaceContainerHigh = Color(0xFFE4F0F3),
    surfaceContainerHighest = Color(0xFFD9EDF3),
    inverseSurface = Slate,
    inverseOnSurface = AppBackground,
    inversePrimary = SkySecondary,
    surfaceTint = SkyBlue,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorContainer,
    onErrorContainer = Color(0xFF6E2630),
)

private val SkyDarkColors = darkColorScheme(
    primary = SkyContainer,
    onPrimary = Slate,
    primaryContainer = Color(0xFF365F82),
    onPrimaryContainer = Color(0xFFE3F4F8),
    secondary = SkySecondary,
    onSecondary = Slate,
    secondaryContainer = Color(0xFF395D70),
    onSecondaryContainer = Color(0xFFD9EDF3),
    tertiary = SkyYellow,
    onTertiary = Color(0xFF3A3212),
    tertiaryContainer = Color(0xFF665B27),
    onTertiaryContainer = Color(0xFFFFF3C4),
    background = Slate,
    onBackground = AppBackground,
    surface = Slate,
    onSurface = AppBackground,
    surfaceVariant = Color(0xFF334A5A),
    onSurfaceVariant = Color(0xFFD9EDF3),
    outline = SkySecondary,
    outlineVariant = Color(0xFF526A78),
    surfaceContainerLowest = Color(0xFF172033),
    surfaceContainerLow = Color(0xFF243442),
    surfaceContainer = Color(0xFF293B49),
    surfaceContainerHigh = Color(0xFF304552),
    surfaceContainerHighest = Color(0xFF38505E),
    inverseSurface = AppBackground,
    inverseOnSurface = Slate,
    inversePrimary = SkyBlue,
    surfaceTint = SkyContainer,
    error = DarkErrorRed,
    onError = Color(0xFF5F111D),
    errorContainer = DarkErrorContainer,
    onErrorContainer = Color(0xFFFFD9DC),
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
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    uiPalette: UiPalette = UiPalette.INDIGO,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val colors = when (uiPalette) {
        UiPalette.INDIGO -> if (darkTheme) DarkColors else LightColors
        UiPalette.SKY -> if (darkTheme) SkyDarkColors else SkyLightColors
    }
    CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
        MaterialTheme(
            colorScheme = colors,
            typography = DstTypography,
            content = content,
        )
    }
}
