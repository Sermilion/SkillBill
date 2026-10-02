package skillbill.engine.goalrunner.planning

import skillbill.application.testHarnessClock
import skillbill.engine.goalrunner.RecordingOutcomeStore
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.planning.model.GoalPlanningAttemptOutcome
import skillbill.engine.goalrunner.planning.model.GoalPlanningLogRequest
import skillbill.engine.goalrunner.status.completed
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.RejectedOutputDiagnosticMetadataValidator
import skillbill.ports.persistence.UnitOfWork
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.model.goalobservability.GoalProgressOutcome
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PARENT_WORKFLOW_ID = "wftr-pairing"
private const val ISSUE_KEY = "SKILL-1"
private const val OPERATION = "plan:3:attempt:1"

class GoalPlanningLogPairingTest {
  @Test
  fun `a relaunched attempt does not borrow the previous segment's completion`() {
    val log =
      logFrom(
        started("2026-08-24T11:57:20Z"),
        completed("2026-08-24T11:57:50Z", outcome = "failed"),
        started("2026-08-24T12:28:01Z"),
        completed("2026-08-24T12:32:01Z", outcome = "succeeded"),
      )

    assertEquals(2, log.attempts.size, "each segment is its own attempt")
    val (first, second) = log.attempts
    assertEquals(30_000L, first.durationMs)
    assertEquals(GoalPlanningAttemptOutcome.FAILED, first.outcome)
    assertEquals(240_000L, second.durationMs)
    assertEquals(GoalPlanningAttemptOutcome.SUCCEEDED, second.outcome)
    assertEquals(270_000L, log.totalPlanningMs)
    assertTrue(log.attempts.none { it.timestampsInconsistent })
  }

  @Test
  fun `an attempt whose process died stays in flight instead of taking a later finish`() {
    val log =
      logFrom(
        started("2026-08-24T11:57:20Z"),
        started("2026-08-24T12:28:01Z"),
        completed("2026-08-24T12:32:01Z", outcome = "succeeded"),
      )

    assertEquals(2, log.attempts.size)
    val crashed = log.attempts.first { it.outcome == GoalPlanningAttemptOutcome.IN_FLIGHT }
    assertNull(crashed.finishedAt, "a killed attempt has no finish of its own")
    assertNull(crashed.durationMs)
    assertFalse(crashed.timestampsInconsistent)
    assertEquals(240_000L, log.totalPlanningMs, "only the measured interval counts")
  }

  @Test
  fun `an inverted pair reports no duration and says the record is inconsistent`() {
    val log =
      logFrom(
        started("2026-08-24T12:28:01Z"),
        completed("2026-08-24T11:57:50Z", outcome = "failed"),
      )

    val attempt = log.attempts.single()
    assertTrue(attempt.timestampsInconsistent)
    assertNull(attempt.durationMs, "a finish before its start is not a duration")
    assertEquals(0L, log.totalPlanningMs, "an unusable record must not subtract from the total")
  }

  private fun logFrom(vararg events: GoalProgressEvent) =
    GoalPlanningLogService(
      manifestStore = StubManifestStore,
      outcomeStore = StubOutcomeStore(events.toList()),
      database = UnreadableDatabase,
      diagnosticMetadataValidator = RejectedOutputDiagnosticMetadataValidator { },
      clock = testHarnessClock,
    ).log(GoalPlanningLogRequest(issueKey = ISSUE_KEY))

  private fun started(timestamp: String): GoalProgressEvent =
    GoalProgressEvent(
      eventKind = GoalProgressEventKind.OPERATION_STARTED,
      workflowId = PARENT_WORKFLOW_ID,
      workflowPhase = "goal_planning",
      processAlive = true,
      sequenceNumber = 0,
      timestamp = timestamp,
      operationName = OPERATION,
      expectedLong = true,
    )

  private fun completed(
    timestamp: String,
    outcome: String,
  ): GoalProgressEvent =
    GoalProgressEvent(
      eventKind = GoalProgressEventKind.OPERATION_COMPLETED,
      workflowId = PARENT_WORKFLOW_ID,
      workflowPhase = "goal_planning",
      processAlive = true,
      sequenceNumber = 0,
      timestamp = timestamp,
      operationName = OPERATION,
      expectedLong = true,
      outcome = GoalProgressOutcome.fromWire(outcome),
    )
}

private object StubManifestStore : GoalRunnerManifestStoreDefaults() {
  override fun loadByIssueKey(
    issueKey: String,
    repoRoot: Path?,
  ) = GoalRunnerManifestState(PARENT_WORKFLOW_ID, "/fake/metrics.db", manifest(subtaskCount = 3))

  override fun save(state: GoalRunnerManifestState) = state

  override fun acquireExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
    expectedOwnerToken: String?,
  ): Boolean = true

  override fun heartbeatExecutionLease(
    parentWorkflowId: String,
    lease: GoalRunnerExecutionLease,
  ): Boolean = true

  override fun releaseExecutionLease(
    parentWorkflowId: String,
    ownerToken: String,
    generation: Long,
  ): Boolean = true
}

private class StubOutcomeStore(private val events: List<GoalProgressEvent>) :
  GoalRunnerWorkflowOutcomeStore by RecordingOutcomeStore() {
  override fun progressEvents(workflowId: String) = events
}

private object UnreadableDatabase : DatabaseSessionFactory {
  override fun resolveDbPath(): Path = Path.of("/fake/metrics.db")

  override fun databaseExists(): Boolean = false

  override fun <T> read(block: (UnitOfWork) -> T): T = unsupported()

  override fun <T> transaction(block: (UnitOfWork) -> T): T = unsupported()

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = unsupported()

  private fun unsupported(): Nothing = throw UnsupportedOperationException("no database in this test")
}
