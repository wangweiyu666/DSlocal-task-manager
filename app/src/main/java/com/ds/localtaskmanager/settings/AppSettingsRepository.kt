package com.ds.localtaskmanager.settings

import android.content.Context
import android.content.SharedPreferences
import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

data class AppSettings(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val reduceMotion: Boolean = false,
    val lastStatisticsPeriod: StatisticsPeriod = StatisticsPeriod.SEVEN_DAYS,
    val informationPrivacyConfirmed: Boolean = false,
    val notificationPermissionRequested: Boolean = false,
    val backupPrivacyConfirmed: Boolean = false,
    val lastBackupExportAtEpochMillis: Long? = null,
    val lastBackupRestoreAtEpochMillis: Long? = null,
)

class AppSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val legacyNotificationPreferences =
        context.applicationContext.getSharedPreferences(LEGACY_NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
    private val legacySharePreferences =
        context.applicationContext.getSharedPreferences(LEGACY_SHARE_PREFERENCES, Context.MODE_PRIVATE)
    private val mutableSettings: MutableStateFlow<AppSettings>

    val settings: StateFlow<AppSettings>
        get() = mutableSettings.asStateFlow()

    init {
        migrateLegacyPreferences()
        mutableSettings = MutableStateFlow(readSettings())
    }

    fun setThemeMode(mode: AppThemeMode) = update(KEY_THEME_MODE, mode.name)

    fun setReduceMotion(enabled: Boolean) = update(KEY_REDUCE_MOTION, enabled)

    fun setLastStatisticsPeriod(period: StatisticsPeriod) = update(KEY_STATISTICS_PERIOD, period.name)

    fun confirmInformationPrivacy() = update(KEY_INFORMATION_PRIVACY_CONFIRMED, true)

    fun resetInformationPrivacy() = update(KEY_INFORMATION_PRIVACY_CONFIRMED, false)

    fun setNotificationPermissionRequested(requested: Boolean = true) =
        update(KEY_NOTIFICATION_PERMISSION_REQUESTED, requested)

    fun confirmBackupPrivacy() = update(KEY_BACKUP_PRIVACY_CONFIRMED, true)

    fun resetBackupPrivacy() = update(KEY_BACKUP_PRIVACY_CONFIRMED, false)

    fun recordBackupExported(atEpochMillis: Long) = update(KEY_LAST_BACKUP_EXPORT_AT, atEpochMillis)

    fun recordBackupRestored(atEpochMillis: Long) = update(KEY_LAST_BACKUP_RESTORE_AT, atEpochMillis)

    fun restorePortableSettings(themeMode: AppThemeMode, reduceMotion: Boolean, period: StatisticsPeriod) {
        preferences.edit()
            .putString(KEY_THEME_MODE, themeMode.name)
            .putBoolean(KEY_REDUCE_MOTION, reduceMotion)
            .putString(KEY_STATISTICS_PERIOD, period.name)
            .apply()
        mutableSettings.value = readSettings()
    }

    private fun migrateLegacyPreferences() {
        val editor = preferences.edit()
        var changed = false
        if (!preferences.contains(KEY_INFORMATION_PRIVACY_CONFIRMED) &&
            legacySharePreferences.contains(LEGACY_INFORMATION_PRIVACY_CONFIRMED)
        ) {
            editor.putBoolean(
                KEY_INFORMATION_PRIVACY_CONFIRMED,
                legacySharePreferences.getBoolean(LEGACY_INFORMATION_PRIVACY_CONFIRMED, false),
            )
            changed = true
        }
        if (!preferences.contains(KEY_NOTIFICATION_PERMISSION_REQUESTED) &&
            legacyNotificationPreferences.contains(LEGACY_NOTIFICATION_PERMISSION_REQUESTED)
        ) {
            editor.putBoolean(
                KEY_NOTIFICATION_PERMISSION_REQUESTED,
                legacyNotificationPreferences.getBoolean(LEGACY_NOTIFICATION_PERMISSION_REQUESTED, false),
            )
            changed = true
        }
        if (changed) editor.apply()
    }

    private fun readSettings() = AppSettings(
        themeMode = preferences.getString(KEY_THEME_MODE, null).toEnumOrDefault(AppThemeMode.SYSTEM),
        reduceMotion = preferences.getBoolean(KEY_REDUCE_MOTION, false),
        lastStatisticsPeriod = preferences.getString(KEY_STATISTICS_PERIOD, null)
            .toEnumOrDefault(StatisticsPeriod.SEVEN_DAYS),
        informationPrivacyConfirmed = preferences.getBoolean(KEY_INFORMATION_PRIVACY_CONFIRMED, false),
        notificationPermissionRequested = preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false),
        backupPrivacyConfirmed = preferences.getBoolean(KEY_BACKUP_PRIVACY_CONFIRMED, false),
        lastBackupExportAtEpochMillis = preferences.getLong(KEY_LAST_BACKUP_EXPORT_AT, Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE },
        lastBackupRestoreAtEpochMillis = preferences.getLong(KEY_LAST_BACKUP_RESTORE_AT, Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE },
    )

    private fun update(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
        mutableSettings.value = readSettings()
    }

    private fun update(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).apply()
        mutableSettings.value = readSettings()
    }

    private fun update(key: String, value: Long) {
        preferences.edit().putLong(key, value).apply()
        mutableSettings.value = readSettings()
    }

    private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
        this?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: default

    private companion object {
        const val PREFERENCES_NAME = "app_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_STATISTICS_PERIOD = "last_statistics_period"
        const val KEY_INFORMATION_PRIVACY_CONFIRMED = "information_privacy_confirmed"
        const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        const val KEY_BACKUP_PRIVACY_CONFIRMED = "backup_privacy_confirmed"
        const val KEY_LAST_BACKUP_EXPORT_AT = "last_backup_export_at"
        const val KEY_LAST_BACKUP_RESTORE_AT = "last_backup_restore_at"

        const val LEGACY_NOTIFICATION_PREFERENCES = "notification_settings"
        const val LEGACY_NOTIFICATION_PERMISSION_REQUESTED = "permission_requested"
        const val LEGACY_SHARE_PREFERENCES = "share_preferences"
        const val LEGACY_INFORMATION_PRIVACY_CONFIRMED = "information_privacy_confirmed"
    }
}
