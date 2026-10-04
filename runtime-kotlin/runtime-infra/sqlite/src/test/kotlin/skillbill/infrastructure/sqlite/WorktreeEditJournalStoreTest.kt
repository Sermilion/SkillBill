package skillbill.infrastructure.sqlite

import skillbill.idestatus.model.WorktreeEditSource
import skillbill.idestatus.model.WorktreeEditTick
import skillbill.workflow.model.goalobservability.GoalObservabilityFileDiffStat
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class WorktreeEditJournalStoreTest {
  @Test
  fun `latest tick returns only the newest tick and trimming drops whole oldest ticks per workflow`() {
    val tempDir = Files.createTempDirectory("worktree-edit-journal-store")
    val factory = sqliteDatabaseSessionFactory(userHome = tempDir, environment = emptyMap())
    val firstTick = tick("2026-09-18T10:00:00Z", "implement", "src/A.kt" to (3 to 1), "src/B.kt" to (2 to 0))
    val secondTick = tick("2026-09-18T10:00:05Z", "implement", "src/A.kt" to (5 to 1))
    val otherTick = tick("2026-09-18T10:00:01Z", "review", "docs/README.md" to (1 to 0))
    factory.selfManagedWrite { unitOfWork ->
      unitOfWork.worktreeEditJournal.append("wfl-a", firstTick)
      unitOfWork.worktreeEditJournal.append("wfl-a", secondTick)
      unitOfWork.worktreeEditJournal.append("wfl-b", otherTick)
    }

    factory.read { unitOfWork ->
      assertEquals(secondTick, unitOfWork.worktreeEditJournal.latestTick("wfl-a"))
    }

    val deleted =
      factory.selfManagedWrite { unitOfWork ->
        unitOfWork.worktreeEditJournal.trimToCap("wfl-a", maxRows = secondTick.entries.size)
      }

    assertEquals(firstTick.entries.size, deleted)
    factory.read { unitOfWork ->
      assertEquals(secondTick, assertNotNull(unitOfWork.worktreeEditJournal.latestTick("wfl-a")))
      assertEquals(otherTick, unitOfWork.worktreeEditJournal.latestTick("wfl-b"))
    }

    val replaced = tick("2026-09-18T10:00:05Z", "review", "src/C.kt" to (9 to 2))
    factory.selfManagedWrite { unitOfWork ->
      unitOfWork.worktreeEditJournal.append("wfl-a", replaced)
    }
    factory.read { unitOfWork ->
      assertEquals(replaced, unitOfWork.worktreeEditJournal.latestTick("wfl-a"))
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
