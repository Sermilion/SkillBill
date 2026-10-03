package skillbill.engine.goalrunner.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.model.GoalAttemptLedgerEntryDraft
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.status.liveLease
import skillbill.error.shellcontent.InvalidGoalSubtaskReviewStateSchemaError
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.model.toSnapshot
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowGoalRunnerOutcomeStoreTaskRuntimeTest {
  @Test
  fun `reads progress from task runtime workflows without probing prose mode`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("wftr-task-runtime"), RUNTIME)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val progress = requireNotNull(store.progress("wftr-task-runtime"))

    assertEquals("wftr-task-runtime", progress.workflowId)
    assertEquals("running", progress.workflowStatus.wireValue)
    assertEquals("implement", progress.currentStepId)
  }

  @Test
  fun `appends attempt ledger entries to task runtime workflows without probing prose mode`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("wftr-task-runtime"), RUNTIME)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val recorded =
      store.recordAttemptLedgerEntry(
        GoalRunnerAttemptLedgerRecordRequest(
          workflowId = "wftr-task-runtime",
          issueKey = "SKILL-64",
          draft =
            GoalAttemptLedgerEntryDraft(
              action = GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME,
              timestamp = Instant.parse("2026-06-11T18:28:09Z"),
              finalReconciledResult = "blocked",
            ),
        ),
      )

    assertTrue(recorded)
    assertEquals(RUNTIME, workflows.getFeatureTaskWorkflow("wftr-task-runtime")?.mode)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-task-runtime", RUNTIME)).toSnapshot()
    val artifacts = saved.artifacts.toMap()
    val ledger = artifacts["goal_attempt_ledger"] as List<*>
    val entry = ledger.single() as Map<*, *>
    assertEquals("final_reconciled_outcome", entry["action"])
    assertEquals("blocked", entry["final_reconciled_result"])
  }

  @Test
  fun `appends worker subtask request outcomes to task runtime workflows`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("wftr-task-runtime"), RUNTIME)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val recorded =
      store.recordWorkerSubtaskRequestOutcomes(
        workflowId = "wftr-task-runtime",
        outcomes =
          listOf(
            GoalRunnerWorkerSubtaskRequestOutcome.Rejected(
              sourceStream = "stdout",
              reason = GoalRunnerWorkerSubtaskRequestRejectionReason.UNSAFE_PATH,
              message = "unsafe path",
            ),
          ),
      )

    assertTrue(recorded)
    assertEquals(RUNTIME, workflows.getFeatureTaskWorkflow("wftr-task-runtime")?.mode)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-task-runtime", RUNTIME)).toSnapshot()
    val artifacts = saved.artifacts.toMap()
    val outcomes = artifacts["goal_worker_subtask_request_outcomes"] as List<*>
    val rejected = outcomes.single() as Map<*, *>
    assertEquals("rejected", rejected["status"])
    assertEquals("unsafe_path", rejected["reason"])
  }

  @Test
  fun `raw review evidence must match a compact pass before it can be emitted or acknowledged`() {
    val workflows = InMemoryWorkflowStates()
    val state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )
    workflows.saveFeatureTaskWorkflow(
      goalReviewWorkflowRecord(
        workflowId = "wftr-goal-review",
        state = state,
        rawReviewResult =
          """
          {"verdict":"changes_requested","produced_outputs":{}}
          """.trimIndent(),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
      )

    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      store.unemittedGoalReviewPasses("wftr-goal-review")
    }
    assertFailsWith<InvalidGoalSubtaskReviewStateSchemaError> {
      store.acknowledgeGoalReviewPass("wftr-goal-review", 1)
    }
  }

  @Test
  fun `prose raw review evidence with a matching empty compact pass can be emitted`() {
    val workflows = InMemoryWorkflowStates()
    val state =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = "a".repeat(40),
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.AUTO,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )
    workflows.saveFeatureTaskWorkflow(
      goalReviewWorkflowRecord(
        workflowId = "wftr-goal-review-prose",
        state = state,
        rawReviewResult =
          """
          [F-001] Major | path="runtime-kotlin/Example.kt" | line=10 | description=example finding in prose.
          """.trimIndent(),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val passes = store.unemittedGoalReviewPasses("wftr-goal-review-prose")
    assertEquals(1, passes.size)
    assertEquals(FeatureTaskRuntimeVerdict.APPROVED, passes.single().verdict)
    assertTrue(store.acknowledgeGoalReviewPass("wftr-goal-review-prose", 1))
  }

  @Test
  fun `evidence-based reconcile keeps a running subtask with recent declared progress`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      runtimeCandidateRecord("wftr-alive", declaredProgressTimestamp = Instant.now()),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcomes =
      store.reconcileAuthoritativeOutcomes(
        issueKey = "SKILL-87.1",
        activeWorkflowIds = emptySet(),
        gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      )

    assertTrue(outcomes.isEmpty(), "a live subtask must not be reconciled into a terminal outcome")
    val alive = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-alive", RUNTIME)).toSnapshot()
    assertEquals("running", alive.workflowStatus.wireValue, "a live subtask must not be marked blocked")
  }

  @Test
  fun `evidence-based reconcile blocks a running subtask with no liveness past the staleness window`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      runtimeCandidateRecord(
        "wftr-stale",
        declaredProgressTimestamp = Instant.now().minus(2, ChronoUnit.HOURS),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcomes =
      store.reconcileAuthoritativeOutcomes(
        issueKey = "SKILL-87.1",
        activeWorkflowIds = emptySet(),
        gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      )

    val outcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("wftr-stale", outcome.workflowId)
    val stale = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-stale", RUNTIME)).toSnapshot()
    assertEquals("blocked", stale.workflowStatus.wireValue)
  }

  @Test
  fun `evidence-based reconcile blocks a running subtask whose only liveness is an old sqlite updatedAt`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      runtimeCandidateRecordNoDeclaredEvent(
        "wftr-old-updatedat",
        updatedAt = outcomeStoreSqliteTimestamp(Instant.now().minus(2, ChronoUnit.HOURS)),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcomes =
      store.reconcileAuthoritativeOutcomes(
        issueKey = "SKILL-87.1",
        activeWorkflowIds = emptySet(),
        gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      )

    val outcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("wftr-old-updatedat", outcome.workflowId)
    val stale = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-old-updatedat", RUNTIME)).toSnapshot()
    assertEquals("blocked", stale.workflowStatus.wireValue)
  }

  @Test
  fun `evidence-based reconcile keeps a running subtask whose only liveness is a recent sqlite updatedAt`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      runtimeCandidateRecordNoDeclaredEvent(
        "wftr-recent-updatedat",
        updatedAt = outcomeStoreSqliteTimestamp(Instant.now().minus(5, ChronoUnit.MINUTES)),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcomes =
      store.reconcileAuthoritativeOutcomes(
        issueKey = "SKILL-87.1",
        activeWorkflowIds = emptySet(),
        gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      )

    assertTrue(outcomes.isEmpty(), "a recent updated_at must keep the subtask out of a terminal outcome")
    val alive = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-recent-updatedat", RUNTIME)).toSnapshot()
    assertEquals("running", alive.workflowStatus.wireValue, "a recently-updated subtask must not be marked blocked")
  }

  @Test
  fun `evidence-based reconcile keeps a running subtask with genuinely empty liveness`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      runtimeCandidateRecordNoDeclaredEvent("wftr-empty-liveness", updatedAt = null),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcomes =
      store.reconcileAuthoritativeOutcomes(
        issueKey = "SKILL-87.1",
        activeWorkflowIds = emptySet(),
        gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      )

    assertTrue(outcomes.isEmpty(), "empty-liveness must bias to alive, not produce a terminal outcome")
    val alive = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-empty-liveness", RUNTIME)).toSnapshot()
    assertEquals("running", alive.workflowStatus.wireValue, "empty-liveness must not be marked blocked")
  }

  @Test
  fun `a crashed goal child with an expired lease and dead process reconciles to a resumable outcome`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(crashedChildRecord("wftr-crashed-child"), RUNTIME)
    workflows.seedWorkerOwnership(expiredLeaseOwnership("wftr-crashed-child"))
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        workerSupervisor = DeadProcessSupervisor,
      )

    val outcome =
      store.recoverAndPersistTerminalOutcome(
        workflowId = "wftr-crashed-child",
        issueKey = "SKILL-87.1",
        subtaskId = 1,
        repoRoot = Path.of("."),
      )

    val reconciled = assertNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.RECONCILABLE, reconciled.status)
    assertEquals("implement", reconciled.lastResumableStep)
    assertEquals(
      "pending",
      requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-crashed-child", RUNTIME)).workflowStatus,
    )
    assertNull(workflows.getFeatureTaskRuntimeWorkerOwnership("wftr-crashed-child"))
  }

  @Test
  fun `operator resume reopens a running review phase left on a blocked child`() {
    val workflows = InMemoryWorkflowStates()
    val original = tornBlockedReviewRecord("wftr-torn-review")
    val oldReason = "Subtask 9 timed out before reaching a terminal workflow-store outcome."
    workflows.saveFeatureTaskWorkflow(
      original.copy(
        artifactsJson =
          JsonCodec.valueToJsonString(
            original.toSnapshot().artifacts +
              (DecompositionManifestPayloadKeys.BLOCKED_REASON to oldReason),
          ),
      ),
      RUNTIME,
    )
    val execution =
      ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD)
    execution.seed(workflows, "wftr-torn-review")
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    assertTrue(
      store.reopenBlockedPhaseForOperatorResume(
        workflowId = "wftr-torn-review",
        preferredPhaseId = "review",
        reason = "Operator resumed the goal after a blocked stop at subtask 9.",
        expectedIdentity = execution.identity("wftr-torn-review"),
        expectedExecutionPlan = ValidatedFeatureTaskRuntimeExecutionPlan.read(execution.encoded, execution.validator),
      ),
    )

    val updated = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-torn-review", RUNTIME))
    assertEquals("running", updated.workflowStatus)
    assertEquals("review", updated.currentStepId)
    val artifacts = updated.toSnapshot().artifacts
    assertNull(artifacts[DecompositionManifestPayloadKeys.BLOCKED_REASON])
    val retry = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.value(artifacts) as Map<*, *>
    assertEquals(oldReason, retry["previous_blocked_reason"])
    val review =
      phaseRecordsFromWorkflowArtifacts(decodeWorkflowArtifactsForTest(updated.artifactsJson))
        .getValue("review")
    assertEquals("pending", review.status.wireValue)
    val newReason = "Required build gate declaration is absent from dominant pack 'generic'."
    workflows.saveFeatureTaskWorkflow(
      updated.copy(
        workflowStatus = "blocked",
        currentStepId = "build",
        stepsJson = blockedBuildStepsJson(updated),
        artifactsJson =
          JsonCodec.valueToJsonString(
            artifacts + (
              "goal_continuation_outcome" to
                mapOf(
                  "issue_key" to "SKILL-191",
                  "subtask_id" to 9,
                  "status" to "blocked",
                  "blocked_reason" to newReason,
                  "last_resumable_step" to "build",
                )
            ),
          ),
      ),
      RUNTIME,
    )
    val outcome = assertNotNull(store.terminalOutcome("wftr-torn-review", "SKILL-191", 9))
    assertEquals(newReason, outcome.blockedReason)
    assertEquals("build", outcome.lastResumableStep)
  }

  @Test
  fun `a goal child with a live lease or live process is never reconciled and yields no outcome`() {
    val liveLease = InMemoryWorkflowStates()
    liveLease.saveFeatureTaskWorkflow(crashedChildRecord("wftr-live-lease"), RUNTIME)
    liveLease.seedWorkerOwnership(expiredLeaseOwnership("wftr-live-lease", expiresAt = "2999-01-01T00:00:30Z"))
    val liveLeaseStore =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(liveLease),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        workerSupervisor = DeadProcessSupervisor,
      )
    assertNull(
      liveLeaseStore.recoverAndPersistTerminalOutcome("wftr-live-lease", "SKILL-87.1", 1, Path.of(".")),
    )
    assertEquals(
      "running",
      requireNotNull(liveLease.getFeatureTaskWorkflowAsMode("wftr-live-lease", RUNTIME)).workflowStatus,
    )
    assertNotNull(liveLease.getFeatureTaskRuntimeWorkerOwnership("wftr-live-lease"))

    val liveProcess = InMemoryWorkflowStates()
    liveProcess.saveFeatureTaskWorkflow(crashedChildRecord("wftr-live-process"), RUNTIME)
    liveProcess.seedWorkerOwnership(expiredLeaseOwnership("wftr-live-process"))
    val liveProcessStore =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(liveProcess),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        workerSupervisor = LiveProcessSupervisor,
      )
    assertNull(
      liveProcessStore.recoverAndPersistTerminalOutcome("wftr-live-process", "SKILL-87.1", 1, Path.of(".")),
    )
    assertEquals(
      "running",
      requireNotNull(liveProcess.getFeatureTaskWorkflowAsMode("wftr-live-process", RUNTIME)).workflowStatus,
    )
  }
}
