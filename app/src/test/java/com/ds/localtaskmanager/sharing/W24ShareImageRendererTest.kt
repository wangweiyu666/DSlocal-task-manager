package com.ds.localtaskmanager.sharing

import com.ds.localtaskmanager.settings.UiPalette
import com.ds.localtaskmanager.ui.result.ResultGroupPresentation
import com.ds.localtaskmanager.ui.result.ResultPresentation
import com.ds.localtaskmanager.ui.result.ResultTaskPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class W24ShareImageRendererTest {
    private val renderer = ShareImageRenderer()

    @Test
    fun resultAndInformationImagesUseStableWidthAndNames() {
        val result = renderer.renderResult(resultPresentation(3))
        val information = renderer.renderInformation("告知/测试", "2026-07-22", "Dom", "第一行\n第二行")

        assertEquals(1080, result.bitmap.width)
        assertTrue(result.bitmap.height > 0)
        assertEquals("今日结果-2026-07-22.png", result.fileName)
        assertEquals("告知-告知-测试-2026-07-22.png", information.fileName)
    }

    @Test(expected = ShareImageTooLargeException::class)
    fun oversizedResultIsRejectedWithoutTruncation() {
        renderer.renderResult(resultPresentation(2_000))
    }

    @Test
    fun unsafeFileNameCharactersAreRemoved() {
        assertEquals("a-b-c", ShareImageRenderer.sanitizeFileName("a/b:c"))
    }

    @Test
    fun skyPaletteChangesShareImageAccent() {
        renderer.renderResult(resultPresentation(1), UiPalette.SKY)

        assertEquals(0xFF78A4CB.toInt(), UiPalette.SKY.sharePrimaryColor)
    }

    @Test
    fun incompleteFilterHidesCompletedTasksAndEmptyGroups() {
        val result = resultPresentation(3).copy(
            status = "尚未完成",
            groups = listOf(
                resultPresentation(3).groups.single().copy(
                    status = "尚未完成",
                    tasks = listOf(
                        ResultTaskPresentation("完成任务", "必做", "已完成", 1),
                        ResultTaskPresentation("待办任务", "必做", "待完成", 0),
                        ResultTaskPresentation("错过任务", "选做", "未完成", -1),
                    ),
                ),
                ResultGroupPresentation(
                    name = "已完成分组",
                    status = "全部完成",
                    points = 1,
                    message = null,
                    tasks = listOf(ResultTaskPresentation("已完成", "必做", "已完成", 1)),
                ),
            ),
        )
        val filtered = renderer.renderResult(result, taskFilter = ResultShareTaskFilter.INCOMPLETE_ONLY)
        val expectedVisibleShape = renderer.renderResult(
            result.copy(
                groups = listOf(
                    result.groups.first().copy(tasks = result.groups.first().tasks.drop(1)),
                ),
            ),
        )

        assertEquals(expectedVisibleShape.bitmap.height, filtered.bitmap.height)
    }

    @Test
    fun allCompleteIncompleteFilterRendersCompactCompletionCard() {
        val full = renderer.renderResult(resultPresentation(3))
        val filtered = renderer.renderResult(
            resultPresentation(3),
            taskFilter = ResultShareTaskFilter.INCOMPLETE_ONLY,
        )

        assertTrue(filtered.bitmap.height < full.bitmap.height)
        assertEquals(1080, filtered.bitmap.width)
    }

    @Test
    fun longInformationBodyIncreasesImageHeightWithoutTruncation() {
        val short = renderer.renderInformation("告知", "2026-07-22", "Dom", "一行")
        val long = renderer.renderInformation("告知", "2026-07-22", "Dom", List(12) { "完整正文" }.joinToString("\n"))

        assertTrue(long.bitmap.height > short.bitmap.height)
    }

    private fun resultPresentation(count: Int) = ResultPresentation(
        taskDate = "2026-07-22",
        dateLabel = "2026年7月22日 星期三",
        domName = "Dom",
        status = "全部完成",
        totalPoints = count,
        groups = listOf(
            ResultGroupPresentation(
                name = "工作",
                status = "全部完成",
                points = count,
                message = "完成文案",
                tasks = List(count) { ResultTaskPresentation("任务 $it", "必做", "已完成", 1) },
            ),
        ),
    )
}
