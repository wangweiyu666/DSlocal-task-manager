package com.ds.localtaskmanager.ui.sharing

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ds.localtaskmanager.sharing.GeneratedShareImage
import com.ds.localtaskmanager.sharing.ResultShareTaskFilter
import com.ds.localtaskmanager.sharing.ShareImageService
import com.ds.localtaskmanager.ui.components.BackNavigationIcon
import com.ds.localtaskmanager.ui.navigation.predictiveBackTransform
import com.ds.localtaskmanager.ui.navigation.rememberPredictiveBackState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class PendingSensitiveAction { SAVE, SEND }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePreviewDialog(
    image: GeneratedShareImage,
    service: ShareImageService,
    sensitive: Boolean,
    onResultFilterChange: (suspend (ResultShareTaskFilter) -> GeneratedShareImage)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var displayedImage by remember(image) { mutableStateOf(image) }
    var selectedFilter by remember(image) { mutableStateOf(ResultShareTaskFilter.ALL) }
    var working by remember { mutableStateOf(false) }
    var regenerating by remember { mutableStateOf(false) }
    var regenerationJob by remember { mutableStateOf<Job?>(null) }
    var regenerationRequest by remember { mutableIntStateOf(0) }
    var pendingSensitiveAction by remember { mutableStateOf<PendingSensitiveAction?>(null) }
    val busy = working || regenerating

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            working = true
            runCatching { service.writeToUri(displayedImage, uri) }
                .onSuccess { snackbar.showSnackbar("图片已保存") }
                .onFailure { snackbar.showSnackbar("保存失败，请重试") }
            working = false
        }
    }

    fun save() {
        if (sensitive && !service.informationPrivacyConfirmed()) {
            pendingSensitiveAction = PendingSensitiveAction.SAVE
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) scope.launch {
            working = true
            runCatching { service.saveToGallery(displayedImage) }
                .onSuccess { snackbar.showSnackbar("已保存到相册") }
                .onFailure { snackbar.showSnackbar("保存失败，请重试") }
            working = false
        } else {
            createDocument.launch(displayedImage.fileName)
        }
    }

    fun send() {
        if (sensitive && !service.informationPrivacyConfirmed()) {
            pendingSensitiveAction = PendingSensitiveAction.SEND
            return
        }
        scope.launch {
            working = true
            runCatching { service.cache(displayedImage) }
                .onSuccess { service.send(context, it) }
                .onFailure { snackbar.showSnackbar("暂时无法发送图片") }
            working = false
        }
    }

    fun selectFilter(filter: ResultShareTaskFilter) {
        val regenerate = onResultFilterChange ?: return
        if (filter == selectedFilter || regenerating) return
        val previousFilter = selectedFilter
        selectedFilter = filter
        regenerationJob?.cancel()
        val request = ++regenerationRequest
        regenerationJob = scope.launch {
            regenerating = true
            try {
                displayedImage = regenerate(filter)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (request == regenerationRequest) {
                    selectedFilter = previousFilter
                    snackbar.showSnackbar("暂时无法更新图片")
                }
            } finally {
                if (request == regenerationRequest) regenerating = false
            }
        }
    }

    val predictiveBack = rememberPredictiveBackState(
        enabled = pendingSensitiveAction == null,
        onBack = onDismiss,
    )
    Surface(
        modifier = Modifier.fillMaxSize().predictiveBackTransform(predictiveBack),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = { Text("分享预览") },
                        navigationIcon = { BackNavigationIcon(onDismiss) },
                    )
                },
                bottomBar = {
                    Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = ::save,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("保存到相册") }
                            Button(
                                onClick = ::send,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("发送给其他应用") }
                        }
                    }
                },
            ) { padding ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                ) {
                    if (onResultFilterChange != null) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("显示内容", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = selectedFilter == ResultShareTaskFilter.ALL,
                                    onClick = { selectFilter(ResultShareTaskFilter.ALL) },
                                    label = { Text("全部任务") },
                                    enabled = !busy,
                                    modifier = Modifier.testTag("share-filter-all"),
                                )
                                FilterChip(
                                    selected = selectedFilter == ResultShareTaskFilter.INCOMPLETE_ONLY,
                                    onClick = { selectFilter(ResultShareTaskFilter.INCOMPLETE_ONLY) },
                                    label = { Text("仅未完成") },
                                    enabled = !busy,
                                    modifier = Modifier.testTag("share-filter-incomplete"),
                                )
                            }
                        }
                    }
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    ) {
                        val previewHeight = maxHeight
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = previewHeight)
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Text(
                                        "双指缩放 · 双击放大",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.align(Alignment.End),
                                    )
                                    ZoomableShareImage(displayedImage)
                                }
                            }
                        }
                        if (busy) {
                            Box(
                                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
        }
    }

    pendingSensitiveAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingSensitiveAction = null },
            title = { Text("确认分享告知内容") },
            text = { Text("图片包含你填写的告知内容，请确认接收方和保存位置。") },
            dismissButton = { TextButton(onClick = { pendingSensitiveAction = null }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    pendingSensitiveAction = null
                    service.confirmInformationPrivacy()
                    if (action == PendingSensitiveAction.SAVE) save() else send()
                }) { Text("继续") }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ZoomableShareImage(image: GeneratedShareImage) {
    var scale by remember(image.bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(image.bitmap) { mutableStateOf(Offset.Zero) }
    var imageSize by remember(image.bitmap) { mutableStateOf(IntSize.Zero) }

    fun constrainedOffset(candidate: Offset, targetScale: Float): Offset {
        val maxX = imageSize.width * (targetScale - 1f) / 2f
        val maxY = imageSize.height * (targetScale - 1f) / 2f
        return if (targetScale <= 1f) Offset.Zero else Offset(
            x = candidate.x.coerceIn(-maxX, maxX),
            y = candidate.y.coerceIn(-maxY, maxY),
        )
    }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val targetScale = (scale * zoomChange).coerceIn(1f, 3f)
        offset = constrainedOffset(offset + panChange, targetScale)
        scale = targetScale
    }

    LaunchedEffect(image.bitmap) {
        scale = 1f
        offset = Offset.Zero
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(14.dp))
            .padding(3.dp),
    ) {
        Image(
            bitmap = image.bitmap.asImageBitmap(),
            contentDescription = "待分享图片预览",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(image.bitmap.width.toFloat() / image.bitmap.height.coerceAtLeast(1))
                .onSizeChanged { imageSize = it }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
                .transformable(
                    state = transformState,
                    canPan = { scale > 1f },
                    lockRotationOnZoomPan = true,
                )
                .pointerInput(image.bitmap) {
                    detectTapGestures(
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2f
                            offset = Offset.Zero
                        },
                    )
                },
            contentScale = ContentScale.FillWidth,
        )
    }
}
