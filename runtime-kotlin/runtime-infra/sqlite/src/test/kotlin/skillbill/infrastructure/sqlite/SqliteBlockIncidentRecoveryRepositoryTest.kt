package skillbill.infrastructure.sqlite

import skillbill.infrastructure.sqlite.blockincident.SqliteBlockIncidentRecoveryRepository
import skillbill.ports.blockincident.model.BlockIncidentRecoveryTransition
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAction
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttempt
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryAttemptStatus
import skillbill.workflow.taskruntime.model.blockincident.BlockIncidentRecoveryFailure
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteBlockIncidentRecoveryRepositoryTest {
  @Test
  fun `replayed recovery settles only for its owner and releases its lease`() {
    val dbPath = Files.createTempDirectory("runtime-kotlin-block-recovery-repository").resolve("attempt.db")
    DatabaseRuntime.ensureDatabase(dbPath).use { connection ->
      val token = "bi_${"c".repeat(64)}"
      connection.createStatement().use { statement ->
        statement.executeUpdate(
          "INSERT INTO block_incidents (occurrence_token, contract_version, generation, origin, reason, " +
            "observed_at, evidence_availability, deadline, exit_behavior, identity_availability) VALUES " +
            "('$token', '0.1', 1, 'accepted_phase_block', 'original failure', " +
            "'2026-10-21T12:00:00Z', 'database', '2026-10-21T12:05:00Z', 'unknown_historical', " +
            "'unknown_historical')",
        )
      }
      val repository = SqliteBlockIncidentRecoveryRepository(connection)
      val requestedAt = Instant.parse("2026-10-21T12:02:00Z")
      val attempt =
        BlockIncidentRecoveryAttempt(
          "bra_first", token, BlockIncidentRecoveryAction.REOPEN_BLOCKED_PHASE, 1,
          BlockIncidentRecoveryAttempt.idempotencyKey(token, BlockIncidentRecoveryAction.REOPEN_BLOCKED_PHASE, 1),
          BlockIncidentRecoveryAttemptStatus.REQUESTED, null, null, null, null,
          requestedAt, requestedAt, "wftr-target", null,
        )

      val first = repository.insertIfAbsent(attempt)
      val duplicate = repository.insertIfAbsent(attempt.copy(attemptId = "bra_second"))

      assertEquals("bra_first", first.attemptId)
      assertEquals(first, duplicate)
      connection.createStatement().use { statement ->
        statement.executeQuery("SELECT count(*) FROM block_incident_recovery_attempts").use { rows ->
          rows.next()
          assertEquals(1, rows.getInt(1))
        }
      }
      assertResumedSettlement(repository, first, requestedAt)
      assertFailedSettlement(repository, attempt, requestedAt)
    }
  }

  private fun assertResumedSettlement(
    repository: SqliteBlockIncidentRecoveryRepository,
    first: BlockIncidentRecoveryAttempt,
    requestedAt: Instant,
  ) {
    val expiresAt = requestedAt.plusSeconds(300)
    assertTrue(
      repository.transition(
        BlockIncidentRecoveryTransition(
          first.attemptId,
          BlockIncidentRecoveryAttemptStatus.REQUESTED,
          BlockIncidentRecoveryAttemptStatus.APPLYING,
          null,
          "owner",
          1,
          expiresAt,
          requestedAt,
        ),
      ),
    )
    assertTrue(
      repository.transition(
        BlockIncidentRecoveryTransition(
          first.attemptId,
          BlockIncidentRecoveryAttemptStatus.APPLYING,
          BlockIncidentRecoveryAttemptStatus.APPLIED,
          null,
          "owner",
          1,
          expiresAt,
          requestedAt,
        ),
      ),
    )
    assertTrue(
      repository.transition(
        BlockIncidentRecoveryTransition(
          first.attemptId,
          BlockIncidentRecoveryAttemptStatus.APPLIED,
          BlockIncidentRecoveryAttemptStatus.RESUMING,
          null,
          "resume-owner",
          1,
          expiresAt,
          requestedAt,
        ),
      ),
    )
    assertResumeOwnerSettlement(repository, first, requestedAt)
  }

  private fun assertResumeOwnerSettlement(
    repository: SqliteBlockIncidentRecoveryRepository,
    first: BlockIncidentRecoveryAttempt,
    requestedAt: Instant,
  ) {
    assertFalse(
      repository.transition(
        BlockIncidentRecoveryTransition(
          first.attemptId,
          BlockIncidentRecoveryAttemptStatus.RESUMING,
          BlockIncidentRecoveryAttemptStatus.RESUMED,
          null,
          "other-owner",
          1,
          null,
          requestedAt,
        ),
      ),
    )
    assertTrue(
      repository.transition(
        BlockIncidentRecoveryTransition(
          first.attemptId,
          BlockIncidentRecoveryAttemptStatus.RESUMING,
          BlockIncidentRecoveryAttemptStatus.RESUMED,
          null,
          "resume-owner",
          1,
          null,
          requestedAt,
        ),
      ),
    )
    val resumed = requireNotNull(repository.byIdempotencyKey(first.idempotencyKey))
    assertEquals(BlockIncidentRecoveryAttemptStatus.RESUMED, resumed.status)
    assertNull(resumed.ownerToken)
    assertNull(resumed.ownerGeneration)
    assertNull(resumed.ownerExpiresAt)
  }

  private fun assertFailedSettlement(
    repository: SqliteBlockIncidentRecoveryRepository,
    attempt: BlockIncidentRecoveryAttempt,
    requestedAt: Instant,
  ) {
    val expiresAt = requestedAt.plusSeconds(300)
    val failedAttempt =
      repository.insertIfAbsent(
        attempt.copy(
          attemptId = "bra_failed",
          incidentRevision = 2,
          idempotencyKey = BlockIncidentRecoveryAttempt.idempotencyKey(attempt.occurrenceToken, attempt.action, 2),
        ),
      )
    assertTrue(
      repository.transition(
        BlockIncidentRecoveryTransition(
          failedAttempt.attemptId,
          BlockIncidentRecoveryAttemptStatus.REQUESTED,
          BlockIncidentRecoveryAttemptStatus.APPLYING,
          null,
          "owner",
          1,
          expiresAt,
          requestedAt,
        ),
      ),
    )
    assertTrue(
      repository.transition(
        BlockIncidentRecoveryTransition(
          failedAttempt.attemptId,
          BlockIncidentRecoveryAttemptStatus.APPLYING,
          BlockIncidentRecoveryAttemptStatus.FAILED,
          BlockIncidentRecoveryFailure.ACTION_FAILED,
          "owner",
          1,
          null,
          requestedAt,
        ),
      ),
    )
    val failed = requireNotNull(repository.byIdempotencyKey(failedAttempt.idempotencyKey))
    assertEquals(BlockIncidentRecoveryFailure.ACTION_FAILED, failed.failure)
    assertNull(failed.ownerToken)
    assertNull(failed.ownerGeneration)
    assertNull(failed.ownerExpiresAt)
  }
}
