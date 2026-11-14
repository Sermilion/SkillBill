package dev.skillbill.desktop.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.model.DatabaseFileIdentity
import dev.skillbill.desktop.core.domain.settings.FavoriteSpecKey
import dev.skillbill.desktop.core.domain.settings.FavoriteWorkKey
import dev.skillbill.desktop.core.domain.settings.NewTaskChatLayout
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.domain.settings.CriterionMark
import dev.skillbill.desktop.core.domain.settings.CriterionMarkKey
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.QaDevicePickKind
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
  fun `missing tour preference reads false and leaves existing and future settings intact`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "tour-missing.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())
    val futureKey = androidx.datastore.preferences.core.stringPreferencesKey("future_setting")
    store.edit {
      it[AppPreferenceKeys.DATABASE_PATH] = "/tmp/previous.db"
      it[AppPreferenceKeys.CHAT_WIDTH_DP] = 640
      it[futureKey] = "preserve"
    }

    assertEquals(Outcome.Success(false), repository.readOnboardingTourSeen())
    assertEquals("/tmp/previous.db", repository.settings.first().databasePath)
    assertEquals(640, repository.settings.first().chatWidthDp)
    assertEquals("preserve", store.data.first()[futureKey])
  }

  @Test
  fun `marking tour seen twice persists and preserves other preferences`() = runTest {
    val file = File(directory, "tour-mark.preferences_pb")
    val futureKey = androidx.datastore.preferences.core.stringPreferencesKey("future_setting")
    val owner = Job(backgroundScope.coroutineContext[Job])
    val firstStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(backgroundScope.coroutineContext + owner)) { file }
    val firstRepository = DataStoreAppSettingsRepository(firstStore, RecordingDiagnostics())
    firstStore.edit {
      it[AppPreferenceKeys.DATABASE_PATH] = "/tmp/previous.db"
      it[futureKey] = "preserve"
    }
    assertEquals(Outcome.Success(Unit), firstRepository.markOnboardingTourSeen())
    assertEquals(Outcome.Success(Unit), firstRepository.markOnboardingTourSeen())
    owner.cancelAndJoin()

    val secondStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
    val secondRepository = DataStoreAppSettingsRepository(secondStore, RecordingDiagnostics())
    assertEquals(Outcome.Success(true), secondRepository.readOnboardingTourSeen())
    assertEquals("/tmp/previous.db", secondStore.data.first()[AppPreferenceKeys.DATABASE_PATH])
    assertEquals("preserve", secondStore.data.first()[futureKey])
  }

  @Test
  fun `a database path of only spaces is refused and the stored path stays`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "settings.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())

    assertEquals(Outcome.Success(Unit), repository.setDatabasePath("/tmp/a.db"))
    assertEquals(Outcome.Failure(SettingsRejection.BlankDatabasePath), repository.setDatabasePath("   "))
    assertEquals("/tmp/a.db", repository.settings.first().databasePath)
  }

  @Test
  fun `chat width round trips and clamps to the maximum`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "chat-width.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())

    assertEquals(Outcome.Success(Unit), repository.setChatWidthDp(700))
    assertEquals(700, repository.settings.first().chatWidthDp)
    assertEquals(Outcome.Success(Unit), repository.setChatWidthDp(5000))
    // The stored value itself is clamped, not only the decoded settings, which clamp on read too.
    assertEquals(2000, store.data.first()[AppPreferenceKeys.CHAT_WIDTH_DP])
    assertEquals(2000, repository.settings.first().chatWidthDp)
  }

  @Test
  fun `details width writes and clears only its key in a populated preferences store`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "details-width.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())
    val futureKey = androidx.datastore.preferences.core.stringPreferencesKey("future_setting")
    val work = FavoriteWorkKey(DatabaseFileIdentity("/db", "identity"), "SONA-16")
    val spec = FavoriteSpecKey("project", "spec.md")
    store.edit {
      it[AppPreferenceKeys.DATABASE_PATH] = "/db/review.db"
      it[AppPreferenceKeys.CHAT_WIDTH_DP] = 640
      it[AppPreferenceKeys.FAVORITE_WORK] = setOf(FavoriteWorkKeyEncoding.encode(work))
      it[AppPreferenceKeys.FAVORITE_SPECS] = setOf(FavoriteSpecKeyEncoding.encode(spec))
      it[futureKey] = "preserve"
    }

    assertEquals(null, repository.settings.first().detailsWidthDp)
    assertEquals(Outcome.Failure(SettingsRejection.InvalidDetailsWidth), repository.setDetailsWidthDp(271))
    assertEquals(Outcome.Success(Unit), repository.setDetailsWidthDp(272))
    assertEquals(Outcome.Success(Unit), repository.setDetailsWidthDp(50_000))
    assertEquals(50_000, store.data.first()[AppPreferenceKeys.DETAILS_WIDTH_DP])
    assertEquals(Outcome.Success(Unit), repository.setDetailsWidthDp(null))

    val saved = store.data.first()
    assertEquals(null, saved[AppPreferenceKeys.DETAILS_WIDTH_DP])
    assertEquals("/db/review.db", saved[AppPreferenceKeys.DATABASE_PATH])
    assertEquals(640, saved[AppPreferenceKeys.CHAT_WIDTH_DP])
    assertEquals(setOf(FavoriteWorkKeyEncoding.encode(work)), saved[AppPreferenceKeys.FAVORITE_WORK])
    assertEquals(setOf(FavoriteSpecKeyEncoding.encode(spec)), saved[AppPreferenceKeys.FAVORITE_SPECS])
    assertEquals("preserve", saved[futureKey])
  }

  @Test
  fun `a stored details width below the minimum is reported and decoded as absent`() = runTest {
    val reported = mutableListOf<String>()
    val preferences = mutablePreferencesOf(AppPreferenceKeys.DETAILS_WIDTH_DP to 271)

    val decoded = preferences.toAppSettings { reported += it }

    assertEquals(null, decoded.detailsWidthDp)
    assertEquals(listOf("details_width_dp=271"), reported)

    val diagnostics = RecordingDiagnostics()
    val store = object : DataStore<Preferences> {
      override val data = flowOf(preferences)
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = error("unexpected write")
    }
    assertEquals(null, DataStoreAppSettingsRepository(store, diagnostics).settings.first().detailsWidthDp)
    assertTrue("desktop_settings_value_unsupported" in diagnostics.categories)
  }

  @Test
  fun `a stored chat width out of range decodes clamped and is reported, and a missing one decodes to the default`() {
    val reported = mutableListOf<String>()

    val clamped = mutablePreferencesOf(AppPreferenceKeys.CHAT_WIDTH_DP to 120).toAppSettings { reported += it }

    assertEquals(360, clamped.chatWidthDp)
    assertEquals(listOf("chat_width_dp=120"), reported)
    assertEquals(560, emptyPreferences().toAppSettings {}.chatWidthDp)
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

  @Test
  fun `an unknown chat layout reads as Tabs and a written layout reads back`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "chat-layout.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())
    store.edit { it[AppPreferenceKeys.NEW_TASK_CHAT_LAYOUT] = "grid" }

    assertEquals(NewTaskChatLayout.Tabs, repository.settings.first().newTaskChatLayout)
    assertEquals(Outcome.Success(Unit), repository.setNewTaskChatLayout(NewTaskChatLayout.SideList))
    assertEquals(NewTaskChatLayout.SideList, repository.settings.first().newTaskChatLayout)
  }

  @Test
  fun `the default agent round trips and clearing removes it`() = runTest {
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(directory, "default-agent.preferences_pb") }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())

    assertEquals(null, repository.settings.first().defaultAgentId)
    assertEquals(Outcome.Success(Unit), repository.setDefaultAgent(" claude "))
    assertEquals("claude", repository.settings.first().defaultAgentId)
    assertEquals(Outcome.Success(Unit), repository.setDefaultAgent(null))
    assertEquals(null, repository.settings.first().defaultAgentId)
    assertEquals(null, store.data.first()[AppPreferenceKeys.DEFAULT_AGENT])
  }

  @Test
  fun `QA device selection round trips and clearing preserves unrelated settings`() = runTest {
    val file = File(directory, "qa-device.preferences_pb")
    val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())
    val serial = QaDevicePick(QaDevicePickKind.Serial, "R58M123", "Pixel phone")
    assertEquals(Outcome.Success(Unit), repository.setQaDevice(serial))
    store.edit { it[AppPreferenceKeys.CLAUDE_CONFIG_DIR] = "/agent/config" }

    assertEquals(Outcome.Success(serial), repository.readQaDevice())
    val avd = QaDevicePick(QaDevicePickKind.Avd, "Pixel_8_API_35", "Pixel 8")
    assertEquals(Outcome.Success(Unit), repository.setQaDevice(avd))
    assertEquals(avd, repository.settings.first().qaDevice)
    assertEquals(Outcome.Success(Unit), repository.setQaDevice(null))
    assertEquals(null, repository.settings.first().qaDevice)
    assertEquals("/agent/config", store.data.first()[AppPreferenceKeys.CLAUDE_CONFIG_DIR])
    assertEquals(null, store.data.first()[AppPreferenceKeys.QA_DEVICE_KIND])
  }

  @Test
  fun `partial or malformed QA device preferences decode to null without exposing identity`() {
    val reported = mutableListOf<String>()
    val prefs = mutablePreferencesOf(
      AppPreferenceKeys.QA_DEVICE_KIND to "bluetooth",
      AppPreferenceKeys.QA_DEVICE_VALUE to "private-serial",
    )

    assertEquals(null, prefs.toAppSettings { reported += it }.qaDevice)
    assertEquals(listOf("qa_device_invalid"), reported)
  }

  @Test
  fun `QA device read failure stays distinct from the automatic selection`() = runTest {
    val store = object : DataStore<Preferences> {
      override val data = flow<Preferences> { throw IOException("unavailable") }
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = error("unexpected write")
    }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())

    assertEquals(Outcome.Failure(SettingsRejection.ReadFailed), repository.readQaDevice())
  }

  @Test
  fun `a failed QA device write leaves the previously stored choice`() = runTest {
    val original = QaDevicePick(QaDevicePickKind.Serial, "R58M123", "Pixel phone")
    val initial = mutablePreferencesOf(
      AppPreferenceKeys.QA_DEVICE_KIND to original.kind.wireValue,
      AppPreferenceKeys.QA_DEVICE_VALUE to original.value,
      AppPreferenceKeys.QA_DEVICE_DISPLAY_NAME to original.displayName,
    )
    val store = object : DataStore<Preferences> {
      override val data = flowOf(initial)
      override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = throw IOException("read-only")
    }
    val repository = DataStoreAppSettingsRepository(store, RecordingDiagnostics())

    assertEquals(
      Outcome.Failure(SettingsRejection.WriteFailed),
      repository.setQaDevice(QaDevicePick(QaDevicePickKind.Avd, "Pixel_8_API_35", "Pixel 8")),
    )
    assertEquals(Outcome.Success(original), repository.readQaDevice())
  }
}
