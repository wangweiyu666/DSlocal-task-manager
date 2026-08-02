package com.ds.localtaskmanager.ui.today

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.ds.localtaskmanager.sharing.ShareImageRenderer
import com.ds.localtaskmanager.ui.result.ResultGroupPresentation
import com.ds.localtaskmanager.ui.result.ResultPresentation
import com.ds.localtaskmanager.ui.result.ResultTaskPresentation
import com.ds.localtaskmanager.ui.theme.DstTheme

@Preview(name = "W24 · 今日结果", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun TodayResultPreview() {
    DstTheme {
        Scaffold(
            bottomBar = {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({}, Modifier.weight(1f)) { Text("复制结果") }
                    Button({}, Modifier.weight(1f)) { Text("分享图片") }
                }
            },
        ) { padding ->
            ResultContent(w24ResultSample(), onClose = {}, modifier = Modifier.padding(padding), previewMode = true)
        }
    }
}

@Preview(name = "W24 · 空结果", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun TodayResultEmptyPreview() {
    DstTheme { ResultEmpty(onClose = {}) }
}

@Preview(name = "W24 · 结果图片", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun ResultShareImagePreview() {
    val image = remember { ShareImageRenderer().renderResult(w24ResultSample()).bitmap.asImageBitmap() }
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Image(image, "今日结果图片", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}

@Preview(name = "W24 · 告知图片", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun InformationShareImagePreview() {
    val image = remember {
        ShareImageRenderer().renderInformation(
            taskName = "填写今日反馈",
            taskDate = "2026-07-22",
            domName = "示例 Dom",
            body = "今天已完成主要任务。\n专注过程顺利，明天会继续保持。",
        ).bitmap.asImageBitmap()
    }
    Box(Modifier.fillMaxSize().padding(12.dp)) {
        Image(image, "信息告知图片", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
    }
}

internal fun w24ResultSample() = ResultPresentation(
    taskDate = "2026-07-22",
    dateLabel = "2026年7月22日 星期三",
    domName = "示例 Dom",
    status = "尚未完成",
    totalPoints = 13,
    groups = listOf(
        ResultGroupPresentation(
            name = "工作与学习",
            status = "尚未完成",
            points = 13,
            message = null,
            tasks = listOf(
                ResultTaskPresentation("完成项目复盘", "必做", "已完成", 10),
                ResultTaskPresentation("整理明日计划", "必做", "待完成", 0),
                ResultTaskPresentation("阅读技术文章", "选做", "已完成", 3),
            ),
        ),
    ),
)
