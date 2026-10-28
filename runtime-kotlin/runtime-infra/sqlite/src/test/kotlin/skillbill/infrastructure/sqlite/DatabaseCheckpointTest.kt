package skillbill.infrastructure.sqlite

import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.ports.db.model.DatabaseCheckpointResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DatabaseCheckpointTest {
  @Test
  fun `checkpoint reports busy for a held reader and truncates after its snapshot ends`() {
    val tempDir = Files.createTempDirectory("database-checkpoint")
    val diagnostics = RecordingCheckpointDiagnostics()
    val factory = sqliteDatabaseSessionFactory(userHome = tempDir, environment = emptyMap(), diagnostics = diagnostics)
    factory.selfManagedWrite { it.worktreeEditJournal.append("wfl-a", tick()) }
    val dbPath = factory.resolveDbPath()
    val reader = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}")
    reader.autoCommit = false
    reader.createStatement().use { statement ->
      statement.executeQuery("SELECT count(*) FROM worktree_edit_journal").use { rows ->
        assertTrue(rows.next())
        assertEquals(1, rows.getInt(1))
      }
    }

    factory.selfManagedWrite { it.worktreeEditJournal.append("wfl-b", tick()) }
    assertIs<DatabaseCheckpointResult.Busy>(factory.truncateWal())
    assertTrue(diagnostics.warnings.any { it.contains("seam=wal_checkpoint") })
    reader.rollback()

    val result = assertIs<DatabaseCheckpointResult.Truncated>(factory.truncateWal())
    assertTrue(result.walPagesBefore > 0)
    assertTrue(!Files.exists(Path.of("$dbPath-wal")) || Files.size(Path.of("$dbPath-wal")) == 0L)
    reader.close()
  }

  @Test
  fun `checkpoint inside current write returns busy`() {
    val tempDir = Files.createTempDirectory("database-checkpoint-active-write")
    val diagnostics = RecordingCheckpointDiagnostics()
    val factory = sqliteDatabaseSessionFactory(userHome = tempDir, environment = emptyMap(), diagnostics = diagnostics)
    factory.transaction {
      assertIs<DatabaseCheckpointResult.Busy>(factory.truncateWal())
    }
    assertTrue(diagnostics.warnings.single().contains("cause=active_transaction"))
  }

  @Test
  fun `checkpoint on an absent path does not create a database`() {
    val tempDir = Files.createTempDirectory("database-checkpoint-absent")
    val dbPath = tempDir.resolve("missing.db")
    val factory =
      sqliteDatabaseSessionFactory(
        userHome = tempDir,
        dbPathOverride = dbPath.toString(),
        environment = emptyMap(),
      )

    assertEquals(DatabaseCheckpointResult.Absent, factory.truncateWal())
    assertFalse(Files.exists(dbPath))
  }

  private fun tick() =
    WorktreeEditTick(
      recordedAt = Instant.parse("2026-10-28T12:00:00Z"),
      phaseId = "implement",
      source = WorktreeEditSource.WORKTREE_PROBE,
      entries = listOf(GoalObservabilityFileDiffStat("src/A.kt", 1, 0)),
    )
}

private class RecordingCheckpointDiagnostics : RuntimeDiagnostics {
  val warnings = mutableListOf<String>()

  override fun warning(
    message: String,
    error: Throwable?,
  ) {
    warnings += message
  }

  override fun error(
    message: String,
    error: Throwable?,
  ) = Unit
}
