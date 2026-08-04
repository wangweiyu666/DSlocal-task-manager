package com.ds.localtaskmanager.ui.backup

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ds.localtaskmanager.backup.BackupCounts
import com.ds.localtaskmanager.backup.BackupMetadata
import com.ds.localtaskmanager.backup.BackupPayload
import com.ds.localtaskmanager.backup.DecodedBackup
import com.ds.localtaskmanager.backup.MergeConflict
import com.ds.localtaskmanager.backup.MergePreview
import com.ds.localtaskmanager.settings.AppSettings
import com.ds.localtaskmanager.settings.AppThemeMode
import com.ds.localtaskmanager.ui.theme.DstTheme
import java.io.File

@PreviewTest
@Preview(name = "W31 · 备份首页", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun BackupHomeScreenshot() = ScreenshotTheme {
    screen(BackupPage.Home, AppSettings(backupPrivacyConfirmed = true))
}

@PreviewTest
@Preview(name = "W31 · 恢复预览", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun BackupRestorePreviewScreenshot() = ScreenshotTheme {
    val payload = BackupPayload()
    screen(
        BackupPage.Preview(
            File("preview.dstb"),
            DecodedBackup(metadata(), payload),
            MergePreview(payload, emptyList(), 12, 3, 5),
        ),
    )
}

@PreviewTest
@Preview(name = "W31 · 冲突处理", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
fun BackupConflictScreenshot() = ScreenshotTheme {
    val payload = BackupPayload()
    val conflicts = listOf(
        MergeConflict("task:1", "任务", "阅读", "阅读；5 分；20:00", "阅读 30 分钟；8 分；21:00"),
        MergeConflict("note:1", "任务备注", "运动", "完成热身", "膝盖不适，降低强度"),
    )
    val preview = BackupPage.Preview(
        File("preview.dstb"), DecodedBackup(metadata(), payload), MergePreview(payload, conflicts, 1, 1, 2),
    )
    screen(BackupPage.Conflicts(preview))
}

@PreviewTest
@Preview(name = "W31 · 结果页100%字体", showBackground = true, widthDp = 393, heightDp = 852, fontScale = 1f)
@Composable
fun BackupResultScreenshot() = ScreenshotTheme {
    screen(BackupPage.Result("恢复完成", "新增 12 项，更新 3 项，保留本机 5 项。数据已恢复，部分提醒将在下次启动时重建。", true))
}

@Composable
private fun ScreenshotTheme(content: @Composable () -> Unit) {
    DstTheme(themeMode = AppThemeMode.LIGHT, content = content)
}

@Composable
private fun screen(page: BackupPage, settings: AppSettings = AppSettings()) {
    BackupScreen(
        page = page,
        settings = settings,
        onBack = {},
        onExport = {},
        onRestore = {},
        onResetPrivacy = {},
        onSelectMode = {},
        onContinueRestore = {},
        onToggleConflict = {},
        onAllLocal = {},
        onAllBackup = {},
        onConfirmConflicts = {},
        onDone = {},
        lazyConflicts = false,
    )
}

private fun metadata() = BackupMetadata(
    createdAtEpochMillis = 1_784_419_200_000,
    appVersion = "0.1.0-alpha",
    sourceTimeZone = "Asia/Hong_Kong",
    counts = BackupCounts(3, 18, 42, 67, 81, 4),
)
