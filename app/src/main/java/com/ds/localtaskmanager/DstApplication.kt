package com.ds.localtaskmanager

import android.app.Application
import com.ds.localtaskmanager.data.AppDatabase
import com.ds.localtaskmanager.data.TaskRepository

class DstApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.create(this) }
    val taskRepository: TaskRepository by lazy { TaskRepository(database.taskDao()) }
}
