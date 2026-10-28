package dev.skillbill.desktop.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File

/** The preferences file under the user's home. A corrupt file is replaced with empty preferences, so settings fall back to defaults. */
@ContributesTo(AppScope::class)
interface SettingsStoreProviders {
  @Provides
  @SingleIn(AppScope::class)
  fun settingsStore(host: HostEnvironment, diagnostics: Diagnostics): DataStore<Preferences> = PreferenceDataStoreFactory.create(
    corruptionHandler = ReplaceFileCorruptionHandler {
      diagnostics.record("desktop_settings_corrupt")
      emptyPreferences()
    },
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    produceFile = { File(host.userHome, AppPreferenceKeys.FILE_PATH) },
  )
}
