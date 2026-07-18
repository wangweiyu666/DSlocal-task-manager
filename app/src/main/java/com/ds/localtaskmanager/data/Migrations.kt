package com.ds.localtaskmanager.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `task_instance` RENAME TO `task_instance_v1`")
        db.execAll(V2_CREATE_STATEMENTS)

        db.execSQL(
            """
            INSERT OR IGNORE INTO `task_group`
              (`groupId`, `name`, `completeMessage`, `incompleteMessage`, `archived`,
               `createdAtEpochMillis`, `updatedAtEpochMillis`)
            SELECT `groupId`, '未命名积分组', '全部完成', '未完成', 0,
                   MIN(`createdAtEpochMillis`), MAX(`updatedAtEpochMillis`)
            FROM `task_instance_v1`
            WHERE `groupId` IS NOT NULL
            GROUP BY `groupId`
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `task_definition`
              (`taskId`, `name`, `description`, `groupId`, `required`, `taskDate`, `deadline`,
               `points`, `sortOrder`, `completionMessage`, `stepsFingerprint`, `cancelled`,
               `createdAtEpochMillis`, `updatedAtEpochMillis`)
            SELECT `taskId`, MIN(`name`), '', MIN(`groupId`), MAX(`required`), MIN(`taskDate`), NULL,
                   MAX(`points`), NULL, '任务已完成', '', 0,
                   MIN(`createdAtEpochMillis`), MAX(`updatedAtEpochMillis`)
            FROM `task_instance_v1`
            GROUP BY `taskId`
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `task_instance`
              (`taskId`, `occurrenceKey`, `name`, `description`, `taskDate`, `deadline`, `groupId`,
               `required`, `points`, `sortOrder`, `completionMessage`, `status`,
               `completedAtEpochMillis`, `createdAtEpochMillis`, `updatedAtEpochMillis`)
            SELECT `taskId`, `occurrenceKey`, `name`, '', `taskDate`, NULL, `groupId`,
                   `required`, `points`, NULL, '任务已完成', `status`, NULL,
                   `createdAtEpochMillis`, `updatedAtEpochMillis`
            FROM `task_instance_v1`
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE `task_instance_v1`")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA defer_foreign_keys = ON")
        db.execAll(V3_REPLACEMENT_TABLES)

        db.execSQL(
            """
            INSERT INTO `task_definition_new`
            SELECT `taskId`, `name`, `description`, `groupId`, `required`, `taskDate`, `deadline`,
                   `points`, `sortOrder`, `completionMessage`, `stepsFingerprint`, `cancelled`,
                   `createdAtEpochMillis`, `updatedAtEpochMillis`,
                   NULL, NULL, NULL, NULL, NULL, NULL, 'NORMAL', NULL, NULL, NULL
            FROM `task_definition`
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `task_step_definition_new`
            SELECT `taskId`, `position`, `name`, `required` FROM `task_step_definition`
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `task_instance_new`
            SELECT `taskId`, `occurrenceKey`, `name`, `description`, `taskDate`, `deadline`, `groupId`,
                   `required`, `points`, `sortOrder`, `completionMessage`, `status`,
                   `completedAtEpochMillis`, `createdAtEpochMillis`, `updatedAtEpochMillis`,
                   'TEMPORARY', 'NORMAL', NULL, NULL, NULL, `createdAtEpochMillis`
            FROM `task_instance`
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `instance_step_new`
            SELECT `taskId`, `occurrenceKey`, `position`, `name`, `required`, `completed`,
                   `updatedAtEpochMillis`
            FROM `instance_step`
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `points_ledger_new`
            SELECT `ledgerId`, `taskId`, `occurrenceKey`, `groupId`, `delta`, `reason`,
                   `createdAtEpochMillis`
            FROM `points_ledger`
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO `action_log_new`
            SELECT `eventId`, `taskId`, `occurrenceKey`, `batchId`, `action`, `detail`,
                   `createdAtEpochMillis`
            FROM `action_log`
            """.trimIndent(),
        )

        listOf(
            "task_step_definition",
            "instance_step",
            "points_ledger",
            "action_log",
            "task_instance",
            "task_definition",
        ).forEach { db.execSQL("DROP TABLE `$it`") }

        db.execSQL("ALTER TABLE `task_definition_new` RENAME TO `task_definition`")
        db.execSQL(V3_FINAL_INSTANCE_TABLE)
        db.execSQL("INSERT INTO `task_instance` SELECT * FROM `task_instance_new`")

        db.execAll(V3_FINAL_CHILD_TABLES)
        db.execSQL("INSERT INTO `task_step_definition` SELECT * FROM `task_step_definition_new`")
        db.execSQL("INSERT INTO `instance_step` SELECT * FROM `instance_step_new`")
        db.execSQL("INSERT INTO `points_ledger` SELECT * FROM `points_ledger_new`")
        db.execSQL("INSERT INTO `action_log` SELECT * FROM `action_log_new`")
        listOf(
            "task_step_definition_new",
            "instance_step_new",
            "points_ledger_new",
            "action_log_new",
        ).forEach { db.execSQL("DROP TABLE `$it`") }
        db.execSQL("DROP TABLE `task_instance_new`")

        db.execAll(V3_INDEX_AND_NEW_TABLE_STATEMENTS)
    }
}

private fun SupportSQLiteDatabase.execAll(statements: List<String>) {
    statements.forEach(::execSQL)
}

private val V2_CREATE_STATEMENTS = listOf(
    "CREATE TABLE IF NOT EXISTS `app_profile` (`id` INTEGER NOT NULL, `domName` TEXT NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `import_batch` (`batchId` TEXT NOT NULL, `note` TEXT, `importedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`batchId`))",
    "CREATE TABLE IF NOT EXISTS `task_group` (`groupId` TEXT NOT NULL, `name` TEXT NOT NULL, `completeMessage` TEXT NOT NULL, `incompleteMessage` TEXT NOT NULL, `archived` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`groupId`))",
    "CREATE TABLE IF NOT EXISTS `task_definition` (`taskId` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `groupId` TEXT, `required` INTEGER NOT NULL, `taskDate` TEXT NOT NULL, `deadline` TEXT, `points` INTEGER NOT NULL, `sortOrder` INTEGER, `completionMessage` TEXT NOT NULL, `stepsFingerprint` TEXT NOT NULL, `cancelled` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`))",
    "CREATE INDEX IF NOT EXISTS `index_task_definition_groupId` ON `task_definition` (`groupId`)",
    "CREATE INDEX IF NOT EXISTS `index_task_definition_taskDate` ON `task_definition` (`taskDate`)",
    "CREATE TABLE IF NOT EXISTS `task_step_definition` (`taskId` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `required` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `position`))",
    "CREATE INDEX IF NOT EXISTS `index_task_step_definition_taskId` ON `task_step_definition` (`taskId`)",
    "CREATE TABLE IF NOT EXISTS `task_instance` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `taskDate` TEXT NOT NULL, `deadline` TEXT, `groupId` TEXT, `required` INTEGER NOT NULL, `points` INTEGER NOT NULL, `sortOrder` INTEGER, `completionMessage` TEXT NOT NULL, `status` TEXT NOT NULL, `completedAtEpochMillis` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `occurrenceKey`))",
    "CREATE INDEX IF NOT EXISTS `index_task_instance_taskDate` ON `task_instance` (`taskDate`)",
    "CREATE INDEX IF NOT EXISTS `index_task_instance_status` ON `task_instance` (`status`)",
    "CREATE INDEX IF NOT EXISTS `index_task_instance_groupId` ON `task_instance` (`groupId`)",
    "CREATE TABLE IF NOT EXISTS `instance_step` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `required` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `occurrenceKey`, `position`))",
    "CREATE INDEX IF NOT EXISTS `index_instance_step_taskId_occurrenceKey` ON `instance_step` (`taskId`, `occurrenceKey`)",
    "CREATE TABLE IF NOT EXISTS `points_ledger` (`ledgerId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `groupId` TEXT, `delta` INTEGER NOT NULL, `reason` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ledgerId`))",
    "CREATE INDEX IF NOT EXISTS `index_points_ledger_taskId_occurrenceKey` ON `points_ledger` (`taskId`, `occurrenceKey`)",
    "CREATE INDEX IF NOT EXISTS `index_points_ledger_groupId` ON `points_ledger` (`groupId`)",
    "CREATE TABLE IF NOT EXISTS `action_log` (`eventId` TEXT NOT NULL, `taskId` TEXT, `occurrenceKey` TEXT, `batchId` TEXT, `action` TEXT NOT NULL, `detail` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`eventId`))",
    "CREATE INDEX IF NOT EXISTS `index_action_log_taskId_occurrenceKey` ON `action_log` (`taskId`, `occurrenceKey`)",
    "CREATE INDEX IF NOT EXISTS `index_action_log_batchId` ON `action_log` (`batchId`)",
)

private val V3_REPLACEMENT_TABLES = listOf(
    "CREATE TABLE `task_definition_new` (`taskId` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `groupId` TEXT, `required` INTEGER NOT NULL, `taskDate` TEXT NOT NULL, `deadline` TEXT, `points` INTEGER NOT NULL, `sortOrder` INTEGER, `completionMessage` TEXT NOT NULL, `stepsFingerprint` TEXT NOT NULL, `cancelled` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `recurrenceFrequency` INTEGER, `recurrenceStartDate` TEXT, `recurrenceEndDate` TEXT, `recurrenceCount` INTEGER, `recurrenceWeekdaysMask` INTEGER, `recurrenceDeadlineTime` TEXT, `executionKind` TEXT NOT NULL, `executionAction` INTEGER, `executionTarget` INTEGER, `reminderMinutesJson` TEXT, PRIMARY KEY(`taskId`), FOREIGN KEY(`groupId`) REFERENCES `task_group`(`groupId`) ON UPDATE NO ACTION ON DELETE SET NULL)",
    "CREATE TABLE `task_step_definition_new` (`taskId` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `required` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `position`), FOREIGN KEY(`taskId`) REFERENCES `task_definition_new`(`taskId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE TABLE `task_instance_new` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `taskDate` TEXT NOT NULL, `deadline` TEXT, `groupId` TEXT, `required` INTEGER NOT NULL, `points` INTEGER NOT NULL, `sortOrder` INTEGER, `completionMessage` TEXT NOT NULL, `status` TEXT NOT NULL, `completedAtEpochMillis` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `category` TEXT NOT NULL, `executionKind` TEXT NOT NULL, `executionAction` INTEGER, `executionTarget` INTEGER, `reminderMinutesJson` TEXT, `publishedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `occurrenceKey`), FOREIGN KEY(`taskId`) REFERENCES `task_definition_new`(`taskId`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`groupId`) REFERENCES `task_group`(`groupId`) ON UPDATE NO ACTION ON DELETE SET NULL)",
    "CREATE TABLE `instance_step_new` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `required` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `occurrenceKey`, `position`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance_new`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE TABLE `points_ledger_new` (`ledgerId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `groupId` TEXT, `delta` INTEGER NOT NULL, `reason` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ledgerId`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance_new`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`groupId`) REFERENCES `task_group`(`groupId`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
    "CREATE TABLE `action_log_new` (`eventId` TEXT NOT NULL, `taskId` TEXT, `occurrenceKey` TEXT, `batchId` TEXT, `action` TEXT NOT NULL, `detail` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`eventId`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance_new`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`batchId`) REFERENCES `import_batch`(`batchId`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
)

private val V3_FINAL_CHILD_TABLES = listOf(
    "CREATE TABLE `task_step_definition` (`taskId` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `required` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `position`), FOREIGN KEY(`taskId`) REFERENCES `task_definition`(`taskId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE TABLE `instance_step` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `required` INTEGER NOT NULL, `completed` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `occurrenceKey`, `position`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE TABLE `points_ledger` (`ledgerId` TEXT NOT NULL, `taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `groupId` TEXT, `delta` INTEGER NOT NULL, `reason` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`ledgerId`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`groupId`) REFERENCES `task_group`(`groupId`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
    "CREATE TABLE `action_log` (`eventId` TEXT NOT NULL, `taskId` TEXT, `occurrenceKey` TEXT, `batchId` TEXT, `action` TEXT NOT NULL, `detail` TEXT, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`eventId`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`batchId`) REFERENCES `import_batch`(`batchId`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
)

private const val V3_FINAL_INSTANCE_TABLE =
    "CREATE TABLE `task_instance` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `taskDate` TEXT NOT NULL, `deadline` TEXT, `groupId` TEXT, `required` INTEGER NOT NULL, `points` INTEGER NOT NULL, `sortOrder` INTEGER, `completionMessage` TEXT NOT NULL, `status` TEXT NOT NULL, `completedAtEpochMillis` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `category` TEXT NOT NULL, `executionKind` TEXT NOT NULL, `executionAction` INTEGER, `executionTarget` INTEGER, `reminderMinutesJson` TEXT, `publishedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `occurrenceKey`), FOREIGN KEY(`taskId`) REFERENCES `task_definition`(`taskId`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`groupId`) REFERENCES `task_group`(`groupId`) ON UPDATE NO ACTION ON DELETE SET NULL)"

private val V3_INDEX_AND_NEW_TABLE_STATEMENTS = listOf(
    "CREATE INDEX `index_task_definition_groupId` ON `task_definition` (`groupId`)",
    "CREATE INDEX `index_task_definition_taskDate` ON `task_definition` (`taskDate`)",
    "CREATE INDEX `index_task_step_definition_taskId` ON `task_step_definition` (`taskId`)",
    "CREATE INDEX `index_task_instance_taskId` ON `task_instance` (`taskId`)",
    "CREATE INDEX `index_task_instance_taskDate` ON `task_instance` (`taskDate`)",
    "CREATE INDEX `index_task_instance_status` ON `task_instance` (`status`)",
    "CREATE INDEX `index_task_instance_groupId` ON `task_instance` (`groupId`)",
    "CREATE INDEX `index_instance_step_taskId_occurrenceKey` ON `instance_step` (`taskId`, `occurrenceKey`)",
    "CREATE INDEX `index_points_ledger_taskId_occurrenceKey` ON `points_ledger` (`taskId`, `occurrenceKey`)",
    "CREATE INDEX `index_points_ledger_groupId` ON `points_ledger` (`groupId`)",
    "CREATE INDEX `index_action_log_taskId_occurrenceKey` ON `action_log` (`taskId`, `occurrenceKey`)",
    "CREATE INDEX `index_action_log_batchId` ON `action_log` (`batchId`)",
    "CREATE TABLE `execution_progress` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `executionKind` TEXT NOT NULL, `counterValue` INTEGER, `elapsedMillis` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `occurrenceKey`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX `index_execution_progress_taskId_occurrenceKey` ON `execution_progress` (`taskId`, `occurrenceKey`)",
    "CREATE TABLE `information_submission` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, `submittedAtEpochMillis` INTEGER, PRIMARY KEY(`taskId`, `occurrenceKey`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX `index_information_submission_taskId_occurrenceKey` ON `information_submission` (`taskId`, `occurrenceKey`)",
    "CREATE TABLE `task_note` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `occurrenceKey`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX `index_task_note_taskId_occurrenceKey` ON `task_note` (`taskId`, `occurrenceKey`)",
    "CREATE TABLE `result_revision` (`revisionId` TEXT NOT NULL, `taskDate` TEXT NOT NULL, `scope` TEXT NOT NULL, `groupId` TEXT, `oldStatus` TEXT, `newStatus` TEXT, `oldPoints` INTEGER, `newPoints` INTEGER, `reason` TEXT NOT NULL, `batchId` TEXT, `relatedTaskIdsJson` TEXT NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`revisionId`), FOREIGN KEY(`groupId`) REFERENCES `task_group`(`groupId`) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(`batchId`) REFERENCES `import_batch`(`batchId`) ON UPDATE NO ACTION ON DELETE NO ACTION)",
    "CREATE INDEX `index_result_revision_taskDate_scope` ON `result_revision` (`taskDate`, `scope`)",
    "CREATE INDEX `index_result_revision_groupId` ON `result_revision` (`groupId`)",
    "CREATE INDEX `index_result_revision_batchId` ON `result_revision` (`batchId`)",
    "CREATE TABLE `reminder_record` (`taskId` TEXT NOT NULL, `occurrenceKey` TEXT NOT NULL, `minutesBeforeDeadline` INTEGER NOT NULL, `scheduledForEpochMillis` INTEGER NOT NULL, `state` TEXT NOT NULL, `deliveredAtEpochMillis` INTEGER, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`taskId`, `occurrenceKey`, `minutesBeforeDeadline`), FOREIGN KEY(`taskId`, `occurrenceKey`) REFERENCES `task_instance`(`taskId`, `occurrenceKey`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX `index_reminder_record_taskId_occurrenceKey` ON `reminder_record` (`taskId`, `occurrenceKey`)",
    "CREATE INDEX `index_reminder_record_scheduledForEpochMillis` ON `reminder_record` (`scheduledForEpochMillis`)",
    "CREATE INDEX `index_reminder_record_state` ON `reminder_record` (`state`)",
)
