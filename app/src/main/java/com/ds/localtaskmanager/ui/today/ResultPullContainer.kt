package com.ds.localtaskmanager.ui.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

@Composable
fun ResultPullContainer(
    atTop: () -> Boolean,
    onOpenResult: () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    val currentAtTop by rememberUpdatedState(atTop)
    val currentOpen by rememberUpdatedState(onOpenResult)
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    var distance by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(distance >= threshold) {
        if (distance >= threshold) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    Box(
        Modifier.fillMaxSize()
            .testTag("result-pull-container")
            .pointerInput(threshold) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val pointerId = down.id
                    var previousY = down.position.y
                    distance = 0f
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            pressed = false
                        } else {
                            val deltaY = change.position.y - previousY
                            previousY = change.position.y
                            when {
                                deltaY > 0f && currentAtTop() -> {
                                    distance = (distance + deltaY).coerceAtMost(threshold * 1.25f)
                                }
                                deltaY < 0f && distance > 0f -> {
                                    distance = (distance + deltaY).coerceAtLeast(0f)
                                }
                            }
                        }
                    }
                    if (distance >= threshold) currentOpen()
                    distance = 0f
                }
            }
            .semantics {
                customActions = listOf(CustomAccessibilityAction("查看今日结果") { currentOpen(); true })
            },
    ) {
        content(Modifier.fillMaxSize())
        if (distance > 4f) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(progress = { (distance / threshold).coerceIn(0f, 1f) })
                Text(
                    if (distance >= threshold) "松手查看今日结果" else "继续下拉查看今日结果",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}
