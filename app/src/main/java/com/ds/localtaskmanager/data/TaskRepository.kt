package com.ds.localtaskmanager.data

import kotlinx.coroutines.flow.Flow

class TaskRepository(private val taskDao: TaskDao) {
    fun observeTasks(taskDate: String): Flow<List<TaskInstanceEntity>> =
        taskDao.observeForDate(taskDate)
}
