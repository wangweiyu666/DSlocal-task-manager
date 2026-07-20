package com.ds.localtaskmanager.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun ImportDialog(
    state: ImportUiState,
    onInputChange: (String) -> Unit,
    onPreview: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!state.working) onDismiss() },
        title = { Text(if (state.preview == null) "导入任务" else "确认导入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.preview == null) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = onInputChange,
                        label = { Text("粘贴任务内容") },
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
