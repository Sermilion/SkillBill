package dev.skillbill.tray.infrastructure

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import dev.skillbill.tray.core.domain.model.*
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Path
import java.util.prefs.Preferences

@Serializable
internal data class StoredSettings(
  val migrated: Boolean = false,
  val databasePath: String? = null,
  val retention: String? = null,
  val historyFilter: HistoryFilter = HistoryFilter.ALL,
  val notifications: Map<String, Boolean>? = null,
  val startAtLogin: Boolean? = null,
  val lastPopoverTab: String? = null,
) {
  fun toDomain(): AppSettings = AppSettings(
    databasePath = databasePath,
    historyFilter = historyFilter,
    retention = Retention.entries.single { it.cliValue == (retention ?: Retention.DAY.cliValue) },
    notifications = defaultNotifications + notifications.orEmpty(),
    startAtLogin = startAtLogin ?: true,
    lastPopoverTab = lastPopoverTab ?: "Goals",
  )
}

private object SettingsSerializer : Serializer<StoredSettings> {
  override val defaultValue = StoredSettings()
  override suspend fun readFrom(input: InputStream): StoredSettings = try {
    Json.decodeFromString<StoredSettings>(input.readBytes().decodeToString()).also { value ->
      if (value.retention != null && Retention.entries.none { it.cliValue == value.retention })
        throw CorruptionException("Unsupported history retention in settings.")
      if (value.lastPopoverTab != null && value.lastPopoverTab !in setOf("Goals", "Phases"))
        throw CorruptionException("Unsupported tray tab in settings.")
    }
  } catch (error: SerializationException) {
    throw CorruptionException("Settings cannot be decoded.", error)
  }
  override suspend fun writeTo(t: StoredSettings, output: OutputStream) {
    output.write(Json.encodeToString(t).encodeToByteArray())
  }
}

class DataStoreSettingsStore(
  path: Path,
  legacy: Preferences = Preferences.userNodeForPackage(DataStoreSettingsStore::class.java),
) : SettingsStore {
  private val lifetime = SupervisorJob()
  private val store = DataStoreFactory.create(
    serializer = SettingsSerializer,
    migrations = listOf(LegacySettingsMigration(legacy)),
    scope = CoroutineScope(lifetime + Dispatchers.IO),
    produceFile = { path.toFile() },
  )
  override val settings: Flow<Outcome<AppSettings>> = store.data
    .map<StoredSettings, Outcome<AppSettings>> { Outcome.Value(it.toDomain()) }
    .catch { error ->
      if (error is CancellationException) throw error
      emit(settingsFailure(error, reading = true) ?: throw error)
    }

  override suspend fun update(transform: (AppSettings) -> AppSettings): Outcome<Unit> = try {
    store.updateData { old ->
      val settings = transform(old.toDomain())
      old.copy(migrated = true, databasePath = settings.databasePath,
        retention = settings.retention.cliValue, historyFilter = settings.historyFilter, notifications = settings.notifications,
        startAtLogin = settings.startAtLogin, lastPopoverTab = settings.lastPopoverTab)
    }
    Outcome.Value(Unit)
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    settingsFailure(error, reading = false) ?: throw error
  }

  override fun close() { lifetime.cancel() }
  override suspend fun closeAndJoin() { lifetime.cancelAndJoin() }
}

private fun settingsFailure(error: Throwable, reading: Boolean): Outcome.Failure? = when (error) {
  is CorruptionException -> Outcome.Failure(Problem(
    ProblemCode.SETTINGS_CORRUPT,
    "Settings are unreadable. The file was left in place. Back it up before replacing it.",
  ))
  is IOException, is SecurityException -> Outcome.Failure(Problem(
    ProblemCode.SETTINGS_UNAVAILABLE,
    if (reading) "Settings could not be loaded. Check the settings file before retrying."
    else "Settings could not be saved.",
  ))
  else -> null
}

private class LegacySettingsMigration(private val legacy: Preferences) : DataMigration<StoredSettings> {
  override suspend fun shouldMigrate(currentData: StoredSettings): Boolean = !currentData.migrated
  override suspend fun migrate(currentData: StoredSettings): StoredSettings {
    val retention = legacy.get("retention", Retention.DAY.cliValue)
    val tab = legacy.get("lastPopoverTab", "Goals")
    return currentData.copy(
      migrated = true,
      retention = currentData.retention ?: retention.takeIf { value -> Retention.entries.any { it.cliValue == value } }
        ?: Retention.DAY.cliValue.also { RuntimeDiagnostics.record("invalid_retention_preference") },
      notifications = currentData.notifications ?: defaultNotifications.mapValues { (key, default) -> legacy.getBoolean("notify.$key", default) },
      startAtLogin = currentData.startAtLogin ?: legacy.getBoolean("startAtLogin", true),
      lastPopoverTab = currentData.lastPopoverTab ?: tab.takeIf { it in setOf("Goals", "Phases") }
        ?: "Goals".also { RuntimeDiagnostics.record("invalid_last_tab_preference") },
    )
  }
  override suspend fun cleanUp() {
    if (legacy.get("cliOverride", null) != null) RuntimeDiagnostics.record("legacy_cli_preference_discarded")
    if (legacy.get("pollSeconds", null) != null) RuntimeDiagnostics.record("legacy_poll_preference_discarded")
    legacy.remove("cliOverride")
    legacy.remove("pollSeconds")
    legacy.flush()
  }
}
