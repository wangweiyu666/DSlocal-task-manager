package com.ds.localtaskmanager.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest

@PreviewTest
@Preview(name = "W24 · 今日结果", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun TodayResultScreenshot() = TodayResultPreview()

@PreviewTest
@Preview(name = "W24 · 空结果", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun TodayResultEmptyScreenshot() = TodayResultEmptyPreview()

@PreviewTest
@Preview(name = "W24 · 结果图片", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun ResultShareImageScreenshot() = ResultShareImagePreview()

@PreviewTest
@Preview(name = "W24 · 告知图片", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun InformationShareImageScreenshot() = InformationShareImagePreview()
