package skillbill.application.workflow.service

import skillbill.application.FakeDatabaseSessionFactory
import skillbill.application.InMemoryWorkflowStates
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.application.testRepositoryRoot
import skillbill.application.workflow.model.WorkflowUpdateResult
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerLeaseState
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
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
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCheckedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionIndependentProgress
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionMutationDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionPreservedObligation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProgressClassification
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProgressContext
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionProseAudit
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairLaunch
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairStage
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionResolvedObligation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.audit.recordProseAudit
import skillbill.workflow.taskruntime.model.audit.repairAccounting
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
  fun `operator audit retry runs source audit after exhaustion or interrupted repair planning`() {
    listOf(WorkflowStatus.BLOCKED to repairWithFailures(3), WorkflowStatus.PENDING to repairWithFailures(1))
      .forEach { (status, repair) ->
        val fixture = retryFixture()
        val before = assertNotNull(fixture.states.get(WorkflowFamily.TASK_RUNTIME, WORKFLOW_ID))
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
            workflowStatus = status,
            currentStepId = "audit_plan_fix",
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
        assertEquals(emptyMap(), resumed.unsuccessfulTotals)
        assertEquals(repair.outcomes.size, resumed.budgetStartOutcomeIndex)
        assertEquals(repair.completedMutations, resumed.completedMutations)
        assertEquals(repair.independentChecks, resumed.independentChecks)
        assertEquals(
          repair.repairAccounting("AC-001").escalationRequired,
          resumed.repairAccounting("AC-001").escalationRequired,
        )
        assertEquals(AcceptanceCriterionRepairStage.READ_ONLY_AUDIT_REQUIRED, resumed.stage)
        assertEquals(listOf("AC-001"), resumed.pendingAuditRefs)
        assertEquals(resumed, AcceptanceCriterionRepairState.restore(resumed))
        assertEquals(repair.outcomes, resumed.outcomes)
        assertEquals(WorkflowStatus.PENDING, saved.workflowStatus)
        assertEquals("audit", saved.currentStepId)
        assertEquals(WorkflowStepStatus.PENDING, saved.steps.single { it.stepId == "audit" }.status)
        val beforeRepeat = fixture.states.runtimeRecord(WORKFLOW_ID)
        assertIs<WorkflowUpdateResult.Error>(
          fixture.service.retry(FakeDatabaseSessionFactory(fixture.states), WORKFLOW_ID, "audit", "Repeat retry"),
        )
        assertEquals(beforeRepeat, fixture.states.runtimeRecord(WORKFLOW_ID))
      }
  }

  @Test
  fun `operator retry leaves a worker owned workflow unchanged`() {
    val fixture = retryFixture()
    fixture.states.seedWorkerOwnership(
      FeatureTaskRuntimeWorkerOwnership(
        WORKFLOW_ID, 1, "owner", "host", "boot", 1, "birth", FeatureTaskRuntimeWorkerLeaseState.ACTIVE,
        Instant.EPOCH.toString(), Instant.EPOCH.plusSeconds(1).toString(), "audit_plan_fix", 2,
      ),
    )
    val before = fixture.states.runtimeRecord(WORKFLOW_ID)

    assertIs<WorkflowUpdateResult.Error>(fixture.retry(null))

    assertEquals(before, fixture.states.runtimeRecord(WORKFLOW_ID))
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
      assertEquals(WorkflowStatus.PENDING, saved.workflowStatus)
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

private fun repairWithFailures(count: Int): AcceptanceCriterionRepairState {
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
  repeat(count) { index ->
    val id = "repair-$index"
    state = assertNotNull(state.reserve(id))
    state = assertNotNull(state.captureForTest(id, id, "c".repeat(64), "checkpoint:$index"))
    state =
      assertNotNull(
        state.recordIndependently(
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

private fun AcceptanceCriterionRepairState.captureForTest(
  reservationId: String,
  evidenceRef: String,
  treeDigest: String,
  checkpointId: String,
): AcceptanceCriterionRepairState? {
  val ref = reservation?.criterionRef ?: return null
  val inventory = independentChecks.lastOrNull { it.mutation.criterionRef == ref }?.establishedObligations().orEmpty()
  val mutation =
    AcceptanceCriterionCompletedMutation(
      catalogId, specDigest, executionId, ref, reservationId, "worker", "worker-output:$reservationId", "e".repeat(64),
      "a".repeat(64), "before", treeDigest, checkpointId, inventory, AcceptanceCriterionMutationDisposition.INCOMPLETE,
      AcceptanceCriterionRepairLaunch(
        reservationId,
        "provider",
        "model",
        "high",
        "plan",
        "launch:$reservationId",
        repairAccounting(ref).escalationRequired,
      ),
    )
  return retainCompletedMutation(mutation)?.completeRepairAwaitingTargetedVerification(
    reservationId,
    evidenceRef,
    treeDigest,
    checkpointId,
  )
}

private fun AcceptanceCriterionRepairState.recordIndependently(
  outcome: AcceptanceCriterionRepairOutcome,
): AcceptanceCriterionRepairState? {
  if (outcomes.any { it.reservationId == outcome.reservationId }) return record(outcome)
  val tree = assertNotNull(admittedTreeDigest)
  val checkpoint = assertNotNull(admittedCheckpointId)
  val binding = outcome.auditBindingDigest ?: "a".repeat(64)
  val mutation = completedMutations.single { it.repairAttemptId == outcome.reservationId }
  val resolved =
    if (outcome.successful) {
      listOf(
        AcceptanceCriterionResolvedObligation(
          "obligation:${outcome.reservationId}",
          "Production caller closes ${outcome.criterionRef}",
          listOf("before:src/Store.kt:5"),
          listOf("after:src/Store.kt:6"),
        ),
      )
    } else {
      emptyList()
    }
  val preserved =
    mutation.establishedObligations.map {
      AcceptanceCriterionPreservedObligation(it.obligationId, it.obligation, listOf("after:src/Store.kt:4"))
    }
  val comparison =
    AcceptanceCriterionIndependentProgress(
      outcome.criterionRef, outcome.reservationId, mutation.baselineTreeDigest, tree, "before", checkpoint, revision,
      binding, resolved, preserved, if (outcome.successful) emptyList() else listOf("Production gap"),
      if (outcome.successful) {
        AcceptanceCriterionProgressClassification.SATISFIED
      } else {
        AcceptanceCriterionProgressClassification.NO_PROGRESS
      },
    )
  val baselineCitations =
    resolved.flatMap { it.baselineCitations } +
      mutation.establishedObligations.flatMap { it.currentCitations }
  val context =
    AcceptanceCriterionProgressContext(
      catalogId,
      specDigest,
      executionId,
      outcome.criterionRef,
      outcome.reservationId,
      "worker",
      mutation.workerOutputDigest,
      mutation.baselineTreeDigest,
      "before",
      tree,
      checkpoint,
      revision,
      binding,
      "checker",
      outcome.outcomeId,
      outcome.digest,
      true,
      baselineCitations.toSet(),
      (resolved.flatMap { it.currentCitations } + preserved.flatMap { it.currentCitations }).toSet(),
      mutation.establishedObligations, resolved, preserved, mutation,
    )
  return retainIndependentCheck(AcceptanceCriterionCheckedMutation(mutation, comparison, context))
    ?.record(outcome.copy(auditBindingDigest = binding))
}
