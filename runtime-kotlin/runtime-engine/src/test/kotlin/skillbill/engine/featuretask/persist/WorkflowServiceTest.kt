package skillbill.engine.featuretask.persist

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.TestDecompositionManifestStore
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.encodeValidatedDecompositionManifestYaml
import skillbill.application.decomposition.executionModel
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testRepositoryRoot
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.application.workflow.decomposition.alignSubtaskResumeStep
import skillbill.application.workflow.decomposition.findDecomposedParentWorkflow
import skillbill.application.workflow.decomposition.persistParentDecompositionRuntime
import skillbill.application.workflow.model.RepairFeatureTaskRuntimeIdentityArgs
import skillbill.application.workflow.model.WorkflowContinueResult
import skillbill.application.workflow.model.WorkflowFamilyKind
import skillbill.application.workflow.model.WorkflowGetResult
import skillbill.application.workflow.model.WorkflowOpenResult
import skillbill.application.workflow.model.WorkflowServiceOpenFeatureTaskArgs
import skillbill.application.workflow.model.WorkflowUpdateRequest
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.application.workflow.persist.openFeatureTask
import skillbill.application.workflow.service.WorkflowService
import skillbill.application.workflow.service.workflowFamily
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION
import skillbill.contracts.workflow.payload.WorkflowWirePayloadKeys
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.slot.testExecutionPlan
import skillbill.engine.goalrunner.execution.core.testGoalRunnerStatusService
import skillbill.engine.goalrunner.execution.core.testPhaseRecorder
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerManifestStore
import skillbill.engine.goalrunner.execution.core.testWorkflowGoalRunnerOutcomeStore
import skillbill.engine.goalrunner.manifest
import skillbill.engine.goalrunner.manifest.GoalRunnerManifestStore
import skillbill.engine.goalrunner.model.GoalAttemptLedgerEntryDraft
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalProgressEventDraft
import skillbill.engine.goalrunner.model.GoalRunnerAttemptLedgerRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.model.GoalRunnerManifestState
import skillbill.engine.goalrunner.model.GoalRunnerProgressEventRecordRequest
import skillbill.engine.goalrunner.model.GoalRunnerScopedReplanOptions
import skillbill.engine.goalrunner.model.GoalRunnerStatusRequest
import skillbill.engine.goalrunner.persist.OutcomeStoreTestArtifactPorts
import skillbill.engine.goalrunner.persist.decodeWorkflowArtifactsForTest
import skillbill.engine.goalrunner.status.GoalRunnerStatusService
import skillbill.engine.goalrunner.status.completed
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.shellcontent.InvalidGoalObservabilityEventSchemaError
import skillbill.error.shellcontent.InvalidGoalProgressEventSchemaError
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.goalrunner.model.GOAL_ATTEMPT_LEDGER_LIMIT
import skillbill.goalrunner.model.GoalAttemptLedgerAction
import skillbill.goalrunner.model.GoalRunnerControlState
import skillbill.goalrunner.model.GoalRunnerTerminalStatus
import skillbill.goalrunner.model.GoalRunnerWirePayload
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequest
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestOutcome
import skillbill.goalrunner.model.GoalRunnerWorkerSubtaskRequestRejectionReason
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.goalrunner.GoalPlanningPreparationRepositoryDefaults
import skillbill.ports.goalrunner.GoalRunnerControlRepository
import skillbill.ports.goalrunner.model.GoalPlanningContractProvenance
import skillbill.ports.goalrunner.model.GoalPlanningIdentity
import skillbill.ports.goalrunner.model.GoalPlanningPreparationRecord
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GovernedGoalSubtaskDescriptor
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.runner.model.GoalRunnerOutOfBandAcceptance
import skillbill.ports.goalrunner.runner.model.GoalRunnerReviewPolicy
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.decomposition.encodeManifestWireMap
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.GoalChildWorkflowDeletionScope
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.text.sha256HexUtf8
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowDefinition
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowStepState
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateAcknowledgementView
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode.PROSE
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalobservability.GOAL_PROGRESS_HISTORY_LIMIT
import skillbill.workflow.model.goalobservability.GoalProgressEvent
import skillbill.workflow.model.goalobservability.GoalProgressEventKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset.UTC
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun WorkflowService.openTestRuntime(
  sessionId: String = "",
  currentStepId: String? = null,
): WorkflowOpenResult =
  openFeatureTask(
    WorkflowServiceOpenFeatureTaskArgs(
      executionPlan = testExecutionPlan(),
      kind = WorkflowFamilyKind.TASK_RUNTIME,
      sessionId = sessionId,
      currentStepId = currentStepId,
      issueKey = "SKILL-120",
      repositoryIdentity = "repo-root-realpath-v1:/test/repository",
      governedSpecPath = ".feature-specs/SKILL-120/spec.md",
    ),
  )

private val DECOMPOSITION_RUNTIME_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.DECOMPOSITION_RUNTIME.label()

class WorkflowServiceTest {
  @Test
  fun `open returns Ok with dbPath and snapshot`() {
    val service = newService()
    val result = service.openTestRuntime("ftr-001")
    val ok = assertIs<WorkflowOpenResult.Ok>(result)
    assertEquals("running", ok.snapshot.workflowStatus.wireValue)
    assertEquals("preplan", ok.snapshot.currentStepId)
    assertEquals("/fake/metrics.db", ok.dbPath)
  }

  @Test
  fun `runtime opens mint workflow-scoped telemetry sessions`() {
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )

    val first =
      assertIs<WorkflowOpenResult.Ok>(
        service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = "SKILL-120",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-120/spec.md",
          ),
        ),
      )
    val second =
      assertIs<WorkflowOpenResult.Ok>(
        service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = "SKILL-120",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-120/spec.md",
          ),
        ),
      )

    val firstSession = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(first.workflowId, RUNTIME)).sessionId
    val secondSession = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(second.workflowId, RUNTIME)).sessionId
    assertEquals("ftr-${first.workflowId}", firstSession)
    assertEquals("ftr-${second.workflowId}", secondSession)
    assertTrue(firstSession != secondSession)
  }

  @Test
  fun `runtime abandonment is explicit durable and terminal`() {
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )
    val opened =
      assertIs<WorkflowOpenResult.Ok>(
        service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = "SKILL-120",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-120/spec.md",
          ),
        ),
      )

    val abandoned =
      assertIs<WorkflowUpdateResult.Ok>(
        service.abandonFeatureTaskRuntime(opened.workflowId, "Superseded after a deterministic policy block."),
      )

    assertEquals("abandoned", abandoned.acknowledgement.workflowStatus.wireValue)
    assertEquals(listOf("operator_abandonment"), abandoned.acknowledgement.updatedArtifactKeys)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(opened.workflowId, RUNTIME)).toSnapshot()
    assertEquals("abandoned", saved.workflowStatus.wireValue)
    assertContains(JsonCodec.mapToJsonString(saved.artifacts.toMap()), "Superseded after a deterministic policy block.")
    val repeated =
      assertIs<WorkflowUpdateResult.Error>(
        service.abandonFeatureTaskRuntime(opened.workflowId, "Try again."),
      )
    assertContains(repeated.error, "already terminal")
  }

  @Test
  fun `abandon terminalizes a legacy prose-mode goal parent without flipping mode`() {
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )
    val historyArtifact = """{"plan":{"mode":"decompose"},"history_note":"retain-me"}"""
    workflows.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = "wfl-legacy-prose-parent",
        sessionId = "fis-legacy-prose-parent",
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = WorkflowStatus.PAUSED.wireValue,
        currentStepId = "assess",
        stepsJson = """[{"step_id":"assess","status":"completed"}]""",
        artifactsJson = historyArtifact,
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = PROSE,
        implementationSkill = "bill-feature-task-prose",
        issueKey = "SKILL-179",
      ),
      PROSE,
    )

    val abandoned =
      assertIs<WorkflowUpdateResult.Ok>(
        service.abandonFeatureTaskRuntime("wfl-legacy-prose-parent", "Retire legacy prose goal parent."),
      )

    assertEquals("abandoned", abandoned.acknowledgement.workflowStatus.wireValue)
    assertEquals(listOf("operator_abandonment"), abandoned.acknowledgement.updatedArtifactKeys)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-legacy-prose-parent"))
    assertEquals("abandoned", saved.workflowStatus)
    assertEquals(PROSE, saved.mode)
    assertContains(saved.artifactsJson, "Retire legacy prose goal parent.")
    assertContains(saved.artifactsJson, "retain-me")
    val repeated =
      assertIs<WorkflowUpdateResult.Error>(
        service.abandonFeatureTaskRuntime("wfl-legacy-prose-parent", "Again."),
      )
    assertContains(repeated.error, "already terminal")
  }

  @Test
  fun `explicit operator abandonment stays terminal and never resolves to the paused status`() {
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )
    val opened =
      assertIs<WorkflowOpenResult.Ok>(
        service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = "SKILL-141",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-141/spec.md",
          ),
        ),
      )

    val abandoned =
      assertIs<WorkflowUpdateResult.Ok>(
        service.abandonFeatureTaskRuntime(opened.workflowId, "Operator abandoned the goal."),
      )

    assertEquals("abandoned", abandoned.acknowledgement.workflowStatus.wireValue)
    assertEquals(listOf("operator_abandonment"), abandoned.acknowledgement.updatedArtifactKeys)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(opened.workflowId, RUNTIME)).toSnapshot()
    assertContains(JsonCodec.mapToJsonString(saved.artifacts.toMap()), "Operator abandoned the goal.")
    assertTrue(saved.workflowStatus.wireValue in FeatureTaskRuntimePhaseWorkflowDefinition.definition.terminalStatuses)
    assertTrue(WorkflowStatus.PAUSED.wireValue in FeatureTaskRuntimePhaseWorkflowDefinition.definition.workflowStatuses)
    assertIs<WorkflowUpdateResult.Error>(service.abandonFeatureTaskRuntime(opened.workflowId, "Again."))
  }

  @Test
  fun `runtime identity repair requires matching explicit operator inputs`() {
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )
    val opened =
      assertIs<WorkflowOpenResult.Ok>(
        service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = "SKILL-120",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-120/spec.md",
          ),
        ),
      )

    val mismatch =
      assertIs<WorkflowUpdateResult.Error>(
        service.repairFeatureTaskRuntimeIdentity(
          RepairFeatureTaskRuntimeIdentityArgs(
            workflowId = opened.workflowId,
            issueKey = "SKILL-999",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-120/spec.md",
            reason = "Repair a legacy identity.",
          ),
        ),
      )
    assertContains(mismatch.error, "belongs to issue 'SKILL-120'")

    val repaired =
      assertIs<WorkflowUpdateResult.Ok>(
        service.repairFeatureTaskRuntimeIdentity(
          RepairFeatureTaskRuntimeIdentityArgs(
            workflowId = opened.workflowId,
            issueKey = "SKILL-120",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-120/spec.md",
            reason = "Repair a legacy identity.",
          ),
        ),
      )
    assertEquals(listOf("operator_identity_repair"), repaired.acknowledgement.updatedArtifactKeys)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflowAsMode(opened.workflowId, RUNTIME)).toSnapshot()
    assertContains(JsonCodec.mapToJsonString(saved.artifacts.toMap()), "Repair a legacy identity.")
  }

  @Test
  fun `runtime identity repair rejects terminal workflows`() {
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )
    val opened =
      assertIs<WorkflowOpenResult.Ok>(
        service.openFeatureTask(
          WorkflowServiceOpenFeatureTaskArgs(
            executionPlan = testExecutionPlan(),
            kind = WorkflowFamilyKind.TASK_RUNTIME,
            issueKey = "SKILL-120",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-120/spec.md",
          ),
        ),
      )
    assertIs<WorkflowUpdateResult.Ok>(
      service.abandonFeatureTaskRuntime(opened.workflowId, "Terminalize this workflow."),
    )

    val result =
      assertIs<WorkflowUpdateResult.Error>(
        service.repairFeatureTaskRuntimeIdentity(
          RepairFeatureTaskRuntimeIdentityArgs(
            workflowId = opened.workflowId,
            issueKey = "SKILL-120",
            repositoryIdentity = "repo-root-realpath-v1:/test/repository",
            governedSpecPath = ".feature-specs/SKILL-120/spec.md",
            reason = "Repair a legacy identity.",
          ),
        ),
      )

    assertContains(result.error, "already terminal")
  }

  @Test
  fun `open returns Error for invalid step id`() {
    val service = newService()
    val result = service.openTestRuntime(currentStepId = "not-a-step")
    val error = assertIs<WorkflowOpenResult.Error>(result)
    assertTrue(error.error.contains("Invalid current_step_id"))
  }

  @Test
  fun `update validation error does not carry a dbPath wire field`() {
    val service = newService()

    val result =
      service.update(
        WorkflowFamilyKind.TASK_RUNTIME,
        WorkflowUpdateRequest(
          workflowId = "irrelevant",
          workflowStatus = "not-a-status",
          currentStepId = "preplan",
          sessionId = "",
        ),
      )
    val error = assertIs<WorkflowUpdateResult.Error>(result)
    assertTrue(error.error.contains("Invalid workflow_status"))
    assertNull(
      error.dbPath,
      "Validation-time WorkflowUpdateResult.Error must not carry a dbPath; " +
        "the wire envelope predates the typed result and omits db_path on this path.",
    )
  }

  @Test
  fun `update unknown-workflow error does not carry a dbPath wire field`() {
    val service = newService()

    val result =
      service.update(
        WorkflowFamilyKind.TASK_RUNTIME,
        WorkflowUpdateRequest(
          workflowId = "missing",
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "preplan",
          sessionId = "",
        ),
      )
    val error = assertIs<WorkflowUpdateResult.Error>(result)
    assertEquals("Unknown workflow_id 'missing'.", error.error)
    assertNull(
      error.dbPath,
      "Unknown-workflow WorkflowUpdateResult.Error must not carry a dbPath; " +
        "the legacy wire envelope omitted db_path on this path.",
    )
  }

  @Test
  fun `list returns the opened workflow ids in expected order`() {
    val service = newService()
    val first =
      assertIs<WorkflowOpenResult.Ok>(
        service.openTestRuntime("ftr-001"),
      )
    val second =
      assertIs<WorkflowOpenResult.Ok>(
        service.openTestRuntime("ftr-002"),
      )
    val result = service.list(WorkflowFamilyKind.TASK_RUNTIME)
    assertEquals(2, result.workflowCount)
    assertEquals(2, result.workflows.size)
    assertEquals(
      setOf(first.workflowId, second.workflowId),
      result.workflows.map { it.workflowId }.toSet(),
    )
  }

  @Test
  fun `get returns Ok for known workflow`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.openTestRuntime("ftr-001"))
    val got = service.get(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId)
    val ok = assertIs<WorkflowGetResult.Ok>(got)
    assertEquals(opened.workflowId, ok.workflowId)
  }

  @Test
  fun `TASK_RUNTIME resolves to the persisted TASK_RUNTIME family`() {
    assertEquals(WorkflowFamily.TASK_RUNTIME, WorkflowFamilyKind.TASK_RUNTIME.workflowFamily())

    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.openTestRuntime("ftr-001"))
    assertTrue(
      opened.workflowId.startsWith(WorkflowFamily.TASK_RUNTIME.definition.workflowIdPrefix),
      "Workflow opened under TASK_RUNTIME must persist under the TASK_RUNTIME id prefix; got ${opened.workflowId}.",
    )
    val got = assertIs<WorkflowGetResult.Ok>(service.get(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId))
    assertEquals(opened.workflowId, got.workflowId)
  }

  @Test
  fun `continueWorkflow on a blocked runtime row with a missing required phase record stays blocked`() {
    val service = newService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.openTestRuntime("ftr-001"))
    service.update(
      WorkflowFamilyKind.TASK_RUNTIME,
      WorkflowUpdateRequest(
        workflowId = opened.workflowId,
        workflowStatus = WorkflowStatus.BLOCKED.wireValue,
        currentStepId = "implement",
        stepUpdates =
          WorkflowStepUpdates.from(
            listOf(
              mapOf("step_id" to "implement", "status" to "blocked", "attempt_count" to 1),
            ),
          ),
        artifactsPatch = WorkflowArtifactPatch.from(mapOf("preplan_digest" to mapOf("ok" to true))),
      ),
    )
    val standard =
      assertIs<WorkflowContinueResult.Standard>(
        service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
      )
    assertEquals("blocked", standard.view.continueStatus.wireValue)
    assertEquals(listOf("plan"), standard.view.resume.missingArtifacts)
    assertFalse(standard.view.resume.canResume)
  }

  @Test
  fun `InvalidWorkflowStateSchemaError loud-fails through WorkflowService get and continue before projection`() {
    val workflows = InMemoryWorkflowStates()
    val record =
      testWorkflowEngine.openRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        "wftr-loud",
        "ftr-001",
        "preplan",
      ).toRecord()
    workflows.saveFeatureTaskWorkflow(record, RUNTIME)
    val loudFailValidator =
      object : WorkflowSnapshotValidator {
        override fun validate(
          snapshot: WorkflowStateSnapshot,
          slug: String,
        ): Unit =
          throw InvalidWorkflowStateSchemaError("Workflow '$slug': snapshot fails schema validation at '<root>'.")
      }
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = loudFailValidator,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      service.get(WorkflowFamilyKind.TASK_RUNTIME, "wftr-loud")
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, "wftr-loud")
    }
  }

  @Test
  fun `InvalidWorkflowStateSchemaError loud-fails through WorkflowService update before acknowledgement`() {
    val workflows = InMemoryWorkflowStates()
    val opened =
      testWorkflowEngine.openRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        "wftr-update-loud",
        "ftr-001",
        "preplan",
      ).toRecord()
    workflows.saveFeatureTaskWorkflow(opened, RUNTIME)
    val loudFailValidator =
      object : WorkflowSnapshotValidator {
        override fun validate(
          snapshot: WorkflowStateSnapshot,
          slug: String,
        ): Unit =
          throw InvalidWorkflowStateSchemaError("Workflow '$slug': snapshot fails schema validation at '<root>'.")
      }
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = loudFailValidator,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )

    val result =
      assertIs<WorkflowUpdateResult.Error>(
        service.update(
          WorkflowFamilyKind.TASK_RUNTIME,
          WorkflowUpdateRequest(
            workflowId = "wftr-update-loud",
            workflowStatus = WorkflowStatus.RUNNING.wireValue,
            currentStepId = "preplan",
            stepUpdates =
              WorkflowStepUpdates.from(
                listOf(
                  mapOf("step_id" to "preplan", "status" to "running", "attempt_count" to 1),
                ),
              ),
            artifactsPatch =
              WorkflowArtifactPatch.from(
                mapOf("assessment" to mapOf("ok" to true), "branch" to mapOf("ok" to true)),
              ),
            sessionId = "",
          ),
        ),
      )
    assertContains(result.error, "snapshot fails schema validation")
    assertEquals(opened, workflows.getFeatureTaskWorkflowAsMode(opened.workflowId, RUNTIME))
  }

  @Test
  fun `progress event update persists goal observability latest and bounded history artifacts`() {
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = testFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )
    val opened = assertIs<WorkflowOpenResult.Ok>(service.openTestRuntime("ftr-001"))
    assertNull(opened.goalObservability)

    val updated =
      service.update(
        WorkflowFamilyKind.TASK_RUNTIME,
        WorkflowUpdateRequest(
          workflowId = opened.workflowId,
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "goal_continuation" to
                  mapOf(
                    "issue_key" to "SKILL-61",
                    "subtask_id" to 1,
                    "suppress_pr" to true,
                    "goal_branch" to "feat/SKILL-61",
                    "code_review_mode" to "auto",
                  ),
                "progress_event" to
                  mapOf(
                    "step_id" to "implement",
                    "attempt_count" to 1,
                    "source" to "phase_subagent",
                    "kind" to "durable_progress",
                    "message" to "editing runtime files",
                    "sequence" to 7,
                    "timestamp" to "2026-06-01T00:00:00Z",
                  ),
              ),
            ),
          sessionId = "ftr-001",
        ),
      )

    val ok = assertIs<WorkflowUpdateResult.Ok>(updated)
    assertProgressEventAcknowledgement(ok)
    val persisted =
      assertIs<WorkflowGetResult.Ok>(
        service.get(WorkflowFamilyKind.TASK_RUNTIME, opened.workflowId),
      )
    assertPersistedProgressEventArtifacts(persisted, opened.workflowId)
    val decoded = assertNotNull(persisted.goalObservability)
    assertEquals("implement", decoded.workflowPhase)
    assertEquals(0, decoded.sequenceNumber)
    assertEquals(opened.workflowId, decoded.workflowId)
  }

  @Test
  fun `corrupt artifacts fail on resume status and goal progress without rewriting the row`() {
    val workflows = InMemoryWorkflowStates()
    val row =
      testWorkflowEngine.openRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        "wftr-corrupt",
        "session",
        "preplan",
      ).toRecord().copy(artifactsJson = "{")
    workflows.saveFeatureTaskWorkflow(row, RUNTIME)
    val database = FakeDatabaseSessionFactory(workflows)
    val service = newService(workflows)
    val progress = testWorkflowGoalRunnerOutcomeStore(database, testWorkflowSnapshotValidator)

    assertFailsWith<InvalidWorkflowStateSchemaError> { service.resume(WorkflowFamilyKind.TASK_RUNTIME, row.workflowId) }
    assertFailsWith<InvalidWorkflowStateSchemaError> { service.get(WorkflowFamilyKind.TASK_RUNTIME, row.workflowId) }
    assertFailsWith<InvalidWorkflowStateSchemaError> {
      service.continueWorkflow(WorkflowFamilyKind.TASK_RUNTIME, row.workflowId)
    }
    assertFailsWith<InvalidWorkflowStateSchemaError> { progress.progress(row.workflowId) }
    assertEquals(row, workflows.getFeatureTaskWorkflowAsMode(row.workflowId, RUNTIME))
  }

  private fun newService(workflows: InMemoryWorkflowStates = InMemoryWorkflowStates()): WorkflowService {
    return WorkflowService(
      database = FakeDatabaseSessionFactory(workflows),
      gitOperations = NoopWorkflowGitOperations,
      decompositionManifestStore = UnavailableDecompositionManifestStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
      decompositionManifestWriter = testDecompositionManifestWriter,
      repositoryRoot = testRepositoryRoot,
      goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
      runtimeDiagnostics = NoopRuntimeDiagnostics,
      clock = Clock.systemUTC(),
    )
  }
}

private fun encodeDecompositionManifestYaml(
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
  sourceLabel: String = "<in-memory>",
): String =
  skillbill.application.decomposition.encodeValidatedDecompositionManifestYaml(
    manifest,
    validator,
    fileStore,
    sourceLabel,
  ).yamlText

class WorkflowServiceDecomposedParentTest {
  @Test
  fun `decomposed parent lookup ignores child workflows that only carry runtime projection`() {
    val workflows = InMemoryWorkflowStates()
    val childRuntime = decompositionRuntime(status = "blocked")
    val parentRuntime = decompositionRuntime(status = "in_progress")
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(childRuntime),
            ),
          ),
      ),
      PROSE,
    )
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(parentRuntime),
            ),
          ),
      ),
      RUNTIME,
    )

    val selected = workflows.findDecomposedParentWorkflow("SKILL-52.1")

    assertEquals("wfl-parent", selected?.workflowId)
  }

  @Test
  fun `decomposed parent lookup ignores goal-continuation child workflows even when child plan is decompose`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              "goal_continuation" to
                mapOf(
                  "enabled" to true,
                  "issue_key" to "SKILL-52.1",
                  "subtask_id" to 1,
                  "suppress_pr" to true,
                  "goal_branch" to "feat/SKILL-52",
                  "code_review_mode" to "auto",
                ),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(decompositionRuntime(status = "in_progress")),
            ),
          ),
      ),
      PROSE,
    )
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(decompositionRuntime(status = "in_progress")),
            ),
          ),
      ),
      RUNTIME,
    )

    val selected = workflows.findDecomposedParentWorkflow("SKILL-52.1")

    assertEquals("wfl-parent", selected?.workflowId)
  }

  @Test
  fun `decomposed parent lookup prefers active runtime over completed lineage for same issue key`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-completed-discovery",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(decompositionRuntime(status = "complete")),
            ),
          ),
      ),
      RUNTIME,
    )
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-active-implementation",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(decompositionRuntime(status = "blocked")),
            ),
          ),
      ),
      RUNTIME,
    )

    val selected = workflows.findDecomposedParentWorkflow("SKILL-52.1")

    assertEquals("wfl-active-implementation", selected?.workflowId)
  }

  @Test
  fun `decomposed parent lookup drops an abandoned row whose subtask lineage predates a manifest edit`() {
    val workflows = InMemoryWorkflowStates()
    val staleLineage =
      decompositionRuntime(status = "pending").copy(
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 7,
              name = "Compatibility telemetry and end-to-end hardening",
              specPath =
                ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_7_compatibility-telemetry.md",
              status = "pending",
            ),
          ),
      )
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-abandoned-stale",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(staleLineage),
            ),
          ),
        workflowStatus = WorkflowStatus.ABANDONED,
      ),
      RUNTIME,
    )
    val currentManifest =
      staleLineage.copy(
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 7,
              name = "Delegated review launch projections",
              specPath = ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_7_delegated-review.md",
              status = "pending",
            ),
            DecompositionSubtask(
              id = 8,
              name = "Compatibility telemetry and end-to-end hardening",
              specPath =
                ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_8_compatibility-telemetry.md",
              status = "pending",
            ),
          ),
      )

    val withoutComparison = workflows.findDecomposedParentWorkflow("SKILL-52.1")
    assertEquals("wfl-abandoned-stale", withoutComparison?.workflowId)

    val withComparison =
      workflows.findDecomposedParentWorkflow(
        "SKILL-52.1",
        currentManifest,
      )
    assertEquals(null, withComparison)
  }

  @Test
  fun `decomposed parent lookup keeps an abandoned row with real subtask progress even if lineage diverges`() {
    val workflows = InMemoryWorkflowStates()
    val progressedLineage =
      decompositionRuntime(status = "pending").copy(
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 7,
              name = "Compatibility telemetry and end-to-end hardening",
              specPath =
                ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_7_compatibility-telemetry.md",
              status = "in_progress",
              commitSha = "sha-partial",
            ),
          ),
      )
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-abandoned-progressed",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(progressedLineage),
            ),
          ),
        workflowStatus = WorkflowStatus.ABANDONED,
      ),
      RUNTIME,
    )
    val currentManifest =
      progressedLineage.copy(
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 7,
              name = "Delegated review launch projections",
              specPath = ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_7_delegated-review.md",
              status = "pending",
            ),
          ),
      )

    val selected =
      workflows.findDecomposedParentWorkflow(
        "SKILL-52.1",
        currentManifest,
      )

    assertEquals("wfl-abandoned-progressed", selected?.workflowId)
  }

  @Test
  fun `decomposed parent lookup reuses a paused row whose subtask lineage predates a manifest edit`() {
    val workflows = InMemoryWorkflowStates()
    val pausedLineage =
      decompositionRuntime(status = "pending").copy(
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 7,
              name = "Compatibility telemetry and end-to-end hardening",
              specPath =
                ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_7_compatibility-telemetry.md",
              status = "pending",
            ),
          ),
      )
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-paused-parent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(pausedLineage),
            ),
          ),
        workflowStatus = WorkflowStatus.PAUSED,
      ),
      RUNTIME,
    )
    val currentManifest =
      pausedLineage.copy(
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 7,
              name = "Delegated review launch projections",
              specPath = ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_7_delegated-review.md",
              status = "pending",
            ),
          ),
      )

    val selected =
      workflows.findDecomposedParentWorkflow(
        "SKILL-52.1",
        currentManifest,
      )

    assertEquals("wfl-paused-parent", selected?.workflowId)
  }

  @Test
  fun `decomposed parent lookup rejects multiple active runtimes for same issue key`() {
    val workflows = InMemoryWorkflowStates()
    listOf("wfl-active-a", "wfl-active-b").forEach { workflowId ->
      workflows.saveFeatureTaskWorkflow(
        workflowRecord(
          workflowId = workflowId,
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "plan" to mapOf("mode" to "decompose"),
                DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                  testDecompositionManifestValidator.encodeManifestWireMap(decompositionRuntime(status = "blocked")),
              ),
            ),
        ),
        RUNTIME,
      )
    }

    val error =
      assertFailsWith<IllegalStateException> {
        workflows.findDecomposedParentWorkflow("SKILL-52.1")
      }

    assertEquals(
      "Ambiguous decomposed parent workflows for 'SKILL-52.1': wfl-active-a, wfl-active-b. " +
        "Pass an explicit workflow or manifest selector before continuing.",
      error.message,
    )
  }
}

class WorkflowServiceGoalManifestStoreTest {
  @Test
  fun `goal manifest store imports active checked-in projection over completed lineage`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-import")
    val completedPath = repoRoot.resolve(".feature-specs/SKILL-52.1-a-discovery/decomposition-manifest.yaml")
    val activePath = repoRoot.resolve(".feature-specs/SKILL-52.1-z-implementation/decomposition-manifest.yaml")
    val unrelatedInvalidPath = repoRoot.resolve(".feature-specs/SKILL-55-launch-readiness/decomposition-manifest.yaml")
    Files.createDirectories(completedPath.parent)
    Files.createDirectories(activePath.parent)
    Files.createDirectories(unrelatedInvalidPath.parent)
    Files.writeString(
      completedPath,
      encodeDecompositionManifestYaml(
        decompositionRuntime(status = "complete"),
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )
    Files.writeString(
      activePath,
      encodeDecompositionManifestYaml(
        decompositionRuntime(status = "blocked"),
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )
    Files.writeString(
      unrelatedInvalidPath,
      """
      ---
      contract_version: "0.5"
      issue_key: "SKILL-55"
      current_subtask_intent:
        subtask_id: 1
        action: "complete"
      ---
      """.trimIndent(),
    )
    val store =
      testWorkflowGoalRunnerManifestStore(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        decompositionManifestStore = TestDecompositionManifestStore,
        clock = Clock.systemUTC(),
      )

    val state = store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot)

    assertEquals("blocked", state?.manifest?.status)
    assertEquals("wfl-child", state?.manifest?.subtasks?.single()?.workflowId)
  }

  @Test
  fun `an archived manifest on a superseded contract does not fail another goal's read`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-archived-legacy")
    val activePath = repoRoot.resolve(".feature-specs/SKILL-8-implementation/decomposition-manifest.yaml")
    val archivedPath = repoRoot.resolve(".feature-specs/done/SKILL-80-telemetry/decomposition-manifest.yaml")
    Files.createDirectories(activePath.parent)
    Files.createDirectories(archivedPath.parent)
    Files.writeString(
      activePath,
      encodeDecompositionManifestYaml(
        decompositionRuntime(status = "blocked").copy(issueKey = "SKILL-8"),
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )
    Files.writeString(archivedPath, LEGACY_CONTRACT_MANIFEST_YAML)
    val store = manifestStore(rejecting = setOf(archivedPath.toString()))

    val state = store.loadByIssueKey("SKILL-8", repoRoot = repoRoot)

    assertEquals("blocked", state?.manifest?.status)
  }

  @Test
  fun `a schema-invalid manifest claiming this goal's issue key still loud-fails`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-in-scope-invalid")
    val brokenPath = repoRoot.resolve(".feature-specs/SKILL-8-implementation/decomposition-manifest.yaml")
    Files.createDirectories(brokenPath.parent)
    Files.writeString(brokenPath, LEGACY_CONTRACT_MANIFEST_YAML.replace("SKILL-80", "SKILL-8"))
    val store = manifestStore(rejecting = setOf(brokenPath.toString()))

    assertFailsWith<InvalidDecompositionManifestSchemaError> {
      store.loadByIssueKey("SKILL-8", repoRoot = repoRoot)
    }
  }

  @Test
  fun `goal manifest store imports a paused parent and resume reuses its id and planning identity`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-paused-import")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-52.1-implementation/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(
        decompositionRuntime(status = "blocked"),
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )
    val workflows = InMemoryWorkflowStates()
    val store =
      testWorkflowGoalRunnerManifestStore(
        database = FakeDatabaseSessionFactory(workflows),
        decompositionManifestStore = TestDecompositionManifestStore,
        clock = Clock.systemUTC(),
      )

    val imported = assertNotNull(store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot))
    val resumed = assertNotNull(store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot))

    val parentRow = assertNotNull(workflows.getFeatureTaskWorkflow(imported.parentWorkflowId))
    assertEquals("paused", parentRow.workflowStatus)
    assertEquals(imported.parentWorkflowId, resumed.parentWorkflowId)
    assertEquals(
      1,
      workflows.listFeatureTaskWorkflows(RUNTIME, Int.MAX_VALUE).size,
      "Resume must reuse the persisted parent row rather than minting a second one.",
    )
    assertEquals(
      GoalPlanningIdentity(imported.parentWorkflowId, "SKILL-52.1", "repo-root-realpath-v1:/test/repository"),
      GoalPlanningIdentity(resumed.parentWorkflowId, "SKILL-52.1", "repo-root-realpath-v1:/test/repository"),
    )
    val importedArtifacts =
      JsonCodec.parseObjectOrNull(parentRow.artifactsJson)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
    assertEquals(setOf(DECOMPOSITION_RUNTIME_ARTIFACT_KEY), importedArtifacts?.keys)
  }

  @Test
  fun `a goal parent bound to an earlier checkout rebinds to the invoking repository`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-rebind")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-52.1-implementation/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(
        decompositionRuntime(status = "blocked"),
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )
    val store =
      testWorkflowGoalRunnerManifestStore(
        database =
          FakeDatabaseSessionFactory(
            workflowStates = InMemoryWorkflowStates(),
            goalRunnerControls = RecordingGoalRunnerControlRepository(),
          ),
        decompositionManifestStore = TestDecompositionManifestStore,
        clock = Clock.systemUTC(),
      )
    val parentWorkflowId = assertNotNull(store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot)).parentWorkflowId
    store.bindRepositoryIdentity(parentWorkflowId, "repo-root-realpath-v1:/checkouts/first")

    val rebound = store.bindRepositoryIdentity(parentWorkflowId, "repo-root-realpath-v1:/checkouts/second")

    assertEquals("repo-root-realpath-v1:/checkouts/second", rebound.repositoryIdentity)
    assertEquals(
      "repo-root-realpath-v1:/checkouts/second",
      store.controlState(parentWorkflowId).repositoryIdentity,
    )
  }

  @Test
  fun `goal manifest store refreshes stale db projection from complete checked-in projection`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-refresh-complete")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-52.1-implementation/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(
        completeDecompositionRuntime(),
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(decompositionRuntime(status = "blocked")),
            ),
          ),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerManifestStore(
        database = FakeDatabaseSessionFactory(workflows),
        decompositionManifestStore = TestDecompositionManifestStore,
        clock = Clock.systemUTC(),
      )

    val refreshed = store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot)
    val persisted = store.loadByIssueKey("SKILL-52.1", repoRoot = null)

    assertEquals("complete", refreshed?.manifest?.status)
    assertEquals(0, refreshed?.manifest?.currentSubtaskIntent?.subtaskId)
    assertEquals("complete", refreshed?.manifest?.currentSubtaskIntent?.action)
    assertEquals("complete", refreshed?.manifest?.subtasks?.single()?.status)
    assertEquals("sha-complete", refreshed?.manifest?.subtasks?.single()?.commitSha)
    assertEquals("complete", persisted?.manifest?.status)
    assertEquals(0, persisted?.manifest?.currentSubtaskIntent?.subtaskId)
  }

  @Test
  fun `save writes the manifest projection under the loaded repoRoot, never the process cwd`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-save-repo-root")
    val manifestPath = repoRoot.resolve(".feature-specs/SKILL-52.1-implementation/decomposition-manifest.yaml")
    Files.createDirectories(manifestPath.parent)
    Files.writeString(
      manifestPath,
      encodeDecompositionManifestYaml(
        decompositionRuntime(status = "blocked"),
        testDecompositionManifestValidator,
        TestDecompositionManifestStore,
      ),
    )
    val store =
      testWorkflowGoalRunnerManifestStore(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        decompositionManifestStore = TestDecompositionManifestStore,
        clock = Clock.systemUTC(),
      )
    val loaded = assertNotNull(store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot))
    assertEquals(repoRoot, loaded.repoRoot)

    val expectedWritePath =
      repoRoot.resolve(
        ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/install-policy/decomposition-manifest.yaml",
      )
    Files.deleteIfExists(manifestPath)
    check(Files.notExists(expectedWritePath)) { "Fixture must not pre-create the writer's derived path." }

    store.save(loaded.copy(manifest = loaded.manifest.copy(status = "in_progress")))

    assertTrue(
      Files.exists(expectedWritePath),
      "save() must write the manifest projection under the bound repoRoot, not under an unrelated directory.",
    )
    val processCwdEquivalent =
      Path.of("").toAbsolutePath().resolve(
        ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/install-policy/decomposition-manifest.yaml",
      )
    assertFalse(
      Files.exists(processCwdEquivalent),
      "save() must never write the manifest projection relative to the process working directory.",
    )
  }

  @Test
  fun `scoped replan deletes the replanned subtask's hydrated child and preserves completed siblings`() {
    val workflows = RecordingGoalChildDeletionWorkflowStates(childStatus = "paused")
    val before =
      decompositionRuntime(status = "in_progress").copy(
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 2, action = "start"),
        subtasks =
          listOf(
            decompositionRuntime(status = "complete").subtasks.single().copy(
              id = 1,
              status = "complete",
              commitSha = "sha-done",
              workflowId = "wfl-child-1",
              lastResumableStep = "commit_push",
            ),
            decompositionRuntime(status = "blocked").subtasks.single().copy(
              id = 2,
              status = "blocked",
              workflowId = "wfl-child-2",
              blockedReason = "planning import conflicts",
              lastResumableStep = "audit",
            ),
          ),
      )
    val result =
      scopedReplanStore(workflows, before).saveScopedReplan(
        state = scopedReplanState(before, "skillbill-scoped-replan-child"),
        subtaskId = 2,
        options = GoalRunnerScopedReplanOptions(),
      )

    assertEquals(listOf(2), result.clearedChildSubtaskIds)
    assertEquals(listOf(Triple("wfl-parent", 2, "wfl-child-2")), workflows.scopedDeletions)
    val replanned = result.state.manifest.subtasks.single { it.id == 2 }
    assertEquals("pending", replanned.status)
    assertNull(replanned.workflowId, "A stale hydrated child must not survive the replan.")
    assertNull(replanned.blockedReason)
    assertNull(replanned.lastResumableStep)
    val completed = result.state.manifest.subtasks.single { it.id == 1 }
    assertEquals("complete", completed.status)
    assertEquals("sha-done", completed.commitSha, "Scoped replan must preserve completed commit mappings.")
    assertEquals("wfl-child-1", completed.workflowId)
    assertEquals(
      CurrentSubtaskIntent(subtaskId = 2, action = "start"),
      result.state.manifest.currentSubtaskIntent,
      "Child deletion must not retarget the intent the replan already set.",
    )
  }

  @Test
  fun `scoped replan keeps a live child that scoped deletion refuses to remove`() {
    val workflows = RecordingGoalChildDeletionWorkflowStates(childStatus = "running")
    val before =
      decompositionRuntime(status = "in_progress").copy(
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
        subtasks =
          listOf(
            decompositionRuntime(status = "in_progress").subtasks.single().copy(
              id = 1,
              status = "in_progress",
              workflowId = "wfl-child-1",
              lastResumableStep = "implement",
            ),
          ),
      )
    val result =
      scopedReplanStore(workflows, before).saveScopedReplan(
        state = scopedReplanState(before, "skillbill-scoped-replan-live-child"),
        subtaskId = 1,
        options = GoalRunnerScopedReplanOptions(),
      )

    assertEquals(emptyList(), result.clearedChildSubtaskIds)
    assertEquals("wfl-child-1", result.state.manifest.subtasks.single().workflowId)
    assertEquals("implement", result.state.manifest.subtasks.single().lastResumableStep)
  }

  @Test
  fun `goal completion boundary persists terminal child state into the parent workflow`() {
    val workflows = InMemoryWorkflowStates()
    val pending =
      decompositionRuntime(status = "in_progress").copy(
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
        subtasks = decompositionRuntime(status = "in_progress").subtasks.map { it.copy(status = "pending") },
      )
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(pending),
            ),
          ),
      ),
      RUNTIME,
    )
    val store =
      testWorkflowGoalRunnerManifestStore(
        database = FakeDatabaseSessionFactory(workflows),
        decompositionManifestStore = TestDecompositionManifestStore,
        clock = Clock.systemUTC(),
      )
    val completed =
      pending.copy(
        status = "complete",
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
        subtasks =
          pending.subtasks.map {
            it.copy(status = "complete", workflowId = "wfl-child", commitSha = "sha-child")
          },
      )

    val result =
      store.saveCompletedSubtaskAtBoundary(
        GoalRunnerManifestState("wfl-parent", "/fake/metrics.db", completed),
        subtaskId = 1,
      )

    assertEquals("complete", result.state.manifest.status)
    val persisted = workflows.getFeatureTaskWorkflowAsMode("wfl-parent", RUNTIME)
    val persistedManifest =
      requireNotNull(persisted).toSnapshot()
        .decompositionRuntime()
    assertEquals("complete", persistedManifest?.status)
    assertEquals("complete", persistedManifest?.subtasks?.single()?.status)
    assertEquals("sha-child", persistedManifest?.subtasks?.single()?.commitSha)
  }

  @Test
  fun `goal manifest store rejects multiple active checked-in projections`() {
    val repoRoot = Files.createTempDirectory("skillbill-goal-manifest-ambiguous")
    val firstPath = repoRoot.resolve(".feature-specs/SKILL-52.1-a-implementation/decomposition-manifest.yaml")
    val secondPath = repoRoot.resolve(".feature-specs/SKILL-52.1-b-implementation/decomposition-manifest.yaml")
    Files.createDirectories(firstPath.parent)
    Files.createDirectories(secondPath.parent)
    listOf(firstPath, secondPath).forEach { path ->
      Files.writeString(
        path,
        encodeDecompositionManifestYaml(
          decompositionRuntime(status = "blocked"),
          testDecompositionManifestValidator,
          TestDecompositionManifestStore,
        ),
      )
    }
    val store =
      testWorkflowGoalRunnerManifestStore(
        database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
        decompositionManifestStore = TestDecompositionManifestStore,
        clock = Clock.systemUTC(),
      )

    val error =
      assertFailsWith<InvalidDecompositionManifestSchemaError> {
        store.loadByIssueKey("SKILL-52.1", repoRoot = repoRoot)
      }

    assertContains(
      error.message.orEmpty(),
      "multiple active decomposition manifests match the requested issue key",
    )
    assertContains(error.message.orEmpty(), ".feature-specs/SKILL-52.1-a-implementation/decomposition-manifest.yaml")
    assertContains(error.message.orEmpty(), ".feature-specs/SKILL-52.1-b-implementation/decomposition-manifest.yaml")
  }
}

class WorkflowGoalStatusProjectionTest {
  @Test
  fun `goal status omits stale active observability after terminal projection wins`() {
    val workflows = InMemoryWorkflowStates()
    saveCompleteGoalParent(workflows)
    saveStaleRunningChildWithObservability(workflows)
    saveAuthoritativeCompleteChild(workflows)

    val projection =
      newGoalStatusService(workflows).status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-52.1",
          invokedAgentId = "codex",
          repoRoot = Path.of("").toAbsolutePath(),
        ),
      )

    requireNotNull(projection)
    assertEquals(1, projection.completeCount)
    assertEquals(0, projection.pendingCount)
    assertEquals(0, projection.blockedCount)
    assertNull(projection.currentSubtaskId)
    assertNull(projection.latestObservabilityEvent)
    assertNull(projection.latestLivenessSignal)
  }

  @Test
  fun `goal status reports runner_interrupted pause for a legacy prose-mode parent`() {
    val workflows = InMemoryWorkflowStates()
    val controls = RecordingGoalRunnerControlRepository()
    val manifest = decompositionRuntime(status = "in_progress")
    workflows.saveFeatureTaskWorkflow(
      WorkflowStateRecord(
        workflowId = "wfl-prose-parent",
        sessionId = "fis-prose-parent",
        workflowName = "bill-feature-task",
        contractVersion = "0.1",
        workflowStatus = WorkflowStatus.PAUSED.wireValue,
        currentStepId = "assess",
        stepsJson = """[{"step_id":"assess","status":"completed"},{"step_id":"create_branch","status":"pending"}]""",
        artifactsJson =
          JsonCodec.mapToJsonString(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(manifest),
            ),
          ),
        startedAt = null,
        updatedAt = null,
        finishedAt = null,
        mode = PROSE,
        implementationSkill = "bill-feature-task-prose",
        issueKey = "SKILL-52.1",
      ),
      PROSE,
    )
    controls.persistControlState(
      "wfl-prose-parent",
      GoalRunnerControlState(
        paused = true,
        pauseRequested = true,
        pauseConsumed = true,
        pauseReason = "runner_interrupted",
        pausedAt = "2026-08-09T18:46:07Z",
      ),
    )

    val projection =
      newGoalStatusService(workflows, controls).status(
        GoalRunnerStatusRequest(
          issueKey = "SKILL-52.1",
          invokedAgentId = "codex",
          repoRoot = Path.of("").toAbsolutePath(),
        ),
      )

    requireNotNull(projection)
    assertTrue(projection.paused)
    assertEquals("runner_interrupted", projection.pauseReason)
    assertEquals(PROSE, workflows.getFeatureTaskWorkflow("wfl-prose-parent")?.mode)
  }

  private fun saveCompleteGoalParent(workflows: InMemoryWorkflowStates) {
    val manifest =
      decompositionRuntime(status = "complete").copy(
        currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
        subtasks =
          listOf(
            DecompositionSubtask(
              id = 1,
              name = "install-policy-foundation",
              specPath = ".feature-specs/SKILL-52.1/spec_subtask_1.md",
              status = "complete",
              workflowId = "wfl-authoritative",
              commitSha = "sha-1",
              lastResumableStep = "commit_push",
            ),
          ),
      )
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-parent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(manifest),
            ),
          ),
      ),
      RUNTIME,
    )
  }

  private fun saveStaleRunningChildWithObservability(workflows: InMemoryWorkflowStates) {
    val opened =
      testWorkflowEngine.openRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        "wfl-stale",
        "ftr-stale",
        "preplan",
      )
    val running =
      testWorkflowEngine.updateRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
              ),
            ),
          artifactsPatch = WorkflowArtifactPatch.from(goalContinuationArtifact() + staleObservabilityArtifact()),
          sessionId = "ftr-stale",
        ),
      )
    workflows.saveFeatureTaskWorkflow(running.toRecord(), PROSE)
  }

  private fun saveAuthoritativeCompleteChild(workflows: InMemoryWorkflowStates) {
    val opened =
      testWorkflowEngine.openRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        "wfl-authoritative",
        "ftr-done",
        "preplan",
      )
    val complete =
      testWorkflowEngine.updateRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "commit_push",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
              ),
            ),
          artifactsPatch = WorkflowArtifactPatch.from(goalContinuationArtifact() + completeOutcomeArtifact()),
          sessionId = "ftr-done",
        ),
      )
    workflows.saveFeatureTaskWorkflow(complete.toRecord(), PROSE)
  }

  private fun goalContinuationArtifact(): Map<String, Any?> =
    mapOf(
      "goal_continuation" to
        mapOf(
          "issue_key" to "SKILL-52.1",
          "subtask_id" to 1,
          "suppress_pr" to true,
          "goal_branch" to "feat/SKILL-52",
          "code_review_mode" to "auto",
        ),
    )

  private fun staleObservabilityArtifact(): Map<String, Any?> =
    mapOf(
      "goal_observability_latest_event" to
        mapOf(
          "contract_version" to "0.1",
          "issue_key" to "SKILL-52.1",
          "subtask_id" to 1,
          "workflow_id" to "wfl-stale",
          "workflow_phase" to "implement",
          "worker_role" to "phase_subagent",
          "liveness_class" to "durable_progress",
          "activity_summary" to "stale edit",
          "timestamp" to "2026-06-01T00:00:00Z",
          "sequence_number" to 10,
        ),
    )

  private fun completeOutcomeArtifact(): Map<String, Any?> =
    mapOf(
      "goal_continuation_outcome" to
        mapOf(
          "issue_key" to "SKILL-52.1",
          "subtask_id" to 1,
          "status" to "complete",
          "workflow_id" to "wfl-authoritative",
          "commit_sha" to "sha-1",
          "last_resumable_step" to "commit_push",
        ),
    )

  private fun newGoalStatusService(
    workflows: InMemoryWorkflowStates,
    controls: GoalRunnerControlRepository = EmptyGoalRunnerControlRepository,
  ): GoalRunnerStatusService {
    val database = FakeDatabaseSessionFactory(workflows, goalRunnerControls = controls)
    return testGoalRunnerStatusService(
      manifestStore =
        testWorkflowGoalRunnerManifestStore(
          database = database,
          decompositionManifestStore = TestDecompositionManifestStore,
          clock = Clock.systemUTC(),
        ),
      outcomeStore =
        testWorkflowGoalRunnerOutcomeStore(
          database,
          testWorkflowSnapshotValidator,
          artifactPorts =
            OutcomeStoreTestArtifactPorts(
              goalObservabilityEventValidator = testFeatureTaskRuntimeWireArtifactValidator,
            ),
        ),
      phaseRecorder =
        testPhaseRecorder(
          database,
          testWorkflowSnapshotValidator,
          AcceptingFeatureTaskRuntimeWireArtifactValidator,
          AcceptingFeatureTaskRuntimeWireArtifactValidator,
        ),
    )
  }
}

private object HeadShaGitOperations : WorkflowGitOperations by NoopWorkflowGitOperations {
  override fun headCommitSha(repoRoot: Path): WorkflowGitOperationResult =
    WorkflowGitOperationResult.Ok(value = "measured-head-sha")
}

private object PushedHeadGitOperations : WorkflowGitOperations by HeadShaGitOperations {
  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult =
    if (branch == "origin/feat/SKILL-52" && expectedBaseBranch == "HEAD") {
      WorkflowGitOperationResult.Ok(value = expectedBaseBranch)
    } else {
      WorkflowGitOperationResult.Failed(error = "unexpected branch check")
    }
}

private object DivergedHeadGitOperations : WorkflowGitOperations by HeadShaGitOperations {
  override fun validateBranchBase(
    repoRoot: Path,
    branch: String,
    expectedBaseBranch: String,
  ): WorkflowGitOperationResult = WorkflowGitOperationResult.Failed(error = "remote does not contain HEAD")
}

class GoalRunnerCommitShaRecoveryTest {
  @Test
  fun `goal runner outcome store completes blocked commit push when remote contains head`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(blockedCommitPush("wfl-child"), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        gitOperations = PushedHeadGitOperations,
      )

    val outcome = store.recoverAndPersistTerminalOutcome("wfl-child", "SKILL-52.1", 1, repoRoot = Path.of("."))

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, outcome.status)
    assertEquals("measured-head-sha", outcome.commitSha)
    assertNull(outcome.blockedReason)
    val durable = requireNotNull(store.terminalOutcome("wfl-child", "SKILL-52.1", 1))
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, durable.status)
    assertEquals("measured-head-sha", durable.commitSha)
  }

  @Test
  fun `goal runner outcome store preserves blocked commit push when remote does not contain head`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(blockedCommitPush("wfl-child"), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        gitOperations = DivergedHeadGitOperations,
      )

    val outcome = store.recoverAndPersistTerminalOutcome("wfl-child", "SKILL-52.1", 1, repoRoot = Path.of("."))

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("remote branch diverged", outcome.blockedReason)
  }

  @Test
  fun `goal runner outcome store backfills missing commit sha from measured git head`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(commitPushCompletedWithoutCommitSha("wfl-child"), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        gitOperations = HeadShaGitOperations,
      )

    val outcome = store.recoverAndPersistTerminalOutcome("wfl-child", "SKILL-52.1", 1, repoRoot = Path.of("."))

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, outcome.status)
    assertEquals("measured-head-sha", outcome.commitSha)
  }

  @Test
  fun `goal runner outcome store durably persists the measured completion`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(commitPushCompletedWithoutCommitSha("wfl-child"), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        gitOperations = HeadShaGitOperations,
      )

    store.recoverAndPersistTerminalOutcome("wfl-child", "SKILL-52.1", 1, repoRoot = Path.of("."))
    val durable = store.terminalOutcome("wfl-child", "SKILL-52.1", 1)

    requireNotNull(durable)
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, durable.status)
    assertEquals("measured-head-sha", durable.commitSha)
  }

  @Test
  fun `goal runner outcome store stays blocked when measured git head is unavailable`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(commitPushCompletedWithoutCommitSha("wfl-child"), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        gitOperations = NoopWorkflowGitOperations,
      )

    val outcome = store.recoverAndPersistTerminalOutcome("wfl-child", "SKILL-52.1", 1, repoRoot = Path.of("."))

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME, outcome.status)
  }

  @Test
  fun `goal runner outcome store does not measure git head without a repo root`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(commitPushCompletedWithoutCommitSha("wfl-child"), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        gitOperations = HeadShaGitOperations,
      )

    val outcome = store.terminalOutcome("wfl-child", "SKILL-52.1", 1)

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.NO_TERMINAL_STORE_OUTCOME, outcome.status)
  }

  @Test
  fun `goal runner outcome store persists recovered missing result prefix terminal envelope`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "goal_continuation" to
                mapOf(
                  "issue_key" to "SKILL-52.1",
                  "subtask_id" to 1,
                  "suppress_pr" to true,
                  "goal_branch" to "feat/SKILL-52",
                  "code_review_mode" to "auto",
                ),
            ),
          ),
      ),
      PROSE,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcome =
      store.recoverMissingResultPrefixOutput(
        workflowId = "wfl-child",
        issueKey = "SKILL-52.1",
        subtaskId = 1,
        output =
          GoalRunnerWirePayload.from(
            mapOf(
              "status" to "blocked",
              "workflow_id" to "wfl-child",
              "last_resumable_step" to "implement",
              "blocked_reason" to "prefixless terminal json",
            ),
          ),
      )

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("implement", outcome.lastResumableStep)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-child")).toSnapshot()
    val artifacts = saved.artifacts.toMap()
    assertTrue(artifacts.containsKey("goal_runner_missing_result_prefix_recovery"))
    assertEquals("prefixless terminal json", outcome.blockedReason)
  }

  @Test
  fun `reconciliation backfills a pre-existing complete-without-sha outcome from measured git head`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(completeWithoutShaOutcome("wfl-child"), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        gitOperations = HeadShaGitOperations,
      )

    val reconciled = store.reconcileAuthoritativeOutcomes(issueKey = "SKILL-52.1", repoRoot = Path.of("."))

    val outcome = requireNotNull(reconciled[1])
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, outcome.status)
    assertEquals("measured-head-sha", outcome.commitSha)
    val durable = requireNotNull(store.terminalOutcome("wfl-child", "SKILL-52.1", 1))
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, durable.status)
    assertEquals("measured-head-sha", durable.commitSha)
  }

  @Test
  fun `reconciliation without a repo root leaves a complete-without-sha outcome unmeasured`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(completeWithoutShaOutcome("wfl-child"), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        gitOperations = HeadShaGitOperations,
      )

    store.reconcileAuthoritativeOutcomes(issueKey = "SKILL-52.1")
    val durable = requireNotNull(store.terminalOutcome("wfl-child", "SKILL-52.1", 1))

    assertNull(durable.commitSha, "a read-only reconciliation must not backfill a measured SHA")
  }

  private fun commitPushCompletedWithoutCommitSha(workflowId: String): WorkflowStateRecord {
    val opened =
      testWorkflowEngine.openRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        workflowId,
        "ftr-no-sha",
        "preplan",
      )
    val completed =
      testWorkflowEngine.updateRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "commit_push",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "goal_continuation" to
                  mapOf(
                    "issue_key" to "SKILL-52.1",
                    "subtask_id" to 1,
                    "suppress_pr" to true,
                    "goal_branch" to "feat/SKILL-52",
                    "code_review_mode" to "auto",
                  ),
              ),
            ),
          sessionId = "ftr-no-sha",
        ),
      )
    return completed.toRecord()
  }

  private fun blockedCommitPush(workflowId: String): WorkflowStateRecord {
    val opened =
      testWorkflowEngine.openRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        workflowId,
        "ftr-blocked-push",
        "preplan",
      )
    return testWorkflowEngine.updateRecord(
      FeatureTaskRuntimePhaseWorkflowDefinition.definition,
      opened,
      WorkflowUpdateInput(
        terminalInstant = Instant.EPOCH,
        workflowStatus = WorkflowStatus.BLOCKED,
        currentStepId = "commit_push",
        stepUpdates =
          WorkflowStepUpdates.from(
            listOf(
              mapOf("step_id" to "commit_push", "status" to "blocked", "attempt_count" to 1),
            ),
          ),
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "goal_continuation" to
                mapOf(
                  "issue_key" to "SKILL-52.1",
                  "subtask_id" to 1,
                  "suppress_pr" to true,
                  "goal_branch" to "feat/SKILL-52",
                  "code_review_mode" to "auto",
                ),
              "goal_continuation_outcome" to
                mapOf(
                  "issue_key" to "SKILL-52.1",
                  "subtask_id" to 1,
                  "status" to "blocked",
                  "workflow_id" to workflowId,
                  "blocked_reason" to "remote branch diverged",
                  "last_resumable_step" to "commit_push",
                ),
              "blocked_reason" to "remote branch diverged",
            ),
          ),
        sessionId = "ftr-blocked-push",
      ),
    ).toRecord()
  }

  private fun completeWithoutShaOutcome(workflowId: String): WorkflowStateRecord {
    val opened =
      testWorkflowEngine.openRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        workflowId,
        "ftr-stale-complete",
        "preplan",
      )
    val completed =
      testWorkflowEngine.updateRecord(
        FeatureTaskRuntimePhaseWorkflowDefinition.definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "commit_push",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "goal_continuation" to
                  mapOf(
                    "issue_key" to "SKILL-52.1",
                    "subtask_id" to 1,
                    "suppress_pr" to true,
                    "goal_branch" to "feat/SKILL-52",
                    "code_review_mode" to "auto",
                  ),
                "goal_continuation_outcome" to
                  mapOf(
                    "issue_key" to "SKILL-52.1",
                    "subtask_id" to 1,
                    "status" to "complete",
                    "workflow_id" to workflowId,
                    "last_resumable_step" to "commit_push",
                  ),
              ),
            ),
          sessionId = "ftr-stale-complete",
        ),
      )
    return completed.toRecord()
  }
}

class WorkflowUpdateAcknowledgementBudgetTest {
  @Test
  fun `compact update acknowledgement stays under byte ceiling and omits full durable state`() {
    val service = newAckBudgetService()
    val opened = assertIs<WorkflowOpenResult.Ok>(service.openTestRuntime("ftr-001"))
    val updated =
      service.update(
        WorkflowFamilyKind.TASK_RUNTIME,
        WorkflowUpdateRequest(
          workflowId = opened.workflowId,
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "plan" to mapOf("mode" to "implement", "body" to "x".repeat(12000)),
                "preplan_digest" to mapOf("risk" to "low", "notes" to "y".repeat(8000)),
              ),
            ),
          sessionId = "ftr-001",
        ),
      )

    val ok = assertIs<WorkflowUpdateResult.Ok>(updated)
    val ack = ok.acknowledgement
    assertEquals("ok", ack.status)
    assertEquals(opened.workflowId, ack.workflowId)
    assertEquals("running", ack.workflowStatus.wireValue)
    assertEquals("implement", ack.currentStepId)
    assertEquals(listOf("implement"), ack.updatedStepIds)
    assertEquals(listOf("plan", "preplan_digest"), ack.updatedArtifactKeys)

    val ackMap = compactAcknowledgementMap(ack, ok.dbPath)
    val serialized = JsonCodec.mapToJsonString(ackMap)
    val byteSize = serialized.toByteArray(Charsets.UTF_8).size
    assertTrue(
      byteSize < COMPACT_UPDATE_ACK_PAYLOAD_BYTE_CEILING,
      "Compact update acknowledgement was $byteSize bytes, exceeding the " +
        "$COMPACT_UPDATE_ACK_PAYLOAD_BYTE_CEILING ceiling; the full durable state was likely echoed back.",
    )
    assertEquals(
      setOf(
        "status",
        "workflow_id",
        "workflow_name",
        "workflow_status",
        "current_step_id",
        "updated_step_ids",
        "updated_artifact_keys",
        "read_only_full_state_guidance",
        "db_path",
      ),
      ackMap.keys,
    )
    assertFalse(serialized.contains("\"artifacts\""))
    assertFalse(serialized.contains("\"steps\""))
    assertFalse(serialized.contains("x".repeat(2000)))
    assertFalse(serialized.contains("y".repeat(2000)))
    assertTrue(ack.readOnlyFullStateGuidance.isNotBlank())
  }

  private fun compactAcknowledgementMap(
    ack: WorkflowUpdateAcknowledgementView,
    dbPath: String,
  ): Map<String, Any?> =
    linkedMapOf(
      SharedPayloadKeys.STATUS to ack.status,
      SharedPayloadKeys.WORKFLOW_ID to ack.workflowId,
      WorkflowWirePayloadKeys.WORKFLOW_NAME to ack.workflowName,
      WorkflowWirePayloadKeys.WORKFLOW_STATUS to ack.workflowStatus.wireValue,
      WorkflowWirePayloadKeys.CURRENT_STEP_ID to ack.currentStepId,
      WorkflowWirePayloadKeys.UPDATED_STEP_IDS to ack.updatedStepIds,
      WorkflowWirePayloadKeys.UPDATED_ARTIFACT_KEYS to ack.updatedArtifactKeys,
      WorkflowWirePayloadKeys.READ_ONLY_FULL_STATE_GUIDANCE to ack.readOnlyFullStateGuidance,
      "db_path" to dbPath,
    )

  private fun newAckBudgetService(): WorkflowService =
    WorkflowService(
      database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
      gitOperations = NoopWorkflowGitOperations,
      decompositionManifestStore = UnavailableDecompositionManifestStore,
      workflowSnapshotValidator = testWorkflowSnapshotValidator,
      goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
      decompositionManifestValidator = testDecompositionManifestValidator,
      decompositionManifestWriter = testDecompositionManifestWriter,
      repositoryRoot = testRepositoryRoot,
      runtimeDiagnostics = NoopRuntimeDiagnostics,
      clock = Clock.systemUTC(),
    )
}

class WorkflowGoalRunnerOutcomeStoreTest {
  @Test
  fun `goal runner outcome store reads durable blocked continuation outcome`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "goal_continuation" to
                mapOf(
                  "issue_key" to "SKILL-52.1",
                  "subtask_id" to 1,
                  "suppress_pr" to true,
                  "goal_branch" to "feat/SKILL-52",
                  "code_review_mode" to "auto",
                ),
              "goal_continuation_outcome" to
                mapOf(
                  "issue_key" to "SKILL-52.1",
                  "subtask_id" to 1,
                  "status" to "blocked",
                  "workflow_id" to "wfl-child",
                  "blocked_reason" to "preplan could not progress",
                  "last_resumable_step" to "preplan",
                ),
            ),
          ),
        workflowStatus = WorkflowStatus.BLOCKED.wireValue,
      ),
      PROSE,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcome = store.terminalOutcome("wfl-child", "SKILL-52.1", 1)

    requireNotNull(outcome)
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("preplan could not progress", outcome.blockedReason)
    assertEquals("preplan", outcome.lastResumableStep)
  }
}

class WorkflowGoalRunnerReconciliationTest {
  @Test
  fun `goal runner outcome reconciliation closes stale running child in favor of authoritative terminal workflow`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    workflows.saveFeatureTaskWorkflow(staleRunningChildRecord(definition).toRecord(), PROSE)
    workflows.saveFeatureTaskWorkflow(authoritativeCompleteChildRecord(definition).toRecord(), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcomes = store.reconcileAuthoritativeOutcomes("SKILL-52.1", setOf("wfl-stale", "wfl-authoritative"))

    val subtaskOutcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.COMPLETE, subtaskOutcome.status)
    assertEquals("wfl-authoritative", subtaskOutcome.workflowId)
    val stale = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-stale")).toSnapshot()
    assertEquals("blocked", stale.workflowStatus.wireValue)
    assertEquals("blocked", decodeWorkflowStepsForTest(stale.steps).getValue("implement"))
    assertContains(JsonCodec.mapToJsonString(stale.artifacts.toMap()), "stale running child 'wfl-stale'")
  }

  @Test
  fun `goal runner outcome reconciliation closes inactive running child without authoritative sibling`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wfl-orphan", "ftr-001", "preplan")
    val running =
      testWorkflowEngine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "goal_continuation" to
                  mapOf(
                    "issue_key" to "SKILL-52.1",
                    "subtask_id" to 1,
                    "suppress_pr" to true,
                    "goal_branch" to "feat/SKILL-52",
                    "code_review_mode" to "auto",
                  ),
              ),
            ),
          sessionId = "ftr-001",
        ),
      )
    workflows.saveFeatureTaskWorkflow(running.toRecord(), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcomes = store.reconcileAuthoritativeOutcomes("SKILL-52.1", emptySet())

    val outcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("wfl-orphan", outcome.workflowId)
    val orphan = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-orphan")).toSnapshot()
    assertEquals("blocked", orphan.workflowStatus.wireValue)
    assertContains(JsonCodec.mapToJsonString(orphan.artifacts.toMap()), "no longer active")
  }

  @Test
  fun `goal runner outcome reconciliation keeps active running child without authoritative terminal outcome`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wfl-active", "ftr-001", "preplan")
    val running =
      testWorkflowEngine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "goal_continuation" to
                  mapOf(
                    "issue_key" to "SKILL-52.1",
                    "subtask_id" to 1,
                    "suppress_pr" to true,
                    "goal_branch" to "feat/SKILL-52",
                    "code_review_mode" to "auto",
                  ),
              ),
            ),
          sessionId = "ftr-001",
        ),
      )
    workflows.saveFeatureTaskWorkflow(running.toRecord(), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcomes = store.reconcileAuthoritativeOutcomes("SKILL-52.1", setOf("wfl-active"))

    assertTrue(outcomes.isEmpty())
    val active = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-active")).toSnapshot()
    assertEquals("running", active.workflowStatus.wireValue)
    assertEquals("running", decodeWorkflowStepsForTest(active.steps).getValue("implement"))
  }

  @Test
  fun `goal runner outcome reconciliation keeps active retry when only blocked sibling exists`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    workflows.saveFeatureTaskWorkflow(blockedSiblingChildRecord(definition).toRecord(), PROSE)
    workflows.saveFeatureTaskWorkflow(activeRetryChildRecord(definition).toRecord(), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val outcomes = store.reconcileAuthoritativeOutcomes("SKILL-52.1", setOf("wfl-active"))

    val outcome = requireNotNull(outcomes[1])
    assertEquals(GoalRunnerTerminalStatus.BLOCKED, outcome.status)
    assertEquals("wfl-blocked", outcome.workflowId)
    val stillActive = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-active")).toSnapshot()
    assertEquals("running", stillActive.workflowStatus.wireValue)
    assertEquals("running", decodeWorkflowStepsForTest(stillActive.steps).getValue("implement"))
    val stillBlocked = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-blocked")).toSnapshot()
    assertEquals("blocked", stillBlocked.workflowStatus.wireValue)
  }

  @Test
  fun `goal runner outcome store blocks active running step instead of stale requested step`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wfl-child", "ftr-001", "preplan")
    val running =
      testWorkflowEngine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "goal_continuation" to
                  mapOf(
                    "issue_key" to "SKILL-52.1",
                    "subtask_id" to 1,
                    "suppress_pr" to true,
                    "goal_branch" to "feat/SKILL-52",
                    "code_review_mode" to "auto",
                  ),
              ),
            ),
          sessionId = "ftr-001",
        ),
      )
    workflows.saveFeatureTaskWorkflow(running.toRecord(), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val blockedStep = store.markBlocked("wfl-child", "timeout", "preplan")

    assertEquals("implement", blockedStep)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-child")).toSnapshot()
    assertEquals("blocked", saved.workflowStatus.wireValue)
    assertEquals("implement", saved.currentStepId)
    val steps = decodeWorkflowStepsForTest(saved.steps)
    assertEquals("completed", steps.getValue("preplan"))
    assertEquals("blocked", steps.getValue("implement"))
  }

  @Test
  fun `goal runner reconciles crashed runtime row without outcome to real last completed phase not preplan`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wftr-child", "ftr-001", "preplan")
    val crashed =
      testWorkflowEngine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "plan",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "goal_continuation" to
                  mapOf(
                    "issue_key" to "SKILL-85",
                    "subtask_id" to 1,
                    "suppress_pr" to true,
                  ),
                "feature_task_runtime_phase_records" to
                  mapOf(
                    "preplan" to
                      completedRuntimePhaseRecord(
                        "preplan",
                        "2026-06-18T10:00:00Z",
                        "2026-06-18T10:01:00Z",
                      ),
                    "plan" to
                      completedRuntimePhaseRecord(
                        "plan",
                        "2026-06-18T10:02:00Z",
                        "2026-06-18T10:03:00Z",
                      ),
                  ),
              ),
            ),
          sessionId = "ftr-001",
        ),
      )
    workflows.saveFeatureTaskWorkflow(crashed.toRecord(), RUNTIME)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val blockedStep = store.markBlocked("wftr-child", "no terminal outcome", "preplan")

    assertEquals("implement", blockedStep)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-child", RUNTIME)).toSnapshot()
    assertEquals("implement", saved.currentStepId)
    val steps = decodeWorkflowStepsForTest(saved.steps)
    assertEquals("completed", steps.getValue("preplan"))
    assertEquals("completed", steps.getValue("plan"))
    assertEquals("blocked", steps.getValue("implement"))
  }

  @Test
  fun `goal runner resumes a clean-review runtime row at audit not the loop-only implement_fix`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wftr-clean-review", "ftr-002", "preplan")
    val crashed =
      testWorkflowEngine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "audit",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "implement", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "simplify", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "review", "status" to "completed", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "goal_continuation" to
                  mapOf(
                    "issue_key" to "PS-24",
                    "subtask_id" to 2,
                    "suppress_pr" to true,
                  ),
              ),
            ),
          sessionId = "ftr-002",
        ),
      )
    workflows.saveFeatureTaskWorkflow(crashed.toRecord(), RUNTIME)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val blockedStep = store.markBlocked("wftr-clean-review", "no terminal outcome", "preplan")

    assertEquals("audit", blockedStep)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-clean-review", RUNTIME)).toSnapshot()
    assertEquals("audit", saved.currentStepId)
    val steps = decodeWorkflowStepsForTest(saved.steps)
    assertEquals("pending", steps.getValue("implement_fix"))
    assertEquals("completed", steps.getValue("review"))
    assertEquals("blocked", steps.getValue("audit"))
  }

  @Test
  fun `goal runner resumes a runtime row genuinely parked at the loop-only implement_fix there`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wftr-mid-fix", "ftr-003", "preplan")
    val crashed =
      testWorkflowEngine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "implement_fix",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "implement", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "review", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "implement_fix", "status" to "running", "attempt_count" to 1),
              ),
            ),
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                "goal_continuation" to
                  mapOf(
                    "issue_key" to "PS-24",
                    "subtask_id" to 3,
                    "suppress_pr" to true,
                  ),
              ),
            ),
          sessionId = "ftr-003",
        ),
      )
    workflows.saveFeatureTaskWorkflow(crashed.toRecord(), RUNTIME)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val blockedStep = store.markBlocked("wftr-mid-fix", "no terminal outcome", "preplan")

    assertEquals("implement_fix", blockedStep)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflowAsMode("wftr-mid-fix", RUNTIME)).toSnapshot()
    assertEquals("implement_fix", saved.currentStepId)
    val steps = decodeWorkflowStepsForTest(saved.steps)
    assertEquals("completed", steps.getValue("review"))
    assertEquals("blocked", steps.getValue("implement_fix"))
  }

  @Test
  fun `only the runtime family carries loop-only steps so non-runtime boundary scans stay strict`() {
    assertEquals(emptySet<String>(), WorkflowFamily.VERIFY.loopOnlyStepIds)
    assertEquals(
      FeatureTaskRuntimePhaseWorkflowDefinition.transitions.loopOnlyPhaseIds,
      WorkflowFamily.TASK_RUNTIME.loopOnlyStepIds,
    )
    assertEquals(
      setOf("audit_plan_fix", "audit_implement_fix", "implement_fix", "build"),
      WorkflowFamily.TASK_RUNTIME.loopOnlyStepIds,
    )
  }

  private fun completedRuntimePhaseRecord(
    phaseId: String,
    startedAt: String,
    finishedAt: String,
  ): Map<String, Any?> =
    mapOf(
      "contract_version" to FEATURE_TASK_RUNTIME_PERSISTENCE_CONTRACT_VERSION,
      "record_kind" to "private_phase_record",
      "phase_id" to phaseId,
      "status" to "completed",
      "attempt_count" to 1,
      "started_at" to startedAt,
      "first_started_at" to startedAt,
      "finished_at" to finishedAt,
      "resolved_agent_id" to "agent-$phaseId",
      "execution_origin" to "agent-executed",
    )
}

private fun staleRunningChildRecord(definition: WorkflowDefinition) =
  testWorkflowEngine.updateRecord(
    definition,
    testWorkflowEngine.openRecord(definition, "wfl-stale", "ftr-001", "preplan"),
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "implement",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "preplan", "status" to "completed", "attempt_count" to 1),
            mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-52.1",
                "subtask_id" to 1,
                "suppress_pr" to true,
                "goal_branch" to "feat/SKILL-52",
                "code_review_mode" to "auto",
              ),
          ),
        ),
      sessionId = "ftr-001",
    ),
  )

private fun authoritativeCompleteChildRecord(definition: WorkflowDefinition) =
  testWorkflowEngine.updateRecord(
    definition,
    testWorkflowEngine.openRecord(definition, "wfl-authoritative", "ftr-002", "preplan"),
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "commit_push",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "commit_push", "status" to "completed", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-52.1",
                "subtask_id" to 1,
                "suppress_pr" to true,
                "goal_branch" to "feat/SKILL-52",
                "code_review_mode" to "auto",
              ),
            "goal_continuation_outcome" to
              mapOf(
                "issue_key" to "SKILL-52.1",
                "subtask_id" to 1,
                "status" to "complete",
                "workflow_id" to "wfl-authoritative",
                "commit_sha" to "sha-1",
                "last_resumable_step" to "commit_push",
              ),
          ),
        ),
      sessionId = "ftr-002",
    ),
  )

private fun blockedSiblingChildRecord(definition: WorkflowDefinition) =
  testWorkflowEngine.updateRecord(
    definition,
    testWorkflowEngine.openRecord(definition, "wfl-blocked", "ftr-001", "preplan"),
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.BLOCKED,
      currentStepId = "review",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "review", "status" to "blocked", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-52.1",
                "subtask_id" to 1,
                "suppress_pr" to true,
                "goal_branch" to "feat/SKILL-52",
                "code_review_mode" to "auto",
              ),
          ),
        ),
      sessionId = "ftr-001",
    ),
  )

private fun activeRetryChildRecord(definition: WorkflowDefinition) =
  testWorkflowEngine.updateRecord(
    definition,
    testWorkflowEngine.openRecord(definition, "wfl-active", "ftr-002", "preplan"),
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus = WorkflowStatus.RUNNING,
      currentStepId = "implement",
      stepUpdates =
        WorkflowStepUpdates.from(
          listOf(
            mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
          ),
        ),
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "goal_continuation" to
              mapOf(
                "issue_key" to "SKILL-52.1",
                "subtask_id" to 1,
                "suppress_pr" to true,
                "goal_branch" to "feat/SKILL-52",
                "code_review_mode" to "auto",
              ),
          ),
        ),
      sessionId = "ftr-002",
    ),
  )

class WorkflowGoalRunnerProgressStoreTest {
  @Test
  fun `goal runner progress keeps declared liveness when goal observability latest event is malformed`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "goal_observability_latest_event" to
                mapOf(
                  "contract_version" to "0.1",
                  "issue_key" to "SKILL-61",
                ),
              GoalProgressEvent(
                eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
                workflowId = "wfl-child",
                workflowPhase = "validate",
                processAlive = true,
                sequenceNumber = 5,
                timestamp = "2026-06-02T10:00:00Z",
                operationName = "gradlew check",
                operationKind = "build",
                expectedLong = true,
              ).let { event -> "goal_progress_latest_event" to event.toPersistenceWire() },
            ),
          ),
      ),
      PROSE,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        artifactPorts =
          OutcomeStoreTestArtifactPorts(
            goalObservabilityEventValidator =
              object : FeatureTaskRuntimeWireArtifactValidator {
                override fun validate(
                  kind: FeatureTaskRuntimeWireArtifactKind,
                  payload: FeatureTaskRuntimeWorkflowArtifactMap,
                  sourceLabel: String,
                ) {
                  throw InvalidGoalObservabilityEventSchemaError(sourceLabel, "subtask_id", "subtask_id is required.")
                }
              },
          ),
      )

    val progress = requireNotNull(store.progress("wfl-child"))

    assertNull(progress.latestGoalObservabilityEvent)
    val declared = requireNotNull(progress.latestDeclaredProgressEvent)
    assertEquals(GoalProgressEventKind.OPERATION_HEARTBEAT, declared.eventKind)
    assertEquals("gradlew check", declared.operationName)
    assertTrue(declared.processAlive)
  }

  @Test
  fun `goal runner progress returns declared progress when unrelated artifact keys are oversized`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        workflowId = "wfl-child",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "feature_task_runtime_delivered_projections" to
                mapOf(
                  "validate|1" to "z".repeat(1_500_000),
                ),
              GoalProgressEvent(
                eventKind = GoalProgressEventKind.OPERATION_HEARTBEAT,
                workflowId = "wfl-child",
                workflowPhase = "validate",
                processAlive = true,
                sequenceNumber = 9,
                timestamp = "2026-06-02T11:00:00Z",
                operationName = "pack validation gate",
                operationKind = "validate",
                expectedLong = true,
              ).let { event -> "goal_progress_latest_event" to event.toPersistenceWire() },
            ),
          ),
      ),
      PROSE,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
      )

    val progress = requireNotNull(store.progress("wfl-child"))

    assertTrue(progress.progressToken.length < 4_000)
    assertFalse(progress.progressToken.contains("zzzz"))
    val declared = requireNotNull(progress.latestDeclaredProgressEvent)
    assertEquals("pack validation gate", declared.operationName)
    assertEquals(9, declared.sequenceNumber)
  }

  @Test
  fun `goal runner outcome store appends worker subtask request outcomes`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(workflowRecord("wfl-child", emptyMap()), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )
    val acceptedRequest =
      GoalRunnerWorkerSubtaskRequest(
        name = "Accepted follow up",
        specPath = ".feature-specs/SKILL-61/spec_subtask_2_accepted.md",
        sourceStream = "stdout",
      )
    val confirmationRequest =
      GoalRunnerWorkerSubtaskRequest(
        name = "Confirm follow up",
        specPath = ".feature-specs/SKILL-61/spec_subtask_3_confirm.md",
        requiresOperatorConfirmation = true,
        sourceStream = "stderr",
      )

    val recorded =
      store.recordWorkerSubtaskRequestOutcomes(
        workflowId = "wfl-child",
        outcomes =
          listOf(
            GoalRunnerWorkerSubtaskRequestOutcome.Accepted(
              request = acceptedRequest,
              subtask =
                DecompositionSubtask(
                  id = 2,
                  name = "Accepted follow up",
                  specPath = ".feature-specs/SKILL-61/spec_subtask_2_accepted.md",
                ),
            ),
            GoalRunnerWorkerSubtaskRequestOutcome.Rejected(
              sourceStream = "stdout",
              reason = GoalRunnerWorkerSubtaskRequestRejectionReason.UNSAFE_PATH,
              message = "unsafe path",
            ),
            GoalRunnerWorkerSubtaskRequestOutcome.RequiresOperatorConfirmation(
              request = confirmationRequest,
              reason = "needs approval",
            ),
          ),
      )

    assertTrue(recorded)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-child")).toSnapshot()
    val artifacts = saved.artifacts.toMap()
    val outcomes = artifacts["goal_worker_subtask_request_outcomes"] as List<*>
    val accepted = outcomes[0] as Map<*, *>
    val rejected = outcomes[1] as Map<*, *>
    val confirmation = outcomes[2] as Map<*, *>
    assertEquals("accepted", accepted["status"])
    assertEquals(2, accepted["subtask_id"])
    assertEquals("rejected", rejected["status"])
    assertEquals("unsafe_path", rejected["reason"])
    assertEquals("requires_operator_confirmation", confirmation["status"])
    assertEquals("needs approval", confirmation["reason"])
  }

  @Test
  fun `record progress event accumulates append-only and mirrors latest-event key`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(workflowRecord("wfl-child", emptyMap()), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    assertTrue(store.recordProgressEvent(progressEventRequest("wfl-child", tick = 0)))
    assertTrue(store.recordProgressEvent(progressEventRequest("wfl-child", tick = 1)))

    val artifacts = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-child")).toSnapshot().artifacts
    val history = artifacts["goal_progress_run_history"] as List<*>
    assertEquals(2, history.size)
    assertEquals(0, (history[0] as Map<*, *>)["sequence_number"])
    assertEquals(1, (history[1] as Map<*, *>)["sequence_number"])
    val latest = artifacts["goal_progress_latest_event"] as Map<*, *>
    assertEquals(1, latest["sequence_number"])
  }

  @Test
  fun `record progress event prunes oldest entry at retention limit in sequence order`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(workflowRecord("wfl-child", emptyMap()), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val total = GOAL_PROGRESS_HISTORY_LIMIT + 5
    (total - 1 downTo 0).forEach { sequence ->
      store.recordProgressEvent(progressEventRequest("wfl-child", tick = sequence))
    }

    val artifacts = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-child")).toSnapshot().artifacts
    val history = artifacts["goal_progress_run_history"] as List<*>
    assertEquals(GOAL_PROGRESS_HISTORY_LIMIT, history.size)
    val sequences = history.map { (it as Map<*, *>)["sequence_number"] }
    assertEquals(5, sequences.first())
    assertEquals(total - 1, sequences.last())
    assertEquals(sequences.sortedBy { it as Int }, sequences)
  }

  @Test
  fun `record progress event returns false when workflow is missing`() {
    val workflows = InMemoryWorkflowStates()
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    assertFalse(store.recordProgressEvent(progressEventRequest("wfl-missing", tick = 0)))
  }

  @Test
  fun `record progress event loud-fails through the schema validator at the write seam`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(workflowRecord("wfl-child", emptyMap()), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        database = FakeDatabaseSessionFactory(workflows),
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        artifactPorts =
          OutcomeStoreTestArtifactPorts(
            goalProgressEventValidator =
              object : FeatureTaskRuntimeWireArtifactValidator {
                override fun validate(
                  kind: FeatureTaskRuntimeWireArtifactKind,
                  payload: FeatureTaskRuntimeWorkflowArtifactMap,
                  sourceLabel: String,
                ) {
                  throw InvalidGoalProgressEventSchemaError(
                    sourceLabel,
                    "operation_name",
                    "operation_name is required.",
                  )
                }
              },
          ),
      )

    assertFailsWith<InvalidGoalProgressEventSchemaError> {
      store.recordProgressEvent(progressEventRequest("wfl-child", tick = 0))
    }
    val artifacts = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-child")).toSnapshot().artifacts
    assertFalse(artifacts.containsKey("goal_progress_run_history"))
    assertFalse(artifacts.containsKey("goal_progress_latest_event"))
  }

  @Test
  fun `record attempt ledger entry prunes oldest in sequence order at retention limit`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(workflowRecord("wfl-child", emptyMap()), PROSE)
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    val total = GOAL_ATTEMPT_LEDGER_LIMIT + 3
    (total - 1 downTo 0).forEach { sequence ->
      store.recordAttemptLedgerEntry(attemptLedgerRequest("wfl-child", tick = sequence))
    }
    assertFalse(store.recordAttemptLedgerEntry(attemptLedgerRequest("wfl-missing", tick = 0)))

    val artifacts = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-child")).toSnapshot().artifacts
    val history = artifacts["goal_attempt_ledger"] as List<*>
    assertEquals(GOAL_ATTEMPT_LEDGER_LIMIT, history.size)
    val sequences = history.map { (it as Map<*, *>)["sequence_number"] }
    assertEquals(3, sequences.first())
    assertEquals(total - 1, sequences.last())
    assertEquals(sequences.sortedBy { it as Int }, sequences)
  }

  @Test
  fun `the store allocates progress sequence numbers in write order for a continuation child`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        "wfl-child",
        mapOf(
          "goal_continuation" to
            mapOf(
              "issue_key" to "SKILL-64",
              "subtask_id" to 1,
              "suppress_pr" to true,
              "goal_branch" to "feat/SKILL-64",
              "code_review_mode" to "auto",
            ),
        ),
      ),
      PROSE,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )
    store.recordAttemptLedgerEntry(attemptLedgerRequest("wfl-child", tick = 0))
    store.recordAttemptLedgerEntry(attemptLedgerRequest("wfl-child", tick = 1))

    store.recordProgressEvent(progressEventRequest("wfl-child", tick = 3))
    store.recordProgressEvent(progressEventRequest("wfl-child", tick = 2))

    assertEquals(listOf(0, 1), store.progressEvents("wfl-child").map { it.sequenceNumber })
  }

  @Test
  fun `direct progress recording owner preserves public progress events and ledger summary`() {
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        "wfl-child",
        mapOf(
          "goal_continuation" to
            mapOf(
              "issue_key" to "SKILL-64",
              "subtask_id" to 1,
              "suppress_pr" to true,
              "goal_branch" to "feat/SKILL-64",
              "code_review_mode" to "auto",
            ),
        ),
      ),
      PROSE,
    )
    val store =
      testWorkflowGoalRunnerOutcomeStore(
        FakeDatabaseSessionFactory(workflows),
        testWorkflowSnapshotValidator,
      )

    store.recordAttemptLedgerEntry(attemptLedgerRequest("wfl-child", tick = 0))
    store.recordProgressEvent(progressEventRequest("wfl-child", tick = 4))

    assertEquals(listOf(0), store.progressEvents("wfl-child").map { event -> event.sequenceNumber })
    assertEquals(
      1,
      store.readAttemptLedgerSummary("SKILL-64").phaseAttemptCounts["initial_start"],
    )
  }

  @Test
  fun `subtask resume alignment keeps later running step over stale manifest step`() {
    val workflows = InMemoryWorkflowStates()
    val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
    val opened = testWorkflowEngine.openRecord(definition, "wfl-child", "ftr-001", "preplan")
    val running =
      testWorkflowEngine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "implement",
          stepUpdates =
            WorkflowStepUpdates.from(
              listOf(
                mapOf("step_id" to "preplan", "status" to "blocked", "attempt_count" to 1),
                mapOf("step_id" to "plan", "status" to "completed", "attempt_count" to 1),
                mapOf("step_id" to "implement", "status" to "running", "attempt_count" to 1),
              ),
            ),
          artifactsPatch = null,
          sessionId = "ftr-001",
        ),
      )
    workflows.saveFeatureTaskWorkflow(running.toRecord(), PROSE)
    val database = FakeDatabaseSessionFactory(workflows)

    val aligned =
      database.transaction { unitOfWork ->
        testWorkflowEngine.alignSubtaskResumeStep(running, "preplan", unitOfWork)
      }

    assertEquals("implement", aligned.currentStepId)
    val saved = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-child"))
    assertEquals("implement", saved.currentStepId)
    val steps = decodeWorkflowStepsForTest(saved.toSnapshot().steps)
    assertEquals("completed", steps.getValue("preplan"))
    assertEquals("running", steps.getValue("implement"))
  }

  @Test
  fun `parent projection migrates legacy controls before replacing its artifacts`() {
    val manifest = decompositionRuntime(status = "in_progress")
    val acceptance =
      mapOf(
        "subtask_id" to 1,
        "commit_sha" to "abc1234",
        "reason" to "implemented outside the runtime",
        "accepted_at" to "2026-08-01T12:00:00Z",
      )
    val parent =
      workflowRecord(
        workflowId = "wfl-legacy-parent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
                testDecompositionManifestValidator.encodeManifestWireMap(manifest),
              "goal_review_policy" to
                mapOf(
                  "code_review_mode" to CodeReviewExecutionMode.INLINE.wireValue,
                ),
              "goal_out_of_band_acceptances" to listOf(acceptance),
            ),
          ),
      ).copy(issueKey = manifest.issueKey)
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(parent, PROSE)
    val controls = RecordingGoalRunnerControlRepository()

    FakeDatabaseSessionFactory(workflows, goalRunnerControls = controls).transaction { unitOfWork ->
      testWorkflowEngine.persistParentDecompositionRuntime(
        parent.toSnapshot(),
        manifest,
        unitOfWork,
        testDecompositionManifestValidator,
      )
    }

    assertEquals(GoalRunnerReviewPolicy(CodeReviewExecutionMode.INLINE), controls.reviewPolicy("wfl-legacy-parent"))
    assertEquals(
      GoalRunnerOutOfBandAcceptance(
        subtaskId = 1,
        commitSha = "abc1234",
        reason = "implemented outside the runtime",
        acceptedAt = "2026-08-01T12:00:00Z",
      ),
      controls.outOfBandAcceptances("wfl-legacy-parent").getValue(1),
    )
    val persistedArtifacts =
      decodeWorkflowArtifactsForTest(
        requireNotNull(workflows.getFeatureTaskWorkflow("wfl-legacy-parent")).artifactsJson,
      )
    assertEquals(setOf("plan", DECOMPOSITION_RUNTIME_ARTIFACT_KEY), persistedArtifacts.keys)
  }

  @Test
  fun `parent projection rejects a malformed legacy acceptance identity before touching controls or artifacts`() {
    val manifest = decompositionRuntime(status = "in_progress")
    val parent =
      workflowRecord(
        workflowId = "wfl-malformed-parent",
        artifactsPatch =
          WorkflowArtifactPatch.from(
            mapOf(
              "plan" to mapOf("mode" to "decompose"),
              "goal_review_policy" to mapOf("code_review_mode" to CodeReviewExecutionMode.INLINE.wireValue),
              "goal_out_of_band_acceptances" to listOf(legacyAcceptance(subtaskId = 1)),
            ),
          ),
      ).copy(issueKey = manifest.issueKey)
    val workflows = InMemoryWorkflowStates()
    workflows.saveFeatureTaskWorkflow(parent, PROSE)
    val artifactsBefore = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-malformed-parent")).artifactsJson

    listOf<Any>(1.5, 2147483648L, Double.NaN, Double.POSITIVE_INFINITY, 0, -1, "1").forEach { identity ->
      val controls = RecordingGoalRunnerControlRepository()
      val malformed =
        parent.toSnapshot().let { snapshot ->
          snapshot.copy(
            artifacts =
              DurableWorkflowArtifacts.fromMap(
                snapshot.artifacts + ("goal_out_of_band_acceptances" to listOf(legacyAcceptance(identity))),
              ),
          )
        }

      assertFailsWith<InvalidWorkflowStateSchemaError>("subtask_id $identity must be rejected.") {
        FakeDatabaseSessionFactory(workflows, goalRunnerControls = controls).transaction { unitOfWork ->
          testWorkflowEngine.persistParentDecompositionRuntime(
            malformed,
            manifest,
            unitOfWork,
            testDecompositionManifestValidator,
          )
        }
      }

      assertNull(controls.reviewPolicy("wfl-malformed-parent"))
      assertEquals(emptyMap(), controls.outOfBandAcceptances("wfl-malformed-parent"))
      val persisted = requireNotNull(workflows.getFeatureTaskWorkflow("wfl-malformed-parent"))
      assertEquals(artifactsBefore, persisted.artifactsJson)
      val persistedKeys = decodeWorkflowArtifactsForTest(persisted.artifactsJson).keys
      assertTrue("goal_review_policy" in persistedKeys && "goal_out_of_band_acceptances" in persistedKeys)
      assertFalse(DECOMPOSITION_RUNTIME_ARTIFACT_KEY in persistedKeys)
    }
  }
}

private fun legacyAcceptance(subtaskId: Any): Map<String, Any?> =
  mapOf(
    "subtask_id" to subtaskId,
    "commit_sha" to "abc1234",
    "reason" to "implemented outside the runtime",
    "accepted_at" to "2026-08-01T12:00:00Z",
  )

private const val COMPACT_UPDATE_ACK_PAYLOAD_BYTE_CEILING = 1024

private fun decodeWorkflowStepsForTest(steps: List<WorkflowStepState>): Map<String, String> =
  steps.associate { step -> step.stepId to step.status.wireValue }

private fun progressEventRequest(
  workflowId: String,
  tick: Int,
  issueKey: String = "SKILL-64",
): GoalRunnerProgressEventRecordRequest =
  GoalRunnerProgressEventRecordRequest(
    workflowId = workflowId,
    issueKey = issueKey,
    draft =
      GoalProgressEventDraft(
        eventKind = GoalProgressEventKind.PHASE_STARTED,
        workflowId = workflowId,
        workflowPhase = "implement",
        processAlive = true,
        timestamp = Instant.parse("2026-06-02T10:00:0${tick % 10}Z"),
      ),
  )

private fun attemptLedgerRequest(
  workflowId: String,
  tick: Int,
  issueKey: String = "SKILL-64",
): GoalRunnerAttemptLedgerRecordRequest =
  GoalRunnerAttemptLedgerRecordRequest(
    workflowId = workflowId,
    issueKey = issueKey,
    draft =
      GoalAttemptLedgerEntryDraft(
        action = GoalAttemptLedgerAction.CHILD_ACTIVATION,
        timestamp = Instant.parse("2026-06-02T10:00:0${tick % 10}Z"),
      ),
  )

private fun assertProgressEventAcknowledgement(ok: WorkflowUpdateResult.Ok) {
  assertEquals("running", ok.acknowledgement.workflowStatus.wireValue)
  assertEquals("implement", ok.acknowledgement.currentStepId)
  assertEquals(listOf("implement"), ok.acknowledgement.updatedStepIds)
  assertEquals(
    listOf(
      "goal_continuation",
      "goal_observability_latest_event",
      "goal_observability_run_history",
      "progress_event",
    ),
    ok.acknowledgement.updatedArtifactKeys,
  )
}

private fun assertPersistedProgressEventArtifacts(
  persisted: WorkflowGetResult.Ok,
  workflowId: String,
) {
  val latest = persisted.snapshot.artifacts["goal_observability_latest_event"] as Map<*, *>
  val history = persisted.snapshot.artifacts["goal_observability_run_history"] as List<*>
  assertEquals("SKILL-61", latest["issue_key"])
  assertEquals(1, latest["subtask_id"])
  assertEquals("implement", latest["workflow_phase"])
  assertEquals("phase_subagent", latest["worker_role"])
  assertEquals("durable_progress", latest["liveness_class"])
  assertEquals("editing runtime files", latest["activity_summary"])
  assertEquals(workflowId, latest["workflow_id"])
  assertEquals(0, latest["sequence_number"])
  assertEquals(mapOf("files_changed" to 0, "insertions" to 0, "deletions" to 0), latest["diff_stat"])
  assertEquals(1, history.size)
  assertTrue(persisted.snapshot.artifacts.containsKey("progress_event"))
}

private val testWorkflowEngine: WorkflowEngine = WorkflowEngine()

private val testFeatureTaskRuntimeWireArtifactValidator: FeatureTaskRuntimeWireArtifactValidator =
  object : FeatureTaskRuntimeWireArtifactValidator {
    override fun validate(
      kind: FeatureTaskRuntimeWireArtifactKind,
      payload: FeatureTaskRuntimeWorkflowArtifactMap,
      sourceLabel: String,
    ) = Unit
  }

private fun workflowRecord(
  workflowId: String,
  artifactsPatch: WorkflowArtifactPatch?,
  workflowStatus: String = "running",
): WorkflowStateRecord {
  val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
  val opened = testWorkflowEngine.openRecord(definition, workflowId, "ftr-001", "preplan")
  return testWorkflowEngine.updateRecord(
    definition,
    opened,
    WorkflowUpdateInput(
      terminalInstant = Instant.EPOCH,
      workflowStatus =
        WorkflowStatus.fromWire(workflowStatus)
          ?: error("Unknown workflow status '$workflowStatus'."),
      currentStepId = "plan",
      stepUpdates = null,
      artifactsPatch = artifactsPatch,
      sessionId = "ftr-001",
    ),
  ).toRecord()
}

private fun workflowRecord(
  workflowId: String,
  artifactsPatch: Map<String, Any?>,
  workflowStatus: String = "running",
): WorkflowStateRecord =
  workflowRecord(
    workflowId,
    WorkflowArtifactPatch.from(artifactsPatch),
    workflowStatus,
  )

private fun workflowRecord(
  workflowId: String,
  artifactsPatch: WorkflowArtifactPatch?,
  workflowStatus: WorkflowStatus,
): WorkflowStateRecord = workflowRecord(workflowId, artifactsPatch, workflowStatus.wireValue)

private const val LEGACY_CONTRACT_MANIFEST_YAML: String = """
---
contract_version: "0.4"
issue_key: "SKILL-80"
current_subtask_intent:
  subtask_id: null
  action: "complete"
subtasks:
  - id: 1
    status: "completed"
---
"""

private fun manifestStore(rejecting: Set<String>) =
  testWorkflowGoalRunnerManifestStore(
    database = FakeDatabaseSessionFactory(InMemoryWorkflowStates()),
    decompositionManifestValidator = rejectingDecompositionManifestValidator(rejecting),
    decompositionManifestStore = TestDecompositionManifestStore,
    clock = Clock.systemUTC(),
  )

private fun rejectingDecompositionManifestValidator(rejectedSources: Set<String>): DecompositionManifestValidator =
  object : DecompositionManifestValidator by testDecompositionManifestValidator {
    override fun validateYamlText(
      yamlText: String,
      sourceLabel: String,
    ): DecompositionManifest {
      if (sourceLabel in rejectedSources) {
        throw InvalidDecompositionManifestSchemaError(sourceLabel, "contract_version: must be '0.5'")
      }
      return testDecompositionManifestValidator.validateYamlText(yamlText, sourceLabel)
    }
  }

private fun scopedReplanStore(
  workflows: RecordingGoalChildDeletionWorkflowStates,
  manifest: DecompositionManifest,
): GoalRunnerManifestStore {
  workflows.saveFeatureTaskWorkflow(
    workflowRecord(
      workflowId = "wfl-parent",
      artifactsPatch =
        WorkflowArtifactPatch.from(
          mapOf(
            "plan" to mapOf("mode" to "decompose"),
            DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
              testDecompositionManifestValidator.encodeManifestWireMap(manifest),
          ),
        ),
    ),
    RUNTIME,
  )
  return testWorkflowGoalRunnerManifestStore(
    database = FakeDatabaseSessionFactory(workflows),
    decompositionManifestStore = TestDecompositionManifestStore,
    clock = Clock.systemUTC(),
  )
}

private fun scopedReplanState(
  manifest: DecompositionManifest,
  tempPrefix: String,
) = GoalRunnerManifestState(
  "wfl-parent",
  "/fake/metrics.db",
  manifest,
  repoRoot = Files.createTempDirectory(tempPrefix),
)

private fun decompositionRuntime(status: String): DecompositionManifest =
  DecompositionManifest(
    issueKey = "SKILL-52.1",
    featureName = "install-policy-extraction",
    parentSpecPath = ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/spec_subtask_3_install-policy.md",
    status = status,
    executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
    baseBranch = "main",
    featureBranch = "feat/SKILL-52.1-hexagonal-runtime-hardening",
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 1, action = "resume"),
    subtasks =
      listOf(
        DecompositionSubtask(
          id = 1,
          name = "install-policy-foundation",
          specPath = ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/install-policy/spec_subtask_1.md",
          status = status,
          workflowId = "wfl-child",
        ),
      ),
  )

private fun completeDecompositionRuntime(): DecompositionManifest =
  decompositionRuntime(status = "complete").copy(
    currentSubtaskIntent = CurrentSubtaskIntent(subtaskId = 0, action = "complete"),
    subtasks =
      listOf(
        DecompositionSubtask(
          id = 1,
          name = "install-policy-foundation",
          specPath = ".feature-specs/SKILL-52.1-hexagonal-runtime-hardening/install-policy/spec_subtask_1.md",
          status = "complete",
          workflowId = "wfl-child",
          commitSha = "sha-complete",
          lastResumableStep = "commit_push",
        ),
      ),
  )

class GoalChildPlanningHydrationTransactionIntegrationTest {
  @Test
  fun `atomic hydration is singular across duplicate resume and preserves imported outputs for audit reuse`() {
    val harness = hydrationHarness()

    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    val first = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    val resumed = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))

    assertEquals(first, resumed, "duplicate resume must be a byte-identical no-op")
    assertEquals("implement", resumed.currentStepId)
    assertContains(resumed.artifactsJson, "shared preplan prose for hydration")
    assertContains(resumed.artifactsJson, "owned-plan-one")
    assertEquals(2, Regex("goal-planning-import").findAll(resumed.artifactsJson).count())
    assertEquals(2, Regex("\\\"action\\\":\\\"complete\\\"").findAll(resumed.artifactsJson).count())
  }

  @Test
  fun `hydrated preplan and plan record goal-planning-hydrated provenance at attempt 1`() {
    val harness = hydrationHarness()

    harness.store.saveNewChildWorkflow(harness.state, harness.setup)

    val child = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    listOf("preplan", "plan").forEach { phaseId ->
      assertContains(child.artifactsJson, """"phase_id":"$phaseId"""")
      assertContains(child.stepsJson, """"step_id":"$phaseId","status":"completed","attempt_count":1""")
    }
    assertEquals(
      4,
      Regex(""""execution_origin":"goal-planning-hydrated"""").findAll(child.artifactsJson).count(),
      "both planning phase records and both ledger prefix entries carry the hydrated origin",
    )
  }

  @Test
  fun `hydration committed before implementation resumes directly at implement`() {
    val harness = hydrationHarness()
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)

    val recoveredStore = harness.newStore()
    recoveredStore.saveNewChildWorkflow(harness.state, harness.setup)

    val child = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    assertEquals("implement", child.currentStepId)
    assertContains(child.stepsJson, "\"step_id\":\"preplan\",\"status\":\"completed\"")
    assertContains(child.stepsJson, "\"step_id\":\"plan\",\"status\":\"completed\"")
  }

  @Test
  fun `siblings share preplan but hydrate only their owned plans`() {
    val harness = hydrationHarness(twoSubtasks = true)
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    harness.store.saveNewChildWorkflow(harness.state, harness.setupFor(2))

    val first = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME)).artifactsJson
    val second = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode("wfl-child-2", RUNTIME)).artifactsJson
    assertContains(first, "shared preplan prose for hydration")
    assertContains(second, "shared preplan prose for hydration")
    assertContains(first, "owned-plan-one")
    assertFalse(first.contains("owned-plan-two"))
    assertContains(second, "owned-plan-two")
    assertFalse(second.contains("owned-plan-one"))
  }

  @Test
  fun `missing corrupt and conflicting preparation fail before a child is durable`() {
    listOf("missing", "corrupt", "conflict").forEach { variant ->
      val harness = hydrationHarness(variant = variant)
      assertFailsWith<RuntimeException>(variant) {
        harness.store.saveNewChildWorkflow(harness.state, harness.setup)
      }
      assertNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME), variant)
      assertNull(harness.workflows.executionIdentity(CHILD_ID), variant)
    }
  }

  @Test
  fun `hydrating a projection-invalid stored plan loud-fails before any child artifact is written`() {
    val harness = hydrationHarness(variant = "projection_invalid")

    val error =
      assertFailsWith<InvalidFeatureTaskRuntimePhaseOutputSchemaError> {
        harness.store.saveNewChildWorkflow(harness.state, harness.setup)
      }

    assertContains(error.reason, "value")
    assertNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    assertNull(harness.workflows.executionIdentity(CHILD_ID))
  }

  @Test
  fun `resume accepts a child whose imported plan phase was repaired by its own fix loop`() {
    val harness = hydrationHarness()
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    harness.workflows.saveFeatureTaskWorkflow(repairedPlanPhase(harness), RUNTIME)

    harness.store.saveNewChildWorkflow(harness.state, harness.setup)

    val resumed = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    assertContains(resumed.artifactsJson, "repaired-plan-one")
    assertEquals("implement", resumed.currentStepId)
  }

  @Test
  fun `regenerated parent planning does not block resume or replace child outputs`() {
    val harness = hydrationHarness()
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    val imported = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    val shared = requireNotNull(harness.preparations.shared)
    val preplanPayload = shared.preplanPayload.replace("shared preplan prose", "updated preplan prose")
    harness.preparations.shared =
      shared.copy(
        preplanPayload = preplanPayload,
        payloadSha256 = sha256HexUtf8(preplanPayload),
      )
    val plan = requireNotNull(harness.preparations.plans[1])
    val planPayload = planPayload("updated-plan-one")
    harness.preparations.plans[1] =
      plan.copy(
        planPayload = planPayload,
        payloadSha256 = sha256HexUtf8(planPayload),
      )

    harness.newStore().saveNewChildWorkflow(harness.state, harness.setup)

    assertEquals(imported, harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
  }

  @Test
  fun `spec edits do not block child hydration or rewrite imported planning on resume`() {
    val harness = hydrationHarness()
    val original = requireNotNull(harness.setup.planningHydration)
    val changed =
      harness.setup.copy(
        planningHydration =
          original.copy(
            provenance = original.provenance.copy(parentSpecHash = "e".repeat(64)),
            descriptor = original.descriptor.copy(subSpecHash = "f".repeat(64)),
          ),
      )

    harness.store.saveNewChildWorkflow(harness.state, changed)
    val imported = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))

    harness.newStore().saveNewChildWorkflow(harness.state, harness.setup)

    val resumed = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    assertEquals(imported.artifactsJson, resumed.artifactsJson)
    assertEquals("implement", resumed.currentStepId)
  }

  @Test
  fun `resume rejects a child whose import provenance no longer matches and names the divergence`() {
    val harness = hydrationHarness()
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    val child = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    val artifacts =
      JsonCodec.parseObjectOrNull(child.artifactsJson)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        .orEmpty()
        .toMutableMap()
    val importArtifact =
      (artifacts["goal_planning_import"] as Map<*, *>)
        .entries
        .associate { (key, value) -> key.toString() to value }
        .toMutableMap()
    importArtifact["parent_goal_workflow_id"] = "wfl-some-other-parent"
    artifacts["goal_planning_import"] = importArtifact
    harness.workflows.saveFeatureTaskWorkflow(
      child.copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)),
      RUNTIME,
    )

    val error =
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        harness.store.saveNewChildWorkflow(harness.state, harness.setup)
      }

    assertContains(error.message.orEmpty(), "stored import provenance differs from the hydration request")
  }

  @Test
  fun `resume rejects a child that carries no goal planning import artifact`() {
    val harness = hydrationHarness()
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    val child = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    val artifacts = parseChildArtifacts(child)
    artifacts.remove("goal_planning_import")
    harness.workflows.saveFeatureTaskWorkflow(
      child.copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)),
      RUNTIME,
    )

    val error =
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        harness.store.saveNewChildWorkflow(harness.state, harness.setup)
      }

    assertContains(error.message.orEmpty(), "child carries no goal planning import artifact")
  }

  @Test
  fun `resume rejects a child when parent planning checkpoints are no longer available`() {
    val harness = hydrationHarness()
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)

    harness.preparations.shared = null

    val error =
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        harness.store.saveNewChildWorkflow(harness.state, harness.setup)
      }

    assertContains(
      error.message.orEmpty(),
      "parent planning checkpoints are missing or have incompatible provenance",
    )
  }

  @Test
  fun `resume rejects a child whose planning ledger prefix no longer matches the import`() {
    val harness = hydrationHarness()
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    val child = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    val artifacts = parseChildArtifacts(child)
    val ledger = (artifacts["feature_task_runtime_phase_ledger"] as List<*>).toMutableList()
    val firstEntry = (ledger[0] as Map<*, *>).toMutableMap()
    firstEntry["action"] = "retry"
    ledger[0] = firstEntry
    artifacts["feature_task_runtime_phase_ledger"] = ledger
    harness.workflows.saveFeatureTaskWorkflow(
      child.copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)),
      RUNTIME,
    )

    val error =
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        harness.store.saveNewChildWorkflow(harness.state, harness.setup)
      }

    assertContains(error.message.orEmpty(), "phase ledger no longer opens with the goal planning import prefix")
  }

  @Test
  fun `resume rejects a child whose completed plan phase is missing its output artifact`() {
    val harness = hydrationHarness()
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    val child = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    val artifacts = mutatePhaseRecord(child, "plan") { it.also { m -> m.remove("output_artifact") } }
    harness.workflows.saveFeatureTaskWorkflow(
      child.copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)),
      RUNTIME,
    )

    val error =
      assertFailsWith<IncompatibleGoalPlanningPreparationRecoveryError> {
        harness.store.saveNewChildWorkflow(harness.state, harness.setup)
      }

    assertContains(error.message.orEmpty(), "child planning phases are not settled as completed")
  }

  @Test
  fun `resume accepts a child whose plan phase is in the fix loop with output moved to rejected`() {
    val harness = hydrationHarness()
    harness.store.saveNewChildWorkflow(harness.state, harness.setup)
    val child = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    val artifacts =
      mutatePhaseRecord(child, "plan") { planRecord ->
        val output = planRecord.remove("output_artifact")
        planRecord["rejected_output"] = output
        planRecord["status"] = "running"
        planRecord["finished_at"] = null
        planRecord
      }

    val quarantinedStepsJson =
      child.stepsJson.replace(
        "\"step_id\":\"plan\",\"status\":\"completed\"",
        "\"step_id\":\"plan\",\"status\":\"running\"",
      )
    assertNotEquals(child.stepsJson, quarantinedStepsJson)
    harness.workflows.saveFeatureTaskWorkflow(
      child.copy(
        artifactsJson = JsonCodec.mapToJsonString(artifacts),
        stepsJson = quarantinedStepsJson,
      ),
      RUNTIME,
    )

    harness.store.saveNewChildWorkflow(harness.state, harness.setup)

    val resumed = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    assertEquals("implement", resumed.currentStepId)
  }

  private fun parseChildArtifacts(child: WorkflowStateRecord): MutableMap<String, Any?> =
    JsonCodec.parseObjectOrNull(child.artifactsJson)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      .orEmpty()
      .toMutableMap()

  private fun mutatePhaseRecord(
    child: WorkflowStateRecord,
    phaseId: String,
    mutate: (MutableMap<String, Any?>) -> MutableMap<String, Any?>,
  ): MutableMap<String, Any?> {
    val artifacts = parseChildArtifacts(child)
    val records =
      (artifacts["feature_task_runtime_phase_records"] as Map<*, *>)
        .entries.associate { (k, v) -> k.toString() to v }.toMutableMap()
    val phaseRecord =
      (records[phaseId] as Map<*, *>)
        .entries.associate { (k, v) -> k.toString() to v }.toMutableMap()
    records[phaseId] = mutate(phaseRecord)
    artifacts["feature_task_runtime_phase_records"] = records
    return artifacts
  }

  private fun repairedPlanPhase(harness: HydrationHarness): WorkflowStateRecord {
    val child = requireNotNull(harness.workflows.getFeatureTaskWorkflowAsMode(CHILD_ID, RUNTIME))
    val artifacts =
      JsonCodec.parseObjectOrNull(child.artifactsJson)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        .orEmpty()
        .toMutableMap()
    val records =
      (artifacts["feature_task_runtime_phase_records"] as Map<*, *>)
        .entries
        .associate { (key, value) -> key.toString() to value }
        .toMutableMap()
    val planRecord =
      (records["plan"] as Map<*, *>)
        .entries
        .associate { (key, value) -> key.toString() to value }
        .toMutableMap()
    planRecord["attempt_count"] = 3
    planRecord["resolved_agent_id"] = "claude"
    planRecord["duration_millis"] = 4200
    planRecord["output_artifact"] = PLAN_ONE_PAYLOAD.replace("owned-plan-one", "repaired-plan-one")
    records["plan"] = planRecord
    artifacts["feature_task_runtime_phase_records"] = records
    return child.copy(
      artifactsJson = JsonCodec.mapToJsonString(artifacts),
      stepsJson =
        child.stepsJson.replace(
          "\"step_id\":\"plan\",\"status\":\"completed\",\"attempt_count\":1",
          "\"step_id\":\"plan\",\"status\":\"completed\",\"attempt_count\":3",
        ),
    )
  }

  @Test
  fun `standalone open never reads goal preparation at the hydration boundary`() {
    val preparations = RecordingPlanningPreparations(errorOnRead = true)
    val workflows = InMemoryWorkflowStates()
    val service =
      WorkflowService(
        database = FakeDatabaseSessionFactory(workflows, planningPreparations = preparations),
        gitOperations = NoopWorkflowGitOperations,
        decompositionManifestStore = UnavailableDecompositionManifestStore,
        workflowSnapshotValidator = testWorkflowSnapshotValidator,
        decompositionManifestValidator = testDecompositionManifestValidator,
        decompositionManifestWriter = testDecompositionManifestWriter,
        repositoryRoot = testRepositoryRoot,
        goalObservabilityEventValidator = AcceptingFeatureTaskRuntimeWireArtifactValidator,
        runtimeDiagnostics = NoopRuntimeDiagnostics,
        clock = Clock.systemUTC(),
      )

    val opened =
      service.openFeatureTask(
        WorkflowServiceOpenFeatureTaskArgs(
          executionPlan = testExecutionPlan(),
          kind = WorkflowFamilyKind.TASK_RUNTIME,
          issueKey = "SKILL-128",
          repositoryIdentity = REPOSITORY_IDENTITY,
          governedSpecPath = ".feature-specs/SKILL-128/spec.md",
        ),
      )

    assertIs<WorkflowOpenResult.Ok>(opened)
    assertEquals(0, preparations.readCount)
    assertEquals("preplan", opened.snapshot.currentStepId)
  }

  @Test
  fun `a boundary pause stamps the pause timestamp from the injected clock`() {
    val harness = hydrationHarness()
    val store = harness.newClockedStore()

    store.requestPause("goal-parent")
    val paused = store.pauseAtBoundary(harness.state).controlState

    assertTrue(paused.paused)
    assertEquals(PAUSE_CLOCK_INSTANT, paused.pausedAt)
  }

  @Test
  fun `a second boundary pause does not move the original pause timestamp`() {
    val harness = hydrationHarness()
    val store = harness.newClockedStore()

    store.requestPause("goal-parent")
    store.pauseAtBoundary(harness.state)
    val laterStore = harness.newClockedStore(LATER_PAUSE_CLOCK_INSTANT)

    assertEquals(PAUSE_CLOCK_INSTANT, laterStore.pauseAtBoundary(harness.state).controlState.pausedAt)
  }

  @Test
  fun `reaching a stop-after target stamps the pause timestamp too`() {
    val harness = hydrationHarness()
    val store = harness.newClockedStore()

    store.persistStopAfterSubtask("goal-parent", 1)
    val result = store.saveCompletedSubtaskAtBoundary(harness.state, 1)

    assertTrue(result.paused)
    assertEquals("stop_after_subtask", result.state.controlState.pauseReason)
    assertEquals(PAUSE_CLOCK_INSTANT, result.state.controlState.pausedAt)
  }

  @Test
  fun `resume clears the pause timestamp along with the reason`() {
    val harness = hydrationHarness()
    val store = harness.newClockedStore()
    store.requestPause("goal-parent")
    store.pauseAtBoundary(harness.state)

    val resumed = requireNotNull(store.resume("goal-parent")).controlState

    assertEquals(null, resumed.pausedAt)
    assertEquals(null, resumed.pauseReason)
    assertEquals(false, resumed.paused)
  }

  @Test
  fun `pauseNow writes the operator stop straight through in one transaction`() {
    val harness = hydrationHarness()
    val store = harness.newClockedStore()

    val control =
      requireNotNull(
        store.pauseNow("goal-parent", "operator_stop", PAUSE_CLOCK_INSTANT, overwriteExistingReason = true),
      )

    assertTrue(control.paused)
    assertTrue(control.pauseRequested)
    assertTrue(control.pauseConsumed)
    assertEquals("operator_stop", control.pauseReason)
    assertEquals(PAUSE_CLOCK_INSTANT, control.pausedAt)
    assertTrue(control.requiresPauseBoundary(harness.manifest))
  }

  @Test
  fun `pauseNow defers to an existing reason when it must not overwrite`() {
    val harness = hydrationHarness()
    val store = harness.newClockedStore()
    store.pauseNow("goal-parent", "operator_stop", PAUSE_CLOCK_INSTANT, overwriteExistingReason = true)

    val control =
      requireNotNull(
        store.pauseNow("goal-parent", "runner_interrupted", LATER_PAUSE_CLOCK_INSTANT),
      )

    assertEquals("operator_stop", control.pauseReason)
    assertEquals(PAUSE_CLOCK_INSTANT, control.pausedAt)
  }

  @Test
  fun `pauseNow reports no goal for an unknown parent workflow`() {
    assertEquals(
      null,
      hydrationHarness().newClockedStore().pauseNow("missing-parent", "operator_stop", PAUSE_CLOCK_INSTANT),
    )
  }

  private fun hydrationHarness(
    twoSubtasks: Boolean = false,
    variant: String = "valid",
  ): HydrationHarness {
    val workflows = InMemoryWorkflowStates()
    val manifest = hydrationManifest(twoSubtasks)
    workflows.saveFeatureTaskWorkflow(
      workflowRecord(
        "goal-parent",
        mapOf(
          DECOMPOSITION_RUNTIME_ARTIFACT_KEY to
            testDecompositionManifestValidator.encodeManifestWireMap(manifest),
        ),
      ),
      RUNTIME,
    )
    val preparations = RecordingPlanningPreparations()
    if (variant != "missing") preparations.shared = sharedCheckpoint()
    preparations.plans[1] =
      planCheckpoint(1).let {
        when (variant) {
          "corrupt" -> it.copy(planPayload = it.planPayload + "corrupt")
          "conflict" -> it.copy(provenance = it.provenance.copy(planningContractId = "different-planning-contract"))
          "projection_invalid" ->
            it.copy(
              planPayload = EMPTY_VALUE_PLAN_PAYLOAD,
              payloadSha256 = sha256HexUtf8(EMPTY_VALUE_PLAN_PAYLOAD),
            )
          else -> it
        }
      }
    if (twoSubtasks) preparations.plans[2] = planCheckpoint(2)
    return HydrationHarness(workflows, preparations, manifest)
  }

  private data class HydrationHarness(
    val workflows: InMemoryWorkflowStates,
    val preparations: RecordingPlanningPreparations,
    val manifest: DecompositionManifest,
  ) {
    val state = GoalRunnerManifestState("goal-parent", "/fake/metrics.db", manifest)

    val controls = RecordingGoalRunnerControlRepository()
    val store = newStore()
    val setup = setupFor(1)

    fun newClockedStore(instant: String = PAUSE_CLOCK_INSTANT) =
      testWorkflowGoalRunnerManifestStore(
        database =
          FakeDatabaseSessionFactory(
            workflows,
            planningPreparations = preparations,
            goalRunnerControls = controls,
          ),
        decompositionManifestStore = NoWriteDecompositionManifestStore,
        clock = Clock.fixed(Instant.parse(instant), UTC),
      )

    fun newStore() =
      testWorkflowGoalRunnerManifestStore(
        database =
          FakeDatabaseSessionFactory(
            workflows,
            planningPreparations = preparations,
            goalRunnerControls = controls,
          ),
        decompositionManifestStore = NoWriteDecompositionManifestStore,
        clock = Clock.systemUTC(),
      )

    fun setupFor(id: Int): GoalRunnerChildWorkflowSetup {
      val descriptor = descriptor(id)
      return GoalRunnerChildWorkflowSetup(
        executionPlan = testExecutionPlan(SkeletonDefinition.GOAL_CHILD),
        subtaskId = id,
        workflowId = if (id == 1) CHILD_ID else "wfl-child-$id",
        goalBranch = "feat/SKILL-128",
        normalizedIssueKey = "SKILL-128",
        repositoryIdentity = REPOSITORY_IDENTITY,
        governedSpecPath = descriptor.governedSubSpecPath,
        reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
        reviewPolicy = GoalRunnerReviewPolicy(CodeReviewExecutionMode.INLINE),
        planningHydration = GoalChildPlanningHydrationRequest(identity(), provenance(), descriptor),
      )
    }
  }

  private companion object {
    val NoWriteDecompositionManifestStore =
      object :
        DecompositionManifestStore by TestDecompositionManifestStore {
        override fun writeTextAtomically(
          target: Path,
          content: String,
        ) = Unit
      }

    const val CHILD_ID = "wfl-child-1"
    const val PAUSE_CLOCK_INSTANT = "2026-08-07T12:00:00Z"
    const val LATER_PAUSE_CLOCK_INSTANT = "2026-08-07T13:00:00Z"
    val REPOSITORY_IDENTITY = "repo-root-realpath-v1:${Path.of("").toAbsolutePath().normalize()}"

    val PREPLAN_PAYLOAD =
      """
      {
        "contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id":"preplan","status":"completed","summary":"shared",
        "produced_outputs":{"value":"shared preplan prose for hydration"}
      }
      """.trimIndent()

    val EMPTY_VALUE_PLAN_PAYLOAD =
      """
      {
        "contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id":"plan","status":"completed","summary":"no value",
        "produced_outputs":{"prompt":"optional only"}
      }
      """.trimIndent()
    val PLAN_ONE_PAYLOAD = planPayload("owned-plan-one")
    val PLAN_TWO_PAYLOAD = planPayload("owned-plan-two")

    fun planPayload(description: String): String =
      """
      {
        "contract_version":"$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
        "phase_id":"plan","status":"completed","summary":"$description",
        "produced_outputs":{"value":"$description prose for downstream implement."}
      }
      """.trimIndent()

    fun identity() = GoalPlanningIdentity("goal-parent", "SKILL-128", REPOSITORY_IDENTITY)

    fun provenance() =
      GoalPlanningContractProvenance(
        parentSpecHash = "a".repeat(64),
        decompositionManifestHash = "b".repeat(64),
        planningContractId = "https://skill-bill.dev/contracts/goal-planning-preparation-schema.yaml",
      )

    fun descriptor(id: Int) =
      GovernedGoalSubtaskDescriptor(
        id,
        id - 1,
        ".feature-specs/SKILL-128/spec_subtask_$id.md",
        id.toString().repeat(64),
      )

    fun sharedCheckpoint() =
      SharedGoalPreplanCheckpoint(
        identity = identity(),
        provenance = provenance(),
        payloadSha256 = sha256HexUtf8(PREPLAN_PAYLOAD),
        preplanPayload = PREPLAN_PAYLOAD,
      )

    fun planCheckpoint(id: Int): GoalSubtaskPlanCheckpoint {
      val payload = if (id == 1) PLAN_ONE_PAYLOAD else PLAN_TWO_PAYLOAD
      val descriptor = descriptor(id)
      return GoalSubtaskPlanCheckpoint(
        identity = identity(),
        subtaskId = id,
        manifestOrder = descriptor.manifestOrder,
        governedSubSpecPath = descriptor.governedSubSpecPath,
        subSpecHash = descriptor.subSpecHash,
        provenance = provenance(),
        payloadSha256 = sha256HexUtf8(payload),
        planPayload = payload,
      )
    }

    fun hydrationManifest(twoSubtasks: Boolean) =
      DecompositionManifest(
        issueKey = "SKILL-128", featureName = "hydration", parentSpecPath = ".feature-specs/SKILL-128/spec.md",
        status = "pending", executionModel = DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK,
        baseBranch = "main", featureBranch = "feat/SKILL-128",
        currentSubtaskIntent = CurrentSubtaskIntent(1, "resume"),
        subtasks =
          (1..if (twoSubtasks) 2 else 1).map { id ->
            DecompositionSubtask(id, "subtask-$id", descriptor(id).governedSubSpecPath, "pending")
          },
      )
  }
}

private class RecordingPlanningPreparations(
  private val errorOnRead: Boolean = false,
) : GoalPlanningPreparationRepositoryDefaults() {
  var shared: SharedGoalPreplanCheckpoint? = null
  val plans = mutableMapOf<Int, GoalSubtaskPlanCheckpoint>()
  var readCount = 0

  override fun checkpointSharedPreplan(checkpoint: SharedGoalPreplanCheckpoint) {
    shared = checkpoint
  }

  override fun findSharedPreplan(expectedIdentity: GoalPlanningIdentity): SharedGoalPreplanCheckpoint? {
    readCount++
    check(!errorOnRead) { "standalone path read goal preparation" }
    return shared?.takeIf { it.identity == expectedIdentity }
  }

  override fun checkpointSubtaskPlan(checkpoint: GoalSubtaskPlanCheckpoint) {
    plans[checkpoint.subtaskId] = checkpoint
  }

  override fun findSubtaskPlan(
    expectedIdentity: GoalPlanningIdentity,
    subtaskId: Int,
    governedSubSpecPath: String,
  ) = plans[subtaskId]?.takeIf { it.identity == expectedIdentity && it.governedSubSpecPath == governedSubSpecPath }

  override fun listSubtaskPlansOrdered(
    expectedIdentity: GoalPlanningIdentity,
    orderedDescriptors: List<GovernedGoalSubtaskDescriptor>,
  ) = orderedDescriptors.mapNotNull { findSubtaskPlan(expectedIdentity, it.subtaskId, it.governedSubSpecPath) }

  override fun markPrepared(record: GoalPlanningPreparationRecord) = Unit

  override fun deleteByGoal(parentGoalWorkflowId: String) = 0

  override fun listPreparedPlanSubtaskIds(parentGoalWorkflowId: String): List<Int> = plans.keys.sorted()

  override fun hasPreparedSharedPreplan(parentGoalWorkflowId: String): Boolean = shared != null

  override fun sharedPreplanPayloadSha256(parentGoalWorkflowId: String): String? = shared?.payloadSha256
}

private class RecordingGoalRunnerControlRepository : GoalRunnerControlRepository {
  private val policies = mutableMapOf<String, GoalRunnerReviewPolicy>()
  private val acceptances = mutableMapOf<String, MutableMap<Int, GoalRunnerOutOfBandAcceptance>>()
  private val controlStates = mutableMapOf<String, GoalRunnerControlState>()

  override fun controlState(parentWorkflowId: String): GoalRunnerControlState =
    controlStates[parentWorkflowId] ?: GoalRunnerControlState()

  override fun persistControlState(
    parentWorkflowId: String,
    state: GoalRunnerControlState,
  ): GoalRunnerControlState {
    controlStates[parentWorkflowId] = state
    return state
  }

  override fun clearControlState(parentWorkflowId: String) {
    controlStates.remove(parentWorkflowId)
  }

  override fun reviewPolicy(parentWorkflowId: String): GoalRunnerReviewPolicy? = policies[parentWorkflowId]

  override fun persistReviewPolicy(
    parentWorkflowId: String,
    policy: GoalRunnerReviewPolicy,
  ): GoalRunnerReviewPolicy {
    policies[parentWorkflowId] = policy
    return policy
  }

  override fun outOfBandAcceptances(parentWorkflowId: String): Map<Int, GoalRunnerOutOfBandAcceptance> =
    acceptances[parentWorkflowId].orEmpty()

  override fun persistOutOfBandAcceptance(
    parentWorkflowId: String,
    acceptance: GoalRunnerOutOfBandAcceptance,
  ): GoalRunnerOutOfBandAcceptance {
    acceptances.getOrPut(parentWorkflowId, ::mutableMapOf)[acceptance.subtaskId] = acceptance
    return acceptance
  }

  override fun clearOutOfBandAcceptances(parentWorkflowId: String) {
    acceptances.remove(parentWorkflowId)
  }

  override fun clearRunnerInterruptedPause(parentWorkflowId: String): GoalRunnerControlState =
    controlStates[parentWorkflowId] ?: GoalRunnerControlState()
}

internal class RecordingGoalChildDeletionWorkflowStates(
  private val childStatus: String = "blocked",
  delegate: InMemoryWorkflowStates = InMemoryWorkflowStates(),
) : WorkflowStateRepository by delegate {
  val scopedDeletions = mutableListOf<Triple<String, Int, String>>()

  override fun deleteGoalChildWorkflow(
    parentWorkflowId: String,
    subtaskId: Int,
    workflowId: String,
    scope: GoalChildWorkflowDeletionScope,
  ): Int {
    scopedDeletions += Triple(parentWorkflowId, subtaskId, workflowId)
    return if (WorkflowStatus.fromWire(childStatus) in scope.deletableStatuses) 1 else 0
  }
}
