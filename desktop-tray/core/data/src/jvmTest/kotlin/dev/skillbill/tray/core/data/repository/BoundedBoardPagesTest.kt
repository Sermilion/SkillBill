package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.data.workStack
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.toKotlinInstant

class BoundedBoardPagesTest {
  private val now = Instant.now()

  @Test
  fun `five thousand newer terminal records neither starve live work nor widen a page nor entangle sections`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-bounded-pages").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { unit ->
        repeat(LIVE) { index ->
          unit.standalonePhaseStatuses.register(
            registration(fixture.tempDir, "live-$index", now.minusSeconds(3600L + index), "live-$index", "SKILL-${2000 + index}"),
          )
        }
        repeat(TERMINAL) { index ->
          val record = unit.standalonePhaseStatuses.register(
            registration(fixture.tempDir, "done-$index", now.minusSeconds(30), "branch-$index", "SKILL-${10_000 + index}"),
          )
          unit.standalonePhaseStatuses.update(
            StandalonePhaseStatusUpdate(
              record.executionId, record.statusRevision, record.leaseOwner, record.leaseGeneration, "terminal", "review", null,
              now.minusSeconds(1), finishedAt = now.minusSeconds(1), terminalResult = "completed",
            ),
          )
        }
      }
      val beforeNextSelection = AtomicReference<(() -> Unit)?>(null)
      val stack = workStack(
        fixture.tempDir.toRealPath(), TestClock(now.toKotlinInstant()),
        onCandidateQuery = { beforeNextSelection.getAndSet(null)?.invoke() },
      )
      try {
        stack.selectDatabase(fixture.dbPath.toString())
        val phases = value(stack.board(WorkSection.PHASES, Retention.DAY))
        val history = value(stack.board(WorkSection.HISTORY, Retention.DAY))

        // Older live work stays visible behind newer terminal rows: no history starvation.
        assertEquals(PAGE, phases.items.size)
        assertTrue(phases.items.all { it.identity.id.startsWith("live-") }, phases.items.map { it.identity.id }.toString())
        assertEquals(PAGE, history.items.size)
        assertTrue(history.items.all { it.identity.id.startsWith("done-") })

        // The SQL selection is bounded per page, so no read slices the whole table.
        val reads = stack.diagnostics.requests.filter { (category, _) -> category.startsWith("board_read_") }
        assertTrue(reads.isNotEmpty())
        reads.forEach { (category, fields) ->
          assertTrue(fields.getValue("selected_rows").toInt() <= CANDIDATE_BUDGET, "$category selected ${fields["selected_rows"]} rows")
        }

        // Appending one section must not consume or invalidate another section's continuation.
        val historyCursor = assertNotNull(history.nextCursor)
        val moreHistory = value(stack.board(WorkSection.HISTORY, Retention.DAY, historyCursor))
        assertEquals(PAGE, moreHistory.items.size)
        assertTrue((history.items + moreHistory.items).map { it.identity }.toSet().size == 2 * PAGE)
        val morePhases = value(stack.board(WorkSection.PHASES, Retention.DAY, assertNotNull(phases.nextCursor)))
        assertEquals(LIVE - PAGE, morePhases.items.size)
        assertEquals(LIVE, (phases.items + morePhases.items).map { it.identity }.toSet().size)

        // A commit landing while a page is selected forces one coherence retry. The retry is bounded too, and the
        // request-wide accounting counts both attempts instead of reporting only the last one.
        beforeNextSelection.set {
          factory.transaction { unit ->
            unit.standalonePhaseStatuses.register(registration(fixture.tempDir, "late-live", now.minusSeconds(10), "late-branch", "SKILL-3000"))
          }
        }
        val retried = value(stack.board(WorkSection.PHASES, Retention.DAY))
        assertEquals(PAGE, retried.items.size)
        assertEquals("late-live", retried.items.first().identity.id)
        val retriedRead = stack.diagnostics.requests.last { (category, _) -> category == "board_read_phases_success" }.second
        assertEquals(2, retriedRead.getValue("attempts").toInt())
        assertEquals(2, retriedRead.getValue("selection_statements").toInt())
        assertTrue(retriedRead.getValue("selected_rows").toInt() <= 2 * CANDIDATE_BUDGET, retriedRead.toString())
      } finally {
        stack.closeAndJoin()
      }
    }
  }

  private fun registration(root: Path, id: String, started: Instant, branch: String, issueKey: String) =
    StandalonePhaseStatusRegistration(
      "repo-root-realpath-v1:${root.toRealPath()}", branch, issueKey, null, id, "review", "execution-$id", "active", "review",
      started, "owned-test", 1, now.plusSeconds(300),
    )

  private fun value(outcome: Outcome<Board>): Board {
    assertIs<Outcome.Value<Board>>(outcome, outcome.toString())
    return outcome.value
  }

  private companion object {
    const val LIVE = 12
    const val TERMINAL = 5000
    const val PAGE = 10
    const val CANDIDATE_BUDGET = 40
  }
}
