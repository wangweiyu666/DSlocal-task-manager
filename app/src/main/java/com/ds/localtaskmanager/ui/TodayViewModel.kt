package com.ds.localtaskmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.domain.TaskDay
import java.time.LocalDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class TodayViewModel(repository: TaskRepository) : ViewModel() {
    private val currentTaskDate = TaskDay.from(LocalDateTime.now()).toString()

    val tasks: StateFlow<List<TaskInstanceEntity>> = repository
        .observeTasks(currentTaskDate)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val taskDate: String = currentTaskDate
}

class TodayViewModelFactory(
    private val repository: TaskRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TodayViewModel::class.java))
        return TodayViewModel(repository) as T
    }
}
