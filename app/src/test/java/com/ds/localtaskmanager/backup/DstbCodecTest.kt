package com.ds.localtaskmanager.backup

import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DstbCodecTest {
    @Test
    fun roundTripIsStable() {
        val metadata = metadata()
        val payload = payload()

        val first = DstbCodec.encode(metadata, payload)
        val second = DstbCodec.encode(metadata, payload)
        val decoded = DstbCodec.decode(first)

        assertTrue(first.contentEquals(second))
        assertEquals(metadata, decoded.metadata)
        assertEquals(payload, decoded.payload)
        BackupValidator.validate(decoded)
        val allRecords = fullPayload()
        val allMetadata = metadata().copy(counts = BackupCounts(1, 1, 1, 1, 1, 1))
        BackupValidator.validate(DstbCodec.decode(DstbCodec.encode(allMetadata, allRecords)))
        System.getenv("DSTB_VECTOR_DIR")?.takeIf(String::isNotBlank)?.let { generateVectors(File(it)) }
    }

    @Test
    fun changedByteFailsWithoutReturningPayload() {
        val bytes = DstbCodec.encode(metadata(), payload())
        bytes[bytes.lastIndex - 6] = (bytes[bytes.lastIndex - 6].toInt() xor 1).toByte()

        val error = runCatching { DstbCodec.decode(bytes) }.exceptionOrNull()

        assertTrue(error is DstbException)
        assertTrue(error?.message.orEmpty().contains("校验失败"))
    }

    @Test
    fun truncatedFileFails() {
        val bytes = DstbCodec.encode(metadata(), payload()).copyOf(18)
        assertTrue(runCatching { DstbCodec.decode(bytes) }.exceptionOrNull() is DstbException)
    }

    @Test
    fun unknownFutureVersionRequestsUpgrade() {
        val bytes = DstbCodec.encode(metadata(), payload())
        bytes[5] = 2
        rewriteCrc(bytes)

        val error = runCatching { DstbCodec.decode(bytes) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("升级应用"))
    }

    @Test
    fun duplicateKeysAreRejected() {
        val duplicated = payload().let { it.copy(groups = it.groups + it.groups) }
        val metadata = metadata().copy(counts = metadata().counts.copy(groups = 2))
        val decoded = DstbCodec.decode(DstbCodec.encode(metadata, duplicated))

        assertTrue(runCatching { BackupValidator.validate(decoded) }.exceptionOrNull()?.message.orEmpty().contains("重复"))
    }

    private fun rewriteCrc(bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes, 0, bytes.size - 4) }.value
        repeat(4) { index -> bytes[bytes.size - 4 + index] = ((crc ushr (index * 8)) and 0xff).toByte() }
    }

    private fun metadata() = BackupMetadata(
        createdAtEpochMillis = 1_800_000_000_000,
        appVersion = "test",
        sourceTimeZone = "Asia/Hong_Kong",
        counts = BackupCounts(1, 0, 0, 0, 0, 0),
    )

    private fun payload() = BackupPayload(
        groups = listOf(
            GroupBackup("group-1", "学习", "完成", "未完成", false, 1_700_000_000_000, 1_700_000_000_001),
        ),
    )

    private fun generateVectors(directory: File) {
        directory.mkdirs()
        val emptyMetadata = metadata().copy(counts = BackupCounts(0, 0, 0, 0, 0, 0))
        val minimal = DstbCodec.encode(emptyMetadata, BackupPayload())
        File(directory, "minimal-valid.dstb").writeBytes(minimal)

        val full = fullPayload()
        val fullMetadata = metadata().copy(counts = BackupCounts(1, 1, 1, 1, 1, 1))
        File(directory, "all-records-valid.dstb").writeBytes(DstbCodec.encode(fullMetadata, full))

        File(directory, "crc-corrupt.dstb").writeBytes(minimal.copyOf().also { it[it.lastIndex - 5] = (it[it.lastIndex - 5].toInt() xor 1).toByte() })
        File(directory, "truncated.dstb").writeBytes(minimal.copyOf(minimal.size - 8))
        File(directory, "future-version.dstb").writeBytes(minimal.copyOf().also { it[5] = 2; rewriteCrc(it) })
        val duplicate = payload().let { it.copy(groups = it.groups + it.groups) }
        File(directory, "duplicate-key.dstb").writeBytes(
            DstbCodec.encode(metadata().copy(counts = BackupCounts(2, 0, 0, 0, 0, 0)), duplicate),
        )
    }

    private fun fullPayload(): BackupPayload {
        val created = 1_700_000_000_000
        return BackupPayload(
            profiles = listOf(ProfileBackup(1, "示例 Dom", created)),
            importBatches = listOf(ImportBatchBackup("batch-1", "测试向量", created)),
            groups = listOf(GroupBackup("group-1", "学习", "完成", "未完成", false, created, created)),
            definitions = listOf(
                DefinitionBackup(
                    taskId = "task-1", name = "阅读", description = "阅读 30 分钟", groupId = "group-1",
                    required = true, taskDate = "2026-08-05", deadline = "2026-08-05T20:00", points = 5,
                    sortOrder = 1, completionMessage = "完成", stepsFingerprint = "vector", cancelled = false,
                    createdAtEpochMillis = created, updatedAtEpochMillis = created, executionKind = "TIMER",
                    executionTarget = 1_800_000, reminderMinutesJson = "[10]",
                ),
            ),
            definitionSteps = listOf(DefinitionStepBackup("task-1", 0, "打开书", true)),
            instances = listOf(
                InstanceBackup(
                    taskId = "task-1", occurrenceKey = "once", name = "阅读", description = "阅读 30 分钟",
                    taskDate = "2026-08-05", deadline = "2026-08-05T20:00", groupId = "group-1",
                    required = true, points = 5, sortOrder = 1, completionMessage = "完成", status = "PENDING",
                    completedAtEpochMillis = null, createdAtEpochMillis = created, updatedAtEpochMillis = created,
                    category = "TEMPORARY", executionKind = "TIMER", executionAction = null,
                    executionTarget = 1_800_000, reminderMinutesJson = "[10]", publishedAtEpochMillis = created,
                    groupNameSnapshot = "学习",
                ),
            ),
            instanceSteps = listOf(InstanceStepBackup("task-1", "once", 0, "打开书", true, true, created)),
            progress = listOf(ProgressBackup("task-1", "once", "TIMER", null, 600_000, created, created)),
            information = listOf(InformationBackup("task-1", "once", "示例告知", created, created, null)),
            notes = listOf(NoteBackup("task-1", "once", "示例备注", created, created)),
            ledger = listOf(LedgerBackup("ledger-1", "task-1", "once", "group-1", 5, "COMPLETED", created)),
            actionLogs = listOf(ActionLogBackup("event-1", "task-1", "once", "batch-1", "COMPLETED", null, created)),
            resultRevisions = listOf(
                ResultRevisionBackup(
                    "revision-1", "2026-08-05", "GROUP", "group-1", "PENDING", "COMPLETED",
                    0, 5, "MANUAL", "batch-1", "[\"task-1\"]", created,
                ),
            ),
        )
    }
}
