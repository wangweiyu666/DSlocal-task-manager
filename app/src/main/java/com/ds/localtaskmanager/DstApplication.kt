package com.ds.localtaskmanager

import android.app.Application
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.ImportService
import com.ds.localtaskmanager.data.TaskExecutionService
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.domain.SecureRecordIdGenerator
import com.ds.localtaskmanager.protocol.Dst1Parser
import java.time.Clock

class DstApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    private val clock: Clock = Clock.systemDefaultZone()
    private val idGenerator = SecureRecordIdGenerator()
    val taskRepository: TaskRepository by lazy { TaskRepository(database.appDao()) }
    val importService: ImportService by lazy {
        ImportService(database, Dst1Parser(), clock, idGenerator)
    }
    val taskExecutionService: TaskExecutionService by lazy {
        TaskExecutionService(database, clock, idGenerator)
    }
}
