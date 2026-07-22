package com.ds.localtaskmanager

import android.app.Application
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.ImportService
import com.ds.localtaskmanager.data.RoomImportService
import com.ds.localtaskmanager.data.RoomTaskExecutionService
import com.ds.localtaskmanager.data.RoomTaskRepository
import com.ds.localtaskmanager.data.RoomTaskNoteService
import com.ds.localtaskmanager.data.TaskExecutionService
import com.ds.localtaskmanager.data.TaskNoteService
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.data.recurrence.InstanceGenerationService
import com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService
import com.ds.localtaskmanager.data.result.ResultRepository
import com.ds.localtaskmanager.data.result.RoomResultRepository
import com.ds.localtaskmanager.data.history.HistoryRepository
import com.ds.localtaskmanager.data.history.RoomHistoryRepository
import com.ds.localtaskmanager.domain.SecureRecordIdGenerator
import com.ds.localtaskmanager.protocol.Dst1Parser
import com.ds.localtaskmanager.reminder.AndroidReminderNotifier
import com.ds.localtaskmanager.reminder.AndroidReminderScheduler
import com.ds.localtaskmanager.reminder.ReminderCoordinator
import java.time.Clock

class DstApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    private val clock: Clock = Clock.systemDefaultZone()
    private val idGenerator = SecureRecordIdGenerator()
    val taskRepository: TaskRepository by lazy {
        RoomTaskRepository(database.instanceDao(), database.definitionDao(), database.auditDao())
    }
    val importService: ImportService by lazy {
        RoomImportService(database, Dst1Parser(), clock, idGenerator)
    }
    val taskExecutionService: TaskExecutionService by lazy {
        RoomTaskExecutionService(database, clock, idGenerator)
    }
    val taskNoteService: TaskNoteService by lazy {
        RoomTaskNoteService(database, clock)
    }
    val instanceGenerationService: InstanceGenerationService by lazy {
        RoomInstanceGenerationService(database, clock, idGenerator)
    }
    val resultRepository: ResultRepository by lazy {
        RoomResultRepository(database)
    }
    val historyRepository: HistoryRepository by lazy { RoomHistoryRepository(database) }
    private val reminderNotifier by lazy { AndroidReminderNotifier(this) }
    val reminderCoordinator: ReminderCoordinator by lazy {
        ReminderCoordinator(
            database = database,
            scheduler = AndroidReminderScheduler(this),
            notifier = reminderNotifier,
            clock = clock,
            idGenerator = idGenerator,
        )
    }

    override fun onCreate() {
        super.onCreate()
        reminderNotifier.createChannel()
    }
}
