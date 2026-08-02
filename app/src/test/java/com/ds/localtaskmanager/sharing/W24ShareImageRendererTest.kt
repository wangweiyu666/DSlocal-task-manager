package com.ds.localtaskmanager.sharing

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
