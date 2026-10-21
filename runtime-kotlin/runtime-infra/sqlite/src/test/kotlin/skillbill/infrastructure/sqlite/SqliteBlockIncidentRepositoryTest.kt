package skillbill.infrastructure.sqlite

import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteBlockIncidentRepositoryTest {
  @Test
  fun `alias replay keeps original failure and worker claim is revision fenced`() {
    val connection =
      DatabaseRuntime.ensureDatabase(Files.createTempDirectory("sqlite-block-incident").resolve("incidents.db"))
    connection.use {
      val now = Instant.parse("2026-10-21T12:00:00Z")
      val repository = SqliteBlockIncidentRepository(it, Clock.fixed(now, ZoneOffset.UTC))
      val alias = BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "workflow:implement:1")
      val stored = repository.createOrLink(occurrence("bi_${"b".repeat(64)}", "original failure"), alias, now.plusSeconds(300))
      val replay = repository.createOrLink(occurrence("bi_${"c".repeat(64)}", "rewritten failure"), alias, now.plusSeconds(300))

      assertEquals("original failure", replay.occurrence.reason)
      assertEquals(stored.occurrence.occurrenceToken, replay.occurrence.occurrenceToken)
      assertTrue(repository.claim(stored.occurrence.occurrenceToken, 1, "owner-1", 1, "host-1", 42, now.plusSeconds(30)))
      assertFalse(repository.claim(stored.occurrence.occurrenceToken, 1, "owner-2", 1, "host-2", 43, now.plusSeconds(30)))
      assertTrue(repository.renew(stored.occurrence.occurrenceToken, "owner-1", 1, now.plusSeconds(40)))
      assertTrue(
        repository.settle(
          stored.occurrence.occurrenceToken,
          "owner-1",
          1,
          BlockIncidentDiagnosis.COMPLETE,
          null,
        ),
      )
      assertEquals(BlockIncidentDiagnosis.COMPLETE, assertNotNull(repository.find(stored.occurrence.occurrenceToken)).state.diagnosis)
    }
  }

  private fun occurrence(token: String, reason: String) =
    BlockIncidentOccurrence(
      occurrenceToken = token,
      workflowId = "workflow",
      parentWorkflowId = null,
      phaseId = "implement",
      phaseRunId = null,
      invocationId = null,
      executionId = null,
      attempt = 1,
      generation = 1,
      origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
      reason = reason,
      failureCode = null,
      failureDisposition = "needs_user_action",
      throwableType = null,
      exitCode = null,
      observedAt = Instant.parse("2026-10-21T12:00:00Z"),
      evidenceAvailability = BlockIncidentEvidenceAvailability.DATABASE,
    )
}
