package skillbill.engine.featuretask.runner

import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.testHarnessClock
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys
import skillbill.engine.featuretask.lifecycle.continuation.agentAttributionFromPhaseState
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeStatusRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseLedgerRequest
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeDecomposeTerminalRecorder
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.runloop.observability.FeatureTaskRuntimeContinuationKind
import skillbill.engine.goalrunner.persist.decodeWorkflowArtifactsForTest
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.goalrunner.EmptyGoalPlanningPreparationRepository
import skillbill.ports.goalrunner.EmptyGoalRunnerControlRepository
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.ports.learning.LearningRepository
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.persistence.UnitOfWorkDefaults
import skillbill.ports.review.repository.ReviewRepository
import skillbill.ports.telemetry.lifecycle.LifecycleTelemetryRepository
import skillbill.ports.telemetry.transport.TelemetryOutboxRepository
import skillbill.ports.telemetry.transport.TelemetryReconciliationRepository
import skillbill.ports.work.EmptyWorkListRepository
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.WorkflowStateRepository
import skillbill.ports.workflow.WorkflowStateRepositoryDefaults
import skillbill.ports.workflow.model.FeatureTaskWorkflowCandidate
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.toWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.audit.FeatureTaskRuntimeDiagnosticSignal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDiagnosticFailureClass
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.BLOCKED
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.COMPLETE
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.RESUME
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction.START
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRunRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS.label()

class FeatureTaskRuntimeStatusServiceTest {
  @Test
  fun `status reads incompatible execution history without admitting or changing the workflow`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordRunInvariants(FeatureTaskRuntimeFeatureSize.MEDIUM)
    harness.recordRunning("implement", attemptCount = 4)
    val execution = ExecutionPlanAdmissionFixture()
    val descriptor =
      execution.descriptor().toMutableMap().apply {
        put(
          FeatureTaskRuntimeExecutionPlanKeys.DEFINITION,
          mapOf(
            FeatureTaskRuntimeExecutionPlanKeys.ID to execution.plan.definitionId,
            FeatureTaskRuntimeExecutionPlanKeys.SEMANTIC_REVISION to 99,
          ),
        )
      }
    val original = requireNotNull(harness.repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME))
    val incompatible =
      original.copy(
        artifactsJson =
          JsonCodec.mapToJsonString(
            original.toSnapshot().artifacts +
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(
                descriptor,
              ),
          ),
      )
    harness.repository.saveFeatureTaskWorkflow(incompatible, RUNTIME)
    val records = harness.recorder.loadPhaseRecords(WORKFLOW_ID)
    val projection = requireNotNull(harness.service.status(FeatureTaskRuntimeStatusRequest(WORKFLOW_ID)))

    assertEquals(4, projection.phases.single { it.phaseId == "implement" }.attemptCount)
    assertEquals("running", projection.phases.single { it.phaseId == "implement" }.status)
    assertEquals(incompatible, harness.repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME))
    assertEquals(records, harness.recorder.loadPhaseRecords(WORKFLOW_ID))
    assertEquals(0, execution.launches)
  }

  @Test
  fun `corrupt artifacts fail at status projection and preserve the stored bytes`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    val row =
      requireNotNull(harness.repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME)).copy(artifactsJson = "{")
    harness.repository.saveFeatureTaskWorkflow(row, RUNTIME)

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID))
    }
    assertEquals(row, harness.repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME))
  }

  @Test
  fun `null projection for an unknown workflow id`() {
    val harness = statusHarness()

    assertNull(harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = "wftr-missing")))
  }

  @Test
  fun `workflow with no phase records projects every phase pending`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordRunInvariants(FeatureTaskRuntimeFeatureSize.LARGE)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals("LARGE", projection.featureSize)
    assertEquals(0, projection.completeCount)
    assertEquals(15, projection.pendingCount)
    assertEquals(0, projection.blockedCount)
    assertEquals("preplan", projection.currentPhaseId)
    assertEquals(List(15) { "pending" }, projection.phases.map { it.status })
  }

  @Test
  fun `the launched model rides the advancing phase write and reaches the phase status`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement",
        status = "running",
        attemptCount = 1,
        resolvedAgentId = "cursor",
        finished = false,
        launchedModel = "claude-opus-4-8[effort=high]",
        launchOutcomeKnown = true,
      ),
    )
    harness.recordRunning("plan", attemptCount = 1)

    val records = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID))
    assertEquals("claude-opus-4-8[effort=high]", records.getValue("implement").launchedModel)
    assertNull(records.getValue("implement").launchedEffort)
    assertNull(records.getValue("plan").launchedModel)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    assertEquals(
      "claude-opus-4-8[effort=high]",
      projection.phases.single { it.phaseId == "implement" }.launchedModel,
    )
  }

  @Test
  fun `a later block write keeps the launched model the phase actually ran with`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement",
        status = "running",
        attemptCount = 1,
        resolvedAgentId = "cursor",
        finished = false,
        launchedModel = "claude-opus-4-8[effort=high]",
        launchOutcomeKnown = true,
      ),
    )
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement",
        status = "blocked",
        attemptCount = 1,
        resolvedAgentId = "cursor",
        finished = true,
        blockedReason = "operator review",
      ),
    )

    val records = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID))
    assertEquals("claude-opus-4-8[effort=high]", records.getValue("implement").launchedModel)
    assertEquals(
      "claude-opus-4-8[effort=high]",
      requireNotNull(harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)))
        .phases.single { it.phaseId == "implement" }.launchedModel,
    )
  }

  @Test
  fun `a settle write that knows no child launched clears the running write's model`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement",
        status = "running",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = false,
        launchedModel = "claude-opus-4-8",
        launchedEffort = "high",
        launchOutcomeKnown = true,
      ),
    )
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement",
        status = "blocked",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = false,
        blockedReason = "the agent process could not be spawned",
        launchOutcomeKnown = true,
      ),
    )

    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).getValue("implement")
    assertNull(record.launchedModel)
    assertNull(record.launchedEffort)
    assertTrue("launched_model" !in record.asWorkflowArtifactEntry().toWorkflowArtifactMap())
  }

  @Test
  fun `a settle that advances the attempt or swaps the agent does not inherit the prior launch pair`() {
    listOf(
      "advanced attempt" to Pair(2, "claude"),
      "swapped agent" to Pair(1, "branch-setup"),
    ).forEach { (case, identity) ->
      val (attemptCount, agentId) = identity
      val harness = statusHarness()
      harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
      harness.recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = WORKFLOW_ID,
          phaseId = "implement",
          status = "running",
          attemptCount = 1,
          resolvedAgentId = "claude",
          finished = false,
          launchedModel = "claude-opus-4-8",
          launchedEffort = "high",
          launchOutcomeKnown = true,
        ),
      )
      harness.recorder.recordPhaseState(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = WORKFLOW_ID,
          phaseId = "implement",
          status = "blocked",
          attemptCount = attemptCount,
          resolvedAgentId = agentId,
          finished = false,
          blockedReason = "settled before any launch",
        ),
      )

      val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).getValue("implement")
      assertNull(record.launchedModel, "$case must not inherit the model")
      assertNull(record.launchedEffort, "$case must not inherit the effort")
    }
  }

  @Test
  fun `a Cursor-merged write replaces the launch pair instead of retaining the prior effort`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement",
        status = "running",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = false,
        launchedModel = "claude-opus-4-8",
        launchedEffort = "medium",
        launchOutcomeKnown = true,
      ),
    )
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement",
        status = "running",
        attemptCount = 2,
        resolvedAgentId = "cursor",
        finished = false,
        launchedModel = "claude-opus-4-8[effort=high]",
        launchOutcomeKnown = true,
      ),
    )

    val record = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).getValue("implement")
    assertEquals("claude-opus-4-8[effort=high]", record.launchedModel)
    assertNull(record.launchedEffort)
  }

  @Test
  fun `phase whose latest ledger entry is blocked is reported blocked and current`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordRunning("implement", attemptCount = 3)
    harness.recordCompleted("preplan", attemptCount = 1)
    harness.recordCompleted("plan", attemptCount = 1)
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.START, "implement", attemptCount = 1)
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.BLOCKED, "implement", attemptCount = 3)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals(2, projection.completeCount)
    assertEquals(1, projection.blockedCount)
    assertEquals("implement", projection.currentPhaseId)
    assertEquals("completed", projection.phases.single { it.phaseId == "plan" }.status)
    assertEquals("blocked", projection.phases.single { it.phaseId == "implement" }.status)
  }

  @Test
  fun `crash resume process retry and verifier reentry all surface as continuation kinds`() {
    val cases =
      listOf(
        Triple(FeatureTaskRuntimePhaseLedgerAction.RESUME, FeatureTaskRuntimeContinuationKind.CRASH_RESUME, ""),
        Triple(FeatureTaskRuntimePhaseLedgerAction.START, FeatureTaskRuntimeContinuationKind.PROCESS_RETRY, ""),
        Triple(
          FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
          FeatureTaskRuntimeContinuationKind.VERIFIER_REENTRY,
          " driving_verdict=gaps_found",
        ),
        Triple(
          FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
          FeatureTaskRuntimeContinuationKind.SCHEMA_CORRECTION,
          "",
        ),
      )

    cases.forEach { (action, kind, trailing) ->
      val harness = statusHarness()
      harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
      harness.recordRunning("implement", attemptCount = 2)
      harness.recordContinuationLedger(
        phaseId = "implement",
        attemptCount = 2,
        action = action,
        kind = kind,
        trailingDetail = trailing,
      )

      val projection =
        requireNotNull(
          harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
        )

      assertEquals(
        kind.wireValue,
        projection.phases.single { it.phaseId == "implement" }.continuationKind,
        "$action must surface ${kind.wireValue} rather than being filtered out of the lookup",
      )
    }
  }

  @Test
  fun `the newest continuation entry across actions wins`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordRunning("implement", attemptCount = 3)
    harness.recordContinuationLedger(
      phaseId = "implement",
      attemptCount = 2,
      action = FeatureTaskRuntimePhaseLedgerAction.FIX_LOOP_ITERATION,
      kind = FeatureTaskRuntimeContinuationKind.SCHEMA_CORRECTION,
    )
    harness.recordContinuationLedger(
      phaseId = "implement",
      attemptCount = 3,
      action = FeatureTaskRuntimePhaseLedgerAction.RESUME,
      kind = FeatureTaskRuntimeContinuationKind.CRASH_RESUME,
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals(
      FeatureTaskRuntimeContinuationKind.CRASH_RESUME.wireValue,
      projection.phases.single { it.phaseId == "implement" }.continuationKind,
    )
  }

  @Test
  fun `a later resume entry supersedes an earlier block`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordRunning("implement", attemptCount = 4)
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.BLOCKED, "implement", attemptCount = 3)
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.RESUME, "implement", attemptCount = 4)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals(0, projection.blockedCount)
    assertEquals("running", projection.phases.single { it.phaseId == "implement" }.status)
  }

  @Test
  fun `phase with a durable blocked record is reported blocked even when the ledger has no blocked entry`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordCompleted("preplan", attemptCount = 1)
    harness.recordCompleted("plan", attemptCount = 1)
    harness.recordBlocked("implement", attemptCount = 3, "fix loop exhausted")
    harness.recordLedger(FeatureTaskRuntimePhaseLedgerAction.START, "implement", attemptCount = 1)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals(1, projection.blockedCount)
    assertEquals("blocked", projection.phases.single { it.phaseId == "implement" }.status)
    assertEquals("implement", projection.currentPhaseId)
  }

  @Test
  fun `legacy audit gap loop edge does not reopen implement as current`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify", "audit", "review")
      .forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recordLoopEdge(
      phaseId = "implement",
      attemptCount = 1,
      loopId = "audit_gap",
      edgeIteration = 1,
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals("verify_findings", projection.currentPhaseId)
  }

  @Test
  fun `ledger-only review fix projects implement fix as current`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify", "audit", "review", "verify_findings")
      .forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recordLoopEdge(
      phaseId = "implement_fix",
      attemptCount = 1,
      loopId = "review_fix",
      edgeIteration = 1,
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals("implement_fix", projection.currentPhaseId)
  }

  @Test
  fun `projection surfaces the durable resolved feature branch`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recorder.recordResolvedBranch(
      WORKFLOW_ID,
      FeatureTaskRuntimeResolvedBranch(
        branch = "feat/SKILL-65-runtime-feature-task-parity",
        baseBranch = "main",
        created = true,
      ),
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals("feat/SKILL-65-runtime-feature-task-parity", projection.resolvedBranch)
  }

  @Test
  fun `projection resolved branch is null before branch setup`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertNull(projection.resolvedBranch)
  }

  @Test
  fun `projection surfaces the durable decompose terminal with subtask count and guidance fields`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.decomposeTerminalRecorder.recordDecomposeTerminal(
      WORKFLOW_ID,
      FeatureTaskRuntimeDecomposeTerminal(
        reason = "Plan needs ordered subtasks.",
        parentSpecPath = ".feature-specs/SKILL-65-runtime/spec.md",
        decompositionManifestPath = ".feature-specs/SKILL-65-runtime/decomposition-manifest.yaml",
        subtaskSpecPaths =
          listOf(
            ".feature-specs/SKILL-65-runtime/spec_subtask_1_domain.md",
            ".feature-specs/SKILL-65-runtime/spec_subtask_2_runtime.md",
          ),
      ),
      "plan",
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    val terminal = requireNotNull(projection.decomposeTerminal)
    assertEquals("Plan needs ordered subtasks.", terminal.reason)
    assertEquals(".feature-specs/SKILL-65-runtime/decomposition-manifest.yaml", terminal.decompositionManifestPath)
    assertEquals(
      listOf(
        ".feature-specs/SKILL-65-runtime/spec_subtask_1_domain.md",
        ".feature-specs/SKILL-65-runtime/spec_subtask_2_runtime.md",
      ),
      terminal.subtaskSpecPaths,
    )
    assertEquals(2, terminal.subtaskCount)
    assertEquals(0, projection.pendingCount)
    assertEquals(0, projection.blockedCount)
    assertNull(projection.currentPhaseId)
  }

  @Test
  fun `projection decompose terminal is null when no decompose stop was recorded`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertNull(projection.decomposeTerminal)
  }

  @Test
  fun `projection feature size is null before run invariants are persisted`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertNull(projection.featureSize)
  }

  @Test
  fun `fully forward completed run does not project the loop-only implement_fix as current`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf(
      "preplan",
      "plan",
      "implement",
      "simplify",
      "audit",
      "review",
      "verify_findings",
      "validate",
      "write_history",
      "commit_push",
      "pr",
    )
      .forEach { harness.recordCompleted(it, attemptCount = 1) }

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals(11, projection.completeCount)
    assertEquals("pending", projection.phases.single { it.phaseId == "implement_fix" }.status)
    assertNull(projection.currentPhaseId, "a completed forward run reports no current phase, not implement_fix")
  }

  @Test
  fun `a workflow with two durable signals reports count and the latest class phase and attempt`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.seedDiagnosticSignals(
      diagnosticSignal(
        failureClass = FeatureTaskRuntimeDiagnosticFailureClass.CONFLICT,
        phaseId = "validate",
        attempt = 1,
      ),
      diagnosticSignal(
        failureClass = FeatureTaskRuntimeDiagnosticFailureClass.PERMISSION,
        phaseId = "implement",
        attempt = 2,
      ),
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    val degraded = requireNotNull(projection.degradedDiagnostic)
    assertEquals(2, degraded.count)
    assertEquals("permission", degraded.failureClass)
    assertEquals("implement", degraded.phaseId)
    assertEquals(2, degraded.attempt)
  }

  @Test
  fun `a workflow with no diagnostic-signals artifact reports degradedDiagnostic as null`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertNull(projection.degradedDiagnostic)
  }

  @Test
  fun `a diagnostic-signals artifact that is not an array loud-fails status`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.seedDiagnosticSignalsArtifact("not-an-array")

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID))
    }
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-20260603-status-0001"
    const val SESSION_ID = "ftr-status-001"
  }
}

class FeatureTaskRuntimeStatusAttributionTest {
  @Test
  fun `attribution rolls up a single-agent run to participating equals finalizer`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordCompleted("implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(COMPLETE, "commit_push", attemptCount = 1, resolvedAgentId = "codex")

    val attribution = harness.attribution()

    assertEquals("codex", attribution.finalizingAgentId)
    assertEquals(listOf("codex"), attribution.participatingAgentIds)
  }

  @Test
  fun `attribution rolls up a multi-agent recovery handoff to order-stable participants and resuming finalizer`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(RESUME, "implement", attemptCount = 2, resolvedAgentId = "claude")
    harness.recordCompleted("implement", attemptCount = 2, resolvedAgentId = "claude")
    harness.recordLedger(COMPLETE, "commit_push", attemptCount = 1, resolvedAgentId = "claude")

    val attribution = harness.attribution()

    assertEquals("claude", attribution.finalizingAgentId)
    assertEquals(listOf("codex", "claude"), attribution.participatingAgentIds)
  }

  @Test
  fun `attribution finalizer is the terminal blocked ledger entry`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(BLOCKED, "review", attemptCount = 3, resolvedAgentId = "claude")

    val attribution = harness.attribution()

    assertEquals("claude", attribution.finalizingAgentId)
    assertEquals(listOf("codex", "claude"), attribution.participatingAgentIds)
  }

  @Test
  fun `attribution falls back to the durable terminal phase record when the ledger terminal entry is pruned`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordBlocked("implement", attemptCount = 3, blockedReason = "exhausted", resolvedAgentId = "claude")
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")

    val attribution = harness.attribution()

    assertEquals("claude", attribution.finalizingAgentId)
    assertEquals(listOf("codex", "claude"), attribution.participatingAgentIds)
  }

  @Test
  fun `projection surfaces the ledger-derived finalizing agent even without a goal continuation`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordLedger(START, "implement", attemptCount = 1, resolvedAgentId = "codex")
    harness.recordLedger(COMPLETE, "commit_push", attemptCount = 1, resolvedAgentId = "claude")

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals("claude", projection.finalizingAgentId)
  }

  @Test
  fun `first audit pass is a pass not semantic loop 1 when no audit-gap edge has fired`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify").forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recordRunning("audit", attemptCount = 1)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    val execution = requireNotNull(projection.currentPhaseExecution)

    assertEquals("audit", projection.currentPhaseId)
    assertEquals("audit", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.PASS, execution.kind)
    assertEquals(1, execution.count)
    assertNull(execution.total)
  }

  @Test
  fun `legacy audit gap loop edge does not override running audit as current`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify").forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recordRunning("audit", attemptCount = 2)
    harness.recordLoopEdge(
      phaseId = "implement",
      attemptCount = 1,
      loopId = "audit_gap",
      edgeIteration = 2,
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    assertEquals("audit", projection.currentPhaseId)
    val execution = requireNotNull(projection.currentPhaseExecution)
    assertEquals("audit", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.PASS, execution.kind)
    assertEquals(1, execution.count)
    assertNull(execution.total)
  }

  @Test
  fun `legacy blocked audit gap record projects pending not blocked`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify").forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "audit",
        status = "blocked",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = false,
        outputArtifact = null,
        blockedReason = "legacy audit-gap block",
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        edgeIteration = 1,
      ),
    )
    harness.recordLedger(BLOCKED, "audit", attemptCount = 1)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals(0, projection.blockedCount)
    assertEquals("pending", projection.phases.single { it.phaseId == "audit" }.status)
    assertEquals("audit", projection.currentPhaseId)
  }

  @Test
  fun `audit after legacy gap record reports pass execution not semantic loop`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify").forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recordLoopEdge(
      phaseId = "implement",
      attemptCount = 1,
      loopId = "audit_gap",
      edgeIteration = 1,
    )
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement",
        status = "completed",
        attemptCount = 2,
        resolvedAgentId = "claude",
        finished = true,
        outputArtifact = """{"contract_version":"0.1"}""",
        loopId = "audit_gap",
        edgeIteration = 1,
      ),
    )
    harness.recordRunning("audit", attemptCount = 2)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    assertEquals("audit", projection.currentPhaseId)
    val execution = requireNotNull(projection.currentPhaseExecution)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.PASS, execution.kind)
    assertEquals(1, execution.count)
  }

  @Test
  fun `review pass comes from durable review_pass_number not attempt_count`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify", "audit")
      .forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        status = "running",
        attemptCount = 5,
        resolvedAgentId = "claude",
        finished = false,
        reviewPassNumber = 3,
      ),
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    val execution = requireNotNull(projection.currentPhaseExecution)
    assertEquals("review", projection.currentPhaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.PASS, execution.kind)
    assertEquals(3, execution.count)
    assertNull(execution.total)
  }

  @Test
  fun `stale completed review pass is omitted after review_fix implement_fix completes`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify", "audit", "review", "verify_findings")
      .forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = true,
        outputArtifact = """{"contract_version":"0.1"}""",
        reviewPassNumber = 2,
      ),
    )
    harness.recordLoopEdge(
      phaseId = "implement_fix",
      attemptCount = 1,
      loopId = "review_fix",
      edgeIteration = 1,
    )
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement_fix",
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = true,
        outputArtifact = """{"contract_version":"0.1"}""",
        loopId = "review_fix",
        edgeIteration = 1,
      ),
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    assertEquals("validate", projection.currentPhaseId)
    val execution = projection.currentPhaseExecution
    assertTrue(
      execution == null || execution.kind != IdeStatusCurrentPhaseExecutionKind.PASS || execution.count != 2,
      "stale completed review pass must not be reported as current, was $execution",
    )
  }

  @Test
  fun `completed review pass is omitted when a later phase is current`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify", "audit", "review", "verify_findings")
      .forEach { harness.recordCompleted(it, attemptCount = 1) }
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "review",
        status = "completed",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = true,
        outputArtifact = """{"contract_version":"0.1"}""",
        reviewPassNumber = 3,
      ),
    )
    harness.recordRunning("validate", attemptCount = 1)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    assertEquals("validate", projection.currentPhaseId)
    val execution = requireNotNull(projection.currentPhaseExecution)
    assertEquals("validate", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.ATTEMPT, execution.kind)
    assertEquals(1, execution.count)
  }

  @Test
  fun `validation gate run count is gate_run after the gate begins and never invents a total`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    listOf("preplan", "plan", "implement", "simplify", "audit", "review", "verify_findings").forEach {
      harness.recordCompleted(it, attemptCount = 1)
    }
    harness.recordRunning("validate", attemptCount = 2)
    harness.recorder.persistValidationGateProgress(
      WORKFLOW_ID,
      FeatureTaskRuntimeValidationGateProgress(
        gateRunCount = 2,
        gateRuns =
          listOf(
            FeatureTaskRuntimeValidationGateRunRecord(
              durationMs = 10,
              outcome = "failed",
              cacheMode = "warm",
              executedWorkUnits = 1,
              command = "./gradlew check",
              exitCode = 1,
              repositoryCheckpoint = "checkpoint-1",
            ),
            FeatureTaskRuntimeValidationGateRunRecord(
              durationMs = 12,
              outcome = "failed",
              cacheMode = "warm",
              executedWorkUnits = 1,
              command = "./gradlew check --rerun-tasks",
              exitCode = 1,
              repositoryCheckpoint = "checkpoint-2",
            ),
          ),
      ),
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    val execution = requireNotNull(projection.currentPhaseExecution)
    assertEquals("validate", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.GATE_RUN, execution.kind)
    assertEquals(2, execution.count)
    assertNull(execution.total)
  }

  @Test
  fun `bounded regeneration edge exposes iteration and cap without labeling it a semantic loop`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordCompleted("preplan", attemptCount = 1)
    harness.recordCompleted("plan", attemptCount = 1)
    harness.recordCompleted("implement", attemptCount = 1)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement_fix",
        status = "running",
        attemptCount = 2,
        resolvedAgentId = "claude",
        finished = false,
        loopId = "review_fix",
        edgeIteration = 1,
      ),
    )
    harness.recordLoopEdge(
      phaseId = "implement_fix",
      attemptCount = 2,
      loopId = "review_fix",
      edgeIteration = 1,
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    val execution = requireNotNull(projection.currentPhaseExecution)
    assertEquals("implement_fix", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE, execution.kind)
    assertEquals(1, execution.count)
    assertEquals(1, execution.total)
  }

  @Test
  fun `newer LOOP_EDGE wins over a stale phase-record edge watermark`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordCompleted("preplan", attemptCount = 1)
    harness.recordCompleted("plan", attemptCount = 1)
    harness.recordCompleted("implement", attemptCount = 1)
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "implement_fix",
        status = "running",
        attemptCount = 3,
        resolvedAgentId = "claude",
        finished = false,
        loopId = "audit_gap",
        edgeIteration = 5,
      ),
    )
    harness.recordLoopEdge(
      phaseId = "implement_fix",
      attemptCount = 3,
      loopId = "review_fix",
      edgeIteration = 1,
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )
    val execution = requireNotNull(projection.currentPhaseExecution)
    assertEquals("implement_fix", execution.phaseId)
    assertEquals(IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE, execution.kind)
    assertEquals(1, execution.count)
    assertEquals(1, execution.total)
  }

  @Test
  fun `validate blocked with needs user action yields operator decision pause`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    val operatorReason = "Configure GITHUB_REGISTRY_AUTH then run npm ci:safe"
    harness.recorder.recordPhaseState(
      FeatureTaskRuntimePhaseStateRequest(
        workflowId = WORKFLOW_ID,
        phaseId = "validate",
        status = "blocked",
        attemptCount = 1,
        resolvedAgentId = "claude",
        finished = true,
        blockedReason = operatorReason,
        failureDisposition = FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      ),
    )

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals("validate", projection.operatorDecisionPause?.phaseId)
    assertEquals(operatorReason, projection.operatorDecisionPause?.reason)
  }

  @Test
  fun `validate blocked without needs user action yields no operator decision pause`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)
    harness.recordBlocked("validate", attemptCount = 2, blockedReason = "fix loop exhausted")

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertNull(projection.operatorDecisionPause)
  }

  @Test
  fun `pending phase with no attempts omits current phase execution`() {
    val harness = statusHarness()
    harness.recorder.openTestWorkflow(WORKFLOW_ID, SESSION_ID)

    val projection =
      requireNotNull(
        harness.service.status(FeatureTaskRuntimeStatusRequest(workflowId = WORKFLOW_ID)),
      )

    assertEquals("preplan", projection.currentPhaseId)
    assertNull(projection.currentPhaseExecution)
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-20260603-status-0001"
    const val SESSION_ID = "ftr-status-001"
  }
}

private fun diagnosticSignal(
  failureClass: FeatureTaskRuntimeDiagnosticFailureClass,
  phaseId: String,
  attempt: Int,
): FeatureTaskRuntimeDiagnosticSignal =
  FeatureTaskRuntimeDiagnosticSignal(
    operation = "retain-producer-output",
    failureClass = failureClass,
    conflictingKey = "wftr-20260603-status-0001:$phaseId:0:$attempt:1:cursor",
    phaseId = phaseId,
    attempt = attempt,
    repairTurn = 1,
    generation = 0,
    recordedAt = "2026-08-12T20:19:51Z",
  )

internal fun statusHarness(): StatusHarness {
  val repository = StatusInMemoryWorkflowRepository()
  val database = StatusFakeDatabaseSessionFactory(repository)
  val recorder =
    featureTaskRuntimePhaseRecorder(
      database,
      StatusNoopSnapshotValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      testHarnessClock,
      NoopRuntimeDiagnostics,
    )
  val decomposeTerminalRecorder = FeatureTaskRuntimeDecomposeTerminalRecorder(database, testHarnessClock)
  val runInvariantsStore =
    FeatureTaskRuntimeRunInvariantsStore(
      database,
      FeatureTaskRuntimeWorkflowPersistence(database, StatusNoopSnapshotValidator),
    )
  return StatusHarness(
    recorder,
    decomposeTerminalRecorder,
    runInvariantsStore,
    FeatureTaskRuntimeStatusService(
      recorder,
      runInvariantsStore,
      decomposeTerminalRecorder,
    ),
    repository,
  )
}

internal class StatusHarness(
  val recorder: FeatureTaskRuntimePhaseRecorder,
  val decomposeTerminalRecorder: FeatureTaskRuntimeDecomposeTerminalRecorder,
  val runInvariantsStore: FeatureTaskRuntimeRunInvariantsStore,
  val service: FeatureTaskRuntimeStatusService,
  val repository: StatusInMemoryWorkflowRepository,
) {
  fun recordRunning(
    phaseId: String,
    attemptCount: Int,
    resolvedAgentId: String = "claude",
  ) = recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = WORKFLOW_ID,
      phaseId = phaseId,
      status = "running",
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      finished = false,
      outputArtifact = null,
    ),
  )

  fun recordCompleted(
    phaseId: String,
    attemptCount: Int,
    resolvedAgentId: String = "claude",
  ) = recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = WORKFLOW_ID,
      phaseId = phaseId,
      status = "completed",
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      finished = true,
      outputArtifact = """{"contract_version":"0.1"}""",
    ),
  )

  fun recordBlocked(
    phaseId: String,
    attemptCount: Int,
    blockedReason: String,
    resolvedAgentId: String = "claude",
  ) = recorder.recordPhaseState(
    FeatureTaskRuntimePhaseStateRequest(
      workflowId = WORKFLOW_ID,
      phaseId = phaseId,
      status = "blocked",
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      finished = false,
      outputArtifact = null,
      blockedReason = blockedReason,
    ),
  )

  fun recordLedger(
    action: FeatureTaskRuntimePhaseLedgerAction,
    phaseId: String,
    attemptCount: Int,
    resolvedAgentId: String = "claude",
  ) = recorder.appendLedgerEntry(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = WORKFLOW_ID,
      action = action,
      phaseId = phaseId,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      blockedReason = if (action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED) "fix loop exhausted" else null,
    ),
  )

  fun recordContinuationLedger(
    phaseId: String,
    attemptCount: Int,
    action: FeatureTaskRuntimePhaseLedgerAction,
    kind: FeatureTaskRuntimeContinuationKind,
    trailingDetail: String = "",
  ) = recorder.appendLedgerEntry(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = WORKFLOW_ID,
      action = action,
      phaseId = phaseId,
      attemptCount = attemptCount,
      resolvedAgentId = DEFAULT_LEDGER_AGENT,
      blockedReason = FeatureTaskRuntimeContinuationKind.LEDGER_DETAIL_PREFIX + kind.wireValue + trailingDetail,
    ),
  )

  fun recordLoopEdge(
    phaseId: String,
    attemptCount: Int,
    loopId: String,
    edgeIteration: Int,
    resolvedAgentId: String = "claude",
  ) = recorder.appendLedgerEntry(
    FeatureTaskRuntimePhaseLedgerRequest(
      workflowId = WORKFLOW_ID,
      action = LOOP_EDGE,
      phaseId = phaseId,
      attemptCount = attemptCount,
      resolvedAgentId = resolvedAgentId,
      loopId = loopId,
      edgeIteration = edgeIteration,
    ),
  )

  fun seedDiagnosticSignals(vararg signals: FeatureTaskRuntimeDiagnosticSignal) {
    seedDiagnosticSignalsArtifact(signals.map { it.asWorkflowArtifactEntry() })
  }

  fun seedDiagnosticSignalsArtifact(raw: Any?) {
    val row = requireNotNull(repository.getFeatureTaskWorkflowAsMode(WORKFLOW_ID, RUNTIME))
    val artifacts = decodeWorkflowArtifactsForTest(row.artifactsJson).toMutableMap()
    artifacts[FEATURE_TASK_RUNTIME_DIAGNOSTIC_SIGNALS_ARTIFACT_KEY] = raw
    repository.saveFeatureTaskWorkflow(
      row.copy(artifactsJson = JsonCodec.mapToJsonString(artifacts)),
      RUNTIME,
    )
  }

  fun attribution() = agentAttributionFromPhaseState(recorder.phaseQuery, WORKFLOW_ID)

  fun recordRunInvariants(featureSize: FeatureTaskRuntimeFeatureSize) {
    runInvariantsStore.resolve(
      workflowId = WORKFLOW_ID,
      proposed =
        FeatureTaskRuntimeRunInvariants(
          specReference = ".feature-specs/SKILL-65/spec.md",
          featureSize = featureSize,
          acceptanceCriteria = listOf("AC-1"),
          mandatesAndOverrides = emptyList(),
        ),
    )
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-20260603-status-0001"
    const val SESSION_ID = "ftr-status-001"
    const val DEFAULT_LEDGER_AGENT = "claude"
  }
}

private class StatusFakeDatabaseSessionFactory(
  val repository: StatusInMemoryWorkflowRepository,
) : DatabaseSessionFactory {
  private val dbPath = Path.of("/fake/status-metrics.db")

  override fun resolveDbPath(): Path = dbPath

  override fun databaseExists(): Boolean = true

  override fun <T> read(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> selfManagedWrite(block: (UnitOfWork) -> T): T = block(unitOfWork())

  override fun <T> transaction(block: (UnitOfWork) -> T): T = block(unitOfWork())

  private fun unitOfWork(): UnitOfWork =
    object : UnitOfWorkDefaults() {
      override val dbPath: Path = this@StatusFakeDatabaseSessionFactory.dbPath
      override val reviews: ReviewRepository get() = error("unused")
      override val learnings: LearningRepository get() = error("unused")
      override val lifecycleTelemetry: LifecycleTelemetryRepository get() = error("unused")
      override val telemetryReconciliation: TelemetryReconciliationRepository get() = error("unused")
      override val telemetryOutbox: TelemetryOutboxRepository get() = error("unused")
      override val workflowStates: WorkflowStateRepository = repository
      override val workList = EmptyWorkListRepository
      override val goalPlanningPreparations = EmptyGoalPlanningPreparationRepository
      override val goalRunnerControls = EmptyGoalRunnerControlRepository
    }
}

internal class StatusInMemoryWorkflowRepository : WorkflowStateRepositoryDefaults() {
  override fun saveFeatureTaskExecutionIdentity(identity: FeatureTaskExecutionIdentity) = Unit

  override fun findStandaloneFeatureTaskCandidates(
    normalizedIssueKey: String,
    repositoryIdentity: String,
  ) = emptyList<FeatureTaskWorkflowCandidate>()

  private val taskRuntimeRows = linkedMapOf<String, WorkflowStateRecord>()

  override fun saveFeatureTaskWorkflow(
    row: WorkflowStateRecord,
    mode: FeatureTaskWorkflowMode,
  ) {
    taskRuntimeRows[row.workflowId] = row
  }

  override fun getFeatureTaskWorkflowAsMode(
    workflowId: String,
    mode: FeatureTaskWorkflowMode,
  ): WorkflowStateRecord? = taskRuntimeRows[workflowId]

  override fun listFeatureTaskWorkflows(
    mode: FeatureTaskWorkflowMode,
    limit: Int,
  ): List<WorkflowStateRecord> = taskRuntimeRows.values.toList().asReversed().take(limit)

  override fun save(
    family: WorkflowFamily,
    snapshot: WorkflowStateSnapshot,
  ) = saveRecord(family, snapshot.toRecord(taskRuntimeRows[snapshot.workflowId]))

  override fun saveRecord(
    family: WorkflowFamily,
    record: WorkflowStateRecord,
  ) {
    taskRuntimeRows[record.workflowId] = record
  }

  override fun get(
    family: WorkflowFamily,
    workflowId: String,
  ): WorkflowStateSnapshot? = taskRuntimeRows[workflowId]?.toSnapshot()

  override fun list(
    family: WorkflowFamily,
    limit: Int,
  ): List<WorkflowStateSnapshot> =
    taskRuntimeRows.values.toList().asReversed().take(limit).map(WorkflowStateRecord::toSnapshot)

  override fun latest(family: WorkflowFamily): WorkflowStateSnapshot? = list(family, 1).firstOrNull()
}

private object StatusNoopSnapshotValidator : WorkflowSnapshotValidator {
  override fun validate(
    snapshot: WorkflowStateSnapshot,
    slug: String,
  ) = Unit
}
