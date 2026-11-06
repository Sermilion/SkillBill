package dev.skillbill.desktop.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.domain.settings.CriterionMark
import dev.skillbill.desktop.core.domain.settings.CriterionMarkKey
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.IOException

class DataStoreAppSettingsRepositoryTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `a database path of only spaces is refused and the stored path stays`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "settings.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())

    assertEquals(Outcome.Success(Unit), repository.setDatabasePath("/tmp/a.db"))
    assertEquals(Outcome.Failure(SettingsRejection.BlankDatabasePath), repository.setDatabasePath("   "))
    assertEquals("/tmp/a.db", repository.settings.first().databasePath)
  }

  @Test
  fun `favorites default empty and atomic changes preserve other tuples and unknown entries`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "favorites.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())
    assertTrue(repository.settings.first().favoriteWork.isEmpty())
    store.edit { it[AppPreferenceKeys.FAVORITE_WORK] = setOf("unsupported", "v1;3;/a;5;key;1;x") }
    val a = FavoriteWorkKey(DatabaseFileIdentity("/a;z", "key;1"), "x;y")
    val b = FavoriteWorkKey(DatabaseFileIdentity("/b", "file-b"), "x")

    assertEquals(Outcome.Success(Unit), repository.setFavorite(a, true))
    assertEquals(Outcome.Success(Unit), repository.setFavorite(b, true))
    assertEquals(Outcome.Success(Unit), repository.setFavorite(a, false))

    assertEquals(setOf(b), repository.settings.first().favoriteWork)
    assertTrue("unsupported" in store.data.first()[AppPreferenceKeys.FAVORITE_WORK].orEmpty())
  }

  @Test
  fun `favorites recover from a transient read failure without a settings write`() = runTest {
    var reads = 0
    val store = object : DataStore<Preferences> {
      override val data = flow {
        if (reads++ == 0) throw IOException("temporarily unavailable")
        emit(emptyPreferences())
      }
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = error("unexpected write")
    }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())

    assertEquals(Outcome.Success(emptySet()), repository.favoriteWork.first { it is Outcome.Success })
  }

  @Test
  fun `a failed favorite write leaves the confirmed settings unchanged`() = runTest {
    val store = object : DataStore<Preferences> {
      override val data = flowOf(emptyPreferences())
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("read-only")
    }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())
    val key = FavoriteWorkKey(DatabaseFileIdentity("/selected.db", "file"), "SKILL-1")

    assertEquals(Outcome.Failure(SettingsRejection.WriteFailed), repository.setFavorite(key, true))
    assertTrue(repository.settings.first().favoriteWork.isEmpty())
  }

  @Test
  fun `spec favorites round trip through FAVORITE_SPECS without touching FAVORITE_WORK`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "spec-favorites.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())
    store.edit { it[AppPreferenceKeys.FAVORITE_SPECS] = setOf("unsupported-spec") }
    val key = FavoriteSpecKey("proj;1", "path/with/separators")
    val work = FavoriteWorkKey(DatabaseFileIdentity("/db", "file"), "SKILL-1")

    assertEquals(Outcome.Success(Unit), repository.setSpecFavorite(key, true))
    assertEquals(Outcome.Success(Unit), repository.setFavorite(work, true))
    assertEquals(setOf(key), repository.settings.first().favoriteSpecs)
    assertEquals(setOf(work), repository.settings.first().favoriteWork)
    assertTrue("unsupported-spec" in store.data.first()[AppPreferenceKeys.FAVORITE_SPECS].orEmpty())
    assertEquals(Outcome.Success(setOf(key)), repository.favoriteSpecs.first { it is Outcome.Success })
  }

  @Test
  fun `criterion marks replace and clear persisted workflow criteria`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "criterion-marks.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())
    val key = CriterionMarkKey("verify-1", 3)

    assertEquals(Outcome.Success(Unit), repository.setCriterionMark(key, CriterionMark.NotInRepo))
    assertEquals(mapOf(key to CriterionMark.NotInRepo), (repository.criterionMarks.first() as Outcome.Success).value)
    assertEquals(Outcome.Success(Unit), repository.setCriterionMark(key, CriterionMark.SpecQuestion))
    assertEquals(mapOf(key to CriterionMark.SpecQuestion), (repository.criterionMarks.first() as Outcome.Success).value)
    assertEquals(Outcome.Success(Unit), repository.setCriterionMark(key, null))
    assertEquals(emptyMap(), (repository.criterionMarks.first() as Outcome.Success).value)
  }
}
