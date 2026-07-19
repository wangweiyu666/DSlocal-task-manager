package com.ds.localtaskmanager.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ds.localtaskmanager.data.ImportPreview
import com.ds.localtaskmanager.data.ImportService
import com.ds.localtaskmanager.data.TaskInstanceEntity
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.data.recurrence.InstanceGenerationService
import com.ds.localtaskmanager.domain.TaskDay
import java.time.LocalDateTime
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ImportUiState(
    val visible: Boolean = false,
    val input: String = "",
    val preview: ImportPreview? = null,
    val error: String? = null,
    val working: Boolean = false,
)

class TodayViewModel(
    repository: TaskRepository,
    private val importService: ImportService,
    private val generationService: InstanceGenerationService,
) : ViewModel() {
    private val mutableTaskDate = MutableStateFlow(TaskDay.from(LocalDateTime.now()).toString())
    val taskDate: StateFlow<String> = mutableTaskDate.asStateFlow()

    val tasks: StateFlow<List<TaskInstanceEntity>> = mutableTaskDate
        .flatMapLatest(repository::observeTasks)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

    init {
        synchronizeInstances()
    }

    fun synchronizeInstances() {
        viewModelScope.launch {
            val current = TaskDay.from(LocalDateTime.now()).toString()
            generationService.reconcileAll(LocalDate.parse(current))
            mutableTaskDate.value = current
        }
    }

    fun openImport() {
        _importState.value = ImportUiState(visible = true)
    }

    fun closeImport() {
        _importState.value = ImportUiState()
    }

    fun updateImportInput(value: String) {
        _importState.value = _importState.value.copy(input = value, preview = null, error = null)
    }

    fun previewImport() {
        val input = _importState.value.input
        viewModelScope.launch {
            _importState.value = _importState.value.copy(working = true, error = null)
            _importState.value = try {
                val preview = importService.preview(input)
                _importState.value.copy(preview = preview, working = false)
            } catch (error: Exception) {
                _importState.value.copy(error = error.message ?: "导入校验失败", working = false)
            }
        }
    }

    fun confirmImport() {
        val preview = _importState.value.preview ?: return
        viewModelScope.launch {
            _importState.value = _importState.value.copy(working = true, error = null)
            try {
                importService.import(preview)
                closeImport()
            } catch (error: Exception) {
                _importState.value = _importState.value.copy(
                    error = error.message ?: "导入失败",
                    working = false,
                )
            }
        }
    }
}

class TodayViewModelFactory(
    private val repository: TaskRepository,
    private val importService: ImportService,
    private val generationService: InstanceGenerationService,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TodayViewModel::class.java))
        return TodayViewModel(repository, importService, generationService) as T
    }
}
