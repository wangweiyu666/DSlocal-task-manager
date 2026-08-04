package com.ds.localtaskmanager.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ds.localtaskmanager.settings.AppSettings
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.ui.profile.ProfileHeader
import com.ds.localtaskmanager.ui.theme.DstTheme

@PreviewTest
@Preview(name = "W26 · 我的设置入口", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun ProfileSettingsEntryScreenshot() {
    DstTheme(themeMode = AppThemeMode.LIGHT) {
        Column(Modifier.padding(20.dp)) { ProfileHeader("示例 Dom", onSettings = {}) }
    }
}

@PreviewTest
@Preview(name = "W26 · 设置浅色", showBackground = true, widthDp = 393, heightDp = 1000)
@Composable
fun SettingsLightScreenshot() {
    DstTheme(themeMode = AppThemeMode.LIGHT) {
        SettingsPreviewContent(AppThemeMode.LIGHT)
    }
}

@PreviewTest
@Preview(name = "W26 · 设置深色", showBackground = true, widthDp = 393, heightDp = 1000)
@Composable
fun SettingsDarkScreenshot() {
    DstTheme(themeMode = AppThemeMode.DARK) {
        SettingsPreviewContent(AppThemeMode.DARK)
    }
}

@PreviewTest
@Preview(name = "W26 · 设置大字体", showBackground = true, widthDp = 393, heightDp = 1000, fontScale = 1.6f)
@Composable
fun SettingsLargeFontScreenshot() {
    DstTheme(themeMode = AppThemeMode.LIGHT) {
        SettingsPreviewContent(AppThemeMode.SYSTEM)
    }
}

@Composable
private fun SettingsPreviewContent(themeMode: AppThemeMode) {
    SettingsScreen(
        settings = AppSettings(
            themeMode = themeMode,
            informationPrivacyConfirmed = true,
        ),
        notificationsEnabled = true,
        appVersion = "0.1.0-alpha",
        onBack = {},
        onThemeMode = {},
        onReduceMotion = {},
        onNotificationAction = {},
        onResetPrivacy = {},
    )
}
