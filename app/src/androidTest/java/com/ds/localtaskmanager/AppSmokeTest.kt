package com.ds.localtaskmanager

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchShowsTodayScreenAndPrimaryNavigation() {
        composeRule.onNodeWithText("历史").assertIsDisplayed()
        composeRule.onNodeWithText("我的").assertIsDisplayed()
        composeRule.onNodeWithText("还没有任务，点击右下角导入。").assertIsDisplayed()
    }

    @Test
    fun importFabOpensDst1Dialog() {
        composeRule.onNodeWithContentDescription("导入任务").performClick()
        composeRule.onNodeWithText("导入任务").assertIsDisplayed()
        composeRule.onNodeWithText("粘贴任务内容").assertIsDisplayed()
    }

    @Test
    fun settingsShowsUserInitiatedReminderPermissionEntry() {
        composeRule.onNodeWithText("我的").performClick()
        composeRule.onNodeWithContentDescription("设置").performClick()
        composeRule.onNodeWithText("设置").assertIsDisplayed()
        composeRule.onNodeWithText("任务提醒").assertIsDisplayed()
    }
}
