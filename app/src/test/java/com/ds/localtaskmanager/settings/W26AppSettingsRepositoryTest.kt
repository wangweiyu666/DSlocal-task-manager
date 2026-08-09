package com.ds.localtaskmanager.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ds.localtaskmanager.data.statistics.StatisticsPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class W26AppSettingsRepositoryTest {
    private lateinit var context: Context

    @Before
    fun clearPreferences() {
        context = ApplicationProvider.getApplicationContext()
        listOf("app_settings", "notification_settings", "share_preferences").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun missingValuesUseSafeDefaults() {
        val settings = AppSettingsRepository(context).settings.value

        assertEquals(AppThemeMode.SYSTEM, settings.themeMode)
        assertEquals(UiPalette.INDIGO, settings.uiPalette)
        assertFalse(settings.reduceMotion)
        assertEquals(StatisticsPeriod.SEVEN_DAYS, settings.lastStatisticsPeriod)
        assertFalse(settings.informationPrivacyConfirmed)
        assertFalse(settings.notificationPermissionRequested)
    }

    @Test
    fun settingsPersistAcrossRepositoryInstances() {
        AppSettingsRepository(context).apply {
            setThemeMode(AppThemeMode.DARK)
            setUiPalette(UiPalette.SKY)
            setReduceMotion(true)
            setLastStatisticsPeriod(StatisticsPeriod.ALL)
            confirmInformationPrivacy()
            setNotificationPermissionRequested()
        }

        val restored = AppSettingsRepository(context).settings.value
        assertEquals(AppThemeMode.DARK, restored.themeMode)
        assertEquals(UiPalette.SKY, restored.uiPalette)
        assertTrue(restored.reduceMotion)
        assertEquals(StatisticsPeriod.ALL, restored.lastStatisticsPeriod)
        assertTrue(restored.informationPrivacyConfirmed)
        assertTrue(restored.notificationPermissionRequested)
    }

    @Test
    fun legacyNotificationAndShareFlagsAreMigrated() {
        context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("permission_requested", true).commit()
        context.getSharedPreferences("share_preferences", Context.MODE_PRIVATE)
            .edit().putBoolean("information_privacy_confirmed", true).commit()

        val migrated = AppSettingsRepository(context).settings.value
        assertTrue(migrated.notificationPermissionRequested)
        assertTrue(migrated.informationPrivacyConfirmed)
    }

    @Test
    fun invalidEnumValuesFallBackAndPrivacyCanBeReset() {
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
            .putString("theme_mode", "UNKNOWN")
            .putString("ui_palette", "UNKNOWN")
            .putString("last_statistics_period", "UNKNOWN")
            .putBoolean("information_privacy_confirmed", true)
            .commit()
        val repository = AppSettingsRepository(context)

        assertEquals(AppThemeMode.SYSTEM, repository.settings.value.themeMode)
        assertEquals(UiPalette.INDIGO, repository.settings.value.uiPalette)
        assertEquals(StatisticsPeriod.SEVEN_DAYS, repository.settings.value.lastStatisticsPeriod)
        repository.resetInformationPrivacy()
        assertFalse(repository.settings.value.informationPrivacyConfirmed)
    }
}
