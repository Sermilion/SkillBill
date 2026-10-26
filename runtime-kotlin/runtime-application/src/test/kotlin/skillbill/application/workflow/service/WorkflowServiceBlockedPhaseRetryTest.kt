package skillbill.application.workflow.service

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testRepositoryRoot
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.decomposition.UnavailableDecompositionManifestStore
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStepUpdates
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodeRunInvariantsFromArtifact
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class WorkflowServiceBlockedPhaseRetryTest {
  @Test
  fun `operator retry resets an exhausted criterion even after the audit record completed`() {
    val fixture = retryFixture()
    val before = assertNotNull(fixture.states.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW_ID))
    val repair = exhaustedRepair()
    val audit =
      FeatureTaskRuntimePhaseRecord(
        phaseId = "audit",
        status = WorkflowStepStatus.COMPLETED,
        attemptCount = 4,
        startedAt = Instant.EPOCH,
        finishedAt = Instant.EPOCH,
        resolvedAgentId = "codex",
        outputArtifact = "Retained source audit gap",
      )
    val repairFamily = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR
    fixture.states.save(
      WorkflowFamily.TASK_RUNTIME,
      before.copy(
        currentStepId = "review",
        artifacts =
          DurableWorkflowArtifacts.fromMap(
            before.artifacts +
              repairFamily.entry(repair.asRepairWireMap()) +
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                mapOf("audit" to audit.asWorkflowArtifactEntry()),
              ),
          ),
      ),
    )

    assertIs<WorkflowUpdateResult.Ok>(
      fixture.service.retry(FakeDatabaseSessionFactory(fixture.states), WORKFLOW_ID, "audit", "Resume repairs"),
    )

    val saved = assertNotNull(fixture.states.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW_ID))
    val resumed =
      FeatureTaskRuntimeWorkflowArtifactMap.from(repairFamily.value(saved.artifacts))
        .toAcceptanceCriterionRepairState(repairFamily.label())
    assertEquals(repair.resetBudgetForOperatorResume(), resumed)
    assertEquals(repair.outcomes, resumed.outcomes)
    assertEquals(WorkflowStatus.RUNNING, saved.workflowStatus)
    assertEquals("audit", saved.currentStepId)
    assertEquals(WorkflowStepStatus.PENDING, saved.steps.single { it.stepId == "audit" }.status)
    val beforeRepeat = fixture.states.runtimeRecord(WORKFLOW_ID)
    assertIs<WorkflowUpdateResult.Error>(
      fixture.service.retry(FakeDatabaseSessionFactory(fixture.states), WORKFLOW_ID, "audit", "Repeat retry"),
    )
    assertEquals(beforeRepeat, fixture.states.runtimeRecord(WORKFLOW_ID))
  }

  @Test
  fun `explicit run mandate is saved with retry while ordinary retry reasons stay phase scoped`() {
    listOf(null, "Preserve the installed compatibility prerequisites outside AC-007 scope.").forEach { mandate ->
      val fixture = retryFixture()

      assertIs<WorkflowUpdateResult.Ok>(fixture.retry(mandate))

      val saved = assertNotNull(fixture.states.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW_ID))
      val invariants =
        assertNotNull(
          decodeRunInvariantsFromArtifact(
            DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.value(saved.artifacts),
          ),
        )
      assertEquals(
        fixture.invariants.copy(mandatesAndOverrides = listOfNotNull("Existing mandate", mandate)),
        invariants,
      )
      assertEquals(WorkflowStatus.RUNNING, saved.workflowStatus)
      assertEquals(WorkflowStepStatus.PENDING, saved.steps.single { it.stepId == "audit_plan_fix" }.status)
    }
  }

  @Test
  fun `retry refuses a mandate without admitted invariants and leaves the workflow blocked`() {
    val fixture = retryFixture(includeInvariants = false)
    val before = fixture.states.runtimeRecord(WORKFLOW_ID)

    assertIs<WorkflowUpdateResult.Error>(fixture.retry("Retain the operator decision"))

    assertEquals(before, fixture.states.runtimeRecord(WORKFLOW_ID))
  }

  @Test
  fun `retry refuses a blank mandate without reopening the phase`() {
    val fixture = retryFixture()
    val before = fixture.states.runtimeRecord(WORKFLOW_ID)

    assertIs<WorkflowUpdateResult.Error>(fixture.retry("   "))

    assertEquals(before, fixture.states.runtimeRecord(WORKFLOW_ID))
  }
}

private fun exhaustedRepair(): AcceptanceCriterionRepairState {
  val initial =
    assertNotNull(
      AcceptanceCriterionRepairState.admit(
        AcceptanceCriterionRepairAdmission(
          "catalog",
          WORKFLOW_ID,
          "a".repeat(64),
          listOf("Retain evidence"),
          listOf(
            AcceptanceCriterionRepairCriterion(
              "AC-001",
              null,
              "Retain evidence",
              1,
              AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED,
              AcceptanceCriterionValidationObligation.REQUIRED,
              true,
              "discovery",
            ),
          ),
          emptyList(),
        ),
      ),
    )
  var state =
    assertNotNull(
      initial.recordProseAudit(
        AcceptanceCriterionProseAudit(
          "discovery",
          1,
          null,
          false,
          "b".repeat(64),
          "Open criteria: AC-001",
          "c".repeat(64),
          "checkpoint",
        ),
      ),
    )
  repeat(3) { index ->
    val id = "repair-$index"
    state = assertNotNull(state.reserve(id))
    state =
      assertNotNull(
        state.record(
          AcceptanceCriterionRepairOutcome(
            id,
            id,
            "AC-001",
            "d".repeat(64),
            AcceptanceCriterionRepairOutcomeKind.INCOMPLETE,
            id,
          ),
        ),
      )
  }
  assertEquals(AcceptanceCriterionRepairStage.BUDGET_BLOCKED, state.stage)
  return state
}

private const val WORKFLOW_ID = "wftr-mandate"

private class RetryFixture(
  val states: InMemoryWorkflowStates,
  val service: WorkflowServiceBlockedPhaseRetry,
  val invariants: FeatureTaskRuntimeRunInvariants,
) {
  fun retry(mandate: String?): WorkflowUpdateResult =
    service.retry(
      FakeDatabaseSessionFactory(states),
      WORKFLOW_ID,
      "audit_plan_fix",
      "Operator resolved the scope block",
      mandate,
    )
}

private fun retryFixture(includeInvariants: Boolean = true): RetryFixture {
  val engine = WorkflowEngine()
  val states = InMemoryWorkflowStates()
  val invariants =
    FeatureTaskRuntimeRunInvariants(
      specReference = "spec.md",
      acceptanceCriteria = listOf("Preserve the comparison baseline"),
      mandatesAndOverrides = listOf("Existing mandate"),
    )
  val blocked =
    FeatureTaskRuntimePhaseRecord(
      phaseId = "audit_plan_fix",
      status = WorkflowStepStatus.BLOCKED,
      attemptCount = 2,
      startedAt = Instant.EPOCH,
      resolvedAgentId = "codex",
      blockedReason = "Operator scope decision required",
    )
  val artifacts =
    mapOf(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
        mapOf(blocked.phaseId to blocked.asWorkflowArtifactEntry()),
      ),
    ) +
      if (includeInvariants) {
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.entry(invariants.asWorkflowArtifactEntry()),
        )
      } else {
        emptyMap()
      }
  val definition = FeatureTaskRuntimePhaseWorkflowDefinition.definition
  states.save(
    WorkflowFamily.TASK_RUNTIME,
    engine.updateRecord(
      definition,
      engine.openRecord(definition, WORKFLOW_ID, "session", blocked.phaseId),
      WorkflowUpdateInput(
        workflowStatus = WorkflowStatus.BLOCKED,
        currentStepId = blocked.phaseId,
        stepUpdates =
          WorkflowStepUpdates.from(
            listOf(mapOf("step_id" to blocked.phaseId, "status" to "blocked", "attempt_count" to 2)),
          ),
        artifactsPatch = WorkflowArtifactPatch.from(artifacts),
        sessionId = "session",
      ),
    ),
  )
  return RetryFixture(
    states,
    WorkflowServiceBlockedPhaseRetry(
      engine,
      testDecompositionManifestValidator,
      UnavailableDecompositionManifestStore,
      testDecompositionManifestWriter,
      testRepositoryRoot,
      NoopRuntimeDiagnostics,
      Clock.systemUTC(),
    ),
    invariants,
  )
}
