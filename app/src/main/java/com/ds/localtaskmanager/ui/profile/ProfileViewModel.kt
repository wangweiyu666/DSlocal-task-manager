package com.ds.localtaskmanager.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ds.localtaskmanager.data.statistics.GroupStatistics
import com.ds.localtaskmanager.data.statistics.LedgerItem
import com.ds.localtaskmanager.data.statistics.LedgerQuery
import com.ds.localtaskmanager.data.statistics.LedgerType
import com.ds.localtaskmanager.data.statistics.StatisticsDashboard
import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import com.ds.localtaskmanager.data.statistics.StatisticsRepository
import com.ds.localtaskmanager.settings.AppSettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProfileUiState(
    val loading: Boolean = true,
    val period: StatisticsPeriod = StatisticsPeriod.THIRTY_DAYS,
    val dashboard: StatisticsDashboard? = null,
    val errorMessage: String? = null,
    val workingGroupId: String? = null,
)

class ProfileViewModel(
    private val repository: StatisticsRepository,
    private val settingsRepository: AppSettingsRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        ProfileUiState(period = settingsRepository.settings.value.lastStatisticsPeriod),
    )
    val state: StateFlow<ProfileUiState> = mutableState.asStateFlow()
    private var loadJob: Job? = null

    init { refresh() }

    fun selectPeriod(period: StatisticsPeriod) {
        if (period == mutableState.value.period) return
        mutableState.value = mutableState.value.copy(period = period)
        settingsRepository.setLastStatisticsPeriod(period)
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val previous = mutableState.value.dashboard
            mutableState.value = mutableState.value.copy(loading = previous == null, errorMessage = null)
            runCatching { repository.dashboard(mutableState.value.period) }
                .onSuccess { dashboard ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        dashboard = dashboard,
                        errorMessage = null,
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        errorMessage = error.message ?: "统计数据加载失败。",
                    )
                }
        }
    }

    fun setArchived(groupId: String, archived: Boolean) {
        if (mutableState.value.workingGroupId != null) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(workingGroupId = groupId)
            runCatching { repository.setGroupArchived(groupId, archived) }
                .onSuccess { refresh() }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        errorMessage = error.message ?: "积分组更新失败。",
                    )
                }
            mutableState.value = mutableState.value.copy(workingGroupId = null)
        }
    }
}

data class LedgerUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val query: LedgerQuery,
    val groups: List<GroupStatistics> = emptyList(),
    val items: List<LedgerItem> = emptyList(),
    val endReached: Boolean = false,
    val errorMessage: String? = null,
)

class LedgerViewModel(
    private val repository: StatisticsRepository,
    initialPeriod: StatisticsPeriod,
    initialGroupId: String?,
    initialUngrouped: Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        LedgerUiState(
            query = LedgerQuery(
                period = initialPeriod,
                groupId = initialGroupId,
                ungroupedOnly = initialUngrouped,
            ),
        ),
    )
    val state: StateFlow<LedgerUiState> = mutableState.asStateFlow()
    private var page = 0
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { repository.dashboard(StatisticsPeriod.ALL).groups }
                .onSuccess { mutableState.value = mutableState.value.copy(groups = it) }
            reload()
        }
    }

    fun updateSearch(text: String) {
        mutableState.value = mutableState.value.copy(query = mutableState.value.query.copy(text = text))
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            reload()
        }
    }

    fun selectPeriod(period: StatisticsPeriod) = updateQuery(mutableState.value.query.copy(period = period))

    fun selectGroup(groupId: String?, ungrouped: Boolean = false) = updateQuery(
        mutableState.value.query.copy(groupId = groupId, ungroupedOnly = ungrouped),
    )

    fun toggleType(type: LedgerType) {
        val selected = mutableState.value.query.types
        val next = if (type in selected) selected - type else selected + type
        updateQuery(mutableState.value.query.copy(types = next))
    }

    fun retry() = reload()

    fun loadMore() {
        val state = mutableState.value
        if (state.loading || state.loadingMore || state.endReached) return
        viewModelScope.launch {
            mutableState.value = state.copy(loadingMore = true)
            runCatching { repository.ledger(state.query, page + 1) }
                .onSuccess { result ->
                    page += 1
                    mutableState.value = mutableState.value.copy(
                        loadingMore = false,
                        items = mutableState.value.items + result.items,
                        endReached = result.endReached,
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        loadingMore = false,
                        errorMessage = error.message ?: "积分流水加载失败。",
                    )
                }
        }
    }

    private fun updateQuery(query: LedgerQuery) {
        mutableState.value = mutableState.value.copy(query = query)
        reload()
    }

    private fun reload() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            page = 0
            val previous = mutableState.value.items
            mutableState.value = mutableState.value.copy(loading = previous.isEmpty(), errorMessage = null)
            runCatching { repository.ledger(mutableState.value.query, 0) }
                .onSuccess { result ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        items = result.items,
                        endReached = result.endReached,
                    )
                }
                .onFailure { error ->
                    mutableState.value = mutableState.value.copy(
                        loading = false,
                        errorMessage = error.message ?: "积分流水加载失败。",
                    )
                }
        }
    }
}

class ProfileViewModelFactory(
    private val repository: StatisticsRepository,
    private val settingsRepository: AppSettingsRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(repository, settingsRepository) as T
}

class LedgerViewModelFactory(
    private val repository: StatisticsRepository,
    private val period: StatisticsPeriod,
    private val groupId: String?,
    private val ungrouped: Boolean,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        LedgerViewModel(repository, period, groupId, ungrouped) as T
}
