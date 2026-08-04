package com.ds.localtaskmanager.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupMergerTest {
    @Test
    fun newerBackupWinsAndLocalSettingsStay() {
        val local = BackupPayload(
            settings = PortableSettings(themeMode = "DARK"),
            groups = listOf(group("本机", 10)),
        )
        val backup = BackupPayload(
            settings = PortableSettings(themeMode = "LIGHT"),
            groups = listOf(group("备份", 20)),
        )

        val result = BackupMerger.merge(local, backup, emptySet())

        assertEquals("备份", result.merged.groups.single().name)
        assertEquals("DARK", result.merged.settings.themeMode)
        assertEquals(1, result.updated)
    }

    @Test
    fun equalTimestampConflictDefaultsLocalAndCanChooseBackup() {
        val local = BackupPayload(groups = listOf(group("本机", 20)))
        val backup = BackupPayload(groups = listOf(group("备份", 20)))

        val initial = BackupMerger.merge(local, backup, emptySet())
        val selected = BackupMerger.merge(local, backup, setOf("group:g"))

        assertEquals("本机", initial.merged.groups.single().name)
        assertEquals(1, initial.conflicts.size)
        assertEquals("备份", selected.merged.groups.single().name)
    }

    @Test
    fun immutableIdCollisionIsRejected() {
        val local = BackupPayload(ledger = listOf(ledger(1)))
        val backup = BackupPayload(ledger = listOf(ledger(2)))

        assertTrue(
            runCatching { BackupMerger.merge(local, backup, emptySet()) }
                .exceptionOrNull()?.message.orEmpty().contains("无法安全合并"),
        )
    }

    private fun group(name: String, updated: Long) = GroupBackup("g", name, "完成", "未完成", false, 1, updated)

    private fun ledger(delta: Int) = LedgerBackup("l", "task", "once", null, delta, "TEST", 1)
}
