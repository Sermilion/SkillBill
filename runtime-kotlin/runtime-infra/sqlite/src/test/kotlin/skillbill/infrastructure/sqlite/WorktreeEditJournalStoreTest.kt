package skillbill.infrastructure.sqlite

import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class WorktreeEditJournalStoreTest {
  @Test
  fun `retention deletes older ticks only for the appended workflow`() {
    val tempDir = Files.createTempDirectory("worktree-edit-journal-store")
    val factory = sqliteDatabaseSessionFactory(userHome = tempDir, environment = emptyMap())
    val firstTick = tick("2026-09-18T10:00:00Z", "implement", "src/A.kt" to (3 to 1), "src/B.kt" to (2 to 0))
    val secondTick = tick("2026-09-18T10:00:05Z", "implement", "src/A.kt" to (5 to 1), "src/B.kt" to (1 to 0))
    val thirdTick = tick("2026-09-18T10:00:10Z", "implement", "src/C.kt" to (7 to 2), "src/D.kt" to (2 to 1))
    val otherTick = tick("2026-09-18T10:00:01Z", "review", "docs/README.md" to (1 to 0))
    factory.selfManagedWrite { unitOfWork ->
      unitOfWork.worktreeEditJournal.append("wfl-a", firstTick)
      unitOfWork.worktreeEditJournal.deleteTicksBefore("wfl-a", firstTick.recordedAt)
      unitOfWork.worktreeEditJournal.append("wfl-a", secondTick)
      unitOfWork.worktreeEditJournal.deleteTicksBefore("wfl-a", secondTick.recordedAt)
      unitOfWork.worktreeEditJournal.append("wfl-a", thirdTick)
      unitOfWork.worktreeEditJournal.deleteTicksBefore("wfl-a", thirdTick.recordedAt)
      unitOfWork.worktreeEditJournal.append("wfl-b", otherTick)
    }

    assertEquals(2, rowCount(factory.resolveDbPath(), "wfl-a"))
    assertEquals(1, rowCount(factory.resolveDbPath(), "wfl-b"))
    factory.read { unitOfWork ->
      assertEquals(thirdTick, unitOfWork.worktreeEditJournal.latestTick("wfl-a"))
      assertEquals(otherTick, unitOfWork.worktreeEditJournal.latestTick("wfl-b"))
    }

    val replaced = tick("2026-09-18T10:00:10Z", "review", "src/E.kt" to (9 to 2))
    factory.selfManagedWrite { unitOfWork ->
      unitOfWork.worktreeEditJournal.append("wfl-a", replaced)
    }
    factory.read { unitOfWork ->
      assertEquals(replaced, unitOfWork.worktreeEditJournal.latestTick("wfl-a"))
    }
  }

  private fun rowCount(
    dbPath: Path,
    workflowId: String,
  ): Int =
    DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}").use { connection ->
      connection.prepareStatement("SELECT COUNT(*) FROM worktree_edit_journal WHERE workflow_id = ?").use { statement ->
        statement.setString(1, workflowId)
        statement.executeQuery().use { rows ->
          check(rows.next())
          rows.getInt(1)
        }
      }
    }

  private fun tick(
    recordedAt: String,
    phaseId: String,
    vararg entries: Pair<String, Pair<Int, Int>>,
  ): WorktreeEditTick =
    WorktreeEditTick(
      recordedAt = Instant.parse(recordedAt),
      phaseId = phaseId,
      source = WorktreeEditSource.WORKTREE_PROBE,
      entries =
        entries.map { (path, counts) ->
          GoalObservabilityFileDiffStat(path = path, insertions = counts.first, deletions = counts.second)
        },
    )
}
