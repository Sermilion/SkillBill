package skillbill.infrastructure.sqlite

import skillbill.error.blockincident.BlockIncidentFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.infrastructure.sqlite.blockincident.SqliteBlockIncidentRepository
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAlias
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentAliasKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClaim
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentClassifier
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentCorrelatedField
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosis
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisEvidenceKind
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentDiagnosisRecord
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentEvidenceAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentExitBehavior
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFailure
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentFieldAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentIdentityAvailability
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOccurrence
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentOrigin
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentReasonRetention
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentScope
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SqliteBlockIncidentRepositoryTest {
  @Test
  fun `alias replay keeps original failure and worker claim is revision fenced`() {
    val connection =
      ensureTestDatabase(Files.createTempDirectory("sqlite-block-incident").resolve("incidents.db"))
    connection.use {
      val now = Instant.parse("2026-10-21T12:00:00Z")
      val repository = SqliteBlockIncidentRepository(it, Clock.fixed(now, ZoneOffset.UTC), { })
      val alias = BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "workflow:implement:1")
      val stored =
        repository.createOrLink(
          occurrence("bi_${"b".repeat(64)}", "original failure"),
          alias,
          now.plusSeconds(300),
        )
      val conflict =
        assertFailsWith<SkillBillRuntimeException> {
          repository.createOrLink(occurrence("bi_${"c".repeat(64)}", "rewritten failure"), alias, now.plusSeconds(300))
        }
      assertEquals(BlockIncidentFailureCode.IMMUTABLE_CONFLICT, conflict.code)
      val replay = repository.createOrLink(stored.occurrence, alias, now.plusSeconds(300))

      assertEquals("original failure", replay.occurrence.reason)
      assertEquals(stored.occurrence.occurrenceToken, replay.occurrence.occurrenceToken)
      assertTrue(
        repository.claim(
          stored.occurrence.occurrenceToken,
          1,
          BlockIncidentClaim("owner-1", 1, "host-1", 42, now.plusSeconds(30)),
        ),
      )
      assertFalse(
        repository.claim(
          stored.occurrence.occurrenceToken,
          1,
          BlockIncidentClaim("owner-2", 1, "host-2", 43, now.plusSeconds(30)),
        ),
      )
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
      assertEquals(
        BlockIncidentDiagnosis.COMPLETE,
        assertNotNull(repository.find(stored.occurrence.occurrenceToken)).state.diagnosis,
      )
    }
  }

  @Test
  fun `diagnosis history is owner fenced and reads back in revision order`() {
    val connection =
      ensureTestDatabase(Files.createTempDirectory("sqlite-block-diagnosis").resolve("incidents.db"))
    connection.use {
      val clock = MutableClock(Instant.parse("2026-10-21T12:00:00Z"))
      val repository = SqliteBlockIncidentRepository(it, clock, { })
      val stored =
        repository.createOrLink(
          occurrence("bi_${"b".repeat(64)}", "original failure"),
          BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "workflow:implement:1"),
          clock.time.plusSeconds(300),
        )
      val token = stored.occurrence.occurrenceToken
      val record = diagnosisRecord(stored.occurrence, clock.time)
      assertTrue(repository.claim(token, 1, BlockIncidentClaim("owner-1", 1, "host-1", 42, clock.time.plusSeconds(30))))

      assertFalse(
        repository.settleWithDiagnosis("owner-stale", 2, BlockIncidentDiagnosis.PARTIAL, record, null),
      )
      assertTrue(repository.diagnoses(token).isEmpty())
      assertTrue(
        repository.settleWithDiagnosis(
          "owner-1",
          1,
          BlockIncidentDiagnosis.PENDING,
          record,
          clock.time.plusSeconds(10),
        ),
      )
      clock.time = clock.time.plusSeconds(11)
      assertTrue(repository.claim(token, 3, BlockIncidentClaim("owner-2", 1, "host-1", 42, clock.time.plusSeconds(30))))
      assertTrue(repository.settleWithDiagnosis("owner-2", 1, BlockIncidentDiagnosis.PARTIAL, record, null))

      assertEquals(listOf(3L, 5L), repository.diagnoses(token).map(BlockIncidentDiagnosisRecord::revision))
      assertEquals(record.copy(revision = 5), repository.diagnoses(token).last())
    }
  }

  @Test
  fun `corrupt diagnosis row is quarantined and excluded from history`() {
    val connection =
      ensureTestDatabase(Files.createTempDirectory("sqlite-block-diagnosis-corrupt").resolve("incidents.db"))
    connection.use {
      val now = Instant.parse("2026-10-21T12:00:00Z")
      val repository = SqliteBlockIncidentRepository(it, Clock.fixed(now, ZoneOffset.UTC), { })
      val stored =
        repository.createOrLink(
          occurrence("bi_${"b".repeat(64)}", "original failure"),
          BlockIncidentAlias(BlockIncidentAliasKind.PHASE_TRANSITION, "workflow:implement:1"),
          now.plusSeconds(300),
        )
      val token = stored.occurrence.occurrenceToken
      assertTrue(repository.claim(token, 1, BlockIncidentClaim("owner-1", 1, "host-1", 42, now.plusSeconds(30))))
      assertTrue(
        repository.settleWithDiagnosis(
          "owner-1",
          1,
          BlockIncidentDiagnosis.PARTIAL,
          diagnosisRecord(stored.occurrence, now),
          null,
        ),
      )
      it.prepareStatement(
        "INSERT INTO block_incident_diagnoses(occurrence_token, revision, summary, what_happened, certainty, " +
          "responsible_category, proposed_recovery_action, no_workaround_known, degradations, investigator_kind, " +
          "completed_at) SELECT occurrence_token, 99, summary, what_happened, 'bogus', responsible_category, " +
          "proposed_recovery_action, no_workaround_known, degradations, investigator_kind, completed_at " +
          "FROM block_incident_diagnoses WHERE occurrence_token = ?",
      ).use { statement ->
        statement.setString(1, token)
        statement.executeUpdate()
      }

      assertEquals(listOf(3L), repository.diagnoses(token).map(BlockIncidentDiagnosisRecord::revision))
      val quarantined =
        it.prepareStatement("SELECT revision, failure_code FROM block_incident_diagnosis_quarantine").use { statement ->
          statement.executeQuery().use { rows ->
            assertTrue(rows.next())
            rows.getLong(1) to rows.getString(2)
          }
        }
      assertEquals(99L to BlockIncidentFailureCode.QUARANTINE.name, quarantined)
    }
  }

  private fun diagnosisRecord(
    occurrence: BlockIncidentOccurrence,
    completedAt: Instant,
  ) = BlockIncidentClassifier.classify(
    occurrence,
    listOf(
      BlockIncidentDiagnosisEvidence(
        BlockIncidentDiagnosisEvidenceKind.WORKFLOW_STATE,
        "workflow_state:workflow",
        "workflow_state: 12 of 12 bytes retained.",
        "a".repeat(64),
        trusted = true,
      ),
    ),
    emptySet(),
    completedAt,
  )

  private class MutableClock(var time: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = time
  }

  private fun occurrence(
    token: String,
    reason: String,
  ) = BlockIncidentOccurrence(
    occurrenceToken = token,
    scope =
      BlockIncidentScope(
        workflowId = "workflow",
        parentWorkflowId = null,
        phaseId = "implement",
        phaseRunId = null,
        invocationId = null,
        executionId = null,
        attempt = 1,
        generation = 1,
      ),
    failure =
      BlockIncidentFailure(
        origin = BlockIncidentOrigin.ACCEPTED_PHASE_BLOCK,
        reason = reason,
        reasonRetention = BlockIncidentReasonRetention.COMPLETE,
        failureCode = null,
        failureDisposition = "needs_user_action",
        throwableType = null,
        exitCode = null,
        exitBehavior = BlockIncidentExitBehavior.RETURNED_FAILURE,
      ),
    observedAt = Instant.parse("2026-10-21T12:00:00Z"),
    evidenceAvailability = BlockIncidentEvidenceAvailability.DATABASE,
    identityAvailability = BlockIncidentIdentityAvailability.PARTIAL,
    fieldAvailability =
      BlockIncidentCorrelatedField.entries.associateWith {
        when (it) {
          BlockIncidentCorrelatedField.WORKFLOW_ID, BlockIncidentCorrelatedField.PHASE_ID,
          BlockIncidentCorrelatedField.ATTEMPT, BlockIncidentCorrelatedField.GENERATION,
          BlockIncidentCorrelatedField.REASON,
          -> BlockIncidentFieldAvailability.AVAILABLE
          else -> BlockIncidentFieldAvailability.NOT_CAPTURED
        }
      },
  )
}
