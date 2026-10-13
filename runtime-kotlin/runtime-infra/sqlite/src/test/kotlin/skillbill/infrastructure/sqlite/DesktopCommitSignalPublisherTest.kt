package skillbill.infrastructure.sqlite

import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Files
import java.sql.Connection
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopCommitSignalPublisherTest {
  @Test
  fun `work commits publish one complete revision snapshot while rollback and unrelated writes stay silent`() {
    establishTemporarySchemaReadiness("desktop-commit-signal-publisher").use { fixture ->
      val database = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      val signal = desktopCommitSignalPath(fixture.dbPath)
      assertFalse(Files.exists(signal))

      database.transaction { unit ->
        unit.goalRunnerControls.persistControlState("parent", GoalRunnerControlState(pauseRequested = true))
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            "repo-root-realpath-v1:${fixture.tempDir}",
            "main",
            "SKILL-423",
            null,
            "phase-work",
            "review",
            "execution-phase-work",
            "active",
            "review",
            Instant.parse("2026-10-13T00:00:00Z"),
            "test-owner",
            1,
            Instant.parse("2026-10-13T00:05:00Z"),
          ),
        )
      }

      val committed = Files.readString(signal)
      assertTrue(committed.contains("generation="))
      assertTrue(committed.contains("goals=1"))
      assertTrue(committed.contains("phases=1"))

      assertFailsWith<IllegalStateException> {
        database.transaction { unit ->
          unit.goalRunnerControls.persistControlState(
            "parent",
            GoalRunnerControlState(paused = true, pauseReason = "test", pausedAt = "2026-10-13T00:01:00Z"),
          )
          error("reject write")
        }
      }
      assertEquals(committed, Files.readString(signal))

      database.transaction { unit ->
        val connection =
          unit::class.java.getDeclaredField("connection").apply { isAccessible = true }
            .get(unit) as Connection
        connection.createStatement().use { statement ->
          statement.execute("CREATE TABLE unrelated_desktop_signal_probe (id TEXT PRIMARY KEY)")
          statement.execute("INSERT INTO unrelated_desktop_signal_probe VALUES ('committed')")
        }
      }
      assertEquals(committed, Files.readString(signal))
    }
  }
}
