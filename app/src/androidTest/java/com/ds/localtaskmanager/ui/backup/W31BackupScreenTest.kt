package com.ds.localtaskmanager.ui.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ds.localtaskmanager.backup.BackupCounts
import com.ds.localtaskmanager.backup.BackupMetadata
import com.ds.localtaskmanager.backup.BackupPayload
import com.ds.localtaskmanager.backup.DecodedBackup
import com.ds.localtaskmanager.backup.MergeConflict
import com.ds.localtaskmanager.backup.MergePreview
import com.ds.localtaskmanager.backup.RestoreMode
import com.ds.localtaskmanager.settings.AppSettings
import com.ds.localtaskmanager.ui.theme.DstTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class W31BackupScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun homeExposesExportAndRestore() {
        var exported = false
        var restored = false
        setScreen(
            page = BackupPage.Home,
            onExport = { exported = true },
            onRestore = { restored = true },
        )

        composeRule.onNodeWithTag("backup-export").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("backup-restore").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertTrue(exported && restored) }
    }

    @Test
    fun restorePreviewDefaultsToMergeAndAllowsReplace() {
        var selected = RestoreMode.MERGE
        setScreen(preview(), onSelectMode = { selected = it })

        composeRule.onNodeWithText("合并恢复").assertIsDisplayed()
        composeRule.onNodeWithText("完全替换").performClick()
        composeRule.runOnIdle { assertEquals(RestoreMode.REPLACE, selected) }
    }

    @Test
    fun conflictCanChooseBackupVersion() {
        val preview = preview(conflicts = listOf(MergeConflict("task:1", "任务", "阅读", "本机内容", "备份内容")))
        var selected = ""
        setScreen(BackupPage.Conflicts(preview), onToggleConflict = { selected = it })

        composeRule.onNodeWithText("备份：备份内容").assertIsDisplayed()
        composeRule.onNodeWithText("本机：本机内容").assertIsDisplayed()
        composeRule.onNodeWithTag("conflict-task:1").performClick()
        composeRule.runOnIdle { assertEquals("task:1", selected) }
    }

    private fun setScreen(
        page: BackupPage,
        onExport: () -> Unit = {},
        onRestore: () -> Unit = {},
        onSelectMode: (RestoreMode) -> Unit = {},
        onToggleConflict: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            DstTheme {
                BackupScreen(
                    page, AppSettings(), {}, onExport, onRestore, {}, onSelectMode,
                    {}, onToggleConflict, {}, {}, {}, {},
                )
            }
        }
    }

    private fun preview(conflicts: List<MergeConflict> = emptyList()): BackupPage.Preview {
        val payload = BackupPayload()
        val metadata = BackupMetadata(1_800_000_000_000, "test", "Asia/Hong_Kong", counts = BackupCounts(0, 0, 0, 0, 0, 0))
        return BackupPage.Preview(
            File("preview.dstb"),
            DecodedBackup(metadata, payload),
            MergePreview(payload, conflicts, 0, 0, 0),
        )
    }
}
