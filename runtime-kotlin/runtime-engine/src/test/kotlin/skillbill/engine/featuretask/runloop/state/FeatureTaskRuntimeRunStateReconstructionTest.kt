package skillbill.engine.featuretask.runloop.state

import skillbill.application.testHarnessClock
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.phase.record.openTestWorkflow
import skillbill.engine.featuretask.runloop.core.ReconstructFixLoopBudgetBasesArgs
import skillbill.engine.featuretask.runner.IMPLEMENT_OUTPUT
import skillbill.engine.featuretask.runner.NoopWorkflowSnapshotValidator
import skillbill.engine.featuretask.runner.PLAN_OUTPUT
import skillbill.engine.featuretask.runner.PREPLAN_OUTPUT
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.WORKFLOW_ID
import skillbill.engine.featuretask.runner.auditGapsFoundOutput
import skillbill.engine.featuretask.runner.runnerHarness
import skillbill.engine.featuretask.runner.satisfiedAuditLauncher
import skillbill.engine.featuretask.runner.serializeTokenData
import skillbill.engine.featuretask.slot.audit.AcceptanceAuditResumeRules
import skillbill.engine.featuretask.slot.state.PhaseHistoricalInterpreter
import skillbill.engine.featuretask.slot.state.PhaseHistoricalPolicy
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.engine.goalrunner.status.completed
import skillbill.infrastructure.sqlite.SQLiteDatabaseSessionFactory
import skillbill.infrastructure.sqlite.sqliteDatabaseSessionFactory
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskWorkflowMode
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val RESUME_RULES = PhaseHistoricalInterpreter(PhaseHistoricalPolicy.REVISION_1)::resumeRules
private val CANONICAL_IMPLEMENT_PAYLOAD =
  NormalizedFeatureTaskRuntimePhaseOutput.fromEnvelopeText(validJsonOutput("implement"), "implement").canonicalJson

class FeatureTaskRuntimeRunStateReconstructionTest {
  @Test
  fun `resume keeps a completed validation step and invalidates successors of a failed one`() {
    listOf(true to "completed", false to "completed", null to "completed", true to "failed")
      .forEach { (legacySignal, status) ->
        val payload =
          validJsonOutput("validate").let { output ->
            when (legacySignal) {
              true -> output
              false -> output.replace("\"validation_passed\":true", "\"validation_passed\":false")
              null -> output.replace(",\"validation_passed\":true", "")
            }
          }
        val validation =
          FeatureTaskRuntimePhaseRecord(
            phaseId = "validate",
            status = WorkflowStepStatus.COMPLETED,
            attemptCount = 1,
            startedAt = "2026-09-19T00:00:00Z",
            resolvedAgentId = "claude",
            outputArtifact =
              payload.replace(
                "\"status\": \"completed\"",
                if (status == "failed") {
                  "\"status\": \"failed\", \"failure_disposition\": \"retryable\""
                } else {
                  "\"status\": \"completed\""
                },
              ),
          )
        val history = validation.copy(phaseId = "write_history", outputArtifact = validJsonOutput("write_history"))
        val state =
          FeatureTaskRuntimeRunState(
            initialRecords = mapOf("validate" to validation, "write_history" to history),
            transitions = FeatureTaskRuntimeTransitionDeclaration(listOf("validate", "write_history")),
            resumeRulesFn = RESUME_RULES,
          )
        val valid = status == "completed"
        assertEquals(valid, "validate" in state.completedPhaseIds)
        assertEquals(valid, "write_history" in state.completedPhaseIds)
        assertEquals(!valid, "validate" in state.phasesRequiringDurableGateInvalidation)
        assertEquals(!valid, "write_history" in state.phasesRequiringDurableGateInvalidation)
      }
  }

  @Test
  fun `completed phase and output views cannot mutate run state`() {
    val state =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        resumeRulesFn = RESUME_RULES,
      )
    val output =
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        iteration = 1,
        payload = "{}",
      )

    state.recordCompleted(output)
    val outputs = state.outputs().toMutableList()
    outputs.clear()

    assertEquals(listOf(output), state.outputs())
    assertEquals(listOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT), state.completedPhaseIds)
  }

  @Test
  fun `phase token view is a snapshot of named state transitions`() {
    val state =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        resumeRulesFn = RESUME_RULES,
      )

    state.recordPhaseTokenUsage("implement", 11, 17)
    val firstView = state.phaseTokenView
    state.recordPhaseTokenUsage("review", 5, 9)

    assertEquals(mapOf("implement" to (11 to 17)), firstView)
    assertEquals(
      mapOf("implement" to (11 to 17), "review" to (5 to 9)),
      state.phaseTokenView,
    )
  }

  @Test
  fun `resumed phase token telemetry matches live execution for the current phase`() {
    val durableRecords =
      mapOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
          FeatureTaskRuntimePhaseRecord(
            phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
            status = WorkflowStepStatus.COMPLETED,
            attemptCount = 1,
            startedAt = "2026-01-01T00:00:00Z",
            resolvedAgentId = "claude",
            outputArtifact = "{}",
          ),
      )
    val live =
      FeatureTaskRuntimeRunState(
        initialRecords = durableRecords,
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        resumeRulesFn = RESUME_RULES,
      )
    val resumed =
      FeatureTaskRuntimeRunState(
        initialRecords = durableRecords,
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        resumeRulesFn = RESUME_RULES,
      )

    live.recordPhaseTokenUsage("review", 13, 21)
    resumed.recordPhaseTokenUsage("review", 13, 21)

    assertEquals(
      serializeTokenData(live.phaseTokenView),
      serializeTokenData(resumed.phaseTokenView),
    )
  }

  @Test
  fun `resume reconstruction preserves completed phase and backward edge state`() {
    val transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions
    val live =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = transitions,
        resumeRulesFn = RESUME_RULES,
      )
    val output =
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        iteration = 1,
        payload = CANONICAL_IMPLEMENT_PAYLOAD,
      )
    live.recordEdgeIteration(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID, 2)
    live.recordCompleted(output)

    val resumed =
      FeatureTaskRuntimeRunState(
        initialRecords =
          mapOf(
            output.phaseId to
              FeatureTaskRuntimePhaseRecord(
                phaseId = output.phaseId,
                status = WorkflowStepStatus.COMPLETED,
                attemptCount = 1,
                startedAt = "2026-01-01T00:00:00Z",
                resolvedAgentId = "claude",
                outputArtifact = output.payload,
                loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
                edgeIteration = 2,
              ),
          ),
        transitions = transitions,
        durableInitialLedger =
          listOf(
            FeatureTaskRuntimePhaseLedgerEntry(
              action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
              sequenceNumber = 1,
              timestamp = "2026-01-01T00:00:00Z",
              phaseId = output.phaseId,
              attemptCount = 1,
              loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
              edgeIteration = 2,
            ),
          ),
        resumeRulesFn = RESUME_RULES,
      )

    assertEquals(live.completedPhaseIds, resumed.completedPhaseIds)
    assertEquals(
      live.loop(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID).iteration,
      resumed.loop(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID).iteration,
    )
    assertEquals(live.phase(output.phaseId).output?.payload, resumed.phase(output.phaseId).output?.payload)
  }

  @Test
  fun `sqlite durable phase records reconstruct the live checkpoint state`() {
    val tempDir = Files.createTempDirectory("skill-bill-run-state-resume")
    val database = sqliteResumeDatabase(tempDir)
    val workflowId = "wftr-sqlite-resume"
    seedSqliteResumeWorkflow(database, workflowId)
    val recorder =
      featureTaskRuntimePhaseRecorder(
        database,
        NoopWorkflowSnapshotValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        testHarnessClock,
        NoopRuntimeDiagnostics,
      )
    val output =
      FeatureTaskRuntimePhaseOutput(
        phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        iteration = 1,
        payload = CANONICAL_IMPLEMENT_PAYLOAD,
      )
    assertTrue(
      recorder.recordCompletedPhase(
        FeatureTaskRuntimePhaseStateRequest(
          workflowId = workflowId,
          phaseId = output.phaseId,
          status = WorkflowStepStatus.COMPLETED.wireValue,
          attemptCount = output.iteration,
          resolvedAgentId = "claude",
          finished = true,
          outputArtifact = output.payload,
          loopId = FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID,
          edgeIteration = 2,
        ),
      ),
    )

    val live =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        resumeRulesFn = RESUME_RULES,
      ).also {
        it.recordEdgeIteration(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID, 2)
        it.recordCompleted(output)
      }
    val resumed =
      FeatureTaskRuntimeRunState(
        initialRecords = recorder.loadPhaseRecords(workflowId).orEmpty(),
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        durableInitialLedger = recorder.loadPhaseLedger(workflowId).orEmpty(),
        resumeRulesFn = RESUME_RULES,
      )

    assertEquals(live.completedPhaseIds, resumed.completedPhaseIds)
    assertEquals(
      live.loop(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID).iteration,
      resumed.loop(FeatureTaskRuntimePhaseWorkflowDefinition.REVIEW_FIX_LOOP_ID).iteration,
    )
    assertEquals(live.phase(output.phaseId).output?.payload, resumed.phase(output.phaseId).output?.payload)
  }

  private fun sqliteResumeDatabase(tempDir: Path): SQLiteDatabaseSessionFactory =
    sqliteDatabaseSessionFactory(
      userHome = tempDir,
      dbPathOverride = tempDir.resolve("runtime.db").toString(),
      environment = emptyMap(),
    )

  private fun seedSqliteResumeWorkflow(
    database: SQLiteDatabaseSessionFactory,
    workflowId: String,
  ) {
    database.transaction { unitOfWork ->
      unitOfWork.workflowStates.saveFeatureTaskWorkflow(
        WorkflowStateRecord(
          workflowId = workflowId,
          sessionId = "ftr-sqlite-resume",
          workflowName = "bill-feature-task",
          contractVersion = "0.1",
          workflowStatus = WorkflowStatus.RUNNING.wireValue,
          currentStepId = "implement",
          stepsJson = "[]",
          artifactsJson = "{}",
          startedAt = null,
          updatedAt = null,
          finishedAt = null,
          mode = FeatureTaskWorkflowMode.RUNTIME,
        ),
        FeatureTaskWorkflowMode.RUNTIME,
      )
    }
  }

  @Test
  fun `validation settlement owns mutable invalidation state behind read-only snapshots`() {
    val completed = mutableSetOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE)
    val invalidated = mutableSetOf<String>()
    val settlement =
      ValidationSettlementState(
        completed = completed,
        initialRecords = emptyMap(),
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        gateInvalidatedPhases = invalidated,
      )

    completed.clear()
    invalidated += FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN
    val completedView = settlement.completed
    val invalidatedView = settlement.gateInvalidatedPhases
    settlement.invalidateValidationPhase("validate")

    assertEquals(
      setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      completedView,
    )
    assertEquals(emptySet(), invalidatedView)
    assertEquals(
      emptySet(),
      settlement.completed,
    )
    assertEquals(
      setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE),
      settlement.gateInvalidatedPhases,
    )
  }

  @Test
  fun `legacy blocked audit with loop id only normalizes to pending`() {
    val raw =
      auditPhaseRecord(
        status = WorkflowStepStatus.BLOCKED,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        edgeIteration = 1,
        blockedReason = "legacy audit-gap block",
      )
    val normalized =
      FeatureTaskRuntimeRunStateReconstruction
        .normalizeInitialRecordsForStatelessAudit(mapOf("audit" to raw), RESUME_RULES)
        .getValue("audit")
    assertEquals(WorkflowStepStatus.PENDING, normalized.status)
    assertNull(normalized.loopId)
    assertNull(normalized.outputArtifact)
    assertNull(normalized.blockedReason)
    assertFalse(AcceptanceAuditResumeRules.isLegacyAuditGapPersistedBlock(normalized))
  }

  @Test
  fun `legacy completed gaps_found audit normalizes to pending`() {
    val raw =
      auditPhaseRecord(
        status = WorkflowStepStatus.COMPLETED,
        outputArtifact = auditGapsFoundOutput(),
      )
    val normalized =
      FeatureTaskRuntimeRunStateReconstruction
        .normalizeInitialRecordsForStatelessAudit(mapOf("audit" to raw), RESUME_RULES)
        .getValue("audit")
    assertEquals(WorkflowStepStatus.PENDING, normalized.status)
    assertNull(normalized.outputArtifact)
  }

  @Test
  fun `run state drops legacy blocked audit from blocked records`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, "claude", IMPLEMENT_OUTPUT)
    harness.recorder.openTestWorkflow(WORKFLOW_ID, "ftr-test-001")
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
    val loaded = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID))["audit"]
    assertEquals(FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID, loaded?.loopId)
    val state =
      FeatureTaskRuntimeRunState(
        initialRecords = harness.recorder.loadPhaseRecords(WORKFLOW_ID).orEmpty(),
        transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
        resumeRulesFn = RESUME_RULES,
      )
    assertNull(state.phase("audit").blockedReason)
    assertEquals(WorkflowStepStatus.PENDING, state.phase("audit").record?.status)
    assertFalse(state.phase("audit").completed)
  }

  @Test
  fun `normalizeForStatelessAudit applies record and ledger normalization together`() {
    val rawAudit =
      auditPhaseRecord(
        status = WorkflowStepStatus.BLOCKED,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        blockedReason = "legacy audit-gap block",
      )
    val rawLedger =
      listOf(
        FeatureTaskRuntimePhaseLedgerEntry(
          action = FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
          sequenceNumber = 1,
          timestamp = "2026-01-01T00:00:00Z",
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          attemptCount = 1,
        ),
      )
    val normalized =
      FeatureTaskRuntimeRunStateReconstruction.normalizeForStatelessAudit(
        mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to rawAudit),
        rawLedger,
        RESUME_RULES,
      )
    assertEquals(WorkflowStepStatus.PENDING, normalized.records.getValue("audit").status)
    assertTrue(normalized.ledger.isEmpty())
  }

  @Test
  fun `normalize ledger drops retired audit gap loop edges and legacy audit blocks`() {
    val rawAudit =
      auditPhaseRecord(
        status = WorkflowStepStatus.BLOCKED,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        blockedReason = "legacy audit-gap block",
      )
    val ledger =
      listOf(
        FeatureTaskRuntimePhaseLedgerEntry(
          action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
          sequenceNumber = 1,
          timestamp = "2026-01-01T00:00:00Z",
          phaseId = "implement",
          attemptCount = 1,
          loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
          edgeIteration = 1,
        ),
        FeatureTaskRuntimePhaseLedgerEntry(
          action = FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
          sequenceNumber = 2,
          timestamp = "2026-01-01T00:00:01Z",
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          attemptCount = 1,
        ),
      )
    val normalized =
      FeatureTaskRuntimeRunStateReconstruction.normalizeLedgerForStatelessAudit(
        ledger,
        mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to rawAudit),
        RESUME_RULES,
      )
    assertTrue(normalized.isEmpty())
  }

  @Test
  fun `normalize ledger is idempotent on already normalized records`() {
    val rawAudit =
      auditPhaseRecord(
        status = WorkflowStepStatus.BLOCKED,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        blockedReason = "legacy audit-gap block",
      )
    val rawRecords = mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to rawAudit)
    val rawLedger =
      listOf(
        FeatureTaskRuntimePhaseLedgerEntry(
          action = FeatureTaskRuntimePhaseLedgerAction.BLOCKED,
          sequenceNumber = 1,
          timestamp = "2026-01-01T00:00:00Z",
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          attemptCount = 1,
        ),
      )
    val firstPass =
      FeatureTaskRuntimeRunStateReconstruction.normalizeForStatelessAudit(rawRecords, rawLedger, RESUME_RULES)
    val secondPass =
      FeatureTaskRuntimeRunStateReconstruction.normalizeLedgerForStatelessAudit(
        rawLedger,
        firstPass.records,
        RESUME_RULES,
      )
    assertTrue(secondPass.isEmpty())
    assertEquals(firstPass.ledger, secondPass)
  }

  @Test
  fun `fix loop budget bases retain operator retry and ignore retired audit gap edges`() {
    val rawAudit =
      auditPhaseRecord(
        status = WorkflowStepStatus.BLOCKED,
        loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
        blockedReason = "legacy audit-gap block",
      )
    val rawRecords =
      mapOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
          FeatureTaskRuntimePhaseRecord(
            phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
            status = WorkflowStepStatus.COMPLETED,
            attemptCount = 2,
            startedAt = "2026-01-01T00:00:00Z",
            resolvedAgentId = "claude",
            loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
            edgeIteration = 1,
            finishedAt = "2026-01-01T00:01:00Z",
          ),
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to rawAudit,
      )
    val rawLedger =
      listOf(
        FeatureTaskRuntimePhaseLedgerEntry(
          action = FeatureTaskRuntimePhaseLedgerAction.LOOP_EDGE,
          sequenceNumber = 1,
          timestamp = "2026-01-01T00:00:00Z",
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          attemptCount = 2,
          loopId = FeatureTaskRuntimePhaseWorkflowDefinition.AUDIT_GAP_LOOP_ID,
          edgeIteration = 1,
        ),
        FeatureTaskRuntimePhaseLedgerEntry(
          action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
          sequenceNumber = 2,
          timestamp = "2026-01-01T00:00:01Z",
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
          attemptCount = 2,
        ),
      )
    val bases =
      FeatureTaskRuntimeRunStateReconstruction.reconstructFixLoopBudgetBases(
        ReconstructFixLoopBudgetBasesArgs(
          transitions = FeatureTaskRuntimePhaseWorkflowDefinition.transitions,
          edgeIterationByLoop = emptyMap(),
          initialRecords = rawRecords,
          initialLedger = rawLedger,
          completed = setOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT),
          gateInvalidatedPhases = emptySet(),
          nextIteration = { 1 },
          resumeRules = RESUME_RULES,
        ),
      )
    assertEquals(mapOf(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT to 2), bases)
  }

  @Test
  fun `legacy blocked audit run completes after fresh relaunch`() {
    val harness = runnerHarness(RuntimeHarnessConfig(launcher = satisfiedAuditLauncher()))
    harness.seedPhase("preplan", "completed", 1, "claude", PREPLAN_OUTPUT)
    harness.seedPhase("plan", "completed", 1, "claude", PLAN_OUTPUT)
    harness.seedPhase("implement", "completed", 1, "claude", IMPLEMENT_OUTPUT)
    harness.recorder.openTestWorkflow(WORKFLOW_ID, "ftr-test-001")
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
    val report = harness.runner.run(harness.request())
    assertTrue(report is FeatureTaskRuntimeRunReport.Completed)
    assertEquals(1, harness.launchedPromptPhaseOrder().count { it == "audit" })
  }

  private fun auditPhaseRecord(
    status: WorkflowStepStatus,
    loopId: String? = null,
    edgeIteration: Int? = null,
    blockedReason: String? = null,
    outputArtifact: String? = null,
  ): FeatureTaskRuntimePhaseRecord =
    FeatureTaskRuntimePhaseRecord(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      status = status,
      attemptCount = 1,
      startedAt = "2026-01-01T00:00:00Z",
      resolvedAgentId = "claude",
      loopId = loopId,
      edgeIteration = edgeIteration,
      blockedReason = blockedReason,
      outputArtifact = outputArtifact,
      finishedAt = if (status == WorkflowStepStatus.COMPLETED) "2026-01-01T00:01:00Z" else null,
    )

  private object NoopWorkflowSnapshotValidator : WorkflowSnapshotValidator {
    override fun validate(
      snapshot: WorkflowStateSnapshot,
      slug: String,
    ) = Unit
  }
}
