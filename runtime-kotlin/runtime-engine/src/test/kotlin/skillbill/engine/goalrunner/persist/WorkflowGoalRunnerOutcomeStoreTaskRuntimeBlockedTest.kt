package skillbill.engine.goalrunner.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowGoalRunnerOutcomeStoreTaskRuntimeBlockedTest {
  @Test
  fun `stored blocked outcome with standing durable cause is returned with reason text byte-identical`() {
    val reason = "Review requested changes that remain unresolved."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      blockedContinuationRecord(
        BlockedContinuationRecordFixture(
          workflowId = "wftr-standing-block",
          workflowStatus = WorkflowStatus.BLOCKED.wireValue,
          stepStatus = "blocked",
          blockedReasonArtifact = reason,
          storedBlockedReason = reason,
        ),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcome = requireNotNull(store.terminalOutcome("wftr-standing-block", "SKILL-176.4", 4))

    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals(reason, outcome.blockedReason)
  }

  @Test
  fun `standing blocked outcome with only goal_continuation_outcome reason stays authoritative`() {
    val reason = "Review requested changes that remain unresolved."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      blockedContinuationRecord(
        BlockedContinuationRecordFixture(
          workflowId = "wftr-standing-nested-reason",
          workflowStatus = WorkflowStatus.BLOCKED.wireValue,
          stepStatus = "blocked",
          blockedReasonArtifact = null,
          storedBlockedReason = reason,
        ),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val readOnly = requireNotNull(store.terminalOutcome("wftr-standing-nested-reason", "SKILL-176.4", 4))
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, readOnly.status)
    assertEquals(reason, readOnly.blockedReason)

    val recovered =
      requireNotNull(
        store.recoverAndPersistTerminalOutcome(
          workflowId = "wftr-standing-nested-reason",
          issueKey = "SKILL-176.4",
          subtaskId = 4,
          repoRoot = Path.of("."),
        ),
      )
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, recovered.status)
    assertEquals(reason, recovered.blockedReason)
    val artifacts =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-standing-nested-reason", RUNTIME)).artifactsJson,
      )
    assertNull(artifacts["goal_continuation_outcome_displacement"])
    assertEquals(reason, (artifacts["goal_continuation_outcome"] as Map<*, *>)["blocked_reason"])
  }

  @Test
  fun `stored blocked outcome whose cause is gone falls through instead of replaying the stale reason`() {
    val staleReason =
      "Owned paths already staged outside this workflow; run git restore --staged and retry."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      blockedContinuationRecord(
        BlockedContinuationRecordFixture(
          workflowId = "wftr-20260808-175505-c5po",
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          stepStatus = "running",
          blockedReasonArtifact = null,
          storedBlockedReason = staleReason,
        ),
      ),
      RUNTIME,
    )
    workflows.seedWorkerOwnership(expiredLeaseOwnership("wftr-20260808-175505-c5po"))
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        workerSupervisor = DeadProcessSupervisor,
      )

    val readOnly = store.terminalOutcome("wftr-20260808-175505-c5po", "SKILL-176.4", 4)
    assertTrue(
      readOnly == null || readOnly.blockedReason != staleReason,
      "read path must not replay the stale blocked reason; got $readOnly",
    )

    val recovered =
      requireNotNull(
        store.recoverAndPersistTerminalOutcome(
          workflowId = "wftr-20260808-175505-c5po",
          issueKey = "SKILL-176.4",
          subtaskId = 4,
          repoRoot = Path.of("."),
        ),
      )
    assertEquals(GoalRunnerTerminalStatus.RECONCILABLE, recovered.status)
    assertTrue(recovered.blockedReason != staleReason)

    val artifacts =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-20260808-175505-c5po", RUNTIME)).artifactsJson,
      )
    val displacement = artifacts["goal_continuation_outcome_displacement"] as Map<*, *>
    assertEquals(staleReason, displacement["original_blocked_reason"])
    assertNull(artifacts["goal_continuation_outcome"])
  }

  @Test
  fun `displacing a stale blocked outcome is idempotent across a second resume`() {
    val staleReason =
      "Owned paths already staged outside this workflow; run git restore --staged and retry."
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      blockedContinuationRecord(
        BlockedContinuationRecordFixture(
          workflowId = "wftr-stale-idempotent",
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          stepStatus = "running",
          blockedReasonArtifact = null,
          storedBlockedReason = staleReason,
          declaredProgressTimestamp = Instant.now(),
        ),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val first =
      store.reconcileAuthoritativeOutcomes(
        issueKey = "SKILL-176.4",
        activeWorkflowIds = setOf("wftr-stale-idempotent"),
        gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      )
    val artifactsAfterFirst =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-stale-idempotent", RUNTIME)).artifactsJson,
      )
    assertEquals(
      staleReason,
      (artifactsAfterFirst["goal_continuation_outcome_displacement"] as Map<*, *>)["original_blocked_reason"],
    )
    assertNull(artifactsAfterFirst["goal_continuation_outcome"])
    assertEquals(
      "running",
      requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-stale-idempotent", RUNTIME)).workflowStatus,
    )

    val second =
      store.reconcileAuthoritativeOutcomes(
        issueKey = "SKILL-176.4",
        activeWorkflowIds = setOf("wftr-stale-idempotent"),
        gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      )
    assertEquals(first, second)
    val artifactsAfterSecond =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-stale-idempotent", RUNTIME)).artifactsJson,
      )
    assertEquals(
      artifactsAfterFirst["goal_continuation_outcome_displacement"],
      artifactsAfterSecond["goal_continuation_outcome_displacement"],
    )
    assertEquals(
      "running",
      requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-stale-idempotent", RUNTIME)).workflowStatus,
    )
  }

  @Test
  fun `COMPLETE without sha still falls through to the measure branch alongside corroboration`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(completeWithoutShaContinuationRecord("wftr-complete-no-sha"), RUNTIME)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        gitOperations = MeasuringHeadShaGitOperations,
      )

    val readOnly = requireNotNull(store.terminalOutcome("wftr-complete-no-sha", "SKILL-176.4", 4))
    assertEquals(GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME, readOnly.status)
    assertNull(readOnly.commitSha)

    val recovered =
      requireNotNull(
        store.recoverAndPersistTerminalOutcome(
          workflowId = "wftr-complete-no-sha",
          issueKey = "SKILL-176.4",
          subtaskId = 4,
          repoRoot = Path.of("."),
        ),
      )
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, recovered.status)
    assertEquals("measured-head-sha", recovered.commitSha)
  }
}
