package com.ds.localtaskmanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ds.localtaskmanager.data.TaskInstanceEntity

private enum class Destination(
    val route: String,
    val label: String,
    val marker: String,
) {
    History("history", "历史", "◷"),
    Today("today", "今日", "●"),
    Profile("profile", "我的", "○"),
}

@Composable
fun DstApp(todayViewModel: TodayViewModel) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val importState by todayViewModel.importState.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Text(destination.marker) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == Destination.Today.route) {
                ExtendedFloatingActionButton(
                    onClick = todayViewModel::openImport,
                    text = { Text("导入任务") },
                    icon = { Text("+") },
                )
            }
        },
    ) { contentPadding ->
        NavHost(
            navController = navController,
            startDestination = Destination.Today.route,
            modifier = Modifier.padding(contentPadding),
        ) {
            composable(Destination.History.route) {
                PlaceholderScreen(title = "历史", message = "完成记录将按任务日展示")
            }
            composable(Destination.Today.route) {
                TodayScreen(viewModel = todayViewModel)
            }
            composable(Destination.Profile.route) {
                PlaceholderScreen(title = "我的", message = "积分、统计与设置")
            }
        }
    }

    if (importState.visible) {
        ImportDialog(
            state = importState,
            onInputChange = todayViewModel::updateImportInput,
            onPreview = todayViewModel::previewImport,
            onConfirm = todayViewModel::confirmImport,
            onDismiss = todayViewModel::closeImport,
        )
    }
}

@Composable
private fun ImportDialog(
    state: ImportUiState,
    onInputChange: (String) -> Unit,
    onPreview: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.working) onDismiss() },
        title = { Text(if (state.preview == null) "导入 DST1 任务" else "确认导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.preview == null) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = onInputChange,
                        label = { Text("粘贴任务字符串") },
                        minLines = 5,
                        maxLines = 10,
                        enabled = !state.working,
                    )
                } else {
                    Text(state.preview.summary)
                    state.preview.taskChanges.forEach { change ->
                        Text("${change.name}：${change.types.joinToString()}")
                    }
                }
                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
                if (state.working) CircularProgressIndicator()
            }
        },
        confirmButton = {
            Button(
                onClick = if (state.preview == null) onPreview else onConfirm,
                enabled = !state.working && (state.preview != null || state.input.isNotBlank()),
            ) {
                Text(if (state.preview == null) "校验并预览" else "确认导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.working) { Text("取消") }
        },
    )
}

@Composable
private fun TodayScreen(viewModel: TodayViewModel) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("今日", style = MaterialTheme.typography.headlineLarge)
            Text("任务日 ${viewModel.taskDate}", style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun PlaceholderScreen(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}
