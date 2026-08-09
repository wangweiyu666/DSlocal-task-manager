package com.ds.localtaskmanager.ui.today

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.DstApplication
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.ExecutionState
import com.ds.localtaskmanager.sharing.GeneratedShareImage
import com.ds.localtaskmanager.sharing.ResultShareTaskFilter
import com.ds.localtaskmanager.sharing.ShareImageRenderer
import com.ds.localtaskmanager.ui.result.ResultGroupPresentation
import com.ds.localtaskmanager.ui.result.ResultPresentation
import com.ds.localtaskmanager.ui.result.ResultTaskPresentation
import com.ds.localtaskmanager.ui.execution.ExecutionUiState
import com.ds.localtaskmanager.ui.execution.TaskDetailScreen
import com.ds.localtaskmanager.ui.theme.DstTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

class W24ResultScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun resultShowsSnapshotAndTasksAreReadOnly() {
        composeRule.setContent {
            DstTheme { ResultContent(w24ResultSample(), onClose = {}) }
        }

        composeRule.onNodeWithText("今日结果").assertIsDisplayed()
        composeRule.onNodeWithText("完成项目复盘").assertIsDisplayed().assertHasNoClickAction()
        composeRule.onNodeWithText("本日总积分 +13").assertIsDisplayed()
        composeRule.onNodeWithText("继续上滑返回今日").assertIsDisplayed()
    }

    @Test
    fun pullGestureOpensResult() {
        var opened = 0
        composeRule.setContent {
            DstTheme {
                ResultPullContainer(atTop = { true }, onOpenResult = { opened++ }) { modifier ->
                    androidx.compose.foundation.lazy.LazyColumn(modifier) {
                        item { androidx.compose.material3.Text("今日任务") }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("result-pull-container").performTouchInput { swipeDown() }
        composeRule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun slowOneHundredTenDpPullOpensResultOnRelease() {
        var opened = 0
        var pullDistancePx = 0f
        composeRule.setContent {
            pullDistancePx = with(LocalDensity.current) { 110.dp.toPx() }
            DstTheme {
                ResultPullContainer(atTop = { true }, onOpenResult = { opened++ }) { modifier ->
                    androidx.compose.foundation.lazy.LazyColumn(modifier) {
                        item { androidx.compose.material3.Text("今日任务") }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("result-pull-container").performTouchInput {
            val start = Offset(center.x, height * 0.2f)
            swipe(start, start + Offset(0f, pullDistancePx), durationMillis = 1_200)
        }
        composeRule.waitForIdle()
        assertEquals(1, opened)
    }

    @Test
    fun endGestureClosesResult() {
        var closed = 0
        composeRule.setContent {
            DstTheme {
                ResultContent(w24ResultSample(), onClose = { closed++ })
            }
        }
        composeRule.onNodeWithTag("today-result-content").performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        assertEquals(1, closed)
    }

    @Test
    fun emptyResultHasNoShareActions() {
        composeRule.setContent { DstTheme { ResultEmpty(onClose = {}) } }
        composeRule.onNodeWithText("今天还没有可生成的结果").assertIsDisplayed()
    }

    @Test
    fun informationActionsAreAvailableForNonEmptyBody() {
        var copied = 0
        var shared = 0
        composeRule.setContent {
            DstTheme {
                TaskDetailScreen(
                    state = ExecutionUiState(
                        loading = false,
                        instance = informationInstance(),
                        execution = ExecutionState.Information("正文", null),
                        informationDraft = "正文",
                    ),
                    onBack = {}, onRetry = {}, onStepChange = { _, _ -> }, onCounterChange = {},
                    onTimerToggle = {}, onInformationChange = {}, onInformationSave = {}, onNoteChange = {},
                    onComplete = {}, onUndo = {}, onDismissCompletion = {}, onDismissError = {},
                    onCopyInformation = { copied++ }, onShareInformation = { shared++ }, readOnly = true,
                )
            }
        }

        composeRule.onNodeWithText("复制正文").performClick()
        composeRule.onNodeWithText("分享图片").performClick()
        assertEquals(1, copied)
        assertEquals(1, shared)
    }

    @Test
    fun sharePreviewActionsAreFullyVisible() {
        val app = ApplicationProvider.getApplicationContext<DstApplication>()
        val image = GeneratedShareImage(Bitmap.createBitmap(108, 192, Bitmap.Config.ARGB_8888), "preview.png")
        composeRule.setContent {
            DstTheme {
                com.ds.localtaskmanager.ui.sharing.SharePreviewDialog(
                    image = image,
                    service = app.shareImageService,
                    sensitive = false,
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("保存到相册").assertIsDisplayed()
        composeRule.onNodeWithText("发送给其他应用").assertIsDisplayed()
    }

    @Test
    fun resultSharePreviewOffersTaskFilters() {
        val app = ApplicationProvider.getApplicationContext<DstApplication>()
        val image = GeneratedShareImage(Bitmap.createBitmap(108, 192, Bitmap.Config.ARGB_8888), "preview.png")
        composeRule.setContent {
            DstTheme {
                com.ds.localtaskmanager.ui.sharing.SharePreviewDialog(
                    image = image,
                    service = app.shareImageService,
                    sensitive = false,
                    onResultFilterChange = { _: ResultShareTaskFilter -> image },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("全部任务").assertIsDisplayed()
        composeRule.onNodeWithText("仅未完成").assertIsDisplayed().performClick()
    }

    @Test
    fun cachedShareImageUsesContentUri() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DstApplication>()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val uri = app.shareImageService.cache(GeneratedShareImage(bitmap, "test.png"))
        assertEquals("content", uri.scheme)
        assertTrue(uri.authority.orEmpty().endsWith(".files"))
    }

    @Test
    fun generatedImageCanBeSavedToGalleryOnModernAndroid() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val app = ApplicationProvider.getApplicationContext<DstApplication>()
        val image = GeneratedShareImage(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), "w24-test.png")
        val uri = app.shareImageService.saveToGallery(image)
        try {
            assertEquals("content", uri.scheme)
        } finally {
            app.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun oneHundredTaskImageRendersWithinTwoSeconds() {
        val result = ResultPresentation(
            "2026-07-22", "2026年7月22日 星期三", "Dom", "全部完成", 100,
            listOf(
                ResultGroupPresentation(
                    "工作", "全部完成", 100, null,
                    List(100) { ResultTaskPresentation("任务 $it", "必做", "已完成", 1) },
                ),
            ),
        )
        val started = android.os.SystemClock.elapsedRealtime()
        val image = ShareImageRenderer().renderResult(result)
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        assertEquals(1080, image.bitmap.width)
        assertTrue("render took ${elapsed}ms", elapsed < 2_000)
    }

    private fun informationInstance() = TaskInstanceEntity(
        taskId = "Information00001",
        occurrenceKey = "once",
        name = "填写反馈",
        description = "",
        taskDate = "2026-07-22",
        deadline = null,
        groupId = null,
        required = true,
        points = 5,
        sortOrder = null,
        completionMessage = "",
        status = TaskStatus.COMPLETED.name,
        completedAtEpochMillis = 1,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        executionKind = "INFORMATION",
    )
}
