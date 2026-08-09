package com.ds.localtaskmanager.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ds.localtaskmanager.ui.components.BackNavigationIcon

enum class LegalDocument(val title: String, val body: String) {
    PRIVACY(
        "隐私说明",
        """
        DStationery 是完全离线的本地任务应用，不申请网络权限，不收集、上传或出售任何数据。

        任务、备注、积分、历史和设置保存在 Android 应用沙箱中。卸载应用或在系统设置中清除数据会删除这些内容。

        DSTB1 备份由你主动选择保存位置，可能包含任务、备注和完整历史，当前格式不加密。请只保存到可信位置。

        分享图片、备份和诊断导出只在你主动操作时创建。诊断信息不包含任务内容、业务 ID、文件路径、设备标识或异常堆栈。

        应用仅使用通知、设备重启和振动权限。系统文件选择器负责文件授权，不申请照片或广泛存储权限。

        删除全部数据：打开 Android 系统设置 → 应用 → DStationery → 存储 → 清除数据。

        问题反馈：https://github.com/wangweiyu666/DSlocal-task-manager/issues
        """.trimIndent(),
    ),
    LICENSES(
        "开源许可",
        """
        DStationery
        Copyright (C) 2026 rochelimit_cw
        License: GPL-3.0-only

        分发修改版或衍生版时，必须依照 GNU GPL v3 提供对应源码并保留相同许可。

        主要第三方组件：AndroidX、Jetpack Compose、Room、WorkManager、Kotlin、Kotlin Coroutines 与 Kotlin Serialization。它们分别依照各自许可证分发，完整文本见随版本发布的 THIRD_PARTY_NOTICES.txt。

        源码：https://github.com/wangweiyu666/DSlocal-task-manager
        """.trimIndent(),
    ),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(document: LegalDocument, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(document.title) },
                navigationIcon = { BackNavigationIcon(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(document.body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
