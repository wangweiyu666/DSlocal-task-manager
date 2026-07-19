package com.ds.localtaskmanager.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ds.localtaskmanager.data.TaskInstanceEntity

@Composable
fun TodayScreen(viewModel: TodayViewModel) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val taskDate by viewModel.taskDate.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("今日", style = MaterialTheme.typography.headlineLarge)
            Text("任务日 $taskDate", style = MaterialTheme.typography.bodyMedium)
        }
        if (tasks.isEmpty()) {
            item {
                Text(
                    "还没有任务，点击右下角导入 DST1 字符串。",
                    modifier = Modifier.padding(top = 32.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        } else {
            items(tasks, key = { "${it.taskId}:${it.occurrenceKey}" }) { task ->
                TaskCard(task)
            }
        }
    }
}

@Composable
private fun TaskCard(task: TaskInstanceEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(task.name, style = MaterialTheme.typography.titleMedium)
            Text(
                if (task.required) "必做 · ${task.points} 分" else "选做 · ${task.points} 分",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
