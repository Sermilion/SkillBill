package skillbill.engine.goalrunner.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.model.GoalAttemptLedgerEntryDraft
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerReconcileGate
import skillbill.engine.goalrunner.status.liveLease
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalChildHistoryDraft
import skillbill.goalrunner.model.GoalChildHistoryDraftDisposition
import skillbill.goalrunner.model.GoalChildHistoryDraftSaveResult
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.ports.taskruntime.model.ValidatedFeatureTaskRuntimeExecutionPlan
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun seedCurrentParent(
  workflows: InMemoryWorkflowStates,
  childId: String,
  issueKey: String = "SKILL-87.1",
  subtaskId: Int = 1,
) {
  val parent = taskRuntimeWorkflowRecord("wfl-parent")
  val goal =
    manifest(subtaskId).let { original ->
      original.copy(
        issueKey = issueKey,
        subtasks =
          original.subtasks.map {
            if (it.id == subtaskId) it.copy(status = "in_progress", workflowId = childId) else it
          },
      )
    }
  workflows.saveFeatureTaskWorkflow(
    parent.copy(
      artifactsJson =
        JsonCodec.mapToJsonString(
          parent.toSnapshot().artifacts.toMap() +
            DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(
              DecompositionManifestSchemaValidator().encodeManifestWireMap(goal),
            ),
        ),
    ),
    RUNTIME,
  )
}

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
  fun `history drafts are immutable idempotent parent records outside bounded attempt history`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("wftr-parent"), RUNTIME)
    workflows.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("wftr-child", "wftr-parent"), RUNTIME)
    ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD_REVISION_5).seed(workflows, "wftr-child")
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )
    val draft =
      GoalChildHistoryDraft(
        parentWorkflowId = "wftr-parent",
        subtaskId = 1,
        childWorkflowId = "wftr-child",
        disposition = GoalChildHistoryDraftDisposition.DRAFT,
        boundary = "runtime",
        history = "Persist accepted history before completing a child.",
        decisions = "Store drafts on the parent workflow.",
      )

    assertEquals(GoalChildHistoryDraftSaveResult.Saved(draft), store.saveGoalChildHistoryDraft(draft))
    assertEquals(GoalChildHistoryDraftSaveResult.Saved(draft), store.saveGoalChildHistoryDraft(draft))
    assertEquals(
      GoalChildHistoryDraftSaveResult.Conflict,
      store.saveGoalChildHistoryDraft(draft.copy(history = "A conflicting payload.")),
    )
    repeat(201) { tick ->
      store.recordAttemptLedgerEntry(
        GoalRunnerAttemptLedgerRecordRequest(
          workflowId = "wftr-parent",
          issueKey = "SKILL-446",
          draft =
            GoalAttemptLedgerEntryDraft(
              action = GoalAttemptLedgerAction.FINAL_RECONCILED_OUTCOME,
              timestamp = Instant.parse("2026-06-11T18:28:09Z").plus(tick.toLong(), ChronoUnit.SECONDS),
              finalReconciledResult = "completed",
            ),
        ),
      )
    }

    assertEquals(listOf(draft), store.goalChildHistoryDrafts("wftr-parent"))
  }

  @Test
  fun `draft cannot be saved for a child belonging to another parent`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("wftr-parent"), RUNTIME)
    workflows.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("wftr-child", "wftr-other-parent"), RUNTIME)
    ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD_REVISION_5).seed(workflows, "wftr-child")
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )
    val draft =
      GoalChildHistoryDraft(
        parentWorkflowId = "wftr-parent",
        subtaskId = 1,
        childWorkflowId = "wftr-child",
        disposition = GoalChildHistoryDraftDisposition.SKIP,
        skipReason = "No history change.",
      )

    assertFailsWith<SkillBillRuntimeException> { store.saveGoalChildHistoryDraft(draft) }
    assertTrue(store.goalChildHistoryDrafts("wftr-parent").isEmpty())
  }

  @Test
  fun `completion requires a draft for revision five and keeps revision four compatible`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("wftr-parent"), RUNTIME)
    workflows.saveFeatureTaskWorkflow(taskRuntimeWorkflowRecord("wftr-new-child", "wftr-parent"), RUNTIME)
    workflows.saveFeatureTaskWorkflow(
      taskRuntimeWorkflowRecord(
        "wftr-old-child",
        "wftr-parent",
      ).withSettledLegacyHistorySkip(),
      RUNTIME,
    )
    ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD_REVISION_5).seed(workflows, "wftr-new-child")
    ExecutionPlanAdmissionFixture(SkeletonDefinition.GOAL_CHILD).seed(workflows, "wftr-old-child")

    fun selectChild(childId: String) {
      val parent = requireNotNull(workflows.getFeatureTaskWorkflow("wftr-parent"))
      val manifest =
        manifest(1).let { original ->
          original.copy(
            issueKey = "SKILL-446",
            subtasks = original.subtasks.map { it.copy(workflowId = childId) },
          )
        }
      workflows.saveFeatureTaskWorkflow(
        parent.copy(
          artifactsJson =
            JsonCodec.mapToJsonString(
              parent.toSnapshot().artifacts.toMap() +
                DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.entry(
                  DecompositionManifestSchemaValidator().encodeManifestWireMap(manifest),
                ),
            ),
        ),
        RUNTIME,
      )
    }
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    selectChild("wftr-old-child")
    assertTrue(store.hasRequiredGoalChildHistoryDraft("wftr-parent", 1, "wftr-old-child"))
    selectChild("wftr-new-child")
    assertFalse(store.hasRequiredGoalChildHistoryDraft("wftr-parent", 1, "wftr-new-child"))
    store.saveGoalChildHistoryDraft(
      GoalChildHistoryDraft(
        parentWorkflowId = "wftr-parent",
        subtaskId = 1,
        childWorkflowId = "wftr-new-child",
        disposition = GoalChildHistoryDraftDisposition.SKIP,
        skipReason = "No durable history change.",
      ),
    )
    assertTrue(store.hasRequiredGoalChildHistoryDraft("wftr-parent", 1, "wftr-new-child"))
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

    assertFailsWith<SkillBillRuntimeException> {
      store.unemittedGoalReviewPasses("wftr-goal-review")
    }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
    assertFailsWith<SkillBillRuntimeException> {
      store.acknowledgeGoalReviewPass("wftr-goal-review", 1)
    }.also { assertEquals(InstallFailureCode.INVALID_GOAL_SUBTASK_REVIEW_STATE_SCHEMA, it.code) }
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
    seedCurrentParent(workflows, "wftr-alive")
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
        parentWorkflowId = "wfl-parent",
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
    seedCurrentParent(workflows, "wftr-stale")
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
        parentWorkflowId = "wfl-parent",
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
    seedCurrentParent(workflows, "wftr-old-updatedat")
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
        parentWorkflowId = "wfl-parent",
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
    seedCurrentParent(workflows, "wftr-recent-updatedat")
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
        parentWorkflowId = "wfl-parent",
        activeWorkflowIds = emptySet(),
        gate = GoalRunnerReconcileGate(requireStalenessEvidence = true),
      )

    assertTrue(outcomes.isEmpty(), "a recent updated_at must keep the subtask out of a terminal outcome")
    val alive =
      requireNotNull(
        workflows.getFeatureTaskWorkflowAsMode(
          "wftr-recent-updatedat",
          RUNTIME,
        ),
      ).toSnapshot()
    assertEquals(
      "running",
      alive.workflowStatus.wireValue,
      "a recently-updated subtask must not be marked blocked",
    )
  }

  @Test
  fun `evidence-based reconcile keeps a running subtask with genuinely empty liveness`() {
    val workflows = InMemoryWorkflowStates()
    seedCurrentParent(workflows, "wftr-empty-liveness")
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
        parentWorkflowId = "wfl-parent",
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
        parentWorkflowId = "wfl-parent",
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
        expectedExecutionPlan =
          ValidatedFeatureTaskRuntimeExecutionPlan.read(
            execution.encoded,
            execution.validator,
          ),
      ),
    )

    val updated = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-torn-review", RUNTIME))
    assertEquals("running", updated.workflowStatus)
    assertEquals("review", updated.currentStepId)
    val artifacts = updated.toSnapshot().artifacts
    assertNull(artifacts[DecompositionManifestPayloadKeys.BLOCKED_REASON])
    val retry =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.value(artifacts) as Map<
        *,
        *,
      >
    assertEquals(oldReason, retry["previous_blocked_reason"])
    val review =
      phaseRecordsFromWorkflowArtifacts(decodeWorkflowArtifactsForTest(updated.artifactsJson))
        .getValue("review")
    assertEquals("pending", review.status.wireValue)
    val newReason = "Required build gate declaration is absent from dominant pack 'generic'."
    workflows.saveFeatureTaskWorkflow(blockedBuildContinuation(updated, newReason), RUNTIME)
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
      liveLeaseStore.recoverAndPersistTerminalOutcome("wftr-live-lease", "wfl-parent", "SKILL-87.1", 1, Path.of(".")),
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
      liveProcessStore.recoverAndPersistTerminalOutcome(
        "wftr-live-process",
        "wfl-parent",
        "SKILL-87.1",
        1,
        Path.of("."),
      ),
    )
    assertEquals(
      "running",
      requireNotNull(liveProcess.getFeatureTaskWorkflowAsMode("wftr-live-process", RUNTIME)).workflowStatus,
    )
  }
}
