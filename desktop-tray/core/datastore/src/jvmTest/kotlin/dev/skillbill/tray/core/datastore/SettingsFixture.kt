package dev.skillbill.tray.core.datastore

import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.HostEnvironment
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** An in-memory stand-in for the retired `java.util.prefs` node, so tests never touch the user's real preferences. */
internal class FakeLegacySource(
  strings: Map<String, String> = emptyMap(),
  booleans: Map<String, Boolean> = emptyMap(),
) : LegacyPreferencesSource {
  val strings = strings.toMutableMap()
  val booleans = booleans.toMutableMap()

  override val description: String = "fake-legacy"

  override fun string(key: String): String? = strings[key]

  override fun boolean(key: String, default: Boolean): Boolean = booleans[key] ?: default

  override fun remove(key: String) {
    strings.remove(key)
    booleans.remove(key)
  }

  override fun flush() = Unit
}

private object IgnoredDiagnostics : Diagnostics {
  override fun record(category: String) = Unit

  override fun recordRequest(category: String, fields: Map<String, Number>) = Unit
}

/**
 * The settings repository built the way the generated graph builds it, over a disposable home directory. Creating a
 * second fixture over the same [home] is an app restart.
 */
internal class SettingsFixture(val home: Path, legacy: LegacyPreferencesSource = FakeLegacySource()) {
  val reports = CopyOnWriteArrayList<SettingsMigrationReport>()
  private val location = JvmSettingsFileLocation(HostEnvironment(mapOf("SKILL_BILL_TEST" to "1"), home.toString(), home.toString()))
  val file: Path = location.path
  val handle: SettingsStoreHandle = object : JvmSettingsProviders {}.settingsStoreHandle(
    location,
    legacy,
    SettingsMigrationSink { reports += it },
    IgnoredDiagnostics,
    DispatcherProvider(io = Dispatchers.IO),
  )
  val repository = DataStoreSettingsRepository(handle)

  companion object {
    /** A new empty home; `toRealPath` because the temporary directory is a symlink on macOS. */
    fun newHome(prefix: String): Path = Files.createTempDirectory(prefix).toRealPath()
  }
}
