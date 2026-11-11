package io.github.pkczc.eta.data.repository

import io.github.pkczc.eta.data.datastore.SettingsDataStore
import io.github.pkczc.eta.data.model.AppearanceSettings
import io.github.pkczc.eta.data.model.AppearanceUiStyle
import kotlinx.coroutines.flow.Flow

object AppearanceSettingsRepository {
    fun settingsFlow(): Flow<AppearanceSettings> = SettingsDataStore.appearanceSettingsFlow()

    suspend fun settings(): AppearanceSettings = SettingsDataStore.settings().appearance

    suspend fun update(settings: AppearanceSettings) {
        SettingsDataStore.setAppearanceSettings(settings)
    }

    suspend fun update(transform: (AppearanceSettings) -> AppearanceSettings) {
        SettingsDataStore.updateAppearanceSettings(transform)
    }

    suspend fun selectUiStyle(style: AppearanceUiStyle) {
        SettingsDataStore.selectUiStyle(style)
    }
}
