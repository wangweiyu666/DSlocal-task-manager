package com.ds.localtaskmanager.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.ds.localtaskmanager.settings.AppSettings
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.settings.UiPalette
import com.ds.localtaskmanager.ui.theme.DstTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class W26SettingsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun appearanceAndReducedMotionActionsAreExposed() {
        var selectedTheme = AppThemeMode.SYSTEM
        var selectedPalette = UiPalette.INDIGO
        var reducedMotion = false
        composeRule.setContent {
            DstTheme {
                SettingsScreen(
                    settings = AppSettings(),
                    notificationsEnabled = false,
                    appVersion = "0.1.0-alpha",
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onThemeMode = { selectedTheme = it },
                    onUiPalette = { selectedPalette = it },
                    onReduceMotion = { reducedMotion = it },
                    onNotificationAction = {},
                    onResetPrivacy = {},
                )
            }
        }

        composeRule.onNodeWithText("设置").assertIsDisplayed()
        composeRule.onNodeWithText("深色").performClick()
        composeRule.onNodeWithTag("palette-sky").performClick()
        composeRule.onNodeWithTag("reduce-motion-switch").performClick()
        assertEquals(AppThemeMode.DARK, selectedTheme)
        assertEquals(UiPalette.SKY, selectedPalette)
        assertTrue(reducedMotion)
    }

    @Test
    fun confirmedPrivacyCanBeReset() {
        var reset = false
        composeRule.setContent {
            DstTheme {
                SettingsScreen(
                    settings = AppSettings(informationPrivacyConfirmed = true),
                    notificationsEnabled = true,
                    appVersion = "0.1.0-alpha",
                    onBack = {}, onThemeMode = {}, onReduceMotion = {}, onNotificationAction = {},
                    onResetPrivacy = { reset = true },
                )
            }
        }

        composeRule.onNodeWithTag("reset-share-privacy")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertTrue(reset) }
    }
}
