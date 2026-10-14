package dev.skillbill.tray.infrastructure.storage

import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.infrastructure.DataStoreSettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.*

class DataStoreSettingsTest {
  @Test
  fun `migration preserves preferences and atomic concurrent updates survive restart`() = runBlocking {
    val legacy = Preferences.userRoot().node("desktop-test-${UUID.randomUUID()}")
    val path = Files.createTempDirectory("desktop-settings").resolve("settings.json")
    legacy.put("cliOverride", "/retired/skill-bill")
    legacy.put("retention", "7d")
    legacy.putBoolean("startAtLogin", false)
    legacy.putBoolean("notify.goal_completed", false)
    val store = DataStoreSettingsStore(path, legacy)
    try {
      val migrated = (store.settings.first() as Outcome.Value).value
      assertEquals(Retention.WEEK, migrated.retention)
      assertFalse(migrated.startAtLogin)
      assertFalse(migrated.notifications.getValue("goal_completed"))
      assertNull(migrated.databasePath)
      assertNull(legacy.get("cliOverride", null))
      coroutineScope {
        launch { store.update { it.copy(databasePath = "/selected/store.db") } }
        launch { store.update { it.copy(retention = Retention.SIX_HOURS) } }
        launch { store.update { it.copy(lastPopoverTab = "Phases") } }
      }
      val updated = (store.settings.first() as Outcome.Value).value
      assertEquals("/selected/store.db", updated.databasePath)
      assertEquals(Retention.SIX_HOURS, updated.retention)
      assertEquals("Phases", updated.lastPopoverTab)
      store.closeAndJoin()
      legacy.put("retention", "24h")
      val restarted = DataStoreSettingsStore(path, legacy)
      try { assertEquals(updated, (restarted.settings.first() as Outcome.Value).value) } finally { restarted.close() }
    } finally { store.close(); legacy.removeNode() }
  }

  @Test
  fun `corrupt settings remain on disk and report corruption without resetting choices`() = runBlocking {
    val legacy = Preferences.userRoot().node("desktop-test-${UUID.randomUUID()}")
    val path = Files.createTempDirectory("desktop-corrupt-settings").resolve("settings.json")
    val bytes = "existing-corrupt-file".encodeToByteArray()
    Files.write(path, bytes)
    val store = DataStoreSettingsStore(path, legacy)
    try {
      val result = store.settings.first()
      assertIs<Outcome.Failure>(result)
      assertEquals(ProblemCode.SETTINGS_CORRUPT, result.problem.code)
      assertContentEquals(bytes, Files.readAllBytes(path))
    } finally { store.close(); legacy.removeNode() }
  }
}
