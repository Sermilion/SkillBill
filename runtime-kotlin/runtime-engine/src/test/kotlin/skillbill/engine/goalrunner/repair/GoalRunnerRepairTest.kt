package skillbill.engine.goalrunner.repair

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.persist.GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.goalrunner.execution.core.GoalRunnerStatusTestPorts
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerChildRepairStore
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.goalTestPhaseRecorder
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStoreDefaults
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeDiagnosisRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildWedgeRepairRequest
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerRepairRequest
import skillbill.engine.goalrunner.model.GoalRunnerRepairStatus
import skillbill.engine.goalrunner.model.GoalRunnerWedgeClass
import skillbill.engine.goalrunner.persist.GoalRunnerWorkflowOutcomeStore
import skillbill.engine.goalrunner.persist.LiveProcessSupervisor
import skillbill.engine.goalrunner.persist.OutcomeStoreTestArtifactPorts
import skillbill.engine.goalrunner.persist.WorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.persist.decodeWorkflowArtifactsForTest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.goalrunner.goalContinuationOutcome
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_OPERATOR_STOP
import skillbill.goalrunner.model.GOAL_PAUSE_REASON_RUNNER_INTERRUPTED
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeHeartbeat
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatPlan
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeHeartbeatTick
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessIdentity
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineRecoveryRequest
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaselineResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInput
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewInputResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationStatus
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.operatorBlockRetryFromWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.phaseLedgerFromWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.phaseRecordsFromWorkflowArtifacts
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.GoalSubtaskReviewArtifactDecoder
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ISSUE_KEY = "SKILL-176"
internal const val GOAL_BRANCH = "feat/SKILL-176-goal-child-resume-self-heal"
private val REACHABLE_SHA = "c".repeat(40)
private val HEAD_SHA = "d".repeat(40)
private val COMPLETED_COMMIT = "e".repeat(40)

private val DECOMPOSITION_RUNTIME_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()
private val GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_RESULTS.label()
private val GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label()
private val FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()

internal class GoalRunnerRepairTest : GoalRunnerRepairFixtures() {
  @Test
  fun `diagnosis names missing validation_depth with absent current value`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-missing-depth"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = false),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis =
      store.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          subtasks = listOf(subtask(1, workflowId)),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(1, diagnosis.wedges.size)
    assertEquals(GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH, diagnosis.wedges.single().wedgeClass)
    assertEquals("validation_depth", diagnosis.wedges.single().field)
    assertNull(diagnosis.wedges.single().currentValue)
    assertFalse(PASSED_VALIDATION_DEPTH in diagnosis.passedChecks)
  }

  @Test
  fun `diagnosis names missing quality_gate_selection with absent current value`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-missing-selection"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true, includeQualityGateSelection = false),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis =
      store.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          subtasks = listOf(subtask(1, workflowId)),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(1, diagnosis.wedges.size)
    assertEquals(GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION, diagnosis.wedges.single().wedgeClass)
    assertEquals("quality_gate_selection", diagnosis.wedges.single().field)
    assertNull(diagnosis.wedges.single().currentValue)
    assertFalse(PASSED_QUALITY_GATE_SELECTION in diagnosis.passedChecks)
  }

  @Test
  fun `healthy child diagnosis names every check that passed`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-healthy"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis =
      store.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          subtasks = listOf(subtask(1, workflowId)),
          repoRoot = Path.of("."),
        ),
      )

    assertTrue(diagnosis.isHealthy)
    assertEquals(
      listOf(
        PASSED_VALIDATION_DEPTH,
        PASSED_QUALITY_GATE_SELECTION,
        PASSED_REVIEW_BASE,
        PASSED_REMEDIATION_BASE,
        PASSED_CONTINUATION_OUTCOME,
        PASSED_UPSTREAM_OUTPUT,
        PASSED_PHASE_OUTPUT_CONTRACT,
        PASSED_WORKER_LEASE,
      ),
      diagnosis.passedChecks,
    )
  }

  @Test
  fun `diagnosis names phase output contract incompatibility and apply refuses hard reset`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-contract-version"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
          extraArtifacts =
            mapOf(
              "goal_planning_import" to
                mapOf(
                  "phase_output_contract_version" to "0.3",
                ),
            ),
        ),
      ),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())
    val diagnosis =
      store.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          subtasks = listOf(subtask(1, workflowId)),
          repoRoot = Path.of("."),
        ),
      )
    assertFalse(diagnosis.isHealthy)
    assertEquals(
      GoalRunnerWedgeClass.PHASE_OUTPUT_CONTRACT_INCOMPATIBLE,
      diagnosis.wedges.single().wedgeClass,
    )
    assertEquals("0.3", diagnosis.wedges.single().currentValue)

    val service =
      testGoalRunnerStatusService(
        manifestStore = RepairManifestStore(workflowId),
        outcomeStore = store,
        phaseRecorder = goalTestPhaseRecorder(),
        ports =
          GoalRunnerStatusTestPorts(
            childRepairStore = store,
          ),
      )
    val applied =
      service.repair(
        GoalRunnerRepairRequest(
          issueKey = ISSUE_KEY,
          apply = true,
          subtaskId = 1,
          repoRoot = Path.of("."),
        ),
      )
    assertEquals(GoalRunnerRepairStatus.OPERATOR_REQUIRED, applied.status)
    assertTrue(applied.appliedRepairs.isEmpty())
    assertContains(
      applied.refusalReason.orEmpty(),
      "Keep the workflow and checkpoints intact",
    )
  }

  @Test
  fun `diagnosis names unreachable remediation base with the stored sha`() {
    val unreachable = "a".repeat(40)
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-unreachable-remediation"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState().copy(remediationBaseSha = unreachable),
        ),
      ),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit(unreachableShas = setOf(unreachable)))

    val diagnosis =
      store.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          subtasks = listOf(subtask(1, workflowId)),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE, diagnosis.wedges.single().wedgeClass)
    assertEquals(unreachable, diagnosis.wedges.single().currentValue)
  }

  @Test
  fun `repair refusal for unreachable review base points to scoped child reset`() {
    val unreachable = "a".repeat(40)
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-unrecoverable-review"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState().copy(reviewBaseSha = unreachable),
        ),
      ),
      RUNTIME,
    )
    val store =
      repairStore(
        workflows,
        git =
          ReachableGit(
            unreachableShas = setOf(unreachable),
            recoveryStatus = WorkflowGitOperationStatus.ERROR,
          ),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = RepairManifestStore(workflowId),
        outcomeStore = store,
        phaseRecorder = goalTestPhaseRecorder(),
        ports = GoalRunnerStatusTestPorts(childRepairStore = store),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(
          issueKey = ISSUE_KEY,
          apply = true,
          subtaskId = 1,
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(GoalRunnerRepairStatus.OPERATOR_REQUIRED, result.status)
    assertContains(
      result.refusalReason.orEmpty(),
      "skill-bill goal reset $ISSUE_KEY --hard --yes",
    )
  }

  @Test
  fun `repair refusal for unreachable review base with blocked subtask points to scoped child reset`() {
    val unreachable = "a".repeat(40)
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-unrecoverable-review-blocked"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState().copy(reviewBaseSha = unreachable),
          workflowStatus = WorkflowStatus.FAILED.wireValue,
        ),
      ),
      RUNTIME,
    )
    val store =
      repairStore(
        workflows,
        git =
          ReachableGit(
            unreachableShas = setOf(unreachable),
            recoveryStatus = WorkflowGitOperationStatus.ERROR,
          ),
      )
    val manifestStore =
      MutableRepairManifestStore(
        workflowId,
        initialControlState = GoalRunnerControlState(),
        subtasks = listOf(subtask(1, workflowId).copy(status = "blocked")),
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = manifestStore,
        outcomeStore = store,
        phaseRecorder = goalTestPhaseRecorder(),
        ports = GoalRunnerStatusTestPorts(childRepairStore = store),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(
          issueKey = ISSUE_KEY,
          apply = true,
          subtaskId = 1,
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(GoalRunnerRepairStatus.OPERATOR_REQUIRED, result.status)
    assertContains(
      result.refusalReason.orEmpty(),
      "skill-bill goal reset $ISSUE_KEY --subtask 1 --delete-child-workflow",
    )
  }

  @Test
  fun `diagnosis names stale blocked goal_continuation_outcome with the stored reason`() {
    val staleReason = "Persisted review base was orphaned after history rewrite"
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-stale-outcome"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          goalContinuationOutcome =
            mapOf(
              "issue_key" to ISSUE_KEY,
              "subtask_id" to 1,
              "status" to "blocked",
              "workflow_id" to workflowId,
              "blocked_reason" to staleReason,
              "last_resumable_step" to "review",
            ),
        ),
      ),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis =
      store.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          subtasks = listOf(subtask(1, workflowId)),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(
      GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
      diagnosis.wedges.single().wedgeClass,
    )
    assertEquals(staleReason, diagnosis.wedges.single().currentValue)
  }

  @Test
  fun `a blocked step the fix loop moved past does not corroborate a stale blocked outcome`() {
    val staleReason = "Feature-task-runtime phase 'review' governed evidence was never read"
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-abandoned-upstream-block"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          goalContinuationOutcome =
            mapOf(
              "issue_key" to ISSUE_KEY,
              "subtask_id" to 1,
              "status" to "blocked",
              "workflow_id" to workflowId,
              "blocked_reason" to staleReason,
              "last_resumable_step" to "review",
            ),
          abandonedBlockedStepId = "implement_fix",
        ),
      ),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis =
      store.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          subtasks = listOf(subtask(1, workflowId)),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(
      GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME,
      diagnosis.wedges.single().wedgeClass,
    )
    assertEquals(staleReason, diagnosis.wedges.single().currentValue)
  }

  @Test
  fun `diagnosis names completed upstream missing settled output`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-unsettled-upstream"
    val artifacts =
      linkedMapOf<String, Any?>(
        "goal_continuation" to continuationMap(includeValidationDepth = true),
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toPersistenceWire(),
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          mapOf(
            "review" to unsettledUpstreamPhaseRecord("review").asWorkflowArtifactEntry().toWorkflowArtifactMap(),
            "verify_findings" to
              unsettledUpstreamPhaseRecord("verify_findings")
                .asWorkflowArtifactEntry()
                .toWorkflowArtifactMap(),
            "implement_fix" to
              unsettledUpstreamPhaseRecord(
                phaseId = "implement_fix",
                status = "blocked",
                blockedReason =
                  "Phase 'implement_fix' requires upstream output(s) verify_findings that are not present",
              ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
          ),
      )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine()
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "implement_fix")
    workflows.saveFeatureTaskWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "implement_fix",
          stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(artifacts),
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis =
      store.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          subtasks = listOf(subtask(1, workflowId)),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT, diagnosis.wedges.single().wedgeClass)
    assertEquals("verify_findings", diagnosis.wedges.single().field)
    assertFalse(PASSED_UPSTREAM_OUTPUT in diagnosis.passedChecks)
  }

  @Test
  fun `diagnosis names build not validate for build-stamped child missing settled build output`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-unsettled-build-upstream"
    val artifacts =
      linkedMapOf<String, Any?>(
        "goal_continuation" to
          FeatureTaskRuntimeGoalContinuationArtifact(
            issueKey = ISSUE_KEY,
            subtaskId = 1,
            suppressPr = true,
            goalBranch = GOAL_BRANCH,
            parentWorkflowId = "wfl-parent",
            codeReviewMode = CodeReviewExecutionMode.INLINE,
            validationDepth = ValidationDepth.FULL,
            qualityGateSelection = FeatureTaskRuntimeQualityGateSelection.BUILD,
          ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toPersistenceWire(),
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          mapOf(
            "review" to
              unsettledUpstreamPhaseRecord("review", status = "completed").copy(
                outputArtifact = """{"contract_version":"0.1"}""",
              ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
            "build" to unsettledUpstreamPhaseRecord("build").asWorkflowArtifactEntry().toWorkflowArtifactMap(),
            "write_history" to
              unsettledUpstreamPhaseRecord(
                phaseId = "write_history",
                status = "blocked",
                blockedReason = "Phase 'write_history' requires upstream output(s) build that are not present",
              ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
          ),
      )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine()
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "write_history")
    workflows.saveFeatureTaskWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "write_history",
          stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(artifacts),
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val diagnosis =
      store.diagnoseChildWedges(
        GoalRunnerChildWedgeDiagnosisRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          subtasks = listOf(subtask(1, workflowId)),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT, diagnosis.wedges.single().wedgeClass)
    assertEquals("build", diagnosis.wedges.single().field)
    assertFalse(PASSED_UPSTREAM_OUTPUT in diagnosis.passedChecks)
  }

  @Test
  fun `repairing build-stamped completed upstream missing output reopens build not validate`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-unsettled-build-upstream"
    seedRepairParent(workflows, workflowId)
    val artifacts = unsettledBuildUpstreamArtifacts()
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine()
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "write_history")
    workflows.saveFeatureTaskWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.BLOCKED,
          currentStepId = "write_history",
          stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(artifacts),
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val applied =
      store.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          wedgeClasses = listOf(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(1, applied.repairs.size)
    assertEquals("build", applied.repairs.single().field)
    val updated = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
    assertEquals("running", updated.workflowStatus)
    assertEquals("build", updated.currentStepId)
    val records = phaseRecordsFromWorkflowArtifacts(decodeWorkflowArtifactsForTest(updated.artifactsJson))
    assertEquals("pending", records.getValue("build").status.wireValue)
    assertEquals("pending", records.getValue("write_history").status.wireValue)
  }

  @Test
  fun `repairing completed upstream missing output reopens verify_findings and clears implement_fix block`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-unsettled-upstream"
    seedUnsettledUpstreamChild(workflows, workflowId)
    val store =
      repairStore(
        workflows,
        git = ReachableGit(),
        clock = Clock.fixed(Instant.parse("2026-07-27T12:00:00Z"), ZoneOffset.UTC),
      )

    val applied =
      store.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          wedgeClasses = listOf(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(1, applied.repairs.size)
    assertEquals("verify_findings", applied.repairs.single().field)
    val updated = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
    assertEquals("running", updated.workflowStatus)
    assertEquals("verify_findings", updated.currentStepId)
    val updatedArtifacts = decodeWorkflowArtifactsForTest(updated.artifactsJson)
    val records = phaseRecordsFromWorkflowArtifacts(updatedArtifacts)
    assertEquals("pending", records.getValue("verify_findings").status.wireValue)
    assertEquals("pending", records.getValue("implement_fix").status.wireValue)
    assertEquals("2026-07-27T12:00Z", operatorBlockRetryFromWorkflowArtifacts(updatedArtifacts)?.retriedAt)
    assertEquals(
      Instant.parse("2026-07-27T12:00:00Z"),
      phaseLedgerFromWorkflowArtifacts(updatedArtifacts).last().timestamp,
    )
    val evidence =
      (decodeWorkflowArtifactsForTest(updated.artifactsJson)[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>)
        .single() as Map<*, *>
    assertEquals("completed_upstream_missing_output", evidence["wedge_class"])
    assertEquals("verify_findings", evidence["field"])
  }

  private fun seedUnsettledUpstreamChild(
    workflows: InMemoryWorkflowStates,
    workflowId: String,
  ) {
    seedRepairParent(workflows, workflowId)
    val artifacts =
      linkedMapOf<String, Any?>(
        "goal_continuation" to continuationMap(includeValidationDepth = true),
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toPersistenceWire(),
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          mapOf(
            "review" to unsettledUpstreamPhaseRecord("review").asWorkflowArtifactEntry().toWorkflowArtifactMap(),
            "verify_findings" to
              unsettledUpstreamPhaseRecord("verify_findings")
                .asWorkflowArtifactEntry()
                .toWorkflowArtifactMap(),
            "implement_fix" to
              unsettledUpstreamPhaseRecord(
                phaseId = "implement_fix",
                status = "blocked",
                blockedReason =
                  "Phase 'implement_fix' requires upstream output(s) verify_findings that are not present",
              ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
          ),
      )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine()
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "implement_fix")
    workflows.saveFeatureTaskWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.BLOCKED,
          currentStepId = "implement_fix",
          stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(artifacts),
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
      RUNTIME,
    )
  }
}

internal class GoalRunnerRepairContinuationTest : GoalRunnerRepairFixtures() {
  @Test
  fun `repairing completed upstream missing output writes the parent manifest projection`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-unsettled-upstream-projection"
    seedRepairParent(workflows, workflowId)
    saveBlockedUnsettledUpstreamChild(workflows, workflowId)
    val manifestStore = InMemoryRepairManifestFileStore()
    val store = repairStore(workflows, manifestStore = manifestStore)

    val applied =
      store.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          wedgeClasses = listOf(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(1, applied.repairs.size)
    assertEquals(1, manifestStore.writeCount)
  }

  @Test
  fun `repairing completed upstream missing output with another wedge applies both repairs`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-upstream-and-depth"
    seedRepairParent(workflows, workflowId)
    saveBlockedUnsettledUpstreamChild(workflows, workflowId)
    val store = repairStore(workflows, git = ReachableGit())
    val applied =
      store.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          wedgeClasses =
            listOf(
              GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT,
              GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH,
            ),
          repoRoot = Path.of("."),
        ),
      )
    assertEquals(2, applied.repairs.size)
    assertEquals(
      setOf(GoalRunnerWedgeClass.COMPLETED_UPSTREAM_MISSING_OUTPUT, GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH),
      applied.repairs.map { it.wedgeClass }.toSet(),
    )
    val updated = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
    assertEquals("running", updated.workflowStatus)
    assertEquals("verify_findings", updated.currentStepId)
    val after = decodeWorkflowArtifactsForTest(updated.artifactsJson)
    assertEquals("full", (after["goal_continuation"] as Map<*, *>)["validation_depth"])
    val records = phaseRecordsFromWorkflowArtifacts(after)
    assertEquals("pending", records.getValue("verify_findings").status.wireValue)
    assertEquals("pending", records.getValue("implement_fix").status.wireValue)
    assertEquals(2, (after[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>).size)
  }

  private fun saveBlockedUnsettledUpstreamChild(
    workflows: InMemoryWorkflowStates,
    workflowId: String,
  ) {
    val artifacts =
      linkedMapOf<String, Any?>(
        "goal_continuation" to continuationMap(includeValidationDepth = false),
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toPersistenceWire(),
        FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
          mapOf(
            "review" to unsettledUpstreamPhaseRecord("review").asWorkflowArtifactEntry().toWorkflowArtifactMap(),
            "verify_findings" to
              unsettledUpstreamPhaseRecord("verify_findings")
                .asWorkflowArtifactEntry()
                .toWorkflowArtifactMap(),
            "implement_fix" to
              unsettledUpstreamPhaseRecord(
                phaseId = "implement_fix",
                status = "blocked",
                blockedReason =
                  "Phase 'implement_fix' requires upstream output(s) verify_findings that are not present",
              ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
          ),
      )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine()
    val opened = engine.openRecord(definition, workflowId, "fis-repair", "implement_fix")
    workflows.saveFeatureTaskWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.BLOCKED,
          currentStepId = "implement_fix",
          stepUpdates = null,
          artifactsPatch = WorkflowArtifactPatch.from(artifacts),
          sessionId = "ftr-repair",
        ),
      ).toRecord(),
      RUNTIME,
    )
  }

  @Test
  fun `repairing missing quality_gate_selection stamps validate with evidence`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-selection"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true, includeQualityGateSelection = false),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())

    val applied =
      store.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          wedgeClasses = listOf(GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(1, applied.repairs.size)
    assertEquals(GoalRunnerWedgeClass.MISSING_QUALITY_GATE_SELECTION, applied.repairs.single().wedgeClass)
    val after =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )
    val continuation = after["goal_continuation"] as Map<*, *>
    assertEquals("validate", continuation["quality_gate_selection"])
    val evidence = (after[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>).single() as Map<*, *>
    assertEquals("missing_quality_gate_selection", evidence["wedge_class"])
    assertEquals("quality_gate_selection", evidence["field"])
    assertEquals("validate", evidence["new_value"])
  }

  @Test
  fun `repairing missing validation_depth preserves review passes and stamps depth with evidence`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-depth"
    val review =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = REACHABLE_SHA,
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      )
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = false),
          reviewState = review,
          commitSha = COMPLETED_COMMIT,
        ),
      ),
      RUNTIME,
    )
    val before =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )
    val store = repairStore(workflows, git = ReachableGit())

    val applied =
      store.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          wedgeClasses = listOf(GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(1, applied.repairs.size)
    assertEquals("full", applied.repairs.single().newValue)
    val after =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )
    assertEquals(COMPLETED_COMMIT, after["commit_sha"])
    assertEquals(
      before[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY],
      after[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY],
      "review pass results must survive validation_depth repair",
    )
    val continuation = after["goal_continuation"] as Map<*, *>
    assertEquals("full", continuation["validation_depth"])
    val evidence = (after[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>).single() as Map<*, *>
    assertEquals("missing_validation_depth", evidence["wedge_class"])
    assertEquals("validation_depth", evidence["field"])
    assertNull(evidence["prior_value"])
    assertEquals("full", evidence["new_value"])
  }

  @Test
  fun `repairing stale blocked outcome removes the artifact and records evidence while preserving commit sha`() {
    val staleReason = "Persisted review base was orphaned after history rewrite"
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-stale"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          goalContinuationOutcome =
            mapOf(
              "issue_key" to ISSUE_KEY,
              "subtask_id" to 1,
              "status" to "blocked",
              "workflow_id" to workflowId,
              "blocked_reason" to staleReason,
              "last_resumable_step" to "review",
            ),
          commitSha = COMPLETED_COMMIT,
        ),
      ),
      RUNTIME,
    )
    val beforeReview =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY]
    val store = repairStore(workflows, git = ReachableGit())

    val applied =
      store.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          wedgeClasses = listOf(GoalRunnerWedgeClass.STALE_BLOCKED_CONTINUATION_OUTCOME),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(1, applied.repairs.size)
    assertEquals(staleReason, applied.repairs.single().priorValue)
    assertNull(applied.repairs.single().newValue)
    val after =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )
    assertNull(after["goal_continuation_outcome"])
    assertEquals(COMPLETED_COMMIT, after["commit_sha"])
    assertEquals(beforeReview, after[GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY])
    val evidence = (after[GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY] as List<*>).single() as Map<*, *>
    assertEquals("stale_blocked_continuation_outcome", evidence["wedge_class"])
    assertEquals(staleReason, evidence["prior_value"])
  }

  @Test
  fun `repairing unreachable remediation base repoints the sha and preserves completed review passes`() {
    val unreachable = "a".repeat(40)
    val recovered = "b".repeat(40)
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-apply-remediation"
    val review =
      GoalSubtaskReviewState.initial(
        reviewBaseSha = REACHABLE_SHA,
        baselineUntrackedPaths = emptyList(),
        codeReviewMode = CodeReviewExecutionMode.INLINE,
      ).reserveNextPass().completeReservedPass(
        verdict = FeatureTaskRuntimeVerdict.APPROVED,
        unresolvedFindingCount = 0,
        findings = emptyList(),
      ).copy(remediationBaseSha = unreachable)
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = review,
          commitSha = COMPLETED_COMMIT,
        ),
      ),
      RUNTIME,
    )
    val store =
      repairStore(
        workflows,
        git = ReachableGit(unreachableShas = setOf(unreachable), recoveredSha = recovered),
      )

    val applied =
      store.applyChildWedgeRepairs(
        GoalRunnerChildWedgeRepairRequest(
          workflowId = workflowId,
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          wedgeClasses = listOf(GoalRunnerWedgeClass.UNREACHABLE_REMEDIATION_BASE),
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(1, applied.repairs.size)
    assertEquals(unreachable, applied.repairs.single().priorValue)
    assertEquals(recovered, applied.repairs.single().newValue)
    val after =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson,
      )
    val state = requireNotNull(GoalSubtaskReviewArtifactDecoder.decodeReviewStateOnly(after))
    assertEquals(recovered, state.remediationBaseSha)
    assertEquals(1, state.completedPassCount)
    assertEquals(COMPLETED_COMMIT, after["commit_sha"])
  }

  @Test
  fun `mid-repair save failure leaves the durable row unchanged`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-atomicity"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = false),
          reviewState = healthyReviewState(),
          commitSha = COMPLETED_COMMIT,
        ),
      ),
      RUNTIME,
    )
    val beforeJson = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson
    workflows.failSaveWhen = { row ->
      decodeWorkflowArtifactsForTest(row.artifactsJson).containsKey(GOAL_CHILD_REPAIR_EVIDENCE_ARTIFACT_KEY)
    }
    val store = repairStore(workflows, git = ReachableGit())

    val failed =
      runCatching {
        store.applyChildWedgeRepairs(
          GoalRunnerChildWedgeRepairRequest(
            workflowId = workflowId,
            issueKey = ISSUE_KEY,
            subtaskId = 1,
            wedgeClasses = listOf(GoalRunnerWedgeClass.MISSING_VALIDATION_DEPTH),
            repoRoot = Path.of("."),
          ),
        )
      }
    assertTrue(failed.isFailure)
    assertEquals(beforeJson, requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson)
  }

  @Test
  fun `live child worker lease refuses apply and writes nothing`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-live-lease"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = false),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    workflows.seedWorkerOwnership(
      FeatureTaskRuntimeWorkerOwnership(
        workflowId = workflowId,
        generation = 1,
        ownerToken = "owner",
        hostIdentity = "host",
        bootIdentity = "boot",
        pid = 42,
        processBirthToken = "birth",
        leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
        heartbeatAt = "2999-01-01T00:00:00Z",
        expiresAt = "2999-01-01T00:01:00Z",
        phaseId = "implement",
        phaseAttempt = 1,
      ),
    )
    val beforeJson = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson
    val store = repairStore(workflows, git = ReachableGit())
    val database = FakeDatabaseSessionFactory(workflows)
    val phaseRecorder =
      featureTaskRuntimePhaseRecorder(
        database,
        testWorkflowSnapshotValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        testHarnessClock,
        NoopRuntimeDiagnostics,
      )
    val service =
      testGoalRunnerStatusService(
        manifestStore = RepairManifestStore(workflowId),
        outcomeStore = store,
        phaseRecorder = phaseRecorder,
        ports =
          GoalRunnerStatusTestPorts(
            workerSupervisor = LiveProcessSupervisor,
            childRepairStore = store,
          ),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(issueKey = ISSUE_KEY, apply = true, repoRoot = Path.of(".")),
      )

    assertEquals(GoalRunnerRepairStatus.LIVE_LEASE_REFUSED, result.status)
    assertEquals(workflowId, result.liveLeaseWorkflowId)
    assertEquals(beforeJson, requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson)
  }

  @Test
  fun `healthy goal repair is a no-op that reports healthy without durable writes`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-healthy-goal"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    val beforeJson = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson
    val store = repairStore(workflows, git = ReachableGit())
    val service =
      testGoalRunnerStatusService(
        manifestStore = RepairManifestStore(workflowId),
        outcomeStore = store,
        phaseRecorder = goalTestPhaseRecorder(),
        ports =
          GoalRunnerStatusTestPorts(
            childRepairStore = store,
          ),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(issueKey = ISSUE_KEY, apply = true, repoRoot = Path.of(".")),
      )

    assertEquals(GoalRunnerRepairStatus.HEALTHY, result.status)
    assertEquals(beforeJson, requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson)
  }

  @Test
  fun `apply on a not-wedged child reports the passing checks instead of success`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-not-wedged"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    val store = repairStore(workflows, git = ReachableGit())
    val service =
      testGoalRunnerStatusService(
        manifestStore = RepairManifestStore(workflowId),
        outcomeStore = store,
        phaseRecorder = goalTestPhaseRecorder(),
        ports =
          GoalRunnerStatusTestPorts(
            childRepairStore = store,
          ),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(
          issueKey = ISSUE_KEY,
          apply = true,
          subtaskId = 1,
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(GoalRunnerRepairStatus.NOT_WEDGED, result.status)
    assertNotNull(result.refusalReason)
    assertTrue(checkNotNull(result.refusalReason).contains(PASSED_VALIDATION_DEPTH))
  }
}

internal class GoalRunnerRepairLeaseClearanceTest : GoalRunnerRepairFixtures() {
  @Test
  fun `inspect reports stale parent lease child lease and runner interrupted pause`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-issue-342"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    workflows.seedWorkerOwnership(expiredChildWorkerOwnership(workflowId))
    val store = repairStore(workflows, git = ReachableGit())
    val service =
      issue342RepairService(
        Issue342RepairServiceContext(workflows, workflowId, store, issue342StaleParentControlState()),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(issueKey = ISSUE_KEY, apply = false, repoRoot = Path.of(".")),
      )

    assertEquals(GoalRunnerRepairStatus.INSPECTED, result.status)
    assertEquals(
      setOf(
        GoalRunnerWedgeClass.STALE_EXECUTION_LEASE,
        GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE,
      ),
      result.parentWedges.map { it.wedgeClass }.toSet(),
    )
    assertEquals(
      GoalRunnerWedgeClass.STALE_CHILD_WORKER_LEASE,
      result.diagnoses.single().wedges.single().wedgeClass,
    )
    assertTrue(PASSED_PARENT_EXECUTION_LEASE !in result.parentPassedChecks)
    assertTrue(PASSED_PARENT_PAUSE_STATE !in result.parentPassedChecks)
  }

  @Test
  fun `apply clears stale parent lease child lease and runner interrupted pause`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-issue-342-apply"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    workflows.seedWorkerOwnership(expiredChildWorkerOwnership(workflowId))
    val store = repairStore(workflows, git = ReachableGit())
    val manifestStore = MutableRepairManifestStore(workflowId, issue342StaleParentControlState())
    val service =
      issue342RepairService(
        Issue342RepairServiceContext(
          workflows,
          workflowId,
          store,
          issue342StaleParentControlState(),
          manifestStore = manifestStore,
        ),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(issueKey = ISSUE_KEY, apply = true, repoRoot = Path.of(".")),
      )

    assertEquals(GoalRunnerRepairStatus.REPAIRED, result.status)
    assertNull(manifestStore.controlStateValue.executionLease)
    assertEquals(GoalRunnerControlState(), manifestStore.controlStateValue.clearPauseFields())
    assertNull(workflows.getFeatureTaskRuntimeWorkerOwnership(workflowId))
    assertEquals(
      setOf(
        GoalRunnerWedgeClass.STALE_EXECUTION_LEASE,
        GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE,
        GoalRunnerWedgeClass.STALE_CHILD_WORKER_LEASE,
      ),
      result.appliedRepairs.map { it.wedgeClass }.toSet(),
    )
  }

  @Test
  fun `apply refuses when unexpired child lease is present under ambiguous inspection`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-live-unexpired-ambiguous"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = false),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    workflows.seedWorkerOwnership(
      expiredChildWorkerOwnership(workflowId).copy(expiresAt = "2999-01-01T00:01:00Z"),
    )
    val beforeJson = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson
    val store = repairStore(workflows, git = ReachableGit())
    val service =
      issue342RepairService(
        Issue342RepairServiceContext(
          workflows,
          workflowId,
          store,
          GoalRunnerControlState(),
          workerSupervisor = AmbiguousInspectionSupervisor,
        ),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(issueKey = ISSUE_KEY, apply = true, repoRoot = Path.of(".")),
      )

    assertEquals(GoalRunnerRepairStatus.LIVE_LEASE_REFUSED, result.status)
    assertEquals(workflowId, result.liveLeaseWorkflowId)
    assertEquals(beforeJson, requireNotNull(workflows.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME)).artifactsJson)
  }

  @Test
  fun `scoped repair does not clear parent lease or pause wedges`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-scoped-parent-state"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    val parentState = issue342StaleParentControlState()
    val store = repairStore(workflows, git = ReachableGit())
    val manifestStore = MutableRepairManifestStore(workflowId, parentState)
    val service =
      issue342RepairService(
        Issue342RepairServiceContext(
          workflows,
          workflowId,
          store,
          parentState,
          manifestStore = manifestStore,
        ),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(
          issueKey = ISSUE_KEY,
          apply = true,
          subtaskId = 1,
          repoRoot = Path.of("."),
        ),
      )

    assertEquals(GoalRunnerRepairStatus.NOT_WEDGED, result.status)
    assertEquals(parentState, manifestStore.controlStateValue)
    assertTrue(result.parentWedges.isEmpty())
  }

  @Test
  fun `apply preserves operator_stop pause while clearing runner interrupted residue`() {
    val workflows = InMemoryWorkflowStates()
    val workflowId = "wftr-repair-operator-stop"
    workflows.saveFeatureTaskWorkflow(
      repairChildRecord(
        RepairChildRecordArgs(
          workflowId = workflowId,
          continuation = continuationMap(includeValidationDepth = true),
          reviewState = healthyReviewState(),
        ),
      ),
      RUNTIME,
    )
    val operatorStop =
      GoalRunnerControlState(
        pauseRequested = true,
        pauseConsumed = true,
        paused = true,
        pauseReason = GOAL_PAUSE_REASON_OPERATOR_STOP,
        pausedAt = "2000-01-01T00:00:00Z",
        executionLease = issue342ExpiredExecutionLease(),
      )
    val store = repairStore(workflows, git = ReachableGit())
    val manifestStore = MutableRepairManifestStore(workflowId, operatorStop)
    val service =
      issue342RepairService(
        Issue342RepairServiceContext(
          workflows,
          workflowId,
          store,
          operatorStop,
          manifestStore = manifestStore,
        ),
      )

    val result =
      service.repair(
        GoalRunnerRepairRequest(issueKey = ISSUE_KEY, apply = true, repoRoot = Path.of(".")),
      )

    assertEquals(GoalRunnerRepairStatus.REPAIRED, result.status)
    assertNull(manifestStore.controlStateValue.executionLease)
    assertEquals(operatorStop.clearExecutionLease(), manifestStore.controlStateValue)
    assertTrue(result.appliedRepairs.none { it.wedgeClass == GoalRunnerWedgeClass.STALE_RUNNER_INTERRUPTED_PAUSE })
  }

  private fun issue342RepairService(context: Issue342RepairServiceContext): GoalRunnerStatusService {
    val database = FakeDatabaseSessionFactory(context.workflows)
    val phaseRecorder =
      featureTaskRuntimePhaseRecorder(
        database,
        testWorkflowSnapshotValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        testHarnessClock,
        NoopRuntimeDiagnostics,
      )
    return testGoalRunnerStatusService(
      manifestStore = context.manifestStore,
      outcomeStore = context.store,
      phaseRecorder = phaseRecorder,
      ports =
        GoalRunnerStatusTestPorts(
          workerSupervisor = context.workerSupervisor,
          childRepairStore = context.store,
        ),
    )
  }

  private data class Issue342RepairServiceContext(
    val workflows: InMemoryWorkflowStates,
    val workflowId: String,
    val store: RepairTestStore,
    val controlState: GoalRunnerControlState,
    val manifestStore: MutableRepairManifestStore = MutableRepairManifestStore(workflowId, controlState),
    val workerSupervisor: FeatureTaskRuntimeWorkerSupervisor = NoopFeatureTaskRuntimeWorkerSupervisor,
  )

  private fun issue342StaleParentControlState(): GoalRunnerControlState =
    GoalRunnerControlState(
      pauseRequested = true,
      pauseConsumed = true,
      paused = true,
      pauseReason = GOAL_PAUSE_REASON_RUNNER_INTERRUPTED,
      pausedAt = "2000-01-01T00:00:00Z",
      executionLease = issue342ExpiredExecutionLease(),
    )

  private fun issue342ExpiredExecutionLease(): GoalRunnerExecutionLease =
    GoalRunnerExecutionLease(
      generation = 1,
      ownerToken = "parent-owner",
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 42,
      processBirthToken = "birth",
      heartbeatAt = "2000-01-01T00:00:00Z",
      expiresAt = "2000-01-01T00:00:30Z",
    )

  private fun expiredChildWorkerOwnership(workflowId: String): FeatureTaskRuntimeWorkerOwnership =
    FeatureTaskRuntimeWorkerOwnership(
      workflowId = workflowId,
      generation = 1,
      ownerToken = "child-owner",
      hostIdentity = "host",
      bootIdentity = "boot",
      pid = 43,
      processBirthToken = "birth",
      leaseState = FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
      heartbeatAt = "2000-01-01T00:00:00Z",
      expiresAt = "2000-01-01T00:00:30Z",
      phaseId = "implement",
      phaseAttempt = 1,
    )

  private fun GoalRunnerControlState.clearPauseFields(): GoalRunnerControlState =
    copy(
      paused = false,
      pauseRequested = false,
      pauseConsumed = false,
      pauseReason = null,
      pausedAt = null,
    )

  private fun GoalRunnerControlState.clearExecutionLease(): GoalRunnerControlState =
    copy(
      executionLease = null,
      activeDurationAsOf = null,
      subtaskActiveDurationAsOf = null,
    )

  private object AmbiguousInspectionSupervisor : FeatureTaskRuntimeWorkerSupervisor {
    override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
      FeatureTaskRuntimeProcessIdentity("host", "boot", 1, "birth")

    override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) =
      FeatureTaskRuntimeProcessInspection.OwnershipMismatch("the existing process owner is ambiguous")

    override fun awaitExit(
      ownership: FeatureTaskRuntimeWorkerOwnership,
      timeout: Duration,
    ) = Unit

    override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

    override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

    override fun pause(durationMillis: Long) = Unit

    override fun startHeartbeat(
      plan: FeatureTaskRuntimeHeartbeatPlan,
      heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
    ) = NoopFeatureTaskRuntimeHeartbeat
  }
}

internal abstract class GoalRunnerRepairFixtures {
  protected fun seedRepairParent(
    workflows: InMemoryWorkflowStates,
    childWorkflowId: String,
  ) {
    val manifest =
      DecompositionManifest(
        contractVersion = "0.5",
        issueKey = ISSUE_KEY,
        featureName = "repair",
        parentSpecPath = ".feature-specs/$ISSUE_KEY/spec.md",
        status = "in_progress",
        baseBranch = "main",
        featureBranch = "feat/$ISSUE_KEY-repair",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"),
        subtasks = listOf(subtask(1, childWorkflowId)),
      )
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine()
    val opened = engine.openRecord(definition, "wfl-parent", "fis-repair-parent", "preplan")
    workflows.saveFeatureTaskWorkflow(
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "plan",
          stepUpdates = null,
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                  testDecompositionManifestValidator.encodeManifestWireMap(
                    manifest,
                  ),
              ),
            ),
          sessionId = "ftr-repair-parent",
        ),
      ).toRecord().copy(issueKey = ISSUE_KEY),
      RUNTIME,
    )
  }

  protected class RepairTestStore(
    outcomeStore: WorkflowGoalRunnerOutcomeStore,
    childRepairStore: WorkflowGoalRunnerChildRepairStore,
  ) : GoalRunnerWorkflowOutcomeStore by outcomeStore,
    GoalRunnerChildRepairStore by childRepairStore

  protected fun repairStore(
    workflows: InMemoryWorkflowStates,
    git: WorkflowGitOperations = NoopWorkflowGitOperations,
    manifestStore: DecompositionManifestStore = InMemoryRepairManifestFileStore(),
    clock: Clock = testHarnessClock,
  ): RepairTestStore {
    val database = FakeDatabaseSessionFactory(workflows)
    val artifactPorts = OutcomeStoreTestArtifactPorts(decompositionManifestStore = manifestStore)
    return RepairTestStore(
      outcomeStore =
        testWorkflowGoalRunnerOutcomeStore(
          database,
          testWorkflowSnapshotValidator,
          gitOperations = git,
          artifactPorts = artifactPorts,
          clock = clock,
        ),
      childRepairStore =
        testWorkflowGoalRunnerChildRepairStore(
          database,
          gitOperations = git,
          artifactPorts = artifactPorts,
          clock = clock,
        ),
    )
  }

  protected class InMemoryRepairManifestFileStore :
    DecompositionManifestStore by TestDecompositionManifestStore {
    private val files = linkedMapOf<Path, String>()
    var writeCount: Int = 0
      private set

    override fun writeTextAtomically(
      target: Path,
      content: String,
    ) {
      writeCount += 1
      files[target.toAbsolutePath().normalize()] = content
    }

    override fun readText(path: Path): String =
      files[path.toAbsolutePath().normalize()]
        ?: error("InMemoryRepairManifestFileStore has no content for $path")

    override fun isRegularFile(path: Path): Boolean = path.toAbsolutePath().normalize() in files

    override fun deleteIfExists(target: Path) {
      files.remove(target.toAbsolutePath().normalize())
    }
  }

  protected data class RepairChildRecordArgs(
    val workflowId: String,
    val continuation: Map<String, Any?>,
    val reviewState: GoalSubtaskReviewState,
    val workflowStatus: String = "running",
    val goalContinuationOutcome: Map<String, Any?>? = null,
    val commitSha: String? = null,
    val abandonedBlockedStepId: String? = null,
    val extraArtifacts: Map<String, Any?> = emptyMap(),
  )

  protected fun repairChildRecord(args: RepairChildRecordArgs): WorkflowStateRecord {
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val engine = WorkflowEngine()
    val opened = engine.openRecord(definition, args.workflowId, "fis-repair", "preplan")
    val artifacts =
      linkedMapOf<String, Any?>(
        "goal_continuation" to args.continuation,
        GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to args.reviewState.toPersistenceWire(),
      )
    if (args.reviewState.completedPassCount > 0) {
      artifacts[GOAL_SUBTASK_REVIEW_RESULTS_ARTIFACT_KEY] =
        args.reviewState.passResults.associate { result ->
          result.passNumber.toString() to "raw review result for pass ${result.passNumber}"
        }
    }
    args.goalContinuationOutcome?.let { artifacts["goal_continuation_outcome"] = it }
    args.commitSha?.let { artifacts["commit_sha"] = it }
    artifacts.putAll(args.extraArtifacts)
    val currentStepId = if (args.abandonedBlockedStepId == "implement_fix") "validate" else "review"
    return engine.updateRecord(
      definition,
      opened,
      WorkflowUpdateInput(
        terminalInstant = Instant.EPOCH,
        workflowStatus =
          WorkflowStatus.fromWire(args.workflowStatus)
            ?: error("Unknown workflow status '${args.workflowStatus}'."),
        currentStepId = currentStepId,
        stepUpdates =
          WorkflowStepUpdates.from(
            buildList {
              args.abandonedBlockedStepId?.let { stepId ->
                add(mapOf("step_id" to stepId, "status" to "blocked", "attempt_count" to 13))
              }
              add(mapOf("step_id" to currentStepId, "status" to "running", "attempt_count" to 1))
            },
          ),
        artifactsPatch = WorkflowArtifactPatch.from(artifacts),
        sessionId = "ftr-repair",
      ),
    ).toRecord()
  }

  protected fun continuationMap(
    includeValidationDepth: Boolean,
    includeQualityGateSelection: Boolean = true,
  ): Map<String, Any?> =
    FeatureTaskRuntimeGoalContinuationArtifact(
      issueKey = ISSUE_KEY,
      subtaskId = 1,
      suppressPr = true,
      goalBranch = GOAL_BRANCH,
      parentWorkflowId = "wfl-parent",
      codeReviewMode = CodeReviewExecutionMode.INLINE,
      validationDepth = if (includeValidationDepth) ValidationDepth.FULL else null,
      qualityGateSelection =
        if (includeQualityGateSelection) {
          FeatureTaskRuntimeQualityGateSelection.VALIDATE
        } else {
          null
        },
    ).asWorkflowArtifactEntry().toWorkflowArtifactMap().let { map ->
      buildMap {
        putAll(map)
        if (!includeValidationDepth) remove("validation_depth")
        if (!includeQualityGateSelection) remove("quality_gate_selection")
      }
    }

  protected fun healthyReviewState(): GoalSubtaskReviewState =
    GoalSubtaskReviewState.initial(
      reviewBaseSha = REACHABLE_SHA,
      baselineUntrackedPaths = emptyList(),
      codeReviewMode = CodeReviewExecutionMode.INLINE,
    )

  protected fun subtask(
    id: Int,
    workflowId: String?,
  ) = DecompositionSubtask(
    id = id,
    name = "subtask-$id",
    specPath = ".feature-specs/$ISSUE_KEY/spec_subtask_$id.md",
    status = if (workflowId == null) "pending" else "in_progress",
    workflowId = workflowId,
  )

  protected fun unsettledBuildUpstreamArtifacts(): LinkedHashMap<String, Any?> =
    linkedMapOf(
      "goal_continuation" to
        FeatureTaskRuntimeGoalContinuationArtifact(
          issueKey = ISSUE_KEY,
          subtaskId = 1,
          suppressPr = true,
          goalBranch = GOAL_BRANCH,
          parentWorkflowId = "wfl-parent",
          codeReviewMode = CodeReviewExecutionMode.INLINE,
          validationDepth = ValidationDepth.FULL,
          qualityGateSelection = FeatureTaskRuntimeQualityGateSelection.BUILD,
        ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
      GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to healthyReviewState().toPersistenceWire(),
      FEATURE_TASK_RUNTIME_PHASE_RECORDS_ARTIFACT_KEY to
        mapOf(
          "review" to
            unsettledUpstreamPhaseRecord("review", status = "completed").copy(
              outputArtifact = """{"contract_version":"0.1"}""",
            ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
          "build" to unsettledUpstreamPhaseRecord("build").asWorkflowArtifactEntry().toWorkflowArtifactMap(),
          "write_history" to
            unsettledUpstreamPhaseRecord(
              phaseId = "write_history",
              status = "blocked",
              blockedReason = "Phase 'write_history' requires upstream output(s) build that are not present",
            ).asWorkflowArtifactEntry().toWorkflowArtifactMap(),
        ),
    )

  protected fun unsettledUpstreamPhaseRecord(
    phaseId: String,
    status: String = "completed",
    blockedReason: String? = null,
  ): FeatureTaskRuntimePhaseRecord =
    FeatureTaskRuntimePhaseRecord(
      phaseId = phaseId,
      status = status,
      attemptCount = 1,
      startedAt = "2026-08-19T10:00:00Z",
      resolvedAgentId = "cursor",
      outputArtifact = null,
      blockedReason = blockedReason,
      loopId = "review_fix",
      edgeIteration = 1,
    )

  protected class RepairManifestStore(
    childWorkflowId: String,
    controlState: GoalRunnerControlState = GoalRunnerControlState(),
  ) : MutableRepairManifestStore(childWorkflowId, controlState)

  protected open class MutableRepairManifestStore(
    private val childWorkflowId: String,
    initialControlState: GoalRunnerControlState = GoalRunnerControlState(),
    private val subtasks: List<DecompositionSubtask>? = null,
  ) : GoalRunnerManifestStoreDefaults() {
    var controlStateValue: GoalRunnerControlState = initialControlState

    override fun loadByIssueKey(
      issueKey: String,
      repoRoot: Path?,
    ): GoalRunnerManifestState =
      GoalRunnerManifestState(
        parentWorkflowId = "wfl-parent",
        dbPath = "/tmp/repair.db",
        manifest =
          DecompositionManifest(
            contractVersion = "0.5",
            issueKey = issueKey,
            featureName = "repair",
            parentSpecPath = ".feature-specs/$issueKey/spec.md",
            status = "in_progress",
            baseBranch = "main",
            featureBranch = "feat/$issueKey-repair",
            currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"),
            subtasks =
              subtasks ?: listOf(
                DecompositionSubtask(
                  id = 1,
                  name = "child",
                  specPath = ".feature-specs/$issueKey/spec_subtask_1.md",
                  status = "in_progress",
                  workflowId = childWorkflowId,
                ),
              ),
          ),
        controlState = controlStateValue,
        repoRoot = repoRoot,
      )

    override fun controlState(parentWorkflowId: String): GoalRunnerControlState = controlStateValue

    override fun executionLease(parentWorkflowId: String): GoalRunnerExecutionLease? = controlStateValue.executionLease

    override fun persistControlState(
      parentWorkflowId: String,
      state: GoalRunnerControlState,
    ): GoalRunnerControlState {
      controlStateValue = state
      return state
    }

    override fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState {
      val state = controlStateValue
      if (state.pauseReason != GOAL_PAUSE_REASON_RUNNER_INTERRUPTED) return state
      controlStateValue =
        state.copy(
          paused = false,
          pauseRequested = false,
          pauseConsumed = false,
          pauseReason = null,
          pausedAt = null,
        )
      return controlStateValue
    }

    override fun save(state: GoalRunnerManifestState): GoalRunnerManifestState = state

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
    ): Boolean {
      val current = controlStateValue.executionLease ?: return false
      if (current.ownerToken != ownerToken || current.generation != generation) return false
      controlStateValue =
        controlStateValue.copy(
          executionLease = null,
          activeDurationAsOf = null,
          subtaskActiveDurationAsOf = null,
        )
      return true
    }
  }

  protected class ReachableGit(
    private val unreachableShas: Set<String> = emptySet(),
    private val recoveredSha: String = "b".repeat(40),
    private val recoveryStatus: WorkflowGitOperationStatus = WorkflowGitOperationStatus.OK,
  ) : WorkflowGitOperations by NoopWorkflowGitOperations {
    override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
      WorkflowGitOperationResult.Ok(value = HEAD_SHA)

    override fun isCommitAncestor(
      repoRoot: Path,
      ancestorSha: String,
      descendantSha: String,
    ): WorkflowGitOperationResult =
      WorkflowGitOperationResult.Ok(
        value = if (ancestorSha in unreachableShas) "false" else "true",
      )

    override fun captureGoalSubtaskReviewBaseline(
      repoRoot: Path,
      expectedBranch: String,
    ): GoalSubtaskReviewBaselineResult =
      GoalSubtaskReviewBaselineResult(
        status = WorkflowGitOperationStatus.OK,
        baseline = GoalSubtaskReviewBaseline(REACHABLE_SHA, emptyList()),
      )

    override fun buildGoalSubtaskReviewInput(
      repoRoot: Path,
      baseline: GoalSubtaskReviewBaseline,
      expectedBranch: String,
    ): GoalSubtaskReviewInputResult =
      GoalSubtaskReviewInputResult(
        status = WorkflowGitOperationStatus.OK,
        input =
          GoalSubtaskReviewInput(
            reviewBaseSha = baseline.reviewBaseSha,
            currentHeadSha = HEAD_SHA,
            trackedDelta = "",
            ownedUntrackedPatches = "",
          ),
      )

    override fun recoverGoalSubtaskReviewBaseline(
      repoRoot: Path,
      request: GoalSubtaskReviewBaselineRecoveryRequest,
      expectedBranch: String,
    ): GoalSubtaskReviewBaselineResult =
      GoalSubtaskReviewBaselineResult(
        status = recoveryStatus,
        baseline =
          recoveryStatus.takeIf { it == WorkflowGitOperationStatus.OK }
            ?.let { request.toRecoveredBaseline(recoveredSha) },
      )
  }

  protected object LiveProcessSupervisor : FeatureTaskRuntimeWorkerSupervisor {
    override fun currentProcess(): FeatureTaskRuntimeProcessIdentity =
      FeatureTaskRuntimeProcessIdentity("host", "boot", 1, "birth")

    override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) = FeatureTaskRuntimeProcessInspection.ExactLive

    override fun awaitExit(
      ownership: FeatureTaskRuntimeWorkerOwnership,
      timeout: Duration,
    ) = Unit

    override fun terminateGracefully(ownership: FeatureTaskRuntimeWorkerOwnership) = true

    override fun terminateForcibly(ownership: FeatureTaskRuntimeWorkerOwnership) = true

    override fun pause(durationMillis: Long) = Unit

    override fun startHeartbeat(
      plan: FeatureTaskRuntimeHeartbeatPlan,
      heartbeat: () -> FeatureTaskRuntimeHeartbeatTick,
    ) = NoopFeatureTaskRuntimeHeartbeat
  }
}
