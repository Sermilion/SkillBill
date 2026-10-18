package dev.skillbill.tray.core.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import dev.skillbill.tray.core.common.di.AppScope
import dev.skillbill.tray.core.domain.model.AppSettings
import dev.skillbill.tray.core.domain.model.Problem
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.SerializationException
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** Owns the one DataStore instance for the preference file; app-scope teardown closes it. */
interface SettingsLifetime {
  fun close()
  suspend fun closeAndJoin()
}

class SettingsStoreHandle internal constructor(
  internal val store: DataStore<StoredSettings>,
  private val lifetime: Job,
) : SettingsLifetime {
  override fun close() = lifetime.cancel()
  override suspend fun closeAndJoin() = lifetime.cancelAndJoin()
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class DataStoreSettingsRepository(handle: SettingsStoreHandle) : SettingsRepository {
  private val store = handle.store

  override val settings: Flow<SettingsState> = store.data
    .map<StoredSettings, SettingsState> { SettingsState.Ready(it.toDomain()) }
    .catch { error ->
      if (error is CancellationException) throw error
      emit(SettingsState.Failed(readFailure(error) ?: throw error))
    }
    .onStart { emit(SettingsState.Loading) }

  override suspend fun update(transform: (AppSettings) -> AppSettings): Outcome<AppSettings> = try {
    val updated = store.updateData { old -> old.withSettings(transform(old.toDomain())) }
    Outcome.Value(updated.toDomain())
  } catch (error: CancellationException) {
    throw error
  } catch (error: Exception) {
    Outcome.Failure(updateFailure(error) ?: throw error)
  }

  private fun readFailure(error: Throwable): Problem? = when {
    error is CorruptionException -> corrupt()
    isStorageFailure(error) -> Problem(
      ProblemCode.SETTINGS_UNAVAILABLE,
      "Settings could not be loaded. Check the settings file before retrying.",
    )
    else -> null
  }

  private fun updateFailure(error: Throwable): Problem? = when {
    error is CorruptionException -> corrupt()
    isStorageFailure(error) || error is SerializationException -> Problem(
      ProblemCode.SETTINGS_UPDATE_FAILED,
      "Settings could not be saved.",
    )
    else -> null
  }

  private fun corrupt() = Problem(
    ProblemCode.SETTINGS_CORRUPT,
    "Settings are unreadable. The file was left in place. Back it up before replacing it.",
  )

  private fun isStorageFailure(error: Throwable): Boolean = error is IOException || isPlatformStorageFailure(error)
}

internal expect fun isPlatformStorageFailure(error: Throwable): Boolean
