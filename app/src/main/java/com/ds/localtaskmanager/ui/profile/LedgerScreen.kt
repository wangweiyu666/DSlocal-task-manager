package com.ds.localtaskmanager.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.data.statistics.LedgerItem
import com.ds.localtaskmanager.data.statistics.LedgerType
import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LedgerRoute(viewModel: LedgerViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LedgerScreen(state, viewModel, onBack)
}

@Composable
private fun LedgerScreen(state: LedgerUiState, viewModel: LedgerViewModel, onBack: () -> Unit) {
    val listState = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(atEnd) { if (atEnd) viewModel.loadMore() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("积分流水", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = state.query.text,
            onValueChange = viewModel::updateSearch,
            label = { Text("搜索任务名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatisticsPeriod.entries.forEach { period ->
                FilterChip(
                    selected = state.query.period == period,
                    onClick = { viewModel.selectPeriod(period) },
                    label = { Text(period.label) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerType.entries.forEach { type ->
                FilterChip(
                    selected = type in state.query.types,
                    onClick = { viewModel.toggleType(type) },
                    label = { Text(type.label) },
                )
            }
            GroupFilter(state, viewModel)
        }

        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(40.dp))
            state.errorMessage != null && state.items.isEmpty() -> Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::retry) { Text("重试") }
            }
            state.items.isEmpty() -> Text(
                "没有符合条件的积分流水。",
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.items, key = LedgerItem::stableId) { item -> LedgerRow(item) }
                if (state.loadingMore) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                state.errorMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        }
    }
}

@Composable
private fun GroupFilter(state: LedgerUiState, viewModel: LedgerViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = when {
        state.query.ungroupedOnly -> "未分组"
        state.query.groupId != null -> state.groups.firstOrNull { it.groupId == state.query.groupId }?.name ?: "积分组"
        else -> "全部积分组"
    }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }) { Text(selectedName) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部积分组") }, onClick = {
                expanded = false
                viewModel.selectGroup(null)
            })
            state.groups.filter { it.groupId != null }.forEach { group ->
                DropdownMenuItem(text = { Text(group.name) }, onClick = {
                    expanded = false
                    viewModel.selectGroup(group.groupId)
                })
            }
            DropdownMenuItem(text = { Text("未分组") }, onClick = {
                expanded = false
                viewModel.selectGroup(null, true)
            })
        }
    }
}

@Composable
private fun LedgerRow(item: LedgerItem) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.taskName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                when (item) {
                    is LedgerItem.Change -> Text(
                        if (item.delta > 0) "+${item.delta}" else item.delta.toString(),
                        color = if (item.delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    is LedgerItem.Transfer -> Text("${item.points} 分", style = MaterialTheme.typography.titleMedium)
                }
            }
            Text(
                when (item) {
                    is LedgerItem.Change -> "${item.groupName} · ${item.type.detailLabel}"
                    is LedgerItem.Transfer -> "积分从${item.sourceNames.joinToString("、") { "「$it」" }}转移至「${item.targetName}」"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(formatTime(item.createdAtEpochMillis), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val StatisticsPeriod.label: String
    get() = when (this) {
        StatisticsPeriod.SEVEN_DAYS -> "近 7 日"
        StatisticsPeriod.THIRTY_DAYS -> "近 30 日"
        StatisticsPeriod.ALL -> "全部"
    }

private val LedgerType.label: String
    get() = when (this) {
        LedgerType.EARNED -> "获得积分"
        LedgerType.DEDUCTED -> "扣回积分"
        LedgerType.TRANSFER -> "历史转移"
    }

private val LedgerType.detailLabel: String
    get() = when (this) {
        LedgerType.EARNED -> "完成任务获得积分"
        LedgerType.DEDUCTED -> "撤销完成扣回积分"
        LedgerType.TRANSFER -> "历史积分转移"
    }

private fun formatTime(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm"))
