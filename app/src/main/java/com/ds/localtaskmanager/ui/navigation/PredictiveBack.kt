package com.ds.localtaskmanager.ui.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ds.localtaskmanager.ui.theme.LocalReduceMotion
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.collect

@Immutable
data class PredictiveBackUiState(
    val progress: Float = 0f,
    val swipeEdge: Int = BackEventCompat.EDGE_LEFT,
)

@Composable
fun rememberPredictiveBackState(
    enabled: Boolean = true,
    onBack: () -> Unit,
): PredictiveBackUiState {
    var state by remember { mutableStateOf(PredictiveBackUiState()) }
    val currentOnBack by rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { event ->
                state = PredictiveBackUiState(
                    progress = event.progress.coerceIn(0f, 1f),
                    swipeEdge = event.swipeEdge,
                )
            }
            state = state.copy(progress = 1f)
            currentOnBack()
        } catch (cancellation: CancellationException) {
            state = PredictiveBackUiState()
            throw cancellation
        }
    }
    return state
}

@Composable
fun Modifier.predictiveBackTransform(state: PredictiveBackUiState): Modifier {
    if (LocalReduceMotion.current || state.progress <= 0f) return this
    val maxTranslation = with(LocalDensity.current) { 24.dp.toPx() }
    val direction = if (state.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
    val progress = state.progress
    return graphicsLayer {
        translationX = direction * maxTranslation * progress
        scaleX = 1f - 0.04f * progress
        scaleY = 1f - 0.04f * progress
        alpha = 1f - 0.08f * progress
        transformOrigin = TransformOrigin(
            pivotFractionX = if (direction > 0f) 0f else 1f,
            pivotFractionY = 0.5f,
        )
    }
}
