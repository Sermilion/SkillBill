package dev.skillbill.tray.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import dev.skillbill.tray.core.common.Diagnostics
import dev.skillbill.tray.core.common.DispatcherProvider
import dev.skillbill.tray.core.common.HostEnvironment
import dev.skillbill.tray.core.common.di.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Inject
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.prefs.Preferences

/** Resolves `~/.skill-bill/desktop-settings.json` from the injected host home. */
@Inject
@SingleIn(AppScope::class)
class JvmSettingsFileLocation(host: HostEnvironment) {
  val path: Path = Path.of(host.userHome, ".skill-bill", "desktop-settings.json")
}

internal object SettingsSerializer : Serializer<StoredSettings> {
  override val defaultValue = StoredSettings()

  override suspend fun readFrom(input: InputStream): StoredSettings = try {
    Json.decodeFromString<StoredSettings>(input.readBytes().decodeToString()).also { value ->
      value.unsupportedValue()?.let { throw CorruptionException(it) }
    }
  } catch (error: SerializationException) {
    throw CorruptionException("Settings cannot be decoded.", error)
  }

  override suspend fun writeTo(t: StoredSettings, output: OutputStream) {
    output.write(Json.encodeToString(t).encodeToByteArray())
  }
}

/** The `java.util.prefs` node earlier app versions wrote to; it is the package node of the retired preferences class. */
class JavaPreferencesLegacySource internal constructor(private val node: Preferences) : LegacyPreferencesSource {
  override val description: String = "java.util.prefs:${node.absolutePath()}"
  override fun string(key: String): String? = node.get(key, null)
  override fun boolean(key: String, default: Boolean): Boolean = node.getBoolean(key, default)
  override fun remove(key: String) = node.remove(key)
  override fun flush() = node.flush()

  companion object {
    const val LEGACY_NODE_PATH = "dev/skillbill/tray/infrastructure"

    fun forNode(node: Preferences): LegacyPreferencesSource = JavaPreferencesLegacySource(node)
  }
}

/** The real user's retired preferences node; the application passes it in, so a test can pass a disposable source instead. */
fun processLegacyPreferences(): LegacyPreferencesSource =
  JavaPreferencesLegacySource(Preferences.userRoot().node(JavaPreferencesLegacySource.LEGACY_NODE_PATH))

/** Creates the single DataStore for the preference file; every other collaborator receives the repository. */
@ContributesTo(AppScope::class)
interface JvmSettingsProviders {
  @Provides
  @SingleIn(AppScope::class)
  fun settingsStoreHandle(
    location: JvmSettingsFileLocation,
    legacy: LegacyPreferencesSource,
    sink: SettingsMigrationSink,
    diagnostics: Diagnostics,
    dispatchers: DispatcherProvider,
  ): SettingsStoreHandle {
    val lifetime = SupervisorJob()
    val store = DataStoreFactory.create(
      serializer = SettingsSerializer,
      migrations = listOf(LegacySettingsMigration(legacy, sink, diagnostics)),
      scope = CoroutineScope(lifetime + dispatchers.io),
      produceFile = { location.path.toFile() },
    )
    return SettingsStoreHandle(store, lifetime)
  }

  @Provides
  fun settingsLifetime(handle: SettingsStoreHandle): SettingsLifetime = handle
}

internal actual fun isPlatformStorageFailure(error: Throwable): Boolean = error is SecurityException
