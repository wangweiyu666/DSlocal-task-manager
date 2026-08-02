package com.ds.localtaskmanager.ui.profile

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.data.statistics.GroupStatistics
import kotlin.math.roundToInt

@Composable
fun ArchivedGroupsRoute(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    onLedger: (GroupStatistics) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val archived = state.dashboard?.groups.orEmpty()
        .filter(GroupStatistics::archived)
        .sortedBy(GroupStatistics::createdAtEpochMillis)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("返回") }
            Text("已归档积分组", style = MaterialTheme.typography.titleLarge)
        }
        when {
            state.loading && state.dashboard == null -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(40.dp))
            state.errorMessage != null && state.dashboard == null -> Column(
                Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(requireNotNull(state.errorMessage), color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::refresh) { Text("重试") }
            }
            archived.isEmpty() -> Text(
                "还没有已归档积分组。",
                Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items = archived, key = { requireNotNull(it.groupId) }) { group ->
                    Card(onClick = { onLedger(group) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(group.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                Text(if (group.points > 0) "+${group.points}" else group.points.toString())
                            }
                            Text(
                                group.completion.fraction?.let { "必做完成率 ${(it * 100).roundToInt()}%" } ?: "必做完成率 —",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    viewModel.setArchived(requireNotNull(group.groupId), false)
                                    Toast.makeText(context, "已取消归档", Toast.LENGTH_SHORT).show()
                                },
                                enabled = state.workingGroupId == null,
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("取消归档") }
                        }
                    }
                }
            }
        }
    }
}
