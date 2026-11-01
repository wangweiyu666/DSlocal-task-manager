package com.ds.localtaskmanager.ui.execution

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

fun formatTemperature(value: Int?): String = value?.let { "${it / 10}.${it % 10}℃" } ?: "未选择"

@Composable
fun TemperatureSection(
    value: Int?,
    editable: Boolean,
    saveState: NoteSaveState,
    onChange: (Int) -> Unit,
    onRetry: () -> Unit,
) {
    var gestureValue by remember(value) { mutableIntStateOf(value ?: 365) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("体温询问", style = MaterialTheme.typography.titleMedium)
            Text(formatTemperature(value), style = MaterialTheme.typography.headlineMedium)
            if (editable) {
                Text("滑动选择体温后，主动确认完成。")
                Slider(
                    value = (value ?: 365).toFloat(),
                    onValueChange = { gestureValue = it.roundToInt().coerceIn(350, 420); onChange(gestureValue) },
                    onValueChangeFinished = { if (value == null) onChange(gestureValue) },
                    valueRange = 350f..420f,
                    steps = 69,
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "体温，35.0 至 42.0 摄氏度，每步 0.1 度"
                        stateDescription = formatTemperature(value)
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("35.0℃"); Text("42.0℃")
                }
                when (saveState) {
                    NoteSaveState.SAVING -> Text("正在保存…", style = MaterialTheme.typography.bodySmall)
                    NoteSaveState.ERROR -> TextButton(onClick = onRetry) { Text("保存失败，重试") }
                    NoteSaveState.SAVED -> if (value != null) Text("已保存", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
