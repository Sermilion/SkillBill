package skillbill.engine.goalrunner.planning.hydration

import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.persist.workflowArtifactEntryMap
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.engine.goalplanning.readStoredPlanningRecord
import skillbill.engine.goalrunner.model.GoalChildPlanningHydrationRequest
import skillbill.engine.goalrunner.model.GoalRunnerChildWorkflowSetup
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydrationOutcome.Conflicted
import skillbill.engine.goalrunner.planning.hydration.GoalChildPlanningHydrationOutcome.Hydrated
import skillbill.engine.goalrunner.planning.model.GoalChildPlanningHydration
import skillbill.engine.goalrunner.planning.model.provenanceMismatches
import skillbill.engine.goalrunner.planning.outcome.proseRecordPayload
import skillbill.engine.goalrunner.planning.recovery.GoalPlanningRecoveryKind
import skillbill.engine.goalrunner.planning.recovery.classifyGoalPlanningRecovery
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.featuretask.FeatureTaskRuntimePhaseOutputFailureCode
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.error.shellcontent.invalidFeatureTaskRuntimePhaseOutputSchema
import skillbill.error.shellcontent.invalidGoalPlanningPreparationSchemaError
import skillbill.ports.goalrunner.GoalRunnerPersistenceSession
import skillbill.ports.goalrunner.model.GoalPlanningPreparationConflict
import skillbill.ports.goalrunner.model.GoalSubtaskPlanCheckpoint
import skillbill.ports.goalrunner.model.GoalSubtaskPlanLookupResult
import skillbill.ports.goalrunner.model.SharedGoalPreplanCheckpoint
import skillbill.ports.goalrunner.model.SharedGoalPreplanLookupResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.text.sha256HexUtf8
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.model.workflowStepStatus
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.artifact.decodePlanBundleFromArtifact
import skillbill.workflow.taskruntime.artifact.goalPlanningImport
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImportSource
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseExecutionOrigin
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseOutputRepairEvidence
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.isRetainedPhaseLedgerEvidenceWindow
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.time.Clock

private data class ImportedPlanningPhase(
  val storedPayload: String,
  val repairEvidence: FeatureTaskRuntimePhaseOutputRepairEvidence? = null,
)

private data class PreparedGoalPlanning(
  val preplan: ImportedPlanningPhase,
  val plan: ImportedPlanningPhase,
  val planSubtasks: ImportedPlanningPhase,
  val source: FeatureTaskRuntimeGoalPlanningImportSource,
)

private sealed interface PreparationRead {
  data class Prepared(val value: PreparedGoalPlanning) : PreparationRead

  data class Conflicted(val conflict: GoalPlanningPreparationConflict) : PreparationRead
}

internal class GoalChildPlanningHydrator(
  private val clock: Clock,
) {
  private val payloadValidator = PreparedPlanningPayloadValidator()
  private val importMatcher = GoalChildPlanningImportMatcher(payloadValidator)
  private val planWorkflowReader = PlanWorkflowPlanningReader(payloadValidator)
  private val legacyReader = LegacyTablesPlanningReader(payloadValidator)

  fun hydrate(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalChildPlanningHydrationOutcome =
    when (val loaded = load(unitOfWork, setup, request)) {
      is PreparationRead.Conflicted -> Conflicted(loaded.conflict)
      is PreparationRead.Prepared -> Hydrated(createHydration(request, loaded.value))
    }

  fun requireMatchingImport(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
  ): GoalPlanningPreparationConflict? {
    val request =
      requireNotNull(setup.planningHydration) {
        "Prepared goal child '${setup.subtaskId}' requires planning hydration."
      }
    return importMatcher.firstDivergence(unitOfWork, existing, setup, request)?.let { conflict ->
      conflict.copy(reason = "existing child planning import conflicts with request: ${conflict.reason}")
    }
  }

  private fun load(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): PreparationRead =
    if (request.planWorkflowId != null) {
      planWorkflowReader.read(unitOfWork, setup, request, request.planWorkflowId)
    } else {
      legacyReader.read(unitOfWork, setup, request)
    }

  private fun createHydration(
    request: GoalChildPlanningHydrationRequest,
    prepared: PreparedGoalPlanning,
  ): GoalChildPlanningHydration {
    val importedAt = clock.instant().toString()
    val records =
      linkedMapOf(
        "preplan" to importedRecordEntry("preplan", prepared.preplan, importedAt),
        "plan" to importedRecordEntry("plan", prepared.plan, importedAt),
        PLAN_SUBTASKS_PHASE_ID to importedRecordEntry(PLAN_SUBTASKS_PHASE_ID, prepared.planSubtasks, importedAt),
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

  private fun importedRecordEntry(
    phaseId: String,
    phase: ImportedPlanningPhase,
    importedAt: String,
  ): Map<String, Any?> =
    importedRecord(phaseId, phase.storedPayload, phase.repairEvidence, importedAt)
      .asWorkflowArtifactEntry()
      .let(::workflowArtifactEntryMap)

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
  ): FeatureTaskRuntimeGoalPlanningImport =
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
      preplanPayloadSha256 = sha256HexUtf8(prepared.preplan.storedPayload),
      planPayloadSha256 = sha256HexUtf8(prepared.plan.storedPayload),
      source = prepared.source,
    )
}

private class PlanWorkflowPlanningReader(
  private val payloadValidator: PreparedPlanningPayloadValidator,
) {
  fun read(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
    planWorkflowId: String,
  ): PreparationRead {
    fun conflict(reason: String) =
      PreparationRead.Conflicted(
        GoalPlanningPreparationConflict(
          request.identity.parentGoalWorkflowId,
          setup.subtaskId,
          "plan workflow '$planWorkflowId' $reason",
          null,
        ),
      )
    val snapshot =
      unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, planWorkflowId)
        ?: return conflict("is missing")
    val unit =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_BUNDLE.value(snapshot.artifacts)
        ?.let(::decodePlanBundleFromArtifact)
        ?.subtask(request.descriptor.subtaskId)
        ?: return conflict("has no plan bundle entry for subtask ${request.descriptor.subtaskId}")
    val details = unit.planText?.takeIf { unit.detailsSha256 != null && it.isNotBlank() }
    val records = decodePhaseRecords(snapshot.artifacts)
    val preplanPayload = records["preplan"]?.outputArtifact
    val unitPayload =
      records[FeatureTaskRuntimePhaseWorkflowDefinition.planSubtaskUnitRecordId(request.descriptor.subtaskId)]
        ?.takeIf { it.status == WorkflowStepStatus.COMPLETED }
        ?.outputArtifact
        ?.takeIf(String::isNotBlank)
    return when {
      details == null -> conflict("has no planned details for subtask ${request.descriptor.subtaskId}")
      preplanPayload == null -> conflict("has no preplan record")
      unitPayload == null -> conflict("has no plan_subtasks record for subtask ${request.descriptor.subtaskId}")
      else -> {
        payloadValidator.requireValid("preplan", preplanPayload, sha256HexUtf8(preplanPayload), setup.workflowId)
        payloadValidator.requireValid(PLAN_SUBTASKS_PHASE_ID, unitPayload, sha256HexUtf8(unitPayload), setup.workflowId)
        PreparationRead.Prepared(
          PreparedGoalPlanning(
            preplan = ImportedPlanningPhase(preplanPayload),
            plan = ImportedPlanningPhase(proseRecordPayload("plan", details)),
            planSubtasks = ImportedPlanningPhase(unitPayload),
            source =
              FeatureTaskRuntimeGoalPlanningImportSource(
                kind = FeatureTaskRuntimeGoalPlanningImportSource.PLAN_WORKFLOW,
                planWorkflowId = planWorkflowId,
                detailsSha256 = unit.detailsSha256,
              ),
          ),
        )
      }
    }
  }
}

private class LegacyTablesPlanningReader(
  private val payloadValidator: PreparedPlanningPayloadValidator,
) {
  fun read(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): PreparationRead {
    val shared =
      when (val result = unitOfWork.goalPlanningPreparations.findSharedPreplan(request.identity)) {
        is SharedGoalPreplanLookupResult.Conflicted -> return PreparationRead.Conflicted(result.conflict)
        is SharedGoalPreplanLookupResult.Found -> result.checkpoint
      } ?: throw invalidGoalPlanningPreparationSchemaError(
        setup.workflowId,
        "preplan",
        "shared preplan is missing",
      )
    val plan =
      when (
        val result =
          unitOfWork.goalPlanningPreparations.findSubtaskPlan(
            request.identity,
            request.descriptor.subtaskId,
            request.descriptor.governedSubSpecPath,
          )
      ) {
        is GoalSubtaskPlanLookupResult.Conflicted -> return PreparationRead.Conflicted(result.conflict)
        is GoalSubtaskPlanLookupResult.Found -> result.plan
      } ?: throw invalidGoalPlanningPreparationSchemaError(
        setup.workflowId,
        "plan",
        "subtask plan is missing",
      )
    requireMatchingPreparation(setup, request, shared, plan)?.let { return PreparationRead.Conflicted(it) }
    payloadValidator.requireValid("preplan", shared.preplanPayload, shared.payloadSha256, setup.workflowId)
    payloadValidator.requireValid("plan", plan.planPayload, plan.payloadSha256, setup.workflowId)
    return PreparationRead.Prepared(
      PreparedGoalPlanning(
        preplan = ImportedPlanningPhase(shared.preplanPayload, shared.repairEvidence),
        plan = ImportedPlanningPhase(plan.planPayload, plan.repairEvidence),
        planSubtasks = ImportedPlanningPhase(proseRecordPayload(PLAN_SUBTASKS_PHASE_ID, LEGACY_PLAN_SUBTASKS_MARKER)),
        source =
          FeatureTaskRuntimeGoalPlanningImportSource(FeatureTaskRuntimeGoalPlanningImportSource.LEGACY_TABLES),
      ),
    )
  }

  private fun requireMatchingPreparation(
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
    shared: SharedGoalPreplanCheckpoint,
    plan: GoalSubtaskPlanCheckpoint,
  ): GoalPlanningPreparationConflict? {
    val matches =
      listOf(
        shared.provenance.copy(parentSpecHash = request.provenance.parentSpecHash) == request.provenance,
        plan.provenance.copy(parentSpecHash = request.provenance.parentSpecHash) == request.provenance,
        plan.manifestOrder == request.descriptor.manifestOrder,
      ).all { it }
    return if (!matches) {
      GoalPlanningPreparationConflict(
        request.identity.parentGoalWorkflowId,
        setup.subtaskId,
        "stored planning provenance or selected subtask descriptor differs from the hydration request",
        null,
      )
    } else {
      null
    }
  }
}

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
      throw invalidFeatureTaskRuntimePhaseOutputSchema(
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
): Nothing = throw invalidGoalPlanningPreparationSchemaError(workflowId, fieldPath, reason)

private class GoalChildPlanningImportMatcher(
  private val payloadValidator: PreparedPlanningPayloadValidator,
) {
  private val planWorkflowReader = PlanWorkflowPlanningReader(payloadValidator)

  fun firstDivergence(
    unitOfWork: GoalRunnerPersistenceSession,
    existing: WorkflowStateSnapshot,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalPlanningPreparationConflict? {
    val artifacts = existing.artifacts
    val expected =
      artifacts.goalPlanningImport()
        ?: return conflict(request, setup, "child carries no goal planning import artifact")
    val phaseIds = if (expected.source == null) LEGACY_ERA_PHASE_IDS else PLANNING_PHASE_IDS
    val storedFromPlanWorkflow = expected.source?.kind == FeatureTaskRuntimeGoalPlanningImportSource.PLAN_WORKFLOW
    val sourceDivergence =
      if (request.planWorkflowId != null && storedFromPlanWorkflow) {
        planWorkflowDivergence(unitOfWork, expected, setup, request, request.planWorkflowId)
      } else {
        legacyDivergence(unitOfWork, setup, request)
      }
    val provenanceDivergence = provenanceDivergence(expected, request)
    return when {
      sourceDivergence != null -> sourceDivergence
      provenanceDivergence != null -> conflict(request, setup, provenanceDivergence)
      !ledgerMatches(artifacts, phaseIds) ->
        conflict(request, setup, "phase ledger no longer opens with the goal planning import prefix")
      !planningPhasesSettled(artifacts, existing, phaseIds, expected) ->
        conflict(request, setup, "child planning phases are not settled as completed")
      else -> null
    }
  }

  private fun conflict(
    request: GoalChildPlanningHydrationRequest,
    setup: GoalRunnerChildWorkflowSetup,
    reason: String,
  ) = GoalPlanningPreparationConflict(request.identity.parentGoalWorkflowId, setup.subtaskId, reason, null)

  private fun planWorkflowDivergence(
    unitOfWork: GoalRunnerPersistenceSession,
    expected: FeatureTaskRuntimeGoalPlanningImport,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
    planWorkflowId: String,
  ): GoalPlanningPreparationConflict? {
    val prepared =
      when (val read = planWorkflowReader.read(unitOfWork, setup, request, planWorkflowId)) {
        is PreparationRead.Conflicted -> return read.conflict
        is PreparationRead.Prepared -> read.value
      }
    val matches = expected.source == prepared.source
    return if (matches) {
      null
    } else {
      conflict(request, setup, "child planning import no longer matches the linked plan workflow record")
    }
  }

  private fun legacyDivergence(
    unitOfWork: GoalRunnerPersistenceSession,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalPlanningPreparationConflict? {
    val shared =
      when (val result = unitOfWork.goalPlanningPreparations.findSharedPreplan(request.identity)) {
        is SharedGoalPreplanLookupResult.Conflicted -> return result.conflict
        is SharedGoalPreplanLookupResult.Found -> result.checkpoint
      }
    val plan =
      when (
        val result =
          unitOfWork.goalPlanningPreparations.findSubtaskPlan(
            request.identity,
            request.descriptor.subtaskId,
            request.descriptor.governedSubSpecPath,
          )
      ) {
        is GoalSubtaskPlanLookupResult.Conflicted -> return result.conflict
        is GoalSubtaskPlanLookupResult.Found -> result.plan
      }
    return validateAvailablePayloads(shared, plan, setup, request)
      ?: conflict(request, setup, "parent planning checkpoints are missing or have incompatible provenance")
        .takeUnless { preparedMatches(shared, plan, request) }
  }

  private fun validateAvailablePayloads(
    shared: SharedGoalPreplanCheckpoint?,
    plan: GoalSubtaskPlanCheckpoint?,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalPlanningPreparationConflict? {
    shared?.let {
      requireImportedPayloadValid("preplan", it.preplanPayload, it.payloadSha256, setup, request)?.let { conflict ->
        return conflict
      }
    }
    plan?.let {
      requireImportedPayloadValid("plan", it.planPayload, it.payloadSha256, setup, request)?.let { conflict ->
        return conflict
      }
    }
    return null
  }

  private fun requireImportedPayloadValid(
    phaseId: String,
    payload: String,
    digest: String,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
  ): GoalPlanningPreparationConflict? {
    try {
      payloadValidator.requireValid(phaseId, payload, digest, setup.workflowId)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(
        error.code == InstallFailureCode.INVALID_GOAL_PLANNING_PREPARATION_SCHEMA ||
          error.code == InstallFailureCode.GOAL_PLANNING_PREPARATION_CONTRACT_INCOMPATIBLE ||
          error.code is FeatureTaskRuntimePhaseOutputFailureCode,
      )
      return importedPayloadRecoveryError(phaseId, setup, request, error)
    }
    return null
  }

  private fun importedPayloadRecoveryError(
    phaseId: String,
    setup: GoalRunnerChildWorkflowSetup,
    request: GoalChildPlanningHydrationRequest,
    error: Throwable,
  ): GoalPlanningPreparationConflict {
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
    return GoalPlanningPreparationConflict(
      request.identity.parentGoalWorkflowId,
      setup.subtaskId,
      reason,
      error,
    )
  }

  private fun provenanceDivergence(
    expected: FeatureTaskRuntimeGoalPlanningImport,
    request: GoalChildPlanningHydrationRequest,
  ): String? {
    val mismatched = expected.provenanceMismatches(request)
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
    phaseIds: List<String>,
    expected: FeatureTaskRuntimeGoalPlanningImport,
  ): Boolean {
    val records =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.value(artifacts) as? Map<*, *> ?: return false
    val expectedStepStatuses =
      phaseIds.mapNotNull { phaseId ->
        settledStepStatus(records[phaseId] as? Map<*, *>, phaseId)?.let { phaseId to it }
      }.toMap()
    val preplanOutput = (records["preplan"] as? Map<*, *>)?.get("output_artifact") as? String ?: return false
    return expectedStepStatuses.size == phaseIds.size &&
      sha256HexUtf8(preplanOutput) == expected.preplanPayloadSha256 &&
      stepsSettled(existing, expectedStepStatuses, phaseIds)
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

  private fun ledgerMatches(
    artifacts: Map<String, Any?>,
    phaseIds: List<String>,
  ): Boolean {
    val ledger = decodePhaseLedger(artifacts)
    val firstSequence = ledger.firstOrNull()?.sequenceNumber ?: return false
    if (!isRetainedPhaseLedgerEvidenceWindow(ledger, FEATURE_TASK_RUNTIME_PHASE_LEDGER_LIMIT)) return false
    val retainedImport = ledger.takeWhile { it.sequenceNumber < phaseIds.size }
    return (firstSequence > 0 || retainedImport.size == phaseIds.size) &&
      retainedImport.all { entry ->
        entry.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE &&
          entry.phaseId == phaseIds[entry.sequenceNumber] &&
          entry.attemptCount == 1 && entry.resolvedAgentId == null
      }
  }

  private fun stepsSettled(
    existing: WorkflowStateSnapshot,
    expected: Map<String, WorkflowStepStatus>,
    phaseIds: List<String>,
  ): Boolean {
    val planningSteps = existing.steps.filter { it.stepId in phaseIds }
    return planningSteps.size == phaseIds.size &&
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

private const val PLAN_SUBTASKS_PHASE_ID = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN_SUBTASKS
private const val LEGACY_PLAN_SUBTASKS_MARKER = "Imported from the legacy goal planning tables."
private val LEGACY_ERA_PHASE_IDS = listOf("preplan", "plan")
private val PLANNING_PHASE_IDS = LEGACY_ERA_PHASE_IDS + PLAN_SUBTASKS_PHASE_ID
