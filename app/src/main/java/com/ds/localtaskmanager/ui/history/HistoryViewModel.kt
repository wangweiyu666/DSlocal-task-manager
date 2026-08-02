package com.ds.localtaskmanager.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ds.localtaskmanager.data.TaskNoteService
import com.ds.localtaskmanager.data.ResultRevisionEntity
import com.ds.localtaskmanager.data.history.HistoryDay
import com.ds.localtaskmanager.data.history.HistoryTask
import com.ds.localtaskmanager.data.history.HistoryQuery
import com.ds.localtaskmanager.data.history.HistoryRepository
import com.ds.localtaskmanager.data.history.HistoryRequirement
import com.ds.localtaskmanager.data.result.ResultRepository
import com.ds.localtaskmanager.domain.TaskDay
import com.ds.localtaskmanager.domain.TaskStatus
import com.ds.localtaskmanager.domain.execution.TaskInstanceKey
import com.ds.localtaskmanager.domain.result.DailyResultSnapshot
import com.ds.localtaskmanager.ui.execution.ExecutionUiState
import com.ds.localtaskmanager.ui.execution.NoteSaveState
import com.ds.localtaskmanager.ui.execution.TaskDetailTimelineItem
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class HistoryStatusFilter(val label: String, val rawStatuses: Set<String>) {
    PENDING("待完成", setOf(TaskStatus.NOT_STARTED.name, TaskStatus.PENDING.name)),
    COMPLETED("已完成", setOf(TaskStatus.COMPLETED.name)),
    MISSED("未完成", setOf(TaskStatus.MISSED.name)),
    CANCELLED("已撤销", setOf(TaskStatus.CANCELLED.name)),
}

enum class HistorySourceFilter(val label: String, val rawCategory: String) {
    DAILY("每日", "DAILY"),
    WEEKLY("每周", "WEEKLY"),
    TEMPORARY("临时", "TEMPORARY"),
}

data class HistoryUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val days: List<HistoryDay> = emptyList(),
    val endReached: Boolean = false,
    val searchText: String = "",
    val statusFilters: Set<HistoryStatusFilter> = HistoryStatusFilter.entries.toSet(),
    val sourceFilters: Set<HistorySourceFilter> = HistorySourceFilter.entries.toSet(),
    val requirement: HistoryRequirement = HistoryRequirement.ALL,
    val selectedDate: String? = null,
    val filtersVisible: Boolean = false,
    val calendarVisible: Boolean = false,
    val calendarMonth: YearMonth = YearMonth.now(),
    val calendarDates: Set<String> = emptySet(),
    val errorMessage: String? = null,
) {
    val hasActiveConditions: Boolean
        get() = searchText.isNotBlank() || selectedDate != null ||
            statusFilters.size != HistoryStatusFilter.entries.size ||
            sourceFilters.size != HistorySourceFilter.entries.size || requirement != HistoryRequirement.ALL
}

class HistoryViewModel(
    private val repository: HistoryRepository,
    clock: Clock = Clock.systemDefaultZone(),
) : ViewModel() {
    private val throughDate = TaskDay.from(LocalDateTime.ofInstant(clock.instant(), clock.zone)).toString()
    private val mutableState = MutableStateFlow(HistoryUiState(calendarMonth = YearMonth.parse(throughDate.take(7))))
    val state: StateFlow<HistoryUiState> = mutableState.asStateFlow()
    private var page = 0
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init { reload() }

    fun updateSearch(value: String) {
        mutableState.value = mutableState.value.copy(searchText = value)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            reload()
        }
    }

    fun toggleFilters() {
        mutableState.value = mutableState.value.copy(filtersVisible = !mutableState.value.filtersVisible)
    }

    fun toggleStatus(filter: HistoryStatusFilter) {
        val selected = mutableState.value.statusFilters.toggle(filter, HistoryStatusFilter.entries.toSet())
        mutableState.value = mutableState.value.copy(statusFilters = selected)
        reload()
    }

    fun toggleSource(filter: HistorySourceFilter) {
        val selected = mutableState.value.sourceFilters.toggle(filter, HistorySourceFilter.entries.toSet())
        mutableState.value = mutableState.value.copy(sourceFilters = selected)
        reload()
    }

    fun setRequirement(requirement: HistoryRequirement) {
        mutableState.value = mutableState.value.copy(requirement = requirement)
        reload()
    }

    fun openCalendar() {
        mutableState.value = mutableState.value.copy(calendarVisible = true)
        loadCalendarDates(mutableState.value.calendarMonth)
    }

    fun closeCalendar() {
        mutableState.value = mutableState.value.copy(calendarVisible = false)
    }

    fun changeCalendarMonth(delta: Long) {
        val month = mutableState.value.calendarMonth.plusMonths(delta)
        mutableState.value = mutableState.value.copy(calendarMonth = month)
        loadCalendarDates(month)
    }

    fun selectDate(date: String) {
        mutableState.value = mutableState.value.copy(selectedDate = date, calendarVisible = false)
        reload()
    }

    fun clearConditions() {
        mutableState.value = mutableState.value.copy(
            searchText = "",
            statusFilters = HistoryStatusFilter.entries.toSet(),
            sourceFilters = HistorySourceFilter.entries.toSet(),
            requirement = HistoryRequirement.ALL,
            selectedDate = null,
        )
        reload()
    }

    fun clearSelectedDate() {
        mutableState.value = mutableState.value.copy(selectedDate = null)
        reload()
    }

    fun viewPreviousDate() {
        val selected = mutableState.value.selectedDate ?: return
        viewModelScope.launch {
            val previous = repository.previousDate(selected)
            if (previous == null) {
                mutableState.value = mutableState.value.copy(selectedDate = null)
            } else {
                mutableState.value = mutableState.value.copy(selectedDate = previous)
            }
            reload()
        }
    }

    fun retry() = reload()

    fun loadMore() {
        val current = mutableState.value
        if (current.loading || current.loadingMore || current.endReached) return
        loadPage(reset = false)
    }

    private fun reload() {
        page = 0
        loadPage(reset = true)
    }

    private fun loadPage(reset: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            mutableState.value = if (reset) {
                mutableState.value.copy(loading = true, days = emptyList(), errorMessage = null)
            } else {
                mutableState.value.copy(loadingMore = true, errorMessage = null)
            }
            runCatching { repository.loadPage(currentQuery(), throughDate, page) }
                .onSuccess { result ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        loadingMore = false,
                        days = if (reset) result.days else mutableState.value.days + result.days,
                        endReached = result.endReached,
                    )
                    if (!result.endReached) page += 1
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        loadingMore = false,
                        errorMessage = error.message ?: "历史记录加载失败。",
                    )
                }
        }
    }

    private fun loadCalendarDates(month: YearMonth) {
        viewModelScope.launch {
            val from = month.atDay(1).toString()
            val through = minOf(month.atEndOfMonth().toString(), throughDate)
            val dates = if (from > through) emptySet() else repository.datesInRange(currentQuery(), from, through)
            mutableState.value = mutableState.value.copy(calendarDates = dates)
        }
    }

    private fun currentQuery() = HistoryQuery(
        text = mutableState.value.searchText,
        statuses = mutableState.value.statusFilters.flatMapTo(linkedSetOf()) { it.rawStatuses },
        categories = mutableState.value.sourceFilters.mapTo(linkedSetOf()) { it.rawCategory },
        requirement = mutableState.value.requirement,
        selectedDate = mutableState.value.selectedDate,
    )

    private fun <T> Set<T>.toggle(item: T, all: Set<T>): Set<T> =
        (if (item in this) this - item else this + item).ifEmpty { all }
}

data class HistoryDetailUiState(
    val detail: ExecutionUiState = ExecutionUiState(),
    val timeline: List<TaskDetailTimelineItem> = emptyList(),
)

class HistoryDetailViewModel(
    private val key: TaskInstanceKey,
    private val repository: HistoryRepository,
    private val noteService: TaskNoteService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(HistoryDetailUiState())
    val state: StateFlow<HistoryDetailUiState> = mutableState.asStateFlow()
    private var savedNote = ""
    private var noteJob: Job? = null

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            runCatching { repository.getDetail(key) }.onSuccess { result ->
                if (result == null) {
                    mutableState.value = HistoryDetailUiState(
                        detail = ExecutionUiState(loading = false, errorMessage = "找不到历史任务。"),
                    )
                    return@onSuccess
                }
                savedNote = result.note
                mutableState.value = HistoryDetailUiState(
                    detail = ExecutionUiState(
                        loading = false,
                        instance = result.instance,
                        steps = result.steps,
                        execution = result.execution,
                        informationDraft = (result.execution as? com.ds.localtaskmanager.domain.execution.ExecutionState.Information)?.content.orEmpty(),
                        noteDraft = result.note,
                        noteSaveState = NoteSaveState.SAVED,
                    ),
                    timeline = (result.logs.map { log ->
                        TaskDetailTimelineItem(
                            title = actionLabel(log.action),
                            detail = actionDetail(log.action, log.detail),
                            timestamp = formatTime(log.createdAtEpochMillis),
                            sortEpochMillis = log.createdAtEpochMillis,
                        )
                    } + result.revisions.map { revision ->
                        TaskDetailTimelineItem(
                            title = revisionLabel(revision.reason),
                            detail = revisionPointsDetail(revision, result.revisionGroupNames),
                            timestamp = formatTime(revision.createdAtEpochMillis),
                            sortEpochMillis = revision.createdAtEpochMillis,
                        )
                    }).sortedNewestFirst(),
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    detail = ExecutionUiState(loading = false, errorMessage = error.message ?: "历史记录加载失败。"),
                )
            }
        }
    }

    fun updateNote(value: String) {
        val detail = mutableState.value.detail.copy(
            noteDraft = value,
            noteSaveState = if (value == savedNote) NoteSaveState.SAVED else NoteSaveState.SAVING,
        )
        mutableState.value = mutableState.value.copy(detail = detail)
        noteJob?.cancel()
        if (value != savedNote) noteJob = viewModelScope.launch {
            delay(500)
            saveNote()
        }
    }

    fun flushNote(onSaved: () -> Unit) {
        noteJob?.cancel()
        if (mutableState.value.detail.noteDraft == savedNote) onSaved() else viewModelScope.launch {
            if (saveNote()) onSaved()
        }
    }

    private suspend fun saveNote(): Boolean = runCatching {
        val value = mutableState.value.detail.noteDraft
        noteService.saveNote(key, value)
        savedNote = value
        mutableState.value = mutableState.value.copy(
            detail = mutableState.value.detail.copy(noteSaveState = NoteSaveState.SAVED),
        )
    }.fold(
        onSuccess = { true },
        onFailure = {
            mutableState.value = mutableState.value.copy(
                detail = mutableState.value.detail.copy(noteSaveState = NoteSaveState.ERROR),
            )
            false
        },
    )

    private fun actionLabel(action: String): String = when (action) {
        "COMPLETED" -> "任务已完成"
        "COMPLETION_UNDONE" -> "已撤销完成"
        "CANCELLED", "RECURRENCE_CANCELLED" -> "任务已撤销"
        "STEP_COMPLETED" -> "步骤已完成"
        "STEP_UNDONE" -> "步骤已恢复"
        "COUNTER_CHANGED" -> "计数已更新"
        "TIMER_ELAPSED_ADDED" -> "计时进度已更新"
        "INFORMATION_DRAFT_SAVED" -> "告知正文已保存"
        "TASK_DATE_MOVED" -> "任务日期已调整"
        "TASK_REOPENED" -> "任务已重新开放"
        "DEADLINE_EXTENDED" -> "截止时间已延后"
        "STATUS_RECONCILED" -> "任务状态已更新"
        "INSTANCE_PUBLISHED" -> "任务已生成"
        else -> "任务记录已更新"
    }

    private fun actionDetail(action: String, detail: String?): String? = when (action) {
        "STEP_COMPLETED", "STEP_UNDONE" -> detail?.toIntOrNull()?.let { "第 ${it + 1} 步" }
        else -> null
    }

    private fun revisionLabel(reason: String): String = when (reason) {
        "TASK_COMPLETED" -> "当日结果已更新：任务完成"
        "COMPLETION_UNDONE" -> "当日结果已更新：撤销完成"
        "TASK_DELAYED" -> "当日结果已更新：截止时间延后"
        "TASK_REOPENED" -> "当日结果已更新：任务重新开放"
        "TASK_DATE_MOVED" -> "当日结果已更新：任务日期调整"
        else -> "当日结果已更新"
    }

    private fun formatTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"))
}

class HistoryViewModelFactory(
    private val repository: HistoryRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = HistoryViewModel(repository) as T
}

class HistoryDetailViewModelFactory(
    private val key: TaskInstanceKey,
    private val repository: HistoryRepository,
    private val noteService: TaskNoteService,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        HistoryDetailViewModel(key, repository, noteService) as T
}

data class DayHistoryUiState(
    val loading: Boolean = true,
    val taskDate: String = "",
    val result: DailyResultSnapshot? = null,
    val tasks: List<HistoryTask> = emptyList(),
    val revisions: List<TaskDetailTimelineItem> = emptyList(),
    val errorMessage: String? = null,
)

class DayHistoryViewModel(
    private val taskDate: String,
    private val historyRepository: HistoryRepository,
    private val resultRepository: ResultRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DayHistoryUiState(taskDate = taskDate))
    val state: StateFlow<DayHistoryUiState> = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val page = historyRepository.loadPage(
                    HistoryQuery(selectedDate = taskDate),
                    throughDate = taskDate,
                    page = 0,
                    pageSize = 1,
                )
                Triple(
                    page.days.firstOrNull()?.tasks.orEmpty(),
                    resultRepository.getDailyResult(taskDate),
                    resultRepository.getRevisionTimeline(taskDate),
                )
            }.onSuccess { (tasks, result, revisions) ->
                val groupNames = buildMap {
                    tasks.forEach { task ->
                        task.instance.groupId?.let { groupId ->
                            task.instance.groupNameSnapshot?.let { put(groupId, it) }
                        }
                    }
                    result?.groups.orEmpty().forEach { group ->
                        group.groupId?.let { putIfAbsent(it, group.groupName ?: "未命名积分组") }
                    }
                }
                mutableState.value = DayHistoryUiState(
                    loading = false,
                    taskDate = taskDate,
                    result = result,
                    tasks = tasks,
                    revisions = revisions.map { revisionTimelineItem(it, groupNames) }.sortedNewestFirst(),
                )
            }.onFailure { error ->
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    errorMessage = error.message ?: "当日历史加载失败。",
                )
            }
        }
    }

    private fun revisionTimelineItem(
        revision: ResultRevisionEntity,
        groupNames: Map<String, String>,
    ) = TaskDetailTimelineItem(
        title = when (revision.reason) {
            "TASK_COMPLETED" -> "任务完成后更新结果"
            "COMPLETION_UNDONE" -> "撤销完成后更新结果"
            "TASK_DELAYED" -> "截止时间延后后更新结果"
            "TASK_REOPENED" -> "任务重新开放后更新结果"
            "TASK_DATE_MOVED" -> "任务日期调整后更新结果"
            else -> "当日结果已更新"
        },
        detail = revisionPointsDetail(revision, groupNames),
        timestamp = Instant.ofEpochMilli(revision.createdAtEpochMillis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")),
        sortEpochMillis = revision.createdAtEpochMillis,
    )
}

internal fun List<TaskDetailTimelineItem>.sortedNewestFirst(): List<TaskDetailTimelineItem> =
    sortedByDescending(TaskDetailTimelineItem::sortEpochMillis)

internal fun revisionPointsDetail(
    revision: ResultRevisionEntity,
    groupNames: Map<String, String>,
): String? {
    if (revision.oldPoints == null && revision.newPoints == null) return null
    val change = "从 ${revision.oldPoints ?: 0} 分调整为 ${revision.newPoints ?: 0} 分"
    return when (revision.scope) {
        "GLOBAL" -> "每日积分$change"
        "GROUP" -> revision.groupId?.let { groupId ->
            "积分组「${groupNames[groupId] ?: "未命名积分组"}」的积分$change"
        } ?: "未分组积分$change"
        else -> "积分$change"
    }
}

class DayHistoryViewModelFactory(
    private val taskDate: String,
    private val historyRepository: HistoryRepository,
    private val resultRepository: ResultRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DayHistoryViewModel(taskDate, historyRepository, resultRepository) as T
}
