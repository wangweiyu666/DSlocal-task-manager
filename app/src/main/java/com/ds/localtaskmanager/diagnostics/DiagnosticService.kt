package com.ds.localtaskmanager.diagnostics

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.work.WorkManager
import com.ds.localtaskmanager.BuildConfig
import com.ds.localtaskmanager.backup.BackupManager
import com.ds.localtaskmanager.data.AppDatabase
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiagnosticEvent(
    val atEpochMillis: Long,
    val module: String,
    val code: String,
    val recovered: Boolean,
)

class DiagnosticEventStore(context: Context, private val clock: Clock = Clock.systemUTC()) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun record(module: String, code: String, recovered: Boolean) {
        require(SAFE_VALUE.matches(module) && SAFE_VALUE.matches(code))
        val now = clock.millis()
        val values = read(now).toMutableList().apply {
            add(DiagnosticEvent(now, module, code, recovered))
        }.takeLast(MAX_EVENTS)
        preferences.edit().putString(KEY_EVENTS, values.joinToString("\n") { it.encode() }).apply()
    }

    @Synchronized
    fun events(): List<DiagnosticEvent> = read(clock.millis())

    @Synchronized
    fun clear() = preferences.edit().remove(KEY_EVENTS).apply()

    @Synchronized
    fun cleanup() {
        val retained = read(clock.millis())
        preferences.edit().putString(KEY_EVENTS, retained.joinToString("\n") { it.encode() }).apply()
    }

    private fun read(now: Long): List<DiagnosticEvent> {
        val cutoff = now - RETENTION_MILLIS
        return preferences.getString(KEY_EVENTS, null)
            .orEmpty()
            .lineSequence()
            .mapNotNull(::decode)
            .filter { it.atEpochMillis >= cutoff }
            .toList()
            .takeLast(MAX_EVENTS)
    }

    private fun DiagnosticEvent.encode() = "$atEpochMillis|$module|$code|${if (recovered) 1 else 0}"

    private fun decode(value: String): DiagnosticEvent? {
        val parts = value.split('|')
        if (parts.size != 4 || !SAFE_VALUE.matches(parts[1]) || !SAFE_VALUE.matches(parts[2])) return null
        return DiagnosticEvent(
            atEpochMillis = parts[0].toLongOrNull() ?: return null,
            module = parts[1],
            code = parts[2],
            recovered = parts[3] == "1",
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "diagnostic_events"
        const val KEY_EVENTS = "events"
        const val MAX_EVENTS = 100
        const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
        val SAFE_VALUE = Regex("[A-Z0-9_]{1,48}")
    }
}

class DiagnosticService(
    context: Context,
    private val database: AppDatabase,
    val events: DiagnosticEventStore,
) {
    private val appContext = context.applicationContext

    suspend fun writeTo(uri: Uri) = withContext(Dispatchers.IO) {
        val text = buildReport()
        requireNotNull(appContext.contentResolver.openOutputStream(uri, "w")).bufferedWriter(Charsets.UTF_8).use {
            it.write(text)
        }
    }

    fun clearEvents() = events.clear()

    internal fun buildReport(): String {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val notificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val notificationsEnabled = appContext.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        val workStates = runCatching {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWork(BackupManager.UNIQUE_OPERATION)
                .get(5, TimeUnit.SECONDS)
                .joinToString(",") { it.state.name }
                .ifBlank { "NONE" }
        }.getOrDefault("UNAVAILABLE")
        return buildString {
            appendLine("DStationery diagnostic format: 1")
            appendLine("Generated: ${Instant.now()}")
            appendLine()
            appendLine("[Application]")
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            appendLine("Version: ${packageInfo.versionName} ($versionCode)")
            appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
            appendLine("Package: ${appContext.packageName}")
            appendLine("Database schema: ${database.openHelper.readableDatabase.version}")
            appendLine()
            appendLine("[Environment]")
            appendLine("Android API: ${Build.VERSION.SDK_INT}")
            appendLine("Time zone: ${ZoneId.systemDefault().id}")
            appendLine("Notification permission: $notificationPermission")
            appendLine("Notifications enabled: $notificationsEnabled")
            appendLine("Backup work: $workStates")
            appendLine()
            appendLine("[Sanitized events]")
            val values = events.events()
            if (values.isEmpty()) appendLine("None") else values.forEach {
                appendLine("${Instant.ofEpochMilli(it.atEpochMillis)} ${it.module} ${it.code} recovered=${it.recovered}")
            }
            appendLine()
            appendLine("Excluded: task content, notes, identifiers, file paths, device identifiers, stack traces")
        }
    }
}
