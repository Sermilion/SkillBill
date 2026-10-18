package dev.skillbill.tray.core.datastore

import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.defaultNotifications
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataStoreSettingsRepositoryTest {
  private suspend fun DataStoreSettingsRepository.ready() = (settings.first { it !is SettingsState.Loading } as SettingsState.Ready).settings

  @Test
  fun `the first emission is loading and never default settings`() = runBlocking {
    val fixture = SettingsFixture(SettingsFixture.newHome("desktop-settings-loading"))
    try {
      val emissions = fixture.repository.settings.take(2).toList()
      assertEquals(SettingsState.Loading, emissions.first())
      assertIs<SettingsState.Ready>(emissions.last())
    } finally {
      fixture.handle.closeAndJoin()
    }
  }

  @Test
  fun `legacy preferences migrate once and concurrent updates all survive a restart`() = runBlocking {
    val home = SettingsFixture.newHome("desktop-settings")
    val legacy = FakeLegacySource(
      strings = mapOf("cliOverride" to "/retired/skill-bill", "retention" to "7d"),
      booleans = mapOf("startAtLogin" to false, "notify.goal_completed" to false),
    )
    val fixture = SettingsFixture(home, legacy)
    val updated = try {
      val migrated = fixture.repository.ready()
      assertEquals(Retention.WEEK, migrated.retention)
      assertFalse(migrated.startAtLogin)
      assertFalse(migrated.notifications.getValue("goal_completed"))
      assertNull(migrated.databasePath)
      assertNull(legacy.string("cliOverride"))
      coroutineScope {
        launch { fixture.repository.update { it.copy(databasePath = "/selected/store.db") } }
        launch { fixture.repository.update { it.copy(retention = Retention.SIX_HOURS) } }
        launch { fixture.repository.update { it.copy(lastPopoverTab = "Phases") } }
        // One update per notification key: a lost update would leave one of them at its default.
        for ((key, default) in defaultNotifications) {
          launch { fixture.repository.update { it.copy(notifications = it.notifications + (key to !default)) } }
        }
      }
      fixture.repository.ready().also { settings ->
        assertEquals("/selected/store.db", settings.databasePath)
        assertEquals(Retention.SIX_HOURS, settings.retention)
        assertEquals("Phases", settings.lastPopoverTab)
        assertEquals(defaultNotifications.mapValues { (_, default) -> !default }, settings.notifications)
      }
    } finally {
      fixture.handle.closeAndJoin()
    }
    // A legacy value written after the first run must not be migrated again: the file is the source of truth.
    legacy.strings["retention"] = "24h"
    val restarted = SettingsFixture(home, legacy)
    try {
      assertEquals(updated, restarted.repository.ready())
    } finally {
      restarted.handle.closeAndJoin()
    }
  }

  @Test
  fun `each discarded legacy key is reported once and a second run migrates nothing`() = runBlocking {
    val home = SettingsFixture.newHome("desktop-settings-reports")
    val legacy = FakeLegacySource(strings = mapOf("cliOverride" to "/retired/skill-bill", "pollSeconds" to "5"))
    val first = SettingsFixture(home, legacy)
    try {
      first.repository.ready()
      assertEquals(setOf("cliOverride", "pollSeconds"), first.reports.map { it.key }.toSet())
      assertEquals(2, first.reports.size)
    } finally {
      first.handle.closeAndJoin()
    }
    legacy.strings["cliOverride"] = "/retired/again"
    val second = SettingsFixture(home, legacy)
    try {
      second.repository.ready()
      assertTrue(second.reports.isEmpty())
      assertEquals("/retired/again", legacy.string("cliOverride"))
    } finally {
      second.handle.closeAndJoin()
    }
  }

  @Test
  fun `corrupt settings remain on disk and report corruption without resetting choices`() = runBlocking {
    val home = SettingsFixture.newHome("desktop-corrupt-settings")
    val fixture = SettingsFixture(home)
    val bytes = "existing-corrupt-file".encodeToByteArray()
    Files.createDirectories(fixture.file.parent)
    Files.write(fixture.file, bytes)
    try {
      val result = fixture.repository.settings.first { it !is SettingsState.Loading }
      assertIs<SettingsState.Failed>(result)
      assertEquals(ProblemCode.SETTINGS_CORRUPT, result.problem.code)
      val update = fixture.repository.update { it.copy(retention = Retention.WEEK) }
      assertIs<Outcome.Failure>(update)
      assertEquals(ProblemCode.SETTINGS_CORRUPT, update.problem.code)
      assertContentEquals(bytes, Files.readAllBytes(fixture.file))
    } finally {
      fixture.handle.closeAndJoin()
    }
  }

  @Test
  fun `a write that cannot reach the file is an update failure`() = runBlocking {
    val home = SettingsFixture.newHome("desktop-settings-write-failure")
    // The settings directory is a regular file, so the store file cannot be created beneath it.
    Files.writeString(home.resolve(".skill-bill"), "not a directory")
    val fixture = SettingsFixture(home)
    try {
      val update = fixture.repository.update { it.copy(retention = Retention.WEEK) }
      assertIs<Outcome.Failure>(update)
      assertEquals(ProblemCode.SETTINGS_UPDATE_FAILED, update.problem.code)
    } finally {
      fixture.handle.closeAndJoin()
    }
  }
}
