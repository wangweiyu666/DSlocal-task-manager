package com.ds.localtaskmanager.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ds.localtaskmanager.settings.AppSettings
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.settings.UiPalette
import com.ds.localtaskmanager.ui.theme.DstTheme

@PreviewTest
@Preview(name = "W33 · 晴空浅色", showBackground = true, widthDp = 393, heightDp = 1000)
@Composable
fun SkyPaletteLightScreenshot() = SkyPaletteSettings(AppThemeMode.LIGHT)

@PreviewTest
@Preview(name = "W33 · 晴空深色", showBackground = true, widthDp = 393, heightDp = 1000)
@Composable
fun SkyPaletteDarkScreenshot() = SkyPaletteSettings(AppThemeMode.DARK)

@Composable
private fun SkyPaletteSettings(themeMode: AppThemeMode) {
    DstTheme(themeMode = themeMode, uiPalette = UiPalette.SKY) {
        SettingsScreen(
            settings = AppSettings(themeMode = themeMode, uiPalette = UiPalette.SKY),
            notificationsEnabled = false,
            appVersion = "0.1.0-alpha.2",
            onBack = {},
            onThemeMode = {},
            onUiPalette = {},
            onReduceMotion = {},
            onNotificationAction = {},
            onResetPrivacy = {},
        )
    }
}
