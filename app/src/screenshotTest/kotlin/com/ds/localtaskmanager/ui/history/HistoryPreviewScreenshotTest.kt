package com.ds.localtaskmanager.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ds.localtaskmanager.ui.theme.DstTheme

@PreviewTest
@Preview(name = "历史 · 有数据", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun HistoryPopulatedScreenshot() {
    DstTheme(dynamicColor = false) { PopulatedHistoryPreviewContent() }
}

@PreviewTest
@Preview(name = "历史 · 空状态", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun HistoryEmptyScreenshot() {
    DstTheme(dynamicColor = false) { HistoryPreviewContent(HistoryUiState(loading = false)) }
}

@PreviewTest
@Preview(name = "历史 · 筛选无结果", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun HistoryFilteredEmptyScreenshot() {
    DstTheme(dynamicColor = false) {
        HistoryPreviewContent(HistoryUiState(loading = false, searchText = "不存在的任务"))
    }
}
