package com.ds.localtaskmanager.ui.execution

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.ui.theme.DstTheme

@PreviewTest
@Preview(name = "体温未选择", showBackground = true, widthDp = 393)
@Composable
fun TemperatureUnselectedScreenshot() = DstTheme(themeMode = AppThemeMode.LIGHT) {
    TemperatureSection(null, true, NoteSaveState.SAVED, {}, {})
}

@PreviewTest
@Preview(name = "体温已选择", showBackground = true, widthDp = 393)
@Composable
fun TemperatureSelectedScreenshot() = DstTheme(themeMode = AppThemeMode.LIGHT) {
    TemperatureSection(365, true, NoteSaveState.SAVED, {}, {})
}

@PreviewTest
@Preview(name = "体温大字体", showBackground = true, widthDp = 320, fontScale = 1.5f)
@Composable
fun TemperatureLargeFontScreenshot() = DstTheme(themeMode = AppThemeMode.LIGHT) {
    TemperatureSection(420, true, NoteSaveState.ERROR, {}, {})
}
