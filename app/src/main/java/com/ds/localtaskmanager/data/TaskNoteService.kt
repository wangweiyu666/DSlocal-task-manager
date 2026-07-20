package com.ds.localtaskmanager.data

import androidx.room.withTransaction
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import java.time.Clock

interface TaskNoteService {
    suspend fun getNote(key: TaskInstanceKey): String
    suspend fun saveNote(key: TaskInstanceKey, content: String)
}

class RoomTaskNoteService(
    private val database: AppDatabase,
    private val clock: Clock,
) : TaskNoteService {
    override suspend fun getNote(key: TaskInstanceKey): String =
        database.executionDao().getNote(key.taskId, key.occurrenceKey)?.content.orEmpty()

    override suspend fun saveNote(key: TaskInstanceKey, content: String) = database.withTransaction {
        val instance = database.instanceDao().getInstance(key.taskId, key.occurrenceKey)
            ?: throw TaskOperationException(
                com.ds.localtaskmanager.domain.execution.TaskOperationCode.INSTANCE_NOT_FOUND,
                "任务不存在",
            )
        val dao = database.executionDao()
        val existing = dao.getNote(key.taskId, key.occurrenceKey)
        if (content.isBlank()) {
            if (existing != null) dao.deleteNote(existing)
            return@withTransaction
        }
        val now = clock.millis()
        dao.upsertNote(
            TaskNoteEntity(
                taskId = instance.taskId,
                occurrenceKey = instance.occurrenceKey,
                content = content,
                createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                updatedAtEpochMillis = now,
            ),
        )
    }
}
