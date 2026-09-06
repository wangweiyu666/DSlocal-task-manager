package com.ds.localtaskmanager.ui.execution

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.ds.localtaskmanager.BuildConfig
import com.ds.localtaskmanager.R
import kotlin.math.roundToInt

private val moodLabels = listOf("很差", "较差", "一般", "不错", "很好")
private val moodFaces = listOf(R.drawable.mood_1, R.drawable.mood_2, R.drawable.mood_3, R.drawable.mood_4, R.drawable.mood_5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoodSection(
    rating: Int?,
    text: String,
    editable: Boolean,
    working: Boolean,
    saveState: NoteSaveState,
    onRatingChange: (Int) -> Unit,
    onTextChange: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val selected = rating?.takeIf { it in 1..5 }
    val label = selected?.let { moodLabels[it - 1] } ?: "尚未选择心情"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("心情记录", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (selected != null) Image(
                    painterResource(moodFaces[selected - 1]), contentDescription = null,
                    modifier = Modifier.size(64.dp),
                )
                Text(label, style = MaterialTheme.typography.headlineMedium)
            }
            if (editable) {
                if (selected == null) Text("滑动或点选，记录今天的心情", style = MaterialTheme.typography.bodyMedium)
                val haptic = LocalHapticFeedback.current
                val choose: (Int) -> Unit = { value ->
                    if (!working && (value != selected || selected == null)) {
                        if (selected != null) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onRatingChange(value)
                    }
                }
                val currentChoose by rememberUpdatedState(choose)
                val currentRating by rememberUpdatedState(selected)
                val currentWorking by rememberUpdatedState(working)
                val primary = MaterialTheme.colorScheme.primary
                val neutral = MaterialTheme.colorScheme.outlineVariant
                val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
                Slider(
                    value = (selected ?: 3).toFloat(),
                    onValueChange = { choose(it.roundToInt().coerceIn(1, 5)) },
                    valueRange = 1f..5f,
                    steps = 3,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        // A touch at the initial midpoint must count as an explicit answer too.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (currentRating == null && !currentWorking) {
                                    val inset = 10.dp.toPx()
                                    val fraction = ((down.position.x - inset) / (size.width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
                                    currentChoose((1 + fraction * 4).roundToInt())
                                }
                            }
                        }
                        .clearAndSetSemantics {
                            contentDescription = "心情"
                            stateDescription = selected?.let { moodLabels[it - 1] } ?: "未选择"
                            progressBarRangeInfo = ProgressBarRangeInfo((selected ?: 3).toFloat(), 1f..5f, 3)
                            if (working) disabled() else setProgress { value ->
                                choose(value.roundToInt().coerceIn(1, 5)); true
                            }
                        },
                    colors = SliderDefaults.colors(
                        activeTrackColor = if (selected == null) neutral else primary,
                        inactiveTrackColor = neutral,
                        activeTickColor = if (selected == null) neutral else MaterialTheme.colorScheme.onPrimary,
                        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    track = { sliderState ->
                        if (selected == null) {
                            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                                drawLine(neutral, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = size.height)
                                repeat(5) { index -> drawCircle(tickColor, 2.dp.toPx(), Offset(size.width * index / 4, center.y)) }
                            }
                        } else {
                            SliderDefaults.Track(sliderState, colors = SliderDefaults.colors(inactiveTrackColor = neutral))
                        }
                    },
                    thumb = {
                        Canvas(Modifier.size(20.dp)) {
                            if (selected != null) drawCircle(if (working) neutral else primary)
                        }
                    },
                )
                if (LocalDensity.current.fontScale <= 1.3f) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        moodLabels.forEach { Text(it, style = MaterialTheme.typography.labelMedium) }
                    }
                }
                val count = text.codePointCount(0, text.length)
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("想说点什么？（选填）") },
                    isError = count > 2000,
                    supportingText = { Text("$count / 2000") },
                )
                if (BuildConfig.CONNECTED_BUILD) Text("完成后管理员可见", style = MaterialTheme.typography.bodySmall)
                Text(
                    when (saveState) {
                        NoteSaveState.SAVED -> if (selected == null && text.isEmpty()) "选择后自动保存到本机" else "已保存到本机"
                        NoteSaveState.SAVING -> "正在保存到本机…"
                        NoteSaveState.ERROR -> "保存失败，尚未保存当前修改"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (saveState == NoteSaveState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (saveState == NoteSaveState.ERROR) TextButton(onClick = onRetry, enabled = !working) { Text("重试保存") }
            } else if (text.isNotEmpty()) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
