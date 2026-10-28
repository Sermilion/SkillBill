package dev.skillbill.desktop.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.domain.settings.SettingsRejection
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
