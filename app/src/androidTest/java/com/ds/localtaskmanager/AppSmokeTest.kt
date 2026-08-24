package com.ds.localtaskmanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeFalse
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchShowsTodayScreenAndPrimaryNavigation() {
        if (BuildConfig.CONNECTED_BUILD) {
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithText("DStationery 联网版").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("DStationery 联网版").assertIsDisplayed()
            composeRule.onNodeWithText("执行者邮箱").assertIsDisplayed()
            return
        }
        composeRule.onNodeWithText("历史").assertIsDisplayed()
        composeRule.onNodeWithText("我的").assertIsDisplayed()
        composeRule.onNodeWithText("还没有任务，点击右下角导入。").assertIsDisplayed()
    }

    @Test
    fun importFabOpensDst1Dialog() {
        assumeFalse(BuildConfig.CONNECTED_BUILD)
        composeRule.onNodeWithContentDescription("导入任务").performClick()
        composeRule.onNodeWithText("导入任务").assertIsDisplayed()
        composeRule.onNodeWithText("粘贴任务内容").assertIsDisplayed()
    }

    @Test
    fun settingsShowsUserInitiatedReminderPermissionEntry() {
        assumeFalse(BuildConfig.CONNECTED_BUILD)
        composeRule.onNodeWithText("我的").performClick()
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
        composeRule.onNodeWithText("任务提醒").assertIsDisplayed()
    }

    @Test
    fun systemBackFromSettingsReturnsToProfile() {
        assumeFalse(BuildConfig.CONNECTED_BUILD)
        composeRule.onNodeWithText("我的").performClick()
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.onNodeWithTag("settings-screen").assertIsDisplayed()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }

        composeRule.onNodeWithTag("profile-settings").assertIsDisplayed()
    }
}
