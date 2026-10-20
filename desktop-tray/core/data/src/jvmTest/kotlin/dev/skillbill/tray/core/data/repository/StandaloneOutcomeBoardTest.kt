package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.data.workStack
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.RunOutcome
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.model.outcome
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import skillbill.ports.idestatus.model.StandalonePhaseStatusUpdate
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.toKotlinInstant

class StandaloneOutcomeBoardTest {
  private val now = Instant.now()

  @Test
  fun `finished standalone phases carry the outcome the runtime recorded`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-standalone-outcomes").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      val finished = now.minusSeconds(30)
      factory.transaction { unit ->
        val qa = unit.standalonePhaseStatuses.register(registration(fixture.tempDir, "qa-run", "qa"))
        unit.standalonePhaseStatuses.update(StandalonePhaseStatusUpdate(qa.executionId, qa.statusRevision, qa.leaseOwner,
          qa.leaseGeneration, "terminal", "qa", null, finished, finishedAt = finished, terminalResult = "No device attached.",
          qaOutcome = StandaloneQaOutcome.SKIPPED))
        val review = unit.standalonePhaseStatuses.register(registration(fixture.tempDir, "review-run", "review"))
        unit.standalonePhaseStatuses.update(StandalonePhaseStatusUpdate(review.executionId, review.statusRevision,
          review.leaseOwner, review.leaseGeneration, "failed", "review", null, finished.plusSeconds(1),
          finishedAt = finished.plusSeconds(1), terminalResult = "Agent exited with status 1."))
      }
      val source = workStack(fixture.tempDir.toRealPath(), TestClock(now.toKotlinInstant()))
      try {
        source.selectDatabase(fixture.dbPath.toString())
        val items = value(source.board(WorkSection.HISTORY, Retention.WEEK)).items
        assertEquals(mapOf("qa-run" to RunOutcome.SKIPPED, "review-run" to RunOutcome.FAILED), items.associate { it.identity.id to it.outcome() })
        assertFalse(items.any { "terminal" in it.summary.lowercase() }, items.joinToString { it.summary })
      } finally { source.closeAndJoin() }
    }
  }

  private fun registration(root: Path, id: String, phaseId: String) = StandalonePhaseStatusRegistration(
    "repo-root-realpath-v1:${root.toRealPath()}", "main", null, null, id, phaseId, "execution-$id", "active", phaseId,
    now.minusSeconds(60), "owned-test", 1, now.plusSeconds(300))

  private fun value(outcome: Outcome<Board>): Board { assertIs<Outcome.Value<Board>>(outcome, outcome.toString()); return outcome.value }
}
