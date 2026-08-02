package com.ds.localtaskmanager.ui.sharing

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ds.localtaskmanager.sharing.GeneratedShareImage
import com.ds.localtaskmanager.sharing.ShareImageService
import kotlinx.coroutines.launch

private enum class PendingSensitiveAction { SAVE, SEND }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePreviewDialog(
    image: GeneratedShareImage,
    service: ShareImageService,
    sensitive: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var working by remember { mutableStateOf(false) }
    var pendingSensitiveAction by remember { mutableStateOf<PendingSensitiveAction?>(null) }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            working = true
            runCatching { service.writeToUri(image, uri) }
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
            runCatching { service.saveToGallery(image) }
                .onSuccess { snackbar.showSnackbar("已保存到相册") }
                .onFailure { snackbar.showSnackbar("保存失败，请重试") }
            working = false
        } else {
            createDocument.launch(image.fileName)
        }
    }

    fun send() {
        if (sensitive && !service.informationPrivacyConfirmed()) {
            pendingSensitiveAction = PendingSensitiveAction.SEND
            return
        }
        scope.launch {
            working = true
            runCatching { service.cache(image) }
                .onSuccess { service.send(context, it) }
                .onFailure { snackbar.showSnackbar("暂时无法发送图片") }
            working = false
        }
    }

    BackHandler(onBack = onDismiss)
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            )
            Scaffold(
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                snackbarHost = { SnackbarHost(snackbar) },
                topBar = {
                    TopAppBar(
                        title = { Text("图片预览") },
                        navigationIcon = { TextButton(onClick = onDismiss) { Text("返回") } },
                    )
                },
                bottomBar = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(onClick = ::save, enabled = !working, modifier = Modifier.weight(1f)) {
                            Text("保存到相册")
                        }
                        Button(onClick = ::send, enabled = !working, modifier = Modifier.weight(1f)) {
                            Text("发送给其他应用")
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    ) {
                        Image(
                            bitmap = image.bitmap.asImageBitmap(),
                            contentDescription = "待分享图片预览",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                    if (working) CircularProgressIndicator(Modifier.padding(24.dp))
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
