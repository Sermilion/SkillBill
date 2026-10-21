package dev.skillbill.tray.composition

import dev.skillbill.tray.core.datastore.LegacyPreferencesSource
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.SettingsState
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Files
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Nothing earlier versions wrote: the smoke never reads or cleans the real user's preferences. */
internal object NoLegacyPreferences : LegacyPreferencesSource {
  override val description: String = "empty"
  override fun string(key: String): String? = null
  override fun boolean(key: String, default: Boolean): Boolean = default
  override fun remove(key: String) = Unit
  override fun flush() = Unit
}

class ApplicationComponentSmokeTest {
  @Test
  fun `the generated graph settles settings, reads a disposable database and shuts down for good`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-composition-smoke").use { fixture ->
      val home = fixture.tempDir.toRealPath()
      val now = Instant.now()
      sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap()).transaction { unit ->
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            "repo-root-realpath-v1:$home", "main", "SKILL-424", null, "smoke", "review",
            "execution-smoke", "active", "review", now, "owned-test", 1, now.plusSeconds(300),
          ),
        )
      }
      val host = matchedBuildHost(home)
      val application = createApplicationComponent(host, NoLegacyPreferences)
      try {
        assertIs<SettingsState.Ready>(withTimeout(10_000) { application.settings.settings.first { it !is SettingsState.Loading } })
        assertIs<Outcome.Value<*>>(application.settings.update { it.copy(retention = Retention.WEEK) })
        assertTrue(Files.isRegularFile(home.resolve(".skill-bill").resolve("desktop-settings.json")), "Settings belong in the disposable home.")

        application.storeSession.selectDatabase(fixture.dbPath.toString())
        val board = assertIs<Outcome.Value<Board>>(application.boards.board(WorkSection.PHASES, Retention.DAY)).value
        assertEquals(listOf("smoke"), board.items.map { it.identity.id })

        // Scope owners: a window graph per window, an entry graph per entry, and the entry's ViewModel factory lives with its entry.
        val first = application.windows.create()
        val second = application.windows.create()
        assertNotSame(first, second)
        assertSame(first.entries, first.entries)
        assertNotSame(first.entries, second.entries)
        val entry = first.entries.create()
        assertSame(entry.viewModels, entry.viewModels)
        assertNotSame(entry.viewModels, first.entries.create().viewModels)
      } finally {
        application.shutdown.closeAndJoin()
      }
      // Closed means closed: the one session owner accepts no further selection, so no new connection can open.
      assertFailsWith<IllegalStateException> { application.storeSession.selectDatabase(fixture.dbPath.toString()) }
    }
  }
}
