package skillbill.engine.goalrunner.planning.hydration

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.persist.durationMillis
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.engine.goalplanning.readStoredPlanningRecord
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration
import skillbill.engine.goalrunner.planning.model.expectedProvenance
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRecoveryKind
import skillbill.engine.goalrunner.planning.recovery.classifyGoalPlanningRecovery
import skillbill.error.shellcontent.IncompatibleGoalPlanningPreparationRecoveryError
import skillbill.error.shellcontent.InvalidFeatureTaskRuntimePhaseOutputSchemaError
import skillbill.error.shellcontent.InvalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.text.sha256HexUtf8
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import java.time.Clock

private data class PreparedGoalPlanning(
  val shared: SharedGoalPreplanCheckpoint,
  val plan: GoalSubtaskPlanCheckpoint,
)

class GoalChildPlanningHydrator(
  private val clock: Clock,
) {
  private val payloadValidator = PreparedPlanningPayloadValidator()
  private val importMatcher = GoalChildPlanningImportMatcher(payloadValidator)

  fun hydrate(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydration {
    val prepared = loadRequiredPreparation(unitOfWork, setup, request)
    requireMatchingPreparation(setup, request, prepared)
    payloadValidator.requireValid(
      "preplan",
      prepared.shared.preplanPayload,
      prepared.shared.payloadSha256,
      setup.workflowId,
    )
    payloadValidator.requireValid(
      "plan",
      prepared.plan.planPayload,
      prepared.plan.payloadSha256,
      setup.workflowId,
    )
    return createHydration(request, prepared)
  }

  fun requireMatchingImport(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ) {
    val request =
      requireNotNull(setup.planningHydration) {
        "Prepared goal child '${setup.subtaskId}' requires planning hydration."
      }
    importMatcher.firstDivergence(unitOfWork, existing, setup, request)?.let { divergence ->
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        request.identity.parentGoalWorkflowId,
        setup.subtaskId,
        "existing child planning import conflicts with request: $divergence",
      )
    }
  }

  private fun loadRequiredPreparation(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): PreparedGoalPlanning {
    val shared =
      unitOfWork.goalPlanningPreparations.findSharedPreplan(request.identity)
        ?: throw InvalidGoalPlanningPreparationSchemaError(
          setup.workflowId,
          "preplan",
          "shared preplan is missing",
        )
    val plan =
      unitOfWork.goalPlanningPreparations.findSubtaskPlan(
        request.identity,
        request.descriptor.subtaskId,
        request.descriptor.governedSubSpecPath,
      ) ?: throw InvalidGoalPlanningPreparationSchemaError(
        setup.workflowId,
        "plan",
        "subtask plan is missing",
      )
    return PreparedGoalPlanning(shared, plan)
  }

  private fun requireMatchingPreparation(
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
    prepared: PreparedGoalPlanning,
  ) {
    val matches =
      listOf(
        prepared.shared.provenance.copy(parentSpecHash = request.provenance.parentSpecHash) == request.provenance,
        prepared.plan.provenance.copy(parentSpecHash = request.provenance.parentSpecHash) == request.provenance,
        prepared.plan.manifestOrder == request.descriptor.manifestOrder,
      ).all { it }
    if (!matches) {
      throw IncompatibleGoalPlanningPreparationRecoveryError(
        request.identity.parentGoalWorkflowId,
        setup.subtaskId,
        "stored planning provenance or selected subtask descriptor differs from the hydration request",
      )
    }
  }

  private fun createHydration(
    request: GoalChildPlanningHydrationRequest,
    prepared: PreparedGoalPlanning,
  ): GoalChildPlanningHydration {
    val importedAt = clock.instant().toString()
    val records =
      createImportedRecords(
        ImportedPlanningPhase(prepared.shared.preplanPayload, prepared.shared.repairEvidence),
        ImportedPlanningPhase(prepared.plan.planPayload, prepared.plan.repairEvidence),
        importedAt,
      )
    return GoalChildPlanningHydration(
      currentStepId = "implement",
      stepUpdates = records.keys.map(::completedStep),
      artifacts =
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(records),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.entry(createImportedLedger(importedAt)),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(
            createProvenance(request, prepared),
          ),
        ),
    )
  }

  private fun createImportedRecords(
    preplan: ImportedPlanningPhase,
    plan: ImportedPlanningPhase,
    importedAt: String,
  ): Map<String, Map<String, Any?>> =
    linkedMapOf(
      "preplan" to
        importedRecord(
          "preplan",
          preplan.storedPayload,
          preplan.repairEvidence,
          importedAt,
        ).asWorkflowArtifactEntry().let(::workflowArtifactEntryMap),
      "plan" to
        importedRecord(
          "plan",
          plan.storedPayload,
          plan.repairEvidence,
          importedAt,
        ).asWorkflowArtifactEntry().let(::workflowArtifactEntryMap),
    )

  private fun createImportedLedger(importedAt: String): List<Map<String, Any?>> =
    PLANNING_PHASE_IDS.mapIndexed { sequence, phaseId ->
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        sequenceNumber = sequence,
        timestamp = importedAt,
        phaseId = phaseId,
        attemptCount = 1,
        executionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED,
      ).asWorkflowArtifactEntry().let(::workflowArtifactEntryMap)
    }

  private fun createProvenance(
    request: GoalChildPlanningHydrationRequest,
    prepared: PreparedGoalPlanning,
  ): Map<String, Any?> =
    FeatureTaskRuntimeGoalPlanningImport(
      parentGoalWorkflowId = request.identity.parentGoalWorkflowId,
      normalizedIssueKey = request.identity.normalizedIssueKey,
      repositoryIdentity = request.identity.repositoryIdentity,
      parentSpecHash = request.provenance.parentSpecHash,
      decompositionManifestHash = request.provenance.decompositionManifestHash,
      planningContractId = request.provenance.planningContractId,
      planningContractVersion = request.provenance.planningContractVersion,
      phaseOutputContractId = request.provenance.phaseOutputContractId,
      phaseOutputContractVersion = request.provenance.phaseOutputContractVersion,
      subtaskId = request.descriptor.subtaskId,
      manifestOrder = request.descriptor.manifestOrder,
      governedSubSpecPath = request.descriptor.governedSubSpecPath,
      subSpecHash = request.descriptor.subSpecHash,
      preplanPayloadSha256 = prepared.shared.payloadSha256,
      planPayloadSha256 = prepared.plan.payloadSha256,
    ).asWorkflowArtifactEntry().let(::workflowArtifactEntryMap)
}

private data class ImportedPlanningPhase(
  val storedPayload: String,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
)

private class PreparedPlanningPayloadValidator {
  fun requireValid(
    phaseId: String,
    payload: String,
    expectedDigest: String,
    workflowId: String,
  ): NormalizedFeatureTaskRuntimePhaseOutput {
    if (sha256HexUtf8(payload) != expectedDigest) {
      invalidPlanningPreparation(workflowId, "$phaseId.payload_sha256", "payload digest differs")
    }
    val stored = readStoredPlanningRecord(payload, phaseId, workflowId)
    if (stored.output.value.isBlank()) {
      throw InvalidFeatureTaskRuntimePhaseOutputSchemaError(
        sourceLabel = "$workflowId:$phaseId",
        reason = "produced_outputs.value must contain non-blank prose.",
      )
    }
    return stored
  }
}

private fun invalidPlanningPreparation(
  workflowId: String,
  fieldPath: String,
  reason: String,
): Nothing = throw InvalidGoalPlanningPreparationSchemaError(workflowId, fieldPath, reason)

private class GoalChildPlanningImportMatcher(
  private val payloadValidator: PreparedPlanningPayloadValidator,
) {
  fun firstDivergence(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): String? {
    val artifacts = existing.artifacts
    val expected =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.value(artifacts) as? Map<*, *>
        ?: return "child carries no goal planning import artifact"
    val shared = unitOfWork.goalPlanningPreparations.findSharedPreplan(request.identity)
    val plan =
      unitOfWork.goalPlanningPreparations.findSubtaskPlan(
        request.identity,
        request.descriptor.subtaskId,
        request.descriptor.governedSubSpecPath,
      )
    validateAvailablePayloads(shared, plan, setup, request)
    val provenanceDivergence = provenanceDivergence(expected, request)
    return when {
      provenanceDivergence != null -> provenanceDivergence
      !preparedMatches(shared, plan, request) ->
        "parent planning checkpoints are missing or have incompatible provenance"
      !ledgerMatches(artifacts) ->
        "phase ledger no longer opens with the goal planning import prefix"
      !planningPhasesSettled(artifacts, existing) ->
        "child planning phases are not settled as completed"
      else -> null
    }
  }

  private fun validateAvailablePayloads(
    shared: SharedGoalPreplanCheckpoint?,
    plan: GoalSubtaskPlanCheckpoint?,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ) {
    shared?.let {
      requireImportedPayloadValid("preplan", it.preplanPayload, it.payloadSha256, setup, request)
    }
    plan?.let {
      requireImportedPayloadValid("plan", it.planPayload, it.payloadSha256, setup, request)
    }
  }

  private fun requireImportedPayloadValid(
    phaseId: String,
    payload: String,
    digest: String,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ) {
    try {
      payloadValidator.requireValid(phaseId, payload, digest, setup.workflowId)
    } catch (error: InvalidGoalPlanningPreparationSchemaError) {
      throw importedPayloadRecoveryError(phaseId, setup, request, error)
    } catch (error: InvalidFeatureTaskRuntimePhaseOutputSchemaError) {
      throw importedPayloadRecoveryError(phaseId, setup, request, error)
    }
  }

  private fun importedPayloadRecoveryError(
    phaseId: String,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
    error: Throwable,
  ): IncompatibleGoalPlanningPreparationRecoveryError {
    val detail = error.message.orEmpty()
    val reason =
      when (classifyGoalPlanningRecovery(error)) {
        GoalPlanningRecoveryKind.HARD_RESET ->
          "stored goal planning '$phaseId' record for subtask ${request.descriptor.subtaskId} is unsupported " +
            "by this runtime. Keep the workflow and checkpoints intact. Projection failure: $detail"
        GoalPlanningRecoveryKind.SCOPED_REPLAN ->
          "stored goal planning '$phaseId' record for subtask ${request.descriptor.subtaskId} was already " +
            "imported by this child and no longer matches its parent checkpoint. Projection failure: $detail"
        GoalPlanningRecoveryKind.BLOCKED ->
          "stored goal planning '$phaseId' record for subtask ${request.descriptor.subtaskId} fails " +
            "contract validation. Keep the workflow and checkpoints intact, then retry after migration " +
            "support is available. Projection failure: $detail"
      }
    return IncompatibleGoalPlanningPreparationRecoveryError(
      request.identity.parentGoalWorkflowId,
      setup.subtaskId,
      reason,
      error,
    )
  }

  private fun provenanceDivergence(
    expected: Map<*, *>,
    request: GoalChildPlanningHydrationRequest,
  ): String? {
    val mismatched = expectedProvenance(request).filter { (key, value) -> expected[key] != value }.keys
    if (mismatched.isEmpty()) return null
    return "stored import provenance differs from the hydration request at " +
      mismatched.joinToString(", ")
  }

  private fun preparedMatches(
    shared: SharedGoalPreplanCheckpoint?,
    plan: GoalSubtaskPlanCheckpoint?,
    request: GoalChildPlanningHydrationRequest,
  ): Boolean =
    listOf(
      shared != null,
      plan != null,
      shared?.provenance?.copy(parentSpecHash = request.provenance.parentSpecHash) == request.provenance,
      plan?.provenance?.copy(parentSpecHash = request.provenance.parentSpecHash) == request.provenance,
      plan?.manifestOrder == request.descriptor.manifestOrder,
    ).all { it }

  private fun planningPhasesSettled(
    artifacts: Map<String, Any?>,
    existing: WorkflowStateSnapshot,
  ): Boolean {
    val records =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(artifacts) as? Map<*, *> ?: return false
    val expected =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.value(artifacts) as? Map<*, *>
        ?: return false
    val expectedStepStatuses =
      PLANNING_PHASE_IDS.mapNotNull { phaseId ->
        settledStepStatus(records[phaseId] as? Map<*, *>, phaseId)?.let { phaseId to it }
      }.toMap()
    val preplanOutput = (records["preplan"] as? Map<*, *>)?.get("output_artifact") as? String ?: return false
    return expectedStepStatuses.size == PLANNING_PHASE_IDS.size &&
      sha256HexUtf8(preplanOutput) == expected["preplan_payload_sha256"] &&
      stepsSettled(existing, expectedStepStatuses)
  }

  private fun settledStepStatus(
    record: Map<*, *>?,
    phaseId: String,
  ): WorkflowStepStatus? {
    if (record == null || record[SharedPayloadKeys.PHASE_ID] != phaseId) return null
    return when (record[SharedPayloadKeys.STATUS].workflowStepStatus()) {
      WorkflowStepStatus.COMPLETED ->
        WorkflowStepStatus.COMPLETED
          .takeIf { (record["output_artifact"] as? String)?.isNotBlank() == true }
      WorkflowStepStatus.RUNNING -> WorkflowStepStatus.RUNNING
      WorkflowStepStatus.BLOCKED -> WorkflowStepStatus.BLOCKED
      else -> null
    }
  }

  private fun ledgerMatches(artifacts: Map<String, Any?>): Boolean {
    val ledger =
      (DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.value(artifacts) as? List<*>)
        ?.mapNotNull { it as? Map<*, *> }
        ?: return false
    if (ledger.size < PLANNING_PHASE_IDS.size) return false
    return ledger.take(PLANNING_PHASE_IDS.size).withIndex().all { (index, entry) ->
      listOf(
        (entry["action"] as? String)?.let(FeatureTaskRuntimePhaseLedgerAction::fromWire) ==
          FeatureTaskRuntimePhaseLedgerAction.COMPLETE,
        (entry["sequence_number"] as? Number)?.toInt() == index,
        entry[SharedPayloadKeys.PHASE_ID] == PLANNING_PHASE_IDS[index],
        (entry["attempt_count"] as? Number)?.toInt() == 1,
        entry["resolved_agent_id"] == null,
      ).all { it }
    }
  }

  private fun stepsSettled(
    existing: WorkflowStateSnapshot,
    expected: Map<String, WorkflowStepStatus>,
  ): Boolean {
    val planningSteps = existing.steps.filter { it.stepId in PLANNING_PHASE_IDS }
    return planningSteps.size == PLANNING_PHASE_IDS.size &&
      planningSteps.all { it.status.workflowStepStatus() == expected[it.stepId] }
  }
}

private fun completedStep(phaseId: String): Map<String, Any?> =
  linkedMapOf(
    SharedPayloadKeys.STEP_ID to phaseId,
    SharedPayloadKeys.STATUS to "completed",
    "attempt_count" to 1,
  )

private fun importedRecord(
  phaseId: String,
  payload: String,
  repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence?,
  importedAt: String,
): FeatureTaskRuntimePhaseRecord =
  FeatureTaskRuntimePhaseRecord(
    phaseId = phaseId,
    status = "completed",
    attemptCount = 1,
    startedAt = importedAt,
    finishedAt = importedAt,
    durationMillis = 0,
    resolvedAgentId = "goal-planning-import",
    executionOrigin = FeatureTaskRuntimePhaseExecutionOrigin.GOAL_PLANNING_HYDRATED,
    outputArtifact = payload,
    repairEvidence = repairEvidence,
  )

private val PLANNING_PHASE_IDS = listOf("preplan", "plan")
