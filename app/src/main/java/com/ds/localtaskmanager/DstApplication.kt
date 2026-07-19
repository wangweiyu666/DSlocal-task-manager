package com.ds.localtaskmanager

import android.app.Application
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.ImportService
import com.ds.localtaskmanager.data.RoomImportService
import com.ds.localtaskmanager.data.RoomTaskExecutionService
import com.ds.localtaskmanager.data.RoomTaskRepository
import com.ds.localtaskmanager.data.TaskExecutionService
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.data.recurrence.InstanceGenerationService
import com.ds.localtaskmanager.data.recurrence.RoomInstanceGenerationService
import com.ds.localtaskmanager.domain.SecureRecordIdGenerator
import com.ds.localtaskmanager.protocol.Dst1Parser
import java.time.Clock

class DstApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    private val clock: Clock = Clock.systemDefaultZone()
    private val idGenerator = SecureRecordIdGenerator()
    val taskRepository: TaskRepository by lazy {
        RoomTaskRepository(database.instanceDao(), database.auditDao())
    }
    val importService: ImportService by lazy {
        RoomImportService(database, Dst1Parser(), clock, idGenerator)
    }
    val taskExecutionService: TaskExecutionService by lazy {
        RoomTaskExecutionService(database, clock, idGenerator)
    }
    val instanceGenerationService: InstanceGenerationService by lazy {
        RoomInstanceGenerationService(database, clock, idGenerator)
    }
}
