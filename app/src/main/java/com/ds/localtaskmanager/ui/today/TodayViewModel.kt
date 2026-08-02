package com.ds.localtaskmanager.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ds.localtaskmanager.data.ImportPreview
import com.ds.localtaskmanager.data.ImportService
import com.ds.localtaskmanager.data.TaskRepository
import com.ds.localtaskmanager.data.recurrence.InstanceGenerationService
import com.ds.localtaskmanager.domain.TaskDay
import com.ds.localtaskmanager.reminder.ReminderReconciler
import com.ds.localtaskmanager.data.result.ResultRepository
import com.ds.localtaskmanager.domain.result.DailyResultSnapshot
import java.time.LocalDateTime
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ImportUiState(
    val visible: Boolean = false,
    val input: String = "",
    val preview: ImportPreview? = null,
    val error: String? = null,
    val working: Boolean = false,
)

data class TodayResultUiState(
    val visible: Boolean = false,
    val loading: Boolean = false,
    val taskDate: String = "",
    val snapshot: DailyResultSnapshot? = null,
    val error: String? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TodayViewModel(
    repository: TaskRepository,
    private val importService: ImportService,
    private val generationService: InstanceGenerationService,
    private val resultRepository: ResultRepository,
    private val reminderReconciler: ReminderReconciler? = null,
) : ViewModel() {
    private val mutableTaskDate = MutableStateFlow(TaskDay.from(LocalDateTime.now()).toString())
    val taskDate: StateFlow<String> = mutableTaskDate.asStateFlow()

    private val mutableLoading = MutableStateFlow(true)
    private val mutableError = MutableStateFlow<String?>(null)

    private val todayTasks = mutableTaskDate
        .flatMapLatest(repository::observeTodayTasks)
        .onEach {
            mutableLoading.value = false
            mutableError.value = null
        }
        .catch { error ->
            mutableLoading.value = false
            mutableError.value = error.message ?: "今日任务加载失败"
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val state: StateFlow<TodayUiState> = combine(
        mutableTaskDate,
        todayTasks,
        mutableLoading,
        mutableError,
    ) { date, tasks, loading, error ->
        TodayUiState(
            loading = loading,
            taskDate = date,
            sections = buildTodaySections(tasks),
            error = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    private val _importState = MutableStateFlow(ImportUiState())
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()
    private val _resultState = MutableStateFlow(TodayResultUiState())
    val resultState: StateFlow<TodayResultUiState> = _resultState.asStateFlow()

    init {
        synchronizeInstances()
    }

    fun synchronizeInstances() {
        viewModelScope.launch {
            mutableLoading.value = true
            mutableError.value = null
            runCatching {
                val current = TaskDay.from(LocalDateTime.now()).toString()
                generationService.reconcileAll(LocalDate.parse(current))
                reminderReconciler?.reconcileAll()
                mutableTaskDate.value = current
            }.onFailure { error ->
                mutableError.value = error.message ?: "今日任务同步失败"
                mutableLoading.value = false
            }
        }
    }

    fun openResult() {
        if (_resultState.value.visible) return
        val date = mutableTaskDate.value
        _resultState.value = TodayResultUiState(visible = true, loading = true, taskDate = date)
        loadResult(date)
    }

    fun retryResult() {
        val date = _resultState.value.taskDate.ifBlank { mutableTaskDate.value }
        _resultState.value = TodayResultUiState(visible = true, loading = true, taskDate = date)
        loadResult(date)
    }

    fun closeResult() {
        _resultState.value = TodayResultUiState()
        synchronizeInstances()
    }

    private fun loadResult(date: String) {
        viewModelScope.launch {
            runCatching {
                generationService.reconcileAll(LocalDate.parse(date))
                resultRepository.getDailyResult(date)
            }.onSuccess { snapshot ->
                _resultState.value = TodayResultUiState(
                    visible = true,
                    taskDate = date,
                    snapshot = snapshot,
                )
            }.onFailure {
                _resultState.value = TodayResultUiState(
                    visible = true,
                    taskDate = date,
                    error = "暂时无法生成今日结果",
                )
            }
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
                reminderReconciler?.reconcileAll()
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
    private val resultRepository: ResultRepository,
    private val reminderReconciler: ReminderReconciler? = null,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TodayViewModel::class.java))
        return TodayViewModel(repository, importService, generationService, resultRepository, reminderReconciler) as T
    }
}
