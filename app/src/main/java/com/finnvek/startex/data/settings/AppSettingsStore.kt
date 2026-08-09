package com.finnvek.startex.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class OperatingMode {
    PAPER,
    LIVE,
}

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val operatingMode: OperatingMode = OperatingMode.PAPER,
    val secureSession: Boolean = true,
    val unattendedMode: Boolean = false,
    val demoMode: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val snapshotRetentionDays: Int = 7,
    val maximumStoredEvents: Int = 1_000,
    val maximumRememberedCandidates: Int = 250,
    val displayCurrency: String = "EUR",
) {
    init {
        require(secureSession.xor(unattendedMode))
        require(snapshotRetentionDays in 1..365)
        require(maximumStoredEvents in 1..100_000)
        require(maximumRememberedCandidates in 25..5_000)
        require(displayCurrency.length == 3 && displayCurrency.all(Char::isLetter))
    }
}

private val Context.appSettingsDataStore by preferencesDataStore(name = "app_settings")

class AppSettingsStore(
    context: Context,
) {
    private val dataStore: DataStore<Preferences> = context.appSettingsDataStore

    val settings: Flow<AppSettings> =
        dataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }.map(::toSettings)

    suspend fun setOnboardingComplete(value: Boolean) = update(ONBOARDING_COMPLETE, value)

    suspend fun setOperatingMode(value: OperatingMode) = update(OPERATING_MODE, value.name)

    suspend fun setSecurityMode(unattended: Boolean) {
        dataStore.edit { preferences ->
            preferences[SECURE_SESSION] = !unattended
            preferences[UNATTENDED_MODE] = unattended
        }
    }

    suspend fun setDemoMode(value: Boolean) = update(DEMO_MODE, value)

    suspend fun setNotificationsEnabled(value: Boolean) = update(NOTIFICATIONS_ENABLED, value)

    suspend fun setSnapshotRetentionDays(value: Int) {
        require(value in 1..365)
        update(SNAPSHOT_RETENTION_DAYS, value)
    }

    suspend fun setMaximumStoredEvents(value: Int) {
        require(value in 1..100_000)
        update(MAXIMUM_STORED_EVENTS, value)
    }

    suspend fun setMaximumRememberedCandidates(value: Int) {
        require(value in 25..5_000)
        update(MAXIMUM_REMEMBERED_CANDIDATES, value)
    }

    private suspend fun <T> update(
        key: Preferences.Key<T>,
        value: T,
    ) {
        dataStore.edit { preferences -> preferences[key] = value }
    }

    private fun toSettings(preferences: Preferences): AppSettings {
        val unattended = preferences[UNATTENDED_MODE] ?: false
        return AppSettings(
            onboardingComplete = preferences[ONBOARDING_COMPLETE] ?: false,
            operatingMode =
                preferences[OPERATING_MODE]
                    ?.let { stored -> OperatingMode.entries.firstOrNull { it.name == stored } }
                    ?: OperatingMode.PAPER,
            secureSession = !unattended,
            unattendedMode = unattended,
            demoMode = preferences[DEMO_MODE] ?: false,
            notificationsEnabled = preferences[NOTIFICATIONS_ENABLED] ?: true,
            snapshotRetentionDays = preferences[SNAPSHOT_RETENTION_DAYS]?.coerceIn(1, 365) ?: 7,
            maximumStoredEvents = preferences[MAXIMUM_STORED_EVENTS]?.coerceIn(1, 100_000) ?: 1_000,
            maximumRememberedCandidates = preferences[MAXIMUM_REMEMBERED_CANDIDATES]?.coerceIn(25, 5_000) ?: 250,
            displayCurrency =
                preferences[DISPLAY_CURRENCY]
                    ?.takeIf { it.length == 3 && it.all(Char::isLetter) }
                    ?.uppercase()
                    ?: "EUR",
        )
    }

    private companion object {
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val OPERATING_MODE = stringPreferencesKey("operating_mode")
        val SECURE_SESSION = booleanPreferencesKey("secure_session")
        val UNATTENDED_MODE = booleanPreferencesKey("unattended_mode")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val SNAPSHOT_RETENTION_DAYS = intPreferencesKey("snapshot_retention_days")
        val MAXIMUM_STORED_EVENTS = intPreferencesKey("maximum_stored_events")
        val MAXIMUM_REMEMBERED_CANDIDATES = intPreferencesKey("maximum_remembered_candidates")
        val DISPLAY_CURRENCY = stringPreferencesKey("display_currency")
    }
}
