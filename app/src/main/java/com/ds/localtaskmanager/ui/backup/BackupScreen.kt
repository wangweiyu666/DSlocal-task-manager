package com.ds.localtaskmanager.ui.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.ds.localtaskmanager.backup.BackupManager
import com.ds.localtaskmanager.backup.BackupMetadata
import com.ds.localtaskmanager.backup.DecodedBackup
import com.ds.localtaskmanager.backup.MergeConflict
import com.ds.localtaskmanager.backup.MergePreview
import com.ds.localtaskmanager.backup.RestoreMode
import com.ds.localtaskmanager.backup.RoomBackupRepository
import com.ds.localtaskmanager.settings.AppSettings
import com.ds.localtaskmanager.settings.AppSettingsRepository
import com.ds.localtaskmanager.ui.components.BackNavigationIcon
import com.ds.localtaskmanager.ui.navigation.predictiveBackTransform
import com.ds.localtaskmanager.ui.navigation.rememberPredictiveBackState
import com.ds.localtaskmanager.ui.theme.DstTheme
import com.ds.localtaskmanager.ui.theme.LocalReduceMotion
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackupPage {
    data object Home : BackupPage
    data object Reading : BackupPage
    data class Preview(
        val stagedFile: File,
        val decoded: DecodedBackup,
        val mergePreview: MergePreview,
        val mode: RestoreMode = RestoreMode.MERGE,
    ) : BackupPage
    data class Conflicts(val preview: Preview, val backupChoices: Set<String> = emptySet()) : BackupPage
    data class Working(val title: String, val stage: String) : BackupPage
    data class Result(
        val title: String,
        val message: String,
        val success: Boolean,
        val returnToToday: Boolean = false,
    ) : BackupPage
}

class BackupViewModel(
    private val manager: BackupManager,
    private val repository: RoomBackupRepository,
) : ViewModel() {
    private val mutablePage = MutableStateFlow<BackupPage>(BackupPage.Home)
    val page: StateFlow<BackupPage> = mutablePage.asStateFlow()

    fun selectBackup(uri: Uri) {
        mutablePage.value = BackupPage.Reading
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { manager.stageSelectedBackup(uri) }
            }.onSuccess { (file, decoded) ->
                val merge = repository.previewMerge(decoded.payload)
                mutablePage.value = BackupPage.Preview(file, decoded, merge)
            }.onFailure { error ->
                mutablePage.value = BackupPage.Result("无法读取备份", error.message ?: "所选文件无效", false)
            }
        }
    }

    fun selectMode(mode: RestoreMode) {
        val current = mutablePage.value as? BackupPage.Preview ?: return
        mutablePage.value = current.copy(mode = mode)
    }

    fun continueRestore() {
        val current = mutablePage.value as? BackupPage.Preview ?: return
        if (current.mode == RestoreMode.MERGE && current.mergePreview.conflicts.isNotEmpty()) {
            mutablePage.value = BackupPage.Conflicts(current)
        } else {
            startRestore(current, emptySet())
        }
    }

    fun toggleConflict(id: String) {
        val current = mutablePage.value as? BackupPage.Conflicts ?: return
        val selected = current.backupChoices.toMutableSet().apply {
            if (!add(id)) remove(id)
        }
        mutablePage.value = current.copy(backupChoices = selected)
    }

    fun chooseAllBackup(useBackup: Boolean) {
        val current = mutablePage.value as? BackupPage.Conflicts ?: return
        mutablePage.value = current.copy(
            backupChoices = if (useBackup) current.preview.mergePreview.conflicts.mapTo(hashSetOf()) { it.id } else emptySet(),
        )
    }

    fun confirmConflicts() {
        val current = mutablePage.value as? BackupPage.Conflicts ?: return
        startRestore(current.preview, current.backupChoices)
    }

    fun export(uri: Uri) = observe(manager.enqueueExport(uri), "正在导出备份", export = true)

    fun backToHome() {
        when (val current = mutablePage.value) {
            is BackupPage.Preview -> current.stagedFile.delete()
            is BackupPage.Conflicts -> current.preview.stagedFile.delete()
            else -> Unit
        }
        mutablePage.value = BackupPage.Home
    }

    private fun startRestore(preview: BackupPage.Preview, choices: Set<String>) {
        observe(manager.enqueueRestore(preview.stagedFile, preview.mode, choices), "正在恢复数据", export = false)
    }

    private fun observe(id: UUID, title: String, export: Boolean) {
        mutablePage.value = BackupPage.Working(title, if (export) "正在生成快照" else "正在校验备份")
        viewModelScope.launch {
            manager.observeWork(id).filterNotNull().collect { info ->
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                        mutablePage.value = BackupPage.Working(title, "等待系统执行")
                    WorkInfo.State.RUNNING -> mutablePage.value = BackupPage.Working(
                        title,
                        info.progress.getString("stage") ?: if (export) "正在导出备份" else "正在恢复数据",
                    )
                    WorkInfo.State.SUCCEEDED -> {
                        mutablePage.value = if (export) {
                            BackupPage.Result("导出成功", "备份已经写入并通过完整性复核。", true)
                        } else {
                            val pending = info.outputData.getBoolean("reminder_pending", false)
                            BackupPage.Result(
                                "恢复完成",
                                buildString {
                                    append("新增 ${info.outputData.getInt("added", 0)} 项，更新 ${info.outputData.getInt("updated", 0)} 项，保留本机 ${info.outputData.getInt("kept", 0)} 项。")
                                    if (pending) append(" 数据已恢复，部分提醒将在下次启动时重建。")
                                },
                                true,
                                returnToToday = true,
                            )
                        }
                        return@collect
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        mutablePage.value = BackupPage.Result(
                            if (export) "导出失败" else "恢复失败",
                            info.outputData.getString("error") ?: "操作未完成",
                            false,
                        )
                        return@collect
                    }
                }
            }
        }
    }
}

class BackupViewModelFactory(
    private val manager: BackupManager,
    private val repository: RoomBackupRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = BackupViewModel(manager, repository) as T
}

@Composable
fun BackupRoute(
    viewModel: BackupViewModel,
    settingsRepository: AppSettingsRepository,
    onBack: () -> Unit,
    onRestoreComplete: () -> Unit,
) {
    val page by viewModel.page.collectAsState()
    val settings by settingsRepository.settings.collectAsState()
    var showPrivacy by remember { mutableStateOf(false) }
    var confirmReplace by remember { mutableStateOf(false) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let(viewModel::export)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::selectBackup)
    }
    val startExport = {
        if (settings.backupPrivacyConfirmed) createDocument.launch(defaultBackupName()) else showPrivacy = true
    }

    BackupScreen(
        page = page,
        settings = settings,
        onBack = if (page == BackupPage.Home || page is BackupPage.Working) onBack else viewModel::backToHome,
        onExport = startExport,
        onRestore = { openDocument.launch(arrayOf("*/*")) },
        onResetPrivacy = settingsRepository::resetBackupPrivacy,
        onSelectMode = viewModel::selectMode,
        onContinueRestore = {
            if ((page as? BackupPage.Preview)?.mode == RestoreMode.REPLACE) confirmReplace = true
            else viewModel.continueRestore()
        },
        onToggleConflict = viewModel::toggleConflict,
        onAllLocal = { viewModel.chooseAllBackup(false) },
        onAllBackup = { viewModel.chooseAllBackup(true) },
        onConfirmConflicts = viewModel::confirmConflicts,
        onDone = {
            if ((page as? BackupPage.Result)?.returnToToday == true) onRestoreComplete()
            else viewModel.backToHome()
        },
        predictiveBackEnabled = !showPrivacy && !confirmReplace,
    )

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            title = { Text("备份文件未加密") },
            text = { Text("持有备份文件的人可以读取任务、告知正文、备注和积分记录。请只保存到可信位置。") },
            confirmButton = {
                Button(onClick = {
                    settingsRepository.confirmBackupPrivacy()
                    showPrivacy = false
                    createDocument.launch(defaultBackupName())
                }) { Text("了解并继续") }
            },
            dismissButton = { TextButton(onClick = { showPrivacy = false }) { Text("取消") } },
        )
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("完全替换现有数据？") },
            text = { Text("当前任务、历史记录和可迁移设置将由备份内容替换。失败时应用会自动恢复原有数据。") },
            confirmButton = {
                Button(onClick = { confirmReplace = false; viewModel.continueRestore() }) { Text("完全替换并恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    page: BackupPage,
    settings: AppSettings,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onRestore: () -> Unit,
    onResetPrivacy: () -> Unit,
    onSelectMode: (RestoreMode) -> Unit,
    onContinueRestore: () -> Unit,
    onToggleConflict: (String) -> Unit,
    onAllLocal: () -> Unit,
    onAllBackup: () -> Unit,
    onConfirmConflicts: () -> Unit,
    onDone: () -> Unit,
    lazyConflicts: Boolean = true,
    predictiveBackEnabled: Boolean = true,
) {
    val predictiveBack = rememberPredictiveBackState(enabled = predictiveBackEnabled, onBack = onBack)
    val largeFontTopBarHeight = if (LocalDensity.current.fontScale >= 1.5f) 96.dp else 64.dp
    Scaffold(
        modifier = Modifier.fillMaxSize().predictiveBackTransform(predictiveBack).testTag("backup-screen"),
        topBar = {
            CenterAlignedTopAppBar(
                modifier = Modifier.heightIn(min = largeFontTopBarHeight),
                title = { Text(page.title) },
                navigationIcon = { BackNavigationIcon(onBack) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                BackupPage.Home -> BackupHome(settings, onExport, onRestore, onResetPrivacy)
                BackupPage.Reading -> WorkingContent("正在读取并校验备份")
                is BackupPage.Preview -> PreviewContent(page, onSelectMode, onContinueRestore)
                is BackupPage.Conflicts -> ConflictContent(
                    page.preview.mergePreview.conflicts,
                    page.backupChoices,
                    onToggleConflict,
                    onAllLocal,
                    onAllBackup,
                    onConfirmConflicts,
                    lazyConflicts,
                )
                is BackupPage.Working -> WorkingContent(page.stage)
                is BackupPage.Result -> ResultContent(page, onDone)
            }
        }
    }
}

@Composable
private fun BackupHome(settings: AppSettings, onExport: () -> Unit, onRestore: () -> Unit, onResetPrivacy: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("导出备份", style = MaterialTheme.typography.titleLarge)
                Text("把当前任务、历史与可迁移设置保存为 DSTB1 备份。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onExport, modifier = Modifier.fillMaxWidth().testTag("backup-export")) { Text("导出备份") }
                settings.lastBackupExportAtEpochMillis?.let { Text("上次成功导出：${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("恢复备份", style = MaterialTheme.typography.titleLarge)
                Text("选择备份后可以合并现有数据，或完全替换。校验完成前不会修改数据。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth().testTag("backup-restore")) { Text("恢复备份") }
                settings.lastBackupRestoreAtEpochMillis?.let { Text("上次成功恢复：${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
            }
        }
        TextButton(onClick = onResetPrivacy, enabled = settings.backupPrivacyConfirmed) { Text("再次显示备份隐私提示") }
    }
}

@Composable
private fun PreviewContent(page: BackupPage.Preview, onSelectMode: (RestoreMode) -> Unit, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MetadataCard(page.decoded.metadata)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("恢复方式", style = MaterialTheme.typography.titleMedium)
                RestoreMode.entries.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelectMode(mode) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(page.mode == mode, onClick = null)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(if (mode == RestoreMode.MERGE) "合并恢复" else "完全替换")
                            Text(
                                if (mode == RestoreMode.MERGE) "保留本机设置，按更新时间合并数据"
                                else "用备份替换现有任务、历史和可迁移设置",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mode == RestoreMode.REPLACE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().testTag("restore-continue")) {
            Text(if (page.mode == RestoreMode.MERGE && page.mergePreview.conflicts.isNotEmpty()) "处理冲突并恢复" else "开始恢复")
        }
    }
}

@Composable
private fun MetadataCard(metadata: BackupMetadata) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("备份内容", style = MaterialTheme.typography.titleMedium)
            Text("创建于 ${formatTime(metadata.createdAtEpochMillis)}")
            Text("来源版本 ${metadata.appVersion} · ${metadata.sourceTimeZone}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("${metadata.counts.tasks} 个任务 · ${metadata.counts.instances} 个实例 · ${metadata.counts.groups} 个积分组")
            Text("${metadata.counts.ledgerEntries} 条积分流水 · ${metadata.counts.resultRevisions} 个结果版本")
        }
    }
}

@Composable
private fun ConflictContent(
    conflicts: List<MergeConflict>, selected: Set<String>, onToggle: (String) -> Unit,
    onAllLocal: () -> Unit, onAllBackup: () -> Unit, onConfirm: () -> Unit, lazy: Boolean = true,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${conflicts.size} 项内容需要确认", style = MaterialTheme.typography.titleMedium)
            Row { TextButton(onClick = onAllLocal) { Text("全部本机") }; TextButton(onClick = onAllBackup) { Text("全部备份") } }
        }
        if (lazy) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(conflicts, key = MergeConflict::id) { conflict -> ConflictCard(conflict, selected, onToggle) }
            }
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                conflicts.forEach { conflict -> ConflictCard(conflict, selected, onToggle) }
            }
        }
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) { Text("按当前选择恢复") }
    }
}

@Composable
private fun ConflictCard(conflict: MergeConflict, selected: Set<String>, onToggle: (String) -> Unit) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(conflict.title, style = MaterialTheme.typography.titleSmall)
                                Text(conflict.category, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = conflict.id in selected,
                                onCheckedChange = { onToggle(conflict.id) },
                                modifier = Modifier.testTag("conflict-${conflict.id}"),
                            )
                        }
                        Text("本机：${conflict.localValue}")
                        Text("备份：${conflict.backupValue}")
                    }
                }
}

@Composable
private fun WorkingContent(stage: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!LocalReduceMotion.current) CircularProgressIndicator() else HorizontalDivider(Modifier.fillMaxWidth(.35f))
        Spacer(Modifier.height(18.dp))
        Text(stage, style = MaterialTheme.typography.titleMedium)
        Text("可以离开此页面，任务仍会继续。", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultContent(result: BackupPage.Result, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(result.title, style = MaterialTheme.typography.headlineSmall, color = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Text(result.message)
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("完成") }
            }
        }
    }
}

private val BackupPage.title: String get() = when (this) {
    BackupPage.Home -> "备份与恢复"
    BackupPage.Reading -> "校验备份"
    is BackupPage.Preview -> "确认恢复"
    is BackupPage.Conflicts -> "处理冲突"
    is BackupPage.Working -> title
    is BackupPage.Result -> title
}

private fun defaultBackupName(): String =
    "本地任务备份_${DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneId.systemDefault()).format(Instant.now())}.dstb"

private fun formatTime(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMillis))

@Preview(name = "W31 备份首页", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun BackupHomePreview() {
    DstTheme {
        BackupScreen(
            page = BackupPage.Home,
            settings = AppSettings(),
            onBack = {},
            onExport = {},
            onRestore = {},
            onResetPrivacy = {},
            onSelectMode = {},
            onContinueRestore = {},
            onToggleConflict = {},
            onAllLocal = {},
            onAllBackup = {},
            onConfirmConflicts = {},
            onDone = {},
        )
    }
}

@Preview(name = "W31 恢复预览", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun BackupRestorePreview() {
    val metadata = previewMetadata()
    val decoded = DecodedBackup(metadata, com.ds.localtaskmanager.backup.BackupPayload())
    val merge = MergePreview(com.ds.localtaskmanager.backup.BackupPayload(), emptyList(), 12, 3, 5)
    DstTheme {
        PreviewContent(BackupPage.Preview(File("preview.dstb"), decoded, merge), {}, {})
    }
}

@Preview(name = "W31 冲突处理", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun BackupConflictPreview() {
    val conflicts = listOf(
        MergeConflict("task:1", "任务", "阅读", "阅读；5 分；20:00", "阅读 30 分钟；8 分；21:00"),
        MergeConflict("note:1", "任务备注", "运动", "完成热身", "膝盖不适，降低强度"),
    )
    DstTheme { ConflictContent(conflicts, emptySet(), {}, {}, {}, {}) }
}

@Preview(name = "W31 恢复结果", showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun BackupResultPreview() {
    DstTheme {
        ResultContent(BackupPage.Result("恢复完成", "新增 12 项，更新 3 项，保留本机 5 项。", true), {})
    }
}

private fun previewMetadata() = BackupMetadata(
    createdAtEpochMillis = 1_784_419_200_000,
    appVersion = "0.1.0-alpha",
    sourceTimeZone = "Asia/Hong_Kong",
    counts = com.ds.localtaskmanager.backup.BackupCounts(3, 18, 42, 67, 81, 4),
)
